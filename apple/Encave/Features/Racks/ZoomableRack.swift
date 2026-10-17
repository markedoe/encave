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
        if coordinator.rackShape != rack.shape {
            // New rack shape: start again from the fitted size.
            coordinator.rackShape = rack.shape
            coordinator.base = baseCellSize
            coordinator.level = 1
            coordinator.render(keepingCenter: false)
        } else if coordinator.base != baseCellSize {
            // The space changed (card shown, rotation): keep the zoom and the spot in view.
            coordinator.base = baseCellSize
            coordinator.render(keepingCenter: true)
        } else {
            coordinator.render(keepingCenter: false)
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

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { host?.view }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            (scrollView as? CenteringScrollView)?.centerContent()
        }

        func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            let newLevel = min(ZoomableRack.maxZoom, max(1, level * scale))
            guard abs(newLevel - level) > 0.01 || scale != 1 else { return }
            level = newLevel
            render(keepingCenter: true)
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

        /// Draws the rack at `base × level`, then resets the scroll view's own scale to 1.
        func render(keepingCenter: Bool) {
            guard let parent, let model, let host, let scroll else { return }
            // The point of the rack in the middle of the screen, as a fraction of its size.
            let oldSize = host.view.frame.size
            let middle = CGPoint(x: scroll.contentOffset.x + scroll.bounds.width / 2,
                                 y: scroll.contentOffset.y + scroll.bounds.height / 2)
            let fraction = CGPoint(x: oldSize.width > 0 ? middle.x / oldSize.width : 0.5,
                                   y: oldSize.height > 0 ? middle.y / oldSize.height : 0.5)

            host.rootView = AnyView(
                RackGrid(rack: parent.rack, cellSize: base * level, selected: parent.selected,
                         highlighted: parent.highlighted, selectedCell: parent.selectedCell,
                         focusWine: parent.focusWine, onTap: parent.onTap)
                    .padding(FittedRackGrid.padding)
                    .environment(model)
            )
            scroll.minimumZoomScale = 1 / level
            scroll.maximumZoomScale = ZoomableRack.maxZoom / level
            if scroll.zoomScale != 1 { scroll.zoomScale = 1 }
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
    /// What changes the drawing's size: kind, depth and columns per row.
    var shape: String { "\(kind) \(positionsPerCell) " + (0..<rows).map { "\(columns(inRow: $0))" }.joined(separator: ",") }
}
#endif
