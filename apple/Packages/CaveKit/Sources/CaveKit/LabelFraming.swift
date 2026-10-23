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

    /// Kept around a label found from its paper (`LabelPaper`), as a share of the picture.
    public static let paperMargin = 0.06

    /// The part to keep for a label at `text` (from its text) and `paper` (from its
    /// paper), either one missing: as `frame(label:width:height:)` for both together
    /// (text away from the paper is not on this label and is left out), widened so
    /// the paper keeps `paperMargin` on each side even where the text alone would
    /// cut it. Nil when that is nearly the whole picture, or with neither.
    public static func frame(text: ClosedRange<Double>?, paper: ClosedRange<Double>?, width: Double, height: Double)
        -> ClosedRange<Double>? {
        guard let paper else { return text.flatMap { frame(label: $0, width: width, height: height) } }
        guard width > 0, height > 0 else { return nil }
        let text = text.flatMap { $0.lowerBound < paper.upperBound && $0.upperBound > paper.lowerBound ? $0 : nil }
        let label = text.map { min($0.lowerBound, paper.lowerBound)...max($0.upperBound, paper.upperBound) } ?? paper
        let labelHeight = label.upperBound - label.lowerBound
        let top = max(0, min(label.lowerBound - labelHeight * above, paper.lowerBound - paperMargin))
        let bottom = min(1, max(label.upperBound + labelHeight * below, paper.upperBound + paperMargin))
        return bottom - top > 0.9 ? nil : top...bottom
    }

    /// The shape of a cut-out from its pixels (y going down): how far its main axis
    /// leans, how long it is and how much of its own frame, turned with it, it fills,
    /// all from its second moments. A leaning bottle fills its turned frame as well
    /// as an upright one fills its frame; it fills less of the upright frame around it.
    public struct Silhouette: Sendable {
        public private(set) var count = 0
        private var sumX = 0.0, sumY = 0.0, sumXX = 0.0, sumYY = 0.0, sumXY = 0.0

        public init() {}

        public mutating func add(x: Int, y: Int) {
            let fx = Double(x), fy = Double(y)
            count += 1
            sumX += fx
            sumY += fy
            sumXX += fx * fx
            sumYY += fy * fy
            sumXY += fx * fy
        }

        /// Variances along x and y, covariance, and variances along the main and the other axis.
        private var spread: (xx: Double, yy: Double, xy: Double, major: Double, minor: Double) {
            let n = Double(max(count, 1))
            let meanX = sumX / n, meanY = sumY / n
            let xx = sumXX / n - meanX * meanX, yy = sumYY / n - meanY * meanY, xy = sumXY / n - meanX * meanY
            let half = (xx - yy) / 2
            let root = (half * half + xy * xy).squareRoot()
            return (xx, yy, xy, (xx + yy) / 2 + root, max((xx + yy) / 2 - root, 1e-9))
        }

        /// How far the main axis leans from upright (degrees, positive when the top
        /// leans right, which a counterclockwise turn by as much puts upright).
        public var lean: Double {
            let s = spread
            let theta = 0.5 * atan2(2 * s.xy, s.xx - s.yy) * 180 / Double.pi
            return theta > 0 ? theta - 90 : theta + 90
        }

        /// The main axis over the other one.
        public var elongation: Double {
            let s = spread
            return (s.major / s.minor).squareRoot()
        }

        /// The share of its turned frame it fills (1 for a rectangle at any angle):
        /// a rectangle a × b has variances a²/12 and b²/12 along its sides.
        public var orientedFill: Double {
            let s = spread
            return min(1, Double(count) / max(12 * (s.major * s.minor).squareRoot(), 1))
        }
    }

    /// The angle of a line of text (degrees, counterclockwise from level) from its
    /// left and right ends, in Vision's coordinates (0 to 1, from the bottom left) of
    /// a photo `aspect` times as wide as it is tall.
    public static func angle(fromX x1: Double, y1: Double, toX x2: Double, y2: Double, aspect: Double) -> Double {
        atan2(y2 - y1, (x2 - x1) * aspect) * 180 / Double.pi
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
