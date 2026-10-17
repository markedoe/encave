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

/// What a monthly reminder says about the bottles in the cellar.
public struct DrinkingDigest: Equatable, Sendable {
    /// Bottles in the last year of their window.
    public var drinkSoon: Int
    /// Bottles whose window opens this year.
    public var newlyReady: Int
    /// Bottles past their window.
    public var pastPeak: Int
    /// The most urgent wine, to name in the reminder.
    public var firstWine: String?

    public var isEmpty: Bool { drinkSoon == 0 && newlyReady == 0 && pastPeak == 0 }
}

public extension Cellar {
    /// Counts for a reminder in `year`, computed on the device.
    func drinkingDigest(year: Int) -> DrinkingDigest {
        var digest = DrinkingDigest(drinkSoon: 0, newlyReady: 0, pastPeak: 0, firstWine: nil)
        var urgent: [(Maturity, String)] = []
        for bottle in bottlesInCellar {
            guard let wine = wines[bottle.wineID] else { continue }
            switch Maturity.of(wine, year: year) {
            case .drinkSoon:
                digest.drinkSoon += 1
                urgent.append((.drinkSoon, wine.displayName))
            case .pastPeak:
                digest.pastPeak += 1
                urgent.append((.pastPeak, wine.displayName))
            case .ready where wine.drinkFrom == year:
                digest.newlyReady += 1
            default:
                break
            }
        }
        digest.firstWine = urgent.sorted { ($0.0, $0.1) < ($1.0, $1.1) }.first?.1
        return digest
    }
}
