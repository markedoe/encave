import Foundation

/// What the iPhone and iPad widgets show: a small summary of the cellar, written by the
/// app into the App Group container it shares with its widget extension, and read by the
/// widgets. It holds a few counts and names, nothing else: never the cellar, keys or
/// notes. It stays on the device. (Android has its own, WidgetSnapshot.kt.)
public struct WidgetSnapshot: Codable, Sendable, Equatable {
    /// Bumped when the format changes in a way older widgets cannot read.
    public static let currentVersion = 1
    /// The App Group the app and its widgets share (apple/project.yml).
    public static let appGroup = "group.app.encave"
    /// File name of the snapshot in the App Group container.
    public static let fileName = "widget-snapshot.json"
    /// Days of Tonight's suggestion kept ahead, so the widgets change wine at midnight
    /// even when the app is not opened.
    public static let suggestionDays = 14
    /// Wines listed by the Drink Soon widget.
    public static let drinkSoonLimit = 3

    /// A wine to open, with its free bottles (not kept for an occasion).
    public struct Pick: Codable, Sendable, Equatable, Identifiable {
        public var wineID: UUID
        public var name: String
        public var producer: String?
        public var vintage: Int?
        public var color: WineColor?
        public var maturity: Maturity
        public var bottles: Int
        /// "Main rack · B3", the storage note, or nil.
        public var place: String?

        public var id: UUID { wineID }

        /// "Château X 2015", as the app names a wine.
        public var displayName: String { vintage.map { "\(name) \($0)" } ?? name }

        public init(wineID: UUID, name: String, producer: String? = nil, vintage: Int? = nil, color: WineColor? = nil,
                    maturity: Maturity, bottles: Int, place: String? = nil) {
            self.wineID = wineID
            self.name = name
            self.producer = producer
            self.vintage = vintage
            self.color = color
            self.maturity = maturity
            self.bottles = bottles
            self.place = place
        }
    }

    /// Tonight's suggestion for one day ("yyyy-MM-dd", `Cellar.tonightDay`).
    public struct Suggestion: Codable, Sendable, Equatable {
        public var day: String
        public var pick: Pick

        public init(day: String, pick: Pick) {
            self.day = day
            self.pick = pick
        }
    }

    public var version: Int
    /// The year the maturities were worked out for.
    public var year: Int
    /// Bottles in the cellar (not on order, not drunk).
    public var bottles: Int
    /// Wines with bottles in the cellar.
    public var wines: Int
    /// Bottles whose wine is still young.
    public var toKeep: Int
    /// Bottles in the last year of their window.
    public var drinkSoon: Int
    public var ready: Int
    /// Bottles bought but not delivered yet.
    public var onOrder: Int
    /// The first wines to drink soon, the window that closes first first.
    public var drinkSoonPicks: [Pick]
    /// Tonight's suggestion for today and the next days; a day with nothing ready is left out.
    public var suggestions: [Suggestion]

    public init(version: Int = WidgetSnapshot.currentVersion, year: Int, bottles: Int = 0, wines: Int = 0, toKeep: Int = 0,
                drinkSoon: Int = 0, ready: Int = 0, onOrder: Int = 0, drinkSoonPicks: [Pick] = [], suggestions: [Suggestion] = []) {
        self.version = version
        self.year = year
        self.bottles = bottles
        self.wines = wines
        self.toKeep = toKeep
        self.drinkSoon = drinkSoon
        self.ready = ready
        self.onOrder = onOrder
        self.drinkSoonPicks = drinkSoonPicks
        self.suggestions = suggestions
    }

    /// Nothing in the cellar yet: the widgets invite to add bottles.
    public var isEmpty: Bool { bottles == 0 && onOrder == 0 }

