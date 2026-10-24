/// Geometry of the drinking-window gauge: the window as a band on a timeline of years,
/// with a marker for this year and the "Today" label under the marker.
public struct MaturityTimeline: Sendable, Equatable {
    public let from: Int
    public let until: Int
    public let year: Int

    public init(from: Int, until: Int, year: Int) {
        self.from = from
        self.until = until
        self.year = year
    }

    /// One year of margin on each side, and this year always shown.
    public var lower: Int { min(from, year) - 1 }
    public var upper: Int { max(until, year) + 1 }

    /// Where a year starts, from 0 (left) to 1 (right).
    public func position(_ year: Int) -> Double {
        Double(year - lower) / Double(max(1, upper - lower))
    }

    /// The middle of this year's cell, where the marker is.
    public var marker: Double { (position(year) + position(year + 1)) / 2 }

    /// Left edge of a label of `labelWidth` centred under `center` (in points), kept
    /// between the labels of the first and last years when it can, and within `width`.
    public static func labelOrigin(center: Double, labelWidth: Double, width: Double,
                                   leadingReserved: Double = 0, trailingReserved: Double = 0) -> Double {
        let low = leadingReserved
        let high = width - trailingReserved - labelWidth
        let wanted = center - labelWidth / 2
        if low <= high { return min(max(wanted, low), high) }
        return min(max(wanted, 0), max(0, width - labelWidth))
    }
}
