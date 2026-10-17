import Foundation

/// Where a wine is in its drinking window.
public enum Maturity: String, Codable, CaseIterable, Sendable, Comparable {
    /// Inside the window, not in its last year.
    case ready
    /// Last year of the window: drink soon.
    case drinkSoon
    /// Window has passed.
    case pastPeak
    /// Window not reached yet.
    case young
    /// No window known.
    case unknown

    /// Order used in "Tonight": most urgent first.
    private var urgency: Int {
        switch self {
        case .drinkSoon: 0
        case .ready: 1
        case .pastPeak: 2
        case .young: 3
        case .unknown: 4
        }
    }

    public static func < (lhs: Maturity, rhs: Maturity) -> Bool { lhs.urgency < rhs.urgency }

    public static func of(_ wine: Wine, year: Int) -> Maturity {
        guard let from = wine.drinkFrom, let until = wine.drinkUntil, from <= until else { return .unknown }
        if year < from { return .young }
        if year > until { return .pastPeak }
        if year == until { return .drinkSoon }
        return .ready
    }
}

public extension Wine {
    /// Rough drinking window when none is entered, from color and vintage.
    /// Users can always edit it; this only gives "Tonight" something to work with.
    func estimatedWindow() -> (from: Int, until: Int)? {
        guard let vintage else { return nil }
        let (start, length): (Int, Int) = switch color {
        case .red: (3, 12)
        case .white: (1, 6)
        case .rose: (0, 3)
        case .sparkling: (1, 8)
        case .dessert: (3, 25)
        case .fortified: (5, 30)
        case .orange: (1, 8)
        case nil: (2, 8)
        }
        return (vintage + start, vintage + start + length)
    }
}
