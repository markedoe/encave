import Foundation

/// Which entries of the Journal's diary are shown.
public enum DiaryFilter: String, CaseIterable, Sendable {
    /// Everything, newest first.
    case all
    /// Bottles of the cellar that were opened.
    case cellar
    /// Wines tasted elsewhere (restaurant, friends, a fair).
    case elsewhere
    /// Wines tasted elsewhere that are on the shopping list.
    case toBuy

    /// Whether this filter shows tastings of bottles from the cellar.
    public var showsTastings: Bool { self == .all || self == .cellar }
    /// Whether this filter shows wines tasted elsewhere (all of them, or those to buy).
    public var showsElsewhere: Bool { self != .cellar }
}

/// One entry of the diary: a bottle of the cellar that was opened, or a wine tasted elsewhere.
public enum DiaryEntry: Identifiable, Hashable, Sendable {
    case tasting(Bottle)
    case elsewhere(Discovery)

    public var id: UUID {
        switch self {
        case .tasting(let bottle): bottle.id
        case .elsewhere(let discovery): discovery.id
        }
    }

    public var wineID: UUID {
        switch self {
        case .tasting(let bottle): bottle.wineID
        case .elsewhere(let discovery): discovery.wineID
        }
    }

    /// When it was tasted. A bottle opened without a tasting (imported) uses the day it was added.
    public var date: Date {
        switch self {
        case .tasting(let bottle): bottle.tasting?.date ?? bottle.addedAt
        case .elsewhere(let discovery): discovery.date
        }
    }

    public var rating: Int? {
        switch self {
        case .tasting(let bottle): bottle.tasting?.rating
        case .elsewhere(let discovery): discovery.rating
        }
    }

    /// The precise score out of 100, when the rating was entered out of 20 or 100.
    public var score100: Double? {
        switch self {
        case .tasting(let bottle): bottle.tasting?.score100
        case .elsewhere(let discovery): discovery.score100
        }
    }
}

public extension Cellar {
    /// The Journal's diary: opened bottles and wines tasted elsewhere, newest first
    /// (same date: by id, so every device shows the same order).
    ///
    /// `query` looks in the wine (name, producer, origin, grapes, tags, details), the tasting
    /// note (note, occasion, full note, food, aromas, hue) and, for wines tasted elsewhere, the place, note and tags.
    /// `tag` keeps only the wines tasted elsewhere that have it.
    func diary(filter: DiaryFilter = .all, query: String = "", tag: String? = nil) -> [DiaryEntry] {
        var entries: [DiaryEntry] = []
        if filter.showsTastings, tag == nil {
            entries += tastings(matching: query).map(DiaryEntry.tasting)
        }
        if filter.showsElsewhere {
            let found = discoveries(filter: DiscoveryFilter(query: query, tag: tag, toBuyOnly: filter == .toBuy))
            entries += found.map(DiaryEntry.elsewhere)
        }
        return entries.sorted { a, b in
            a.date == b.date ? a.id.uuidString < b.id.uuidString : a.date > b.date
        }
    }
}
