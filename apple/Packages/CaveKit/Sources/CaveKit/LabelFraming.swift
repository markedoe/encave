import Foundation

/// The rules that frame a cut-out bottle on its label for the studio look of a
/// label photo (the Apple app finds the cut-out and the text with Vision, on the
/// device). Positions go from the top of the bottle (0) to its bottom (1).
///
/// The result keeps the full width of the bottle (its sides frame the label),
/// 15% of the label's height above it and 10% below, so the label is about 80%
/// of its height; it is shown as tall as the picture, the glass running off its
/// top and bottom edges.
public enum LabelFraming {
    /// Above and below the label, as a share of its height.
    public static let above = 0.15
    public static let below = 0.10
    /// Text lines leaning more than this (degrees) are not straightened: the photo
    /// was taken sideways or the reading is unsure.
    public static let maximumTilt = 25.0
    /// Text lines leaning less than this are left as they are.
    public static let minimumTilt = 1.5
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
        let top = max(0, label.lowerBound - labelHeight * above)
        let bottom = min(1, label.upperBound + labelHeight * below)
        return bottom - top > 0.9 ? nil : top...bottom
    }

    /// The angle to turn a photo by (degrees, counterclockwise) so its text lines
    /// are level: the median of the lines' angles, nil when they are level already
    /// or lean too much to be sure.
    public static func straightening(lineAngles: [Double]) -> Double? {
        guard !lineAngles.isEmpty else { return nil }
        let sorted = lineAngles.sorted()
        let median = sorted[sorted.count / 2]
        guard abs(median) >= minimumTilt, abs(median) <= maximumTilt else { return nil }
        return -median
    }
}
