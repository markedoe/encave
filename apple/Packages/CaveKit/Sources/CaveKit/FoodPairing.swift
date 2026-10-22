import Foundation

/// Kinds of dishes for food pairing. Works offline from classic pairing rules.
public enum DishCategory: String, Codable, CaseIterable, Sendable {
    case redMeat, poultry, pork, game, fish, shellfish, cheese, spicy, vegetarian, pasta, dessert, aperitif
}

/// One suggested bottle for a dish.
public struct PairingSuggestion: Identifiable, Hashable, Sendable {
    public var group: WineGroup
    /// 0...100, higher is better.
    public var score: Int
    /// Why it matches, as a reason code the UI turns into text.
    public var reasons: [PairingReason]
    public var id: UUID { group.id }

    /// From this score, the apps say "Great match" (the score itself is not shown).
    public static let greatMatch = 60
}

public enum PairingReason: String, Hashable, Sendable {
    case style, grape, region, readyToDrink
}

/// Classic pairing rules: preferred styles, grapes and regions per dish.
public struct PairingRule: Sendable {
    public var colors: [WineColor: Int]
    public var grapes: [String]
    public var regions: [String]

    public static func rule(for dish: DishCategory) -> PairingRule {
        switch dish {
        case .redMeat:
            PairingRule(colors: [.red: 60], grapes: ["cabernet sauvignon", "syrah", "shiraz", "malbec", "merlot", "nebbiolo", "tempranillo", "mourvèdre"],
                        regions: ["bordeaux", "rhône", "rhone", "rioja", "piemonte", "mendoza"])
        case .poultry:
            PairingRule(colors: [.white: 45, .red: 40, .rose: 30], grapes: ["chardonnay", "pinot noir", "gamay", "chenin blanc", "viognier"],
                        regions: ["bourgogne", "burgundy", "beaujolais", "loire"])
        case .pork:
            PairingRule(colors: [.red: 45, .white: 40, .rose: 35], grapes: ["pinot noir", "riesling", "gamay", "grenache", "chenin blanc"],
                        regions: ["alsace", "beaujolais", "loire"])
        case .game:
            PairingRule(colors: [.red: 60], grapes: ["pinot noir", "syrah", "nebbiolo", "grenache", "mourvèdre"],
                        regions: ["bourgogne", "burgundy", "rhône", "rhone", "piemonte"])
        case .fish:
            PairingRule(colors: [.white: 60, .sparkling: 35, .rose: 30], grapes: ["sauvignon blanc", "chardonnay", "chasselas", "albariño", "muscadet", "melon de bourgogne", "riesling"],
                        regions: ["loire", "bourgogne", "chablis", "vaud", "galicia"])
        case .shellfish:
            PairingRule(colors: [.white: 60, .sparkling: 50], grapes: ["muscadet", "melon de bourgogne", "chablis", "chardonnay", "sauvignon blanc", "chasselas", "albariño"],
                        regions: ["loire", "chablis", "champagne", "vaud"])
        case .cheese:
            PairingRule(colors: [.red: 40, .white: 40, .dessert: 40, .fortified: 35], grapes: ["chardonnay", "pinot noir", "chenin blanc", "savagnin", "riesling"],
                        regions: ["jura", "bourgogne", "burgundy", "sauternes", "porto", "douro"])
        case .spicy:
            PairingRule(colors: [.white: 50, .rose: 45, .orange: 40], grapes: ["riesling", "gewurztraminer", "gewürztraminer", "pinot gris", "grenache"],
                        regions: ["alsace", "mosel"])
        case .vegetarian:
            PairingRule(colors: [.white: 45, .rose: 40, .red: 30, .orange: 40], grapes: ["sauvignon blanc", "pinot noir", "grüner veltliner", "gamay", "chenin blanc"],
                        regions: ["loire", "beaujolais", "provence"])
        case .pasta:
            PairingRule(colors: [.red: 50, .white: 35], grapes: ["sangiovese", "barbera", "nebbiolo", "montepulciano", "primitivo", "grenache"],
                        regions: ["toscana", "tuscany", "piemonte", "rhône", "rhone"])
        case .dessert:
            PairingRule(colors: [.dessert: 70, .sparkling: 35, .fortified: 55], grapes: ["sémillon", "semillon", "muscat", "riesling", "gewurztraminer", "chenin blanc"],
                        regions: ["sauternes", "tokaj", "porto", "alsace", "layon"])
        case .aperitif:
            PairingRule(colors: [.sparkling: 70, .white: 45, .rose: 40], grapes: ["chardonnay", "chasselas", "sauvignon blanc", "riesling"],
                        regions: ["champagne", "vaud", "loire", "alsace"])
        }
    }
}

public extension Cellar {
    /// Bottles in the cellar that go well with a dish, best first. Bottles reserved
    /// for an occasion are left out, as in Tonight.
    func pairings(for dish: DishCategory, year: Int, limit: Int = 10) -> [PairingSuggestion] {
        let rule = PairingRule.rule(for: dish)
        return groups().compactMap { all -> PairingSuggestion? in
            let free = all.bottles.filter { $0.reservedFor == nil }
            guard !free.isEmpty else { return nil }
            let group = WineGroup(wine: all.wine, bottles: free)
            let wine = group.wine
            var score = 0
            var reasons: [PairingReason] = []
            if let color = wine.color, let points = rule.colors[color] {
                score += points
                reasons.append(.style)
            }
            let grapes = wine.grapes.map { $0.lowercased() }
            if grapes.contains(where: { grape in rule.grapes.contains { grape.contains($0) } }) {
                score += 25
                reasons.append(.grape)
            }
            let places = [wine.region, wine.appellation, wine.country].compactMap { $0?.lowercased() }
            if places.contains(where: { place in rule.regions.contains { place.contains($0) } }) {
                score += 15
                reasons.append(.region)
            }
            guard score > 0 else { return nil }
            switch Maturity.of(wine, year: year) {
            case .ready, .drinkSoon:
                score += 10
                reasons.append(.readyToDrink)
            case .young:
                score -= 15
            case .pastPeak:
                score -= 5
            case .unknown:
                break
            }
            return PairingSuggestion(group: group, score: min(100, max(0, score)), reasons: reasons)
        }
        .sorted { $0.score != $1.score ? $0.score > $1.score : Cellar.nameOrder($0.group.wine, $1.group.wine) }
        .prefix(limit).map { $0 }
    }
}
