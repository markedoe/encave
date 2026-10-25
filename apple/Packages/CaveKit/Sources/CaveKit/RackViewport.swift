import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

/// How a storage is laid out on screen, the same on both platforms (`RackViewport.kt`):
/// fitted when every place is at least a touch target, else at that size, scrolling, with
/// an overview of the whole storage showing the part in view. Pure numbers: the drawing
/// itself is in the apps.
public enum RackViewport {
    /// Smallest touch target: 44 pt on Apple platforms (48 dp on Android).
    public static let minimumTarget: CGFloat = 44

    /// The back places of a fridge drawn front and back, as a share of the front ones' height
    /// (their width stays, so each back place sits right under its front one).
    public static let backPlaceHeight: CGFloat = 0.78

    /// The cell size at which the smallest place of `rack` is `target` wide and tall: the back
    /// places of a fridge drawn front and back are shorter than the cell.
    public static func minimumCellSize(for rack: Rack, target: CGFloat = minimumTarget) -> CGFloat {
        rack.hasDepthBands ? target / backPlaceHeight : target
    }

    /// A storage only slightly larger than the space is fitted rather than scrolled by a few
    /// points, as long as its places stay at least this large.
    public static let nearFitTarget: CGFloat = 40
    /// How much larger than the space a storage may be at the minimum and still be fitted.
    public static let nearFitMargin: CGFloat = 1.1

    /// Where a storage that scrolls opens: its top-leading corner (fractions of the drawing).
    public static let start = CGPoint.zero

    /// Whether the storage scrolls: its places would be smaller than `minimum` when fitted, and
    /// it is more than 10 % larger than the space at the minimum, or its places would be smaller
    /// than `nearFit` (the cell size for `nearFitTarget`).
    public static func scrolls(fitted: CGFloat, minimum: CGFloat, nearFit: CGFloat) -> Bool {
        fitted < minimum && !(fitted >= nearFit && minimum <= fitted * nearFitMargin)
    }

    /// The cell size to draw at: the fitted one, unless the storage scrolls at the minimum.
    public static func cellSize(fitted: CGFloat, minimum: CGFloat, nearFit: CGFloat) -> CGFloat {
        scrolls(fitted: fitted, minimum: minimum, nearFit: nearFit) ? minimum : fitted
    }

    /// The part of the drawing in view, as fractions of it (0...1). `offset` is the point of
    /// the drawing at the view's top left corner.
    public static func visibleArea(content: CGSize, view: CGSize, offset: CGPoint) -> CGRect {
        func span(_ start: CGFloat, _ length: CGFloat, _ total: CGFloat) -> (CGFloat, CGFloat) {
            guard total > 0 else { return (0, 1) }
            let low = min(1, max(0, start / total)), high = min(1, max(0, (start + length) / total))
            return (low, max(0, high - low))
        }
        let (x, width) = span(offset.x, view.width, content.width)
        let (y, height) = span(offset.y, view.height, content.height)
        return CGRect(x: x, y: y, width: width, height: height)
    }

    /// The offset that brings `point` (fractions of the drawing) to the middle of the view,
    /// as far as the drawing allows: a tap on the overview, a bottle to show.
    public static func offset(centering point: CGPoint, content: CGSize, view: CGSize) -> CGPoint {
        func axis(_ fraction: CGFloat, _ total: CGFloat, _ length: CGFloat) -> CGFloat {
            min(max(0, total - length), max(0, fraction * total - length / 2))
        }
        return CGPoint(x: axis(point.x, content.width, view.width), y: axis(point.y, content.height, view.height))
    }

    /// Where a place is in the drawing, as fractions of it (its middle, roughly: captions and
    /// shelf bars aside). On a fridge drawn front and back, each shelf two deep shows its front
    /// layers, then its back ones, half a place right when head-to-tail; a shelf one deep
    /// shows its layers once.
    public static func center(of cell: RackCell, depth: Int? = nil, in rack: Rack) -> CGPoint {
        let columns = CGFloat(max(1, rack.maxColumns)) + CGFloat(rack.maxBandShift)
            + (rack.kind == .staggered ? 0.5 : 0)
        let shift = (rack.kind == .staggered && rack.isOffset(row: cell.row) ? 0.5 : 0)
            + CGFloat(rack.bandShift(row: cell.row, depth: depth))
        let x = (CGFloat(cell.column) + 0.5 + shift) / columns
        guard rack.hasDepthBands, let shelf = rack.shelfRows.firstIndex(where: { $0.contains(cell.row) }) else {
            return CGPoint(x: min(1, x), y: (CGFloat(cell.row) + 0.5) / CGFloat(max(1, rack.rows)))
        }
        // Each shelf two deep is drawn twice: its front layers, then its back ones.
        let lines = { (index: Int) in rack.shelfRows[index].count * (rack.hasDepthBands(shelf: index) ? 2 : 1) }
        let before = (0..<shelf).reduce(0) { $0 + lines($1) }
        let total = rack.shelfRows.indices.reduce(0) { $0 + lines($1) }
        let rows = rack.shelfRows[shelf]
        let back = depth == 1 && rack.hasDepthBands(shelf: shelf)
        let line = before + (back ? rows.count : 0) + (cell.row - rows.lowerBound)
        return CGPoint(x: min(1, x), y: (CGFloat(line) + 0.5) / CGFloat(max(1, total)))
    }
}

/// The symbol drawn in a bottle end and next to the style's name, so a style never relies on
/// color alone: each differs by its shape. The same choice on Android (`WineStyleMark.kt`).
public enum WineStyleMark: String, CaseIterable, Sendable {
    case glass, sun, heart, leaf, bubbles, drop, shield

    /// The SF Symbol (Material icons on Android: WineBar, WbSunny, Favorite, Eco,
    /// BubbleChart, WaterDrop, Shield).
    public var systemImage: String {
        switch self {
        case .glass: "wineglass.fill"
        case .sun: "sun.max.fill"
        case .heart: "heart.fill"
        case .leaf: "leaf.fill"
        case .bubbles: "bubbles.and.sparkles.fill"
        case .drop: "drop.fill"
        case .shield: "shield.fill"
        }
    }
}

extension WineColor {
    /// Red a glass, white a sun, rosé a heart, orange a leaf, sparkling bubbles, dessert a
    /// drop, fortified a shield.
    public var styleMark: WineStyleMark {
        switch self {
        case .red: .glass
        case .white: .sun
        case .rose: .heart
        case .orange: .leaf
        case .sparkling: .bubbles
        case .dessert: .drop
        case .fortified: .shield
        }
    }
}
