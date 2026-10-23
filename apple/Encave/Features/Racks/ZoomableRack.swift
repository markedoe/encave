#if os(iOS)
import CaveKit
import SwiftUI
import UIKit

/// A rack in a native zooming scroll view, as in Photos or Maps: pinch to zoom up to 4×,
/// pan when zoomed in or when the rack is larger than the screen (its places never drawn
/// under 44 pt); a bottle to find is brought into view. No double-tap zoom: the second tap would also reach the place
/// under the finger (adding or showing a bottle, or moving one there).
/// When a pinch ends the rack is drawn again at the new size, so labels stay sharp
/// and names appear once cells are large enough.
///
/// The pinch is our own recognizer: on iPad (iPadOS 26), the scroll view's built-in pinch
/// recognizes the two fingers and reports their scale, but never changes the zoom.
struct ZoomableRack: UIViewRepresentable {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let baseCellSize: CGFloat
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var selectedPlace: RackPlace?
    var focusWine: UUID?
    var onTap: (RackPlace, [Bottle]) -> Void
    var onDrop: ((UUID, RackPlace) -> Bool)?
    var zoomStep = RackZoomStep()
    var interaction = RackInteraction()
    /// Told what is in view, for the overview; brings a part of the rack into view.
    var tracker: RackViewportTracker?

