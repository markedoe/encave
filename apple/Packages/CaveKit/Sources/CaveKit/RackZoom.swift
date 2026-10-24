import Foundation

/// Mac: the zoom of a storage drawing, where 1 shows it at its base cell size. Zooming
/// out never draws places smaller than the minimum, so the zoom range is what changes
/// the drawing: Zoom Out stops (and is disabled) where it would have no effect.
public enum RackZoom {
    public static let maximum: CGFloat = 3
    /// The furthest out the buttons and the pinch go, when places stay large enough.
    public static let lowest: CGFloat = 0.6
    public static let step: CGFloat = 0.25

    /// The cell size drawn at `zoom`: never smaller than the minimum (nor than the base
    /// when the base is already smaller, as for a storage that scrolls).
    public static func cellSize(base: CGFloat, minimum: CGFloat, zoom: CGFloat) -> CGFloat {
        max(min(base, minimum), base * zoom)
    }

    /// The smallest zoom that still makes places smaller: 1 when they are at the minimum.
    public static func minimum(base: CGFloat, minimum: CGFloat) -> CGFloat {
        guard base > 0 else { return 1 }
        return min(1, max(lowest, min(base, minimum) / base))
    }

    /// `zoom` kept within what has an effect.
    public static func clamped(_ zoom: CGFloat, minimum: CGFloat) -> CGFloat {
        min(maximum, max(minimum, zoom))
    }
}
