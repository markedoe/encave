import Foundation

public extension Collection where Element == Bottle {
    /// One bottle per place to choose from when opening a wine, the free ones first.
    /// Bottles without a rack position, with the same storage note and kept for the
    /// same occasion (or none) are interchangeable, so only the first is listed.
    var drinkChoices: [Bottle] {
        var seen = Set<String>()
        let distinct = filter { bottle in
            let place = bottle.slot.map { "\($0.rackID)/\($0.row)/\($0.column)/\($0.depth)" } ?? "note:\(bottle.storageNote ?? "")"
            return seen.insert(place + "|" + (bottle.reservedFor?.lowercased() ?? "")).inserted
        }
        return distinct.filter { $0.reservedFor == nil } + distinct.filter { $0.reservedFor != nil }
    }
}

public extension Cellar {
    /// The wines Tonight suggests, best first: those to drink soon, then those ready;
    /// within each, the earliest end of window, then more bottles, then the name.
    /// Bottles kept for an occasion are left out, as in Tonight's list.
    func tonightCandidates(year: Int) -> [WineGroup] {
        groupsByMaturity(year: year)
            .filter { $0.maturity == .drinkSoon || $0.maturity == .ready }
            .flatMap { section in section.groups.map { (section.maturity, $0) } }
            .sorted { a, b in
                if a.0 != b.0 { return a.0 < b.0 }
                let untilA = a.1.wine.drinkUntil ?? Int.max, untilB = b.1.wine.drinkUntil ?? Int.max
                if untilA != untilB { return untilA < untilB }
                if a.1.count != b.1.count { return a.1.count > b.1.count }
                return Cellar.nameOrder(a.1.wine, b.1.wine)
            }
            .map(\.1)
    }

    /// Where Tonight's suggestions start on a given day of the year (1…366): a new wine
    /// each day, the same one all day. "Another idea" goes on from there.
    static func tonightStartIndex(dayOfYear: Int, count: Int) -> Int {
        count > 0 ? (max(dayOfYear, 1) - 1) % count : 0
    }

    /// The wine Tonight suggests first on a given day of the year, or nil when none is ready.
    func tonightSuggestion(year: Int, dayOfYear: Int) -> WineGroup? {
        let candidates = tonightCandidates(year: year)
        guard !candidates.isEmpty else { return nil }
        return candidates[Cellar.tonightStartIndex(dayOfYear: dayOfYear, count: candidates.count)]
    }

    /// The young wine whose window opens first (free bottles only), to say when
    /// something will be ready while nothing is now.
    func nextToBeReady(year: Int) -> WineGroup? {
        groupsByMaturity(year: year)
            .first { $0.maturity == .young }?
            .groups
            .min { a, b in
                let fromA = a.wine.drinkFrom ?? Int.max, fromB = b.wine.drinkFrom ?? Int.max
                return fromA != fromB ? fromA < fromB : Cellar.nameOrder(a.wine, b.wine)
            }
    }

    /// Bottles in the cellar kept for an occasion.
    var reservedBottleCount: Int { bottlesInCellar.filter { $0.reservedFor != nil }.count }
}

/// The occasions offered as chips in a tasting note, the same on every device. The
/// occasion is stored as typed text, so a chip matches its label in any language.
public enum Occasion: String, CaseIterable, Sendable {
    case dinnerAtHome, withFriends, celebration, restaurant, weeknight, tasting

    /// English and French labels; the apps show them through their own translations.
    public var labels: (english: String, french: String) {
        switch self {
        case .dinnerAtHome: ("Dinner at home", "Dîner à la maison")
        case .withFriends: ("With friends", "Entre amis")
        case .celebration: ("Celebration", "Fête")
        case .restaurant: ("Restaurant", "Restaurant")
        case .weeknight: ("Weeknight", "En semaine")
        case .tasting: ("Tasting", "Dégustation")
        }
    }

    /// The chip an occasion's text stands for, ignoring case, accents and spaces around.
    public static func matching(_ text: String?) -> Occasion? {
        guard let text else { return nil }
        let needle = fold(text)
        guard !needle.isEmpty else { return nil }
        return allCases.first { fold($0.labels.english) == needle || fold($0.labels.french) == needle }
    }

    static func fold(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX"))
    }
}