    static let maxZoom: CGFloat = 4

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> CenteringScrollView {
        let scroll = CenteringScrollView()
        scroll.delegate = context.coordinator
        scroll.bouncesZoom = true
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.decelerationRate = .fast
        scroll.backgroundColor = .clear
        scroll.accessibilityIdentifier = "rack.canvas"

        let host = UIHostingController(rootView: AnyView(EmptyView()))
        host.view.backgroundColor = .clear
        // The scroll view is already inside the safe area: no extra insets in the drawing.
        host.safeAreaRegions = []
        scroll.addSubview(host.view)
        scroll.zoomedView = host.view
        context.coordinator.host = host
        context.coordinator.scroll = scroll

        let pinch = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pinched(_:)))
        scroll.addGestureRecognizer(pinch)
        context.coordinator.pinch = pinch
        context.coordinator.tracker = tracker
        tracker?.jump = { [weak coordinator = context.coordinator] point in coordinator?.jump(to: point) }
        return scroll
    }

    func updateUIView(_ scroll: CenteringScrollView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        coordinator.model = model
        // SwiftUI redraws this view whenever something it reads changes, also during a pinch
        // (a sync round, the sync status). Changing the drawing then would fight the gesture:
        // wait until the zoom or the scroll is over.
        if coordinator.isBusy {
            coordinator.hasPendingUpdate = true
        } else {
            coordinator.update()
        }
        // A bottle to find (shown from elsewhere, or picked in the list): bring it into view.
        if highlighted != coordinator.highlighted {
            coordinator.highlighted = highlighted
            if let highlighted, highlighted.rackID == rack.id { tracker?.show(highlighted, in: rack) }
        }
        // A zoom button was pressed: zoom around the middle, as a pinch would.
        if zoomStep.id != coordinator.zoomStepID {
            coordinator.zoomStepID = zoomStep.id
            let target = min(ZoomableRack.maxZoom, max(1, coordinator.level * scroll.zoomScale * zoomStep.factor))
            scroll.setZoomScale(target / coordinator.level, animated: true)
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: ZoomableRack?
        var model: CellarModel?
        var host: UIHostingController<AnyView>?
        weak var scroll: CenteringScrollView?
        var base: CGFloat = 0
        var rackShape = ""
        /// The zoom the rack is drawn at, 1 = fits the space.
        var level: CGFloat = 1
        /// The last zoom button press handled.
        var zoomStepID = 0
        /// The bottle to find last brought into view.
        var highlighted: Slot?
        weak var tracker: RackViewportTracker?
        private var isReportScheduled = false
        /// SwiftUI asked for a redraw while the rack was being zoomed or scrolled.
        var hasPendingUpdate = false
        /// Our pinch, and the zoom and the point of the drawing under the fingers when it began.
        weak var pinch: UIPinchGestureRecognizer?
        private var pinchStartScale: CGFloat = 1
        private var pinchAnchor: CGPoint = .zero

        /// Zooming, bouncing back or scrolling: the drawing and its frame must not change.
        var isBusy: Bool {
            guard let scroll else { return false }
            let pinching = pinch?.state == .began || pinch?.state == .changed
            return pinching || scroll.isZooming || scroll.isZoomBouncing || scroll.isDragging || scroll.isDecelerating
        }

        /// Zooms with the fingers, keeping the spot under them in place; a little past the
        /// limits while pinching, then back to them.
        @objc func pinched(_ gesture: UIPinchGestureRecognizer) {
            guard let scroll, let content = host?.view else { return }
            switch gesture.state {
            case .began:
                // The fingers both zoom and move the rack here: no scrolling meanwhile.
                scroll.panGestureRecognizer.isEnabled = false
                pinchStartScale = scroll.zoomScale
                // In the drawing's own coordinates, whatever its current scale.
                pinchAnchor = gesture.location(in: content)
            case .changed:
                let low = scroll.minimumZoomScale, high = scroll.maximumZoomScale
                var scale = pinchStartScale * gesture.scale
                // Resist past the limits, as the scroll view's own zoom does.
                if scale > high { scale = high * pow(scale / high, 0.25) }
                if scale < low { scale = low * pow(scale / low, 0.25) }
                scroll.setZoomScale(scale, animated: false)
                // Keep the anchor under the fingers (which may also move together).
                let fingers = gesture.location(in: scroll)
                let onScreen = CGPoint(x: fingers.x - scroll.contentOffset.x, y: fingers.y - scroll.contentOffset.y)
                let size = scroll.contentSize
                let inset = scroll.contentInset
                let x = pinchAnchor.x * scale - onScreen.x
                let y = pinchAnchor.y * scale - onScreen.y
                scroll.contentOffset = CGPoint(
                    x: min(max(x, -inset.left), max(-inset.left, size.width + inset.right - scroll.bounds.width)),
                    y: min(max(y, -inset.top), max(-inset.top, size.height + inset.bottom - scroll.bounds.height))
                )
            case .ended, .cancelled, .failed:
                scroll.panGestureRecognizer.isEnabled = true
                let low = scroll.minimumZoomScale, high = scroll.maximumZoomScale
                if scroll.zoomScale > high || scroll.zoomScale < low {
                    // Back within the limits; the scroll view then calls scrollViewDidEndZooming.
                    scroll.setZoomScale(min(high, max(low, scroll.zoomScale)), animated: true)
                } else {
                    scrollViewDidEndZooming(scroll, with: content, atScale: scroll.zoomScale)
                }
            default:
                break
            }
        }

        /// Applies what SwiftUI last asked for: a new shape, a new space or a new drawing.
        func update() {
            hasPendingUpdate = false
            guard let parent else { return }
            if rackShape != parent.rack.shape {
                // New rack shape: start again from the fitted size.
                rackShape = parent.rack.shape
                base = parent.baseCellSize
                level = 1
                render(keepingCenter: false)
            } else if base != parent.baseCellSize {
                // The space changed (card shown, rotation): keep the zoom and the spot in view.
                base = parent.baseCellSize
                render(keepingCenter: true)
            } else {
                // Same size (new highlight, focus, selection): redraw without touching the zoom.
                refresh()
            }
        }

        /// Once the gesture is over, applies a redraw that had to wait.
        func updateIfPending() {
            if hasPendingUpdate, !isBusy { update() }
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { updateIfPending() }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate { updateIfPending() }
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { host?.view }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            (scrollView as? CenteringScrollView)?.centerContent()
            report(scrollView)
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) { reportViewport() }

        /// Tells the overview what is in view, once per run of the main loop (also when the
        /// scroll view moves while SwiftUI updates it).
        func reportViewport() {
            guard tracker != nil, !isReportScheduled else { return }
            isReportScheduled = true
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isReportScheduled = false
                guard let scroll = self.scroll else { return }
                self.tracker?.report(
                    RackViewportTracker.Geometry(content: scroll.contentSize, view: scroll.bounds.size, offset: scroll.contentOffset),
                    cellSize: self.base * self.level
                )
            }
        }

        /// Brings a point of the drawing (fractions of it) to the middle, as far as it goes.
        func jump(to point: CGPoint) {
            guard let scroll else { return }
            let content = scroll.contentSize, view = scroll.bounds.size, inset = scroll.contentInset
            let offset = RackViewport.offset(centering: point, content: content, view: view)
            // An axis smaller than the screen stays centered.
            scroll.setContentOffset(CGPoint(x: content.width <= view.width ? -inset.left : offset.x,
                                            y: content.height <= view.height ? -inset.top : offset.y), animated: false)
            reportViewport()
        }

        /// The zoom, for VoiceOver and for the screen tour ("2.2×").
        func report(_ scrollView: UIScrollView) {
            let zoom = level * scrollView.zoomScale
            scrollView.accessibilityValue = String(format: "%.1f×", zoom)
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            let newLevel = min(ZoomableRack.maxZoom, max(1, level * scale))
            if abs(newLevel - level) > 0.01 || scale != 1 {
                level = newLevel
                render(keepingCenter: true)
            }
            // Called while the zoom view still settles: let it finish first.
            Task { @MainActor [weak self] in self?.updateIfPending() }
        }

        /// Same size, new content: only the drawing changes.
        func refresh() {
            guard let host, host.view.frame.size != .zero else { return render(keepingCenter: false) }
            host.rootView = content()
        }

        private func content() -> AnyView {
            guard let parent, let model else { return AnyView(EmptyView()) }
            return AnyView(
                RackGrid(rack: parent.rack, cellSize: base * level, selected: parent.selected,
                         highlighted: parent.highlighted, selectedPlace: parent.selectedPlace,
                         focusWine: parent.focusWine, onTap: parent.onTap, onDrop: parent.onDrop,
                         interaction: parent.interaction)
                    .padding(FittedRackGrid.padding)
                    .environment(model)
            )
        }

        /// Draws the rack at `base × level`, then resets the scroll view's own scale to 1.
        func render(keepingCenter: Bool) {
            guard let host, let scroll else { return }
            // The point of the rack in the middle of the screen, as a fraction of its size.
            let oldSize = host.view.frame.size
            let middle = CGPoint(x: scroll.contentOffset.x + scroll.bounds.width / 2,
                                 y: scroll.contentOffset.y + scroll.bounds.height / 2)
            let fraction = CGPoint(x: oldSize.width > 0 ? middle.x / oldSize.width : 0.5,
                                   y: oldSize.height > 0 ? middle.y / oldSize.height : 0.5)

            host.rootView = content()
            scroll.minimumZoomScale = 1 / level
            scroll.maximumZoomScale = ZoomableRack.maxZoom / level
            // Our pinch zooms (see the type's comment); the scroll view's own one would only
            // compete with it where it works.
            scroll.pinchGestureRecognizer?.isEnabled = false
            if scroll.zoomScale != 1 { scroll.zoomScale = 1 }
            report(scroll)
            reportViewport()
            let size = host.sizeThatFits(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
            guard size != oldSize || !keepingCenter else { return }
            host.view.frame = CGRect(origin: .zero, size: size)
            scroll.contentSize = size
            scroll.centerContent()
            if !keepingCenter {
                // From the top left: a rack larger than the screen starts at its first row.
                scroll.contentOffset = CGPoint(x: -scroll.contentInset.left, y: -scroll.contentInset.top)
            } else {
                let maxX = max(0, size.width - scroll.bounds.width)
                let maxY = max(0, size.height - scroll.bounds.height)
                let x = fraction.x * size.width - scroll.bounds.width / 2
                let y = fraction.y * size.height - scroll.bounds.height / 2
                scroll.contentOffset = CGPoint(x: min(maxX, max(0, x)) - scroll.contentInset.left,
                                               y: min(maxY, max(0, y)) - scroll.contentInset.top)
            }
        }
    }
}

/// A scroll view that keeps its content centered while it is smaller than the screen.
final class CenteringScrollView: UIScrollView {
    weak var zoomedView: UIView?

    override func layoutSubviews() {
        super.layoutSubviews()
        centerContent()
    }

    func centerContent() {
        guard let zoomedView else { return }
        let size = zoomedView.frame.size
        let horizontal = max(0, (bounds.width - size.width) / 2)
        let vertical = max(0, (bounds.height - size.height) / 2)
        let insets = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
        if contentInset != insets { contentInset = insets }
    }
}

private extension Rack {
    /// What changes the drawing's size: kind, depth, columns per row, layers per shelf, zones
    /// and head-to-tail backs (drawn half a place wider).
    var shape: String {
        "\(kind) \(positionsPerCell) " + (0..<rows).map { "\(columns(inRow: $0))" }.joined(separator: ",")
            + " \(shelfLayers ?? []) \(zoneBreaks ?? []) \(isBackReversed)"
    }
}
#endif
