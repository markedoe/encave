#if os(iOS)
import CaveKit
import SwiftUI
import UIKit

/// A rack in a native zooming scroll view, as in Photos or Maps: pinch to zoom up to 4×,
/// double-tap to zoom in on a spot (or back out), pan when zoomed in.
/// When a pinch ends the rack is drawn again at the new size, so labels stay sharp
/// and names appear once cells are large enough.
struct ZoomableRack: UIViewRepresentable {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let baseCellSize: CGFloat
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var selectedCell: RackCell?
    var focusWine: UUID?
    var onTap: (RackCell, [Bottle]) -> Void
    var onDrop: ((UUID, RackCell) -> Bool)?
    var zoomStep = RackZoomStep()

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

        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.cancelsTouchesInView = false
        scroll.addGestureRecognizer(doubleTap)
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
        /// SwiftUI asked for a redraw while the rack was being zoomed or scrolled.
        var hasPendingUpdate = false

        /// Zooming, bouncing back or scrolling: the drawing and its frame must not change.
        var isBusy: Bool {
            guard let scroll else { return false }
            let pinch = scroll.pinchGestureRecognizer?.state
            return scroll.isZooming || scroll.isZoomBouncing || scroll.isDragging || scroll.isDecelerating
                || pinch == .began || pinch == .changed
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

        @objc func doubleTapped(_ gesture: UITapGestureRecognizer) {
            guard let scroll, let content = host?.view else { return }
            if level > 1.01 || scroll.zoomScale > 1.01 {
                // Back to the whole rack.
                scroll.setZoomScale(scroll.minimumZoomScale, animated: true)
            } else {
                let point = gesture.location(in: content)
                let scale = min(2.5, scroll.maximumZoomScale)
                let size = CGSize(width: scroll.bounds.width / scale, height: scroll.bounds.height / scale)
                scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2,
                                       width: size.width, height: size.height), animated: true)
            }
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
                         highlighted: parent.highlighted, selectedCell: parent.selectedCell,
                         focusWine: parent.focusWine, onTap: parent.onTap, onDrop: parent.onDrop)
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
            if scroll.zoomScale != 1 { scroll.zoomScale = 1 }
            report(scroll)
            let size = host.sizeThatFits(in: CGSize(width: CGFloat.infinity, height: CGFloat.infinity))
            guard size != oldSize || !keepingCenter else { return }
            host.view.frame = CGRect(origin: .zero, size: size)
            scroll.contentSize = size
            scroll.centerContent()
            if keepingCenter {
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
    /// What changes the drawing's size: kind, depth, columns per row and layers per shelf.
    var shape: String {
        "\(kind) \(positionsPerCell) " + (0..<rows).map { "\(columns(inRow: $0))" }.joined(separator: ",")
            + " \(shelfLayers ?? [])"
    }
}
#endif
