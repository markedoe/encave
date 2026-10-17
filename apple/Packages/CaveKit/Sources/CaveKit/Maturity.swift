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

/// How fast to drink the cellar so that bottles are opened before their window
/// ends, from the drinking windows alone.
public struct DrinkDownPlan: Equatable, Sendable {
    public struct Year: Identifiable, Equatable, Sendable {
        public var year: Int
        /// Bottles whose window ends that year.
        public var leaving: Int
        public var id: Int { year }
    }

    /// From the current year, up to the last year a window ends (at most 15 years).
    public var years: [Year]
    /// Bottles already past their window.
    public var pastPeak: Int
    /// Bottles without a drinking window, left out of the pace.
    public var withoutWindow: Int
    /// Bottles a month to open everything with a window before it ends (0 if none).
    public var bottlesPerMonth: Double

    public var isEmpty: Bool { years.allSatisfy { $0.leaving == 0 } && pastPeak == 0 }
}

public extension Cellar {
    /// The pace that keeps up with every window: for each year Y, the bottles
    /// whose window ends by Y (past-peak ones count now) over the months left
    /// until the end of Y; the plan takes the largest.
    func drinkDownPlan(year: Int, month: Int, horizon: Int = 15) -> DrinkDownPlan {
        var leaving: [Int: Int] = [:]
        var pastPeak = 0, withoutWindow = 0
        for bottle in bottlesInCellar {
            guard let wine = wines[bottle.wineID], let until = wine.drinkUntil else {
                withoutWindow += 1
                continue
            }
            if until < year { pastPeak += 1 } else { leaving[until, default: 0] += 1 }
        }
        let last = min(year + horizon - 1, leaving.keys.max() ?? year)
        let years = (year...max(year, last)).map { DrinkDownPlan.Year(year: $0, leaving: leaving[$0] ?? 0) }
        var cumulative = pastPeak
        var pace = 0.0
        for item in years {
            cumulative += item.leaving
            // Months left, counting the current one: 13 - month this year, then 12 a year.
            let months = Double(13 - month + 12 * (item.year - year))
            if cumulative > 0 { pace = max(pace, Double(cumulative) / months) }
        }
        return DrinkDownPlan(years: years, pastPeak: pastPeak, withoutWindow: withoutWindow, bottlesPerMonth: pace)
    }
}
