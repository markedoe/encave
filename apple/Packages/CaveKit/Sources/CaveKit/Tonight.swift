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

    /// Tonight's suggestions for a day, in the order "Another idea" goes through them: the
    /// candidates ranked by `tonightRank`, so the first is the same all day and does not
    /// change when other wines are drunk. `kept` (the first one shown today, remembered by
    /// the app) stays first while it is a candidate, so a wine added later does not take
    /// its place either.
    func tonightPicks(year: Int, day: String, keeping kept: UUID? = nil) -> [WineGroup] {
        let ranked = rankedTonightCandidates(year: year, day: day)
        guard let kept, let index = ranked.firstIndex(where: { $0.wine.id == kept }) else { return ranked }
        return [ranked[index]] + ranked[..<index] + ranked[(index + 1)...]
    }

    private func rankedTonightCandidates(year: Int, day: String) -> [WineGroup] {
        let candidates = tonightCandidates(year: year)
        let ranks = Dictionary(uniqueKeysWithValues: candidates.map { ($0.wine.id, Cellar.tonightRank(wineID: $0.wine.id, day: day)) })
        // A stable sort: equal ranks (never in practice) keep the best-first order.
        return candidates.enumerated()
            .sorted { a, b in
                let rankA = ranks[a.element.wine.id]!, rankB = ranks[b.element.wine.id]!
                return rankA != rankB ? rankA < rankB : a.offset < b.offset
            }
            .map(\.element)
    }

    /// A wine's place among Tonight's suggestions on a day: the 64-bit FNV-1a hash of the
    /// UTF-8 text "<lowercase wine id>|<day>". The same on Android (fixtures/tonight-vectors.json).
    static func tonightRank(wineID: UUID, day: String) -> UInt64 {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in "\(wineID.uuidString.lowercased())|\(day)".utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3
        }
        return hash
    }

    /// The day Tonight's suggestion is for, "yyyy-MM-dd" in the Gregorian calendar and the
    /// user's time zone, as on Android: a new wine each day from midnight.
    static func tonightDay(_ date: Date) -> String {
        let parts = CellarYear.calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// The wine Tonight suggests first on a day ("yyyy-MM-dd", see `tonightDay`), the same
    /// as Tonight's card: `kept` is the first one shown that day. Nil when none is ready.
    func tonightSuggestion(year: Int, day: String, keeping kept: UUID? = nil) -> WineGroup? {
        tonightPicks(year: year, day: day, keeping: kept).first
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