    /// The summary of `cellar` from `now`. Tonight's suggestions are those of Tonight's card
    /// for each day (`Cellar.tonightSuggestion`); `kept` is the wine Tonight showed first
    /// today, which stays today's suggestion. `place` names a bottle's position as the app does.
    public init(_ cellar: Cellar, now: Date, keeping kept: UUID? = nil, place: (Bottle) -> String? = { _ in nil }) {
        let year = CellarYear.of(now)
        let counts = cellar.counts(year: year)
        var ready = 0
        for bottle in cellar.bottlesInCellar {
            if let wine = cellar.wines[bottle.wineID], Maturity.of(wine, year: year) == .ready { ready += 1 }
        }
        func pick(_ group: WineGroup, year: Int) -> Pick {
            let wine = group.wine
            return Pick(wineID: wine.id, name: wine.name, producer: wine.producer, vintage: wine.vintage, color: wine.color,
                        maturity: Maturity.of(wine, year: year), bottles: group.count,
                        place: group.bottles.drinkChoices.lazy.compactMap(place).first)
        }
        let soon = cellar.tonightCandidates(year: year)
            .filter { Maturity.of($0.wine, year: year) == .drinkSoon }
            .prefix(Self.drinkSoonLimit)
            .map { pick($0, year: year) }
        let calendar = CellarYear.calendar
        let today = calendar.startOfDay(for: now)
        var suggestions: [Suggestion] = []
        for offset in 0..<Self.suggestionDays {
            guard let date = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            let day = Cellar.tonightDay(date), dayYear = CellarYear.of(date)
            if let group = cellar.tonightSuggestion(year: dayYear, day: day, keeping: offset == 0 ? kept : nil) {
                suggestions.append(Suggestion(day: day, pick: pick(group, year: dayYear)))
            }
        }
        self.init(year: year, bottles: counts.bottles, wines: counts.winesWithBottles, toKeep: counts.bottlesToKeep,
                  drinkSoon: counts.bottlesToDrinkSoon, ready: ready, onOrder: cellar.bottlesOnOrder.count,
                  drinkSoonPicks: Array(soon), suggestions: suggestions)
    }

    /// Tonight's suggestion on `date`. Past the last day kept (the app was not opened for
    /// two weeks), the last one; nil when nothing was ready.
    public func suggestion(on date: Date) -> Pick? {
        let day = Cellar.tonightDay(date)
        if let found = suggestions.first(where: { $0.day == day }) { return found.pick }
        // "yyyy-MM-dd" sorts as dates.
        if let first = suggestions.first, day < first.day { return nil }
        return suggestions.last?.pick
    }

    /// When the widgets should be drawn again: `now`, then each midnight a suggestion
    /// starts after it.
    public func refreshDates(from now: Date) -> [Date] {
        let calendar = CellarYear.calendar
        let today = Cellar.tonightDay(now)
        var dates = [now]
        for suggestion in suggestions where suggestion.day > today {
            if let date = Self.date(of: suggestion.day, calendar: calendar) { dates.append(date) }
        }
        return dates
    }

    private static func date(of day: String, calendar: Calendar) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    /// Stable JSON: an unchanged cellar gives the same bytes, so nothing is rewritten.
    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    /// Reads a snapshot; nil when it is missing, damaged or from a newer format.
    public static func decode(_ data: Data?) -> WidgetSnapshot? {
        guard let data, let snapshot = try? JSONDecoder().decode(WidgetSnapshot.self, from: data) else { return nil }
        return snapshot.version <= currentVersion ? snapshot : nil
    }
}

/// The links a widget opens: encave://wine/<id>, encave://tonight, encave://cellar and
/// encave://add (Add Bottles, on the label scanner).
public enum WidgetLink: Equatable, Sendable {
    case wine(UUID)
    case tonight
    case cellar
    case addBottle

    public var url: URL {
        switch self {
        case .wine(let id): URL(string: "encave://wine/\(id.uuidString.lowercased())")!
        case .tonight: URL(string: "encave://tonight")!
        case .cellar: URL(string: "encave://cellar")!
        case .addBottle: URL(string: "encave://add")!
        }
    }

    public init?(url: URL) {
        guard url.scheme?.lowercased() == "encave", let host = url.host()?.lowercased() else { return nil }
        let path = url.pathComponents.filter { $0 != "/" }
        switch (host, path.count) {
        case ("wine", 1):
            guard let id = UUID(uuidString: path[0]) else { return nil }
            self = .wine(id)
        case ("tonight", 0): self = .tonight
        case ("cellar", 0): self = .cellar
        case ("add", 0): self = .addBottle
        default: return nil
        }
    }
}
