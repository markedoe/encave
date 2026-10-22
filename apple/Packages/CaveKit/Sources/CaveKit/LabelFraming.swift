import Foundation

/// The rules that frame a cut-out bottle on its label for the studio look of a
/// label photo (the Apple app finds the cut-out and the text with Vision, on the
/// device). Positions go from the top of the bottle (0) to its bottom (1).
///
/// The result keeps the full width of the bottle (its sides frame the label),
/// 15% of the label's height above it (the shoulder shows) and 5% below, made
/// taller to `aspect` when the label is short so it fills the list pictures.
public enum LabelFraming {
    /// Width over height of the result for a short label: about the list pictures
    /// (34 × 46 and 44 × 56 points), so the label fills them.
    public static let aspect = 0.72
    /// A cut-out wider than this, compared with its height, is not a standing
    /// bottle (a close-up of a label, several bottles): it is left whole.
    public static let bottleAspect = 0.55
    /// Text covering more of the cut-out than this: already a close-up of the label.
    public static let closeUpTextCoverage = 0.4
    /// Where labels sit on a bottle, used when neither text nor a rectangle is found.
    public static let fallbackBand: ClosedRange<Double> = 0.35...0.85

    /// A line of text read on the bottle, in Vision's coordinates (0 to 1, from the bottom).
    public struct Line: Equatable, Sendable {
        public var minY: Double
        public var maxY: Double
        public var width: Double

        public init(minY: Double, maxY: Double, width: Double) {
            self.minY = minY
            self.maxY = maxY
            self.width = width
        }

        var height: Double { maxY - minY }
    }

    public static func isStandingBottle(width: Double, height: Double) -> Bool {
        height > 0 && width / height < bottleAspect
    }

    /// The label from the lines of text read on the bottle, from the top, with some
    /// paper around the text, and how much of the cut-out the text covers. Lines
    /// too small to count are ignored; lines that follow each other are grouped and
    /// the largest group is the label (not a word on the capsule or the neck).
    public static func label(lines: [Line]) -> (band: ClosedRange<Double>, coverage: Double)? {
        let kept = lines.filter { $0.height >= 0.015 && $0.width >= 0.08 }.sorted { $0.maxY > $1.maxY }
        var groups: [(minY: Double, maxY: Double, area: Double)] = []
        for line in kept {
            let area = line.width * line.height
            if let last = groups.last, last.minY - line.maxY < 0.08 {
                groups[groups.count - 1] = (min(last.minY, line.minY), max(last.maxY, line.maxY), last.area + area)
            } else {
                groups.append((line.minY, line.maxY, area))
            }
        }
        guard let group = groups.max(by: { $0.area < $1.area }) else { return nil }
        let top = 1 - group.maxY, bottom = 1 - group.minY
        // The paper goes on above and below its text.
        let paper = (bottom - top) * 0.25 + 0.02
        let coverage = kept.reduce(0) { $0 + $1.width * $1.height }
        return (max(0, top - paper)...min(1, bottom + paper), coverage)
    }

    /// The part of a bottle `width` × `height` to keep for a label at `label`. Nil
    /// when that is nearly the whole bottle anyway.
    public static func frame(label: ClosedRange<Double>, width: Double, height: Double) -> ClosedRange<Double>? {
        guard width > 0, height > 0 else { return nil }
        let labelHeight = label.upperBound - label.lowerBound
        var top = label.lowerBound - labelHeight * 0.15
        var bottom = label.upperBound + labelHeight * 0.05
        let wanted = width / aspect / height
        if bottom - top < wanted {
            let extra = wanted - (bottom - top)
            top -= extra * 0.6
            bottom += extra * 0.4
        }
        if top < 0 { bottom -= top; top = 0 }
        if bottom > 1 { top = max(0, top - (bottom - 1)); bottom = 1 }
        return bottom - top > 0.85 ? nil : top...bottom
    }
}
