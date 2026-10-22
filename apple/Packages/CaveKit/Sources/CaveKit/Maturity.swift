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

    /// Where the window comes from, to explain it.
    var windowSource: WindowSource {
        guard let from = drinkFrom, let until = drinkUntil else { return .missing }
        if let vintage, let estimate = estimatedWindow(), estimate.from == from, estimate.until == until {
            return .estimated(yearsAfterHarvest: from - vintage, yearsAtBest: until - from)
        }
        return .entered
    }

    /// Moves the end of the window by `years`, never before its start.
    func movingWindowEnd(by years: Int) -> Wine {
        var copy = self
        guard let until = drinkUntil else { return copy }
        copy.drinkUntil = max(drinkFrom ?? Int.min, until + years)
        return copy
    }
}

/// Why a wine has the drinking window it has.
public enum WindowSource: Equatable, Sendable {
    /// Worked out from the style and the vintage.
    case estimated(yearsAfterHarvest: Int, yearsAtBest: Int)
    /// Entered by the user or filled from the label.
    case entered
    case missing
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
            // The same windows as maturity: both years, in order.
            guard let wine = wines[bottle.wineID], let from = wine.drinkFrom, let until = wine.drinkUntil, from <= until else {
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

/// A numbered, compact description of the cellar for an on-device language model
/// ("Ask your cellar"): one line per wine, cut to fit the model's small context. The
/// wines closest to the question come first, then the most urgent. The numbers link
/// the model's answer back to wines.
public struct AssistantCatalog: Sendable {
    public struct Entry: Sendable, Equatable {
        public var number: Int
        public var wineID: UUID
        public var line: String
    }

    public var entries: [Entry]
    /// Wines left out to stay within `limit`.
    public var omitted: Int

    public var text: String { entries.map(\.line).joined(separator: "\n") }
    /// Wines in the cellar, listed or not.
    public var total: Int { entries.count + omitted }

    public func wineID(number: Int) -> UUID? { entries.first { $0.number == number }?.wineID }
}

public extension Cellar {
    /// `question` picks the wines kept when the cellar is larger than `limit`: those whose
    /// name, producer, origin, grapes or style share words with it, and those that suit a
    /// dish it names; then the most urgent, then by name.
    func assistantCatalog(year: Int, limit: Int = 60, question: String = "",
                          place: (Bottle) -> String? = { _ in nil }) -> AssistantCatalog {
        let urgent = groupsByMaturity(year: year).flatMap(\.groups)
        let tokens = AssistantRelevance.tokens(question)
        let dishes = AssistantRelevance.dishes(in: tokens)
        let pairing = Dictionary(dishes.flatMap { pairings(for: $0, year: year, limit: .max) }.map { ($0.group.wine.id, $0.score) },
                                 uniquingKeysWith: max)
        let groups: [WineGroup]
        if tokens.isEmpty {
            groups = urgent
        } else {
            // Sorted by score; the urgency order breaks ties.
            var scored: [(offset: Int, group: WineGroup, score: Int)] = []
            for (offset, group) in urgent.enumerated() {
                let words = AssistantRelevance.score(group.wine, tokens: tokens)
                let dish = pairing[group.wine.id] ?? 0
                scored.append((offset, group, words * 10 + dish / 10))
            }
            scored.sort { $0.score != $1.score ? $0.score > $1.score : $0.offset < $1.offset }
            groups = scored.map(\.group)
        }
        var entries: [AssistantCatalog.Entry] = []
        for (index, group) in groups.prefix(limit).enumerated() {
            let wine = group.wine
            let maturity: String = switch Maturity.of(wine, year: year) {
            case .drinkSoon: "drink soon"
            case .ready: "ready"
            case .young: "too young"
            case .pastPeak: "past its peak"
            case .unknown: "window unknown"
            }
            let origin = [wine.appellation, wine.region, wine.country].compactMap { $0 }.joined(separator: ", ")
            let parts: [String?] = [
                wine.displayName, wine.producer, wine.color?.rawValue, origin.isEmpty ? nil : origin,
                wine.grapes.isEmpty ? nil : wine.grapes.joined(separator: ", "), maturity,
                "\(group.count) bottle\(group.count == 1 ? "" : "s")", group.bottles.bottleToOpen.flatMap(place),
            ]
            // A missing field keeps its column ("–"), so the model reads every line against the header.
            entries.append(.init(number: index + 1, wineID: wine.id,
                                 line: "\(index + 1). " + parts.map { $0 ?? "–" }.joined(separator: " | ")))
        }
        return AssistantCatalog(entries: entries, omitted: max(0, groups.count - limit))
    }
}

/// How close a wine is to a question, in English or French.
enum AssistantRelevance {
    /// Words too common to tell wines apart.
    static let stopWords: Set<String> = [
        "the", "and", "for", "with", "what", "which", "that", "this", "tonight", "wine", "wines", "bottle", "bottles",
        "something", "goes", "good", "some", "from", "have", "who", "love", "loves", "bring", "friends",
        "les", "des", "une", "pour", "avec", "que", "qui", "quoi", "quel", "quelle", "vin", "vins", "bouteille",
        "bouteilles", "soir", "chose", "aller", "bien", "dans", "sur", "est", "aiment", "amis", "apporter",
    ]

    /// Lowercase words without accents, of three letters or more.
    static func tokens(_ text: String) -> [String] {
        Occasion.fold(text)
            .split { !$0.isLetter }
            .map(String.init)
            .filter { $0.count >= 3 && !stopWords.contains($0) }
    }

    /// Words of the question found among the wine's words (a longer word may start with it).
    static func score(_ wine: Wine, tokens: [String]) -> Int {
        var fields: [String] = [wine.name]
        fields += [wine.producer, wine.appellation, wine.region, wine.country].compactMap { $0 }
        fields += wine.grapes
        if let color = wine.color { fields += [color.rawValue, styleWords(color)] }
        let words = Set(fields.flatMap { Occasion.fold($0).split { !$0.isLetter }.map(String.init) })
        return Set(tokens).filter { token in
            if words.contains(token) { return true }
            // "bourguignon" and "bourgogne" no, but "grenaches" and "grenache" yes.
            guard token.count >= 5 else { return false }
            return words.contains { word in word.count >= 5 && (word.hasPrefix(token) || token.hasPrefix(word)) }
        }.count
    }

    /// The style in words people use for it.
    static func styleWords(_ color: WineColor) -> String {
        switch color {
        case .red: "red rouge"
        case .white: "white blanc"
        case .rose: "rose rosé"
        case .sparkling: "sparkling bubbles champagne effervescent bulles petillant"
        case .dessert: "dessert sweet liquoreux moelleux doux"
        case .fortified: "fortified port porto mute"
        case .orange: "orange skin maceration"
        }
    }

    /// Dishes named in the question.
    static func dishes(in tokens: [String]) -> [DishCategory] {
        let words: [DishCategory: [String]] = [
            .redMeat: ["beef", "steak", "lamb", "boeuf", "agneau", "entrecote", "burger"],
            .poultry: ["chicken", "duck", "turkey", "poultry", "poulet", "canard", "dinde", "volaille"],
            .pork: ["pork", "ham", "sausage", "porc", "jambon", "saucisse", "charcuterie"],
            .game: ["game", "venison", "boar", "gibier", "chevreuil", "sanglier", "cerf"],
            .fish: ["fish", "salmon", "tuna", "cod", "poisson", "saumon", "thon", "cabillaud", "sushi"],
            .shellfish: ["shellfish", "oyster", "oysters", "shrimp", "lobster", "mussels", "huitre", "huitres",
                         "crevette", "crevettes", "homard", "moules", "coquillages"],
            .cheese: ["cheese", "fromage", "fromages", "fondue", "raclette"],
            .spicy: ["spicy", "curry", "thai", "chili", "epice", "epicee", "epices", "pimente"],
            .vegetarian: ["vegetarian", "vegetables", "salad", "ratatouille", "vegetarien", "legumes", "salade"],
            .pasta: ["pasta", "pizza", "risotto", "lasagna", "pates", "lasagnes"],
            .dessert: ["dessert", "cake", "chocolate", "tart", "gateau", "chocolat", "tarte"],
            .aperitif: ["aperitif", "apero", "appetizer", "drinks"],
        ]
        return DishCategory.allCases.filter { dish in words[dish, default: []].contains { tokens.contains($0) } }
    }
}
