import Foundation

/// What the home screen and lock screen widgets show: a small summary of the
/// cellar for a year, written by the app into its App Group container and read
/// by the widget extension. It holds counts and a few names, nothing else.
public struct WidgetSnapshot: Codable, Equatable, Sendable {
    /// A wine to open tonight, with its bottles left for tonight.
    public struct Pick: Codable, Equatable, Sendable, Identifiable {
        public var wineID: UUID
        public var name: String
        public var vintage: Int?
        public var color: WineColor?
        public var maturity: Maturity
        /// Bottles of the wine not kept for an occasion.
        public var bottles: Int
        /// "Main rack · B3", the storage note, or nil.
        public var place: String?

        public var id: UUID { wineID }

        public init(wineID: UUID, name: String, vintage: Int?, color: WineColor?, maturity: Maturity,
                    bottles: Int, place: String?) {
            self.wineID = wineID
            self.name = name
            self.vintage = vintage
            self.color = color
            self.maturity = maturity
            self.bottles = bottles
            self.place = place
        }
    }

    /// Bumped when the format changes in a way older widgets cannot read.
    public static let currentVersion = 1
    /// How many picks a snapshot keeps (the large widget shows them all).
    public static let pickLimit = 6

    public var version: Int
    public var year: Int
    /// Bottles in the cellar (not on order, not drunk).
    public var bottles: Int
    /// Wines with bottles in the cellar.
    public var wines: Int
    public var drinkSoon: Int
    public var ready: Int
    public var young: Int
    public var pastPeak: Int
    /// Bottles bought but not delivered yet.
    public var onOrder: Int
    /// The bottles to open tonight, the most urgent first.
    public var tonight: [Pick]

    public init(version: Int = WidgetSnapshot.currentVersion, year: Int, bottles: Int, wines: Int,
                drinkSoon: Int, ready: Int, young: Int, pastPeak: Int, onOrder: Int, tonight: [Pick]) {
        self.version = version
        self.year = year
        self.bottles = bottles
        self.wines = wines
        self.drinkSoon = drinkSoon
        self.ready = ready
        self.young = young
        self.pastPeak = pastPeak
        self.onOrder = onOrder
        self.tonight = tonight
    }

    /// Nothing in the cellar yet: the widgets invite to add bottles.
    public var isEmpty: Bool { bottles == 0 && onOrder == 0 }
}

public extension WidgetSnapshot {
    /// The summary of `cellar` in `year`. Bottles kept for an occasion count in
    /// the totals but are never suggested for tonight, as in the Tonight section.
    init(cellar: Cellar, year: Int, pickLimit: Int = WidgetSnapshot.pickLimit) {
        let inCellar = cellar.bottlesInCellar
        var counts: [Maturity: Int] = [:]
        for bottle in inCellar {
            guard let wine = cellar.wines[bottle.wineID] else { continue }
            counts[Maturity.of(wine, year: year), default: 0] += 1
        }
        let urgent: Set<Maturity> = [.drinkSoon, .ready, .pastPeak]
        let picks = cellar.groupsByMaturity(year: year)
            .filter { urgent.contains($0.maturity) }
            .flatMap { section in
                // Within a section, the window that closes first comes first.
                section.groups.sorted { a, b in
                    let ua = a.wine.drinkUntil ?? .max, ub = b.wine.drinkUntil ?? .max
                    return ua != ub ? ua < ub : Cellar.nameOrder(a.wine, b.wine)
                }
                .map { group in
                    Pick(wineID: group.wine.id, name: group.wine.name, vintage: group.wine.vintage,
                         color: group.wine.color, maturity: section.maturity, bottles: group.count,
                         place: group.bottles.lazy.compactMap { cellar.locationLabel(for: $0) }.first)
                }
            }
            .prefix(max(0, pickLimit))
        self.init(
            year: year,
            bottles: inCellar.count,
            wines: Set(inCellar.compactMap { cellar.wines[$0.wineID] == nil ? nil : $0.wineID }).count,
            drinkSoon: counts[.drinkSoon] ?? 0,
            ready: counts[.ready] ?? 0,
            young: counts[.young] ?? 0,
            pastPeak: counts[.pastPeak] ?? 0,
            onOrder: cellar.bottlesOnOrder.count,
            tonight: Array(picks)
        )
    }

    /// The App Group shared by the app and its widget extension (iOS).
    static let appGroup = "group.app.encave"
    /// File name of the snapshot in the App Group container.
    static let fileName = "widget-snapshot.json"

    /// Stable JSON (sorted keys), so an unchanged cellar gives the same bytes.
    func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    /// Reads a snapshot; nil when the data is missing, damaged or from a newer format.
    static func decode(_ data: Data) -> WidgetSnapshot? {
        guard let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data),
              snapshot.version <= currentVersion else { return nil }
        return snapshot
    }
}
