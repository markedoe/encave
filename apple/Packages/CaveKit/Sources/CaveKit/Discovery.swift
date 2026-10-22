import Foundation

/// A wine tasted or seen somewhere else (at friends', in a restaurant, on a trip),
/// kept to remember it and maybe buy it later. Not a bottle in the cellar.
public struct Discovery: Identifiable, Codable, Hashable, Sendable {
    /// Where the wine was found.
    public enum Setting: String, Codable, CaseIterable, Sendable {
        case friends, restaurant, travel, shop, tasting, other
    }

    public var id: UUID
    public var wineID: UUID
    public var date: Date
    public var setting: Setting?
    /// Free text: restaurant, city, friend's name.
    public var place: String?
    /// 1 to 5, optional.
    public var rating: Int?
    public var note: String?
    public var tags: [String]
    /// Price seen (on the wine list, in the shop).
    public var price: Decimal?
    public var currency: String?
    /// On the shopping list.
    public var wantsToBuy: Bool
    /// When a bottle of it was added to the cellar from the shopping list.
    public var boughtAt: Date?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    /// Part of equality, so a merge that only brings them in is still saved.
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id, wineID, date, setting, place, rating, note, tags, price, currency, wantsToBuy, boughtAt
    }

    public init(
        id: UUID = UUID(), wineID: UUID, date: Date = .now, setting: Setting? = nil, place: String? = nil,
        rating: Int? = nil, note: String? = nil, tags: [String] = [], price: Decimal? = nil,
        currency: String? = nil, wantsToBuy: Bool = false, boughtAt: Date? = nil
    ) {
        self.id = id; self.wineID = wineID; self.date = date; self.setting = setting; self.place = place
        self.rating = rating.map { min(5, max(1, $0)) }
        self.note = note; self.tags = Discovery.normalized(tags); self.price = price; self.currency = currency
        self.wantsToBuy = wantsToBuy; self.boughtAt = boughtAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        wineID = try c.decode(UUID.self, forKey: .wineID)
        // Strict like the other records: an unknown setting fails (SYNC.md §1) rather than
        // being dropped, and a missing date is not replaced by today.
        date = try c.decode(Date.self, forKey: .date)
        setting = try c.decodeIfPresent(Setting.self, forKey: .setting)
        place = try c.decodeIfPresent(String.self, forKey: .place)
        rating = try c.decodeIfPresent(Int.self, forKey: .rating).map { min(5, max(1, $0)) }
        note = try c.decodeIfPresent(String.self, forKey: .note)
        tags = Discovery.normalized(try c.decodeIfPresent([String].self, forKey: .tags) ?? [])
        price = try c.decodeIfPresent(Decimal.self, forKey: .price)
        currency = try c.decodeIfPresent(String.self, forKey: .currency)
        wantsToBuy = try c.decodeIfPresent(Bool.self, forKey: .wantsToBuy) ?? false
        boughtAt = try c.decodeIfPresent(Date.self, forKey: .boughtAt)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(wineID, forKey: .wineID)
        try c.encode(date, forKey: .date)
        try c.encodeIfPresent(setting, forKey: .setting)
        try c.encodeIfPresent(place, forKey: .place)
        try c.encodeIfPresent(rating, forKey: .rating)
        try c.encodeIfPresent(note, forKey: .note)
        try c.encode(tags, forKey: .tags)
        try c.encodeIfPresent(price, forKey: .price)
        try c.encodeIfPresent(currency, forKey: .currency)
        try c.encode(wantsToBuy, forKey: .wantsToBuy)
        try c.encodeIfPresent(boughtAt, forKey: .boughtAt)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Trimmed, without empty or duplicate tags (ignoring case), in the order given.
    public static func normalized(_ tags: [String]) -> [String] {
        var seen = Set<String>()
        return tags.compactMap { tag in
            let trimmed = tag.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, seen.insert(trimmed.lowercased()).inserted else { return nil }
            return trimmed
        }
    }
}

/// What the discoveries list shows.
public struct DiscoveryFilter: Equatable, Sendable {
    public var query: String
    public var tag: String?
    public var toBuyOnly: Bool

    public init(query: String = "", tag: String? = nil, toBuyOnly: Bool = false) {
        self.query = query; self.tag = tag; self.toBuyOnly = toBuyOnly
    }
}

public extension Cellar {
    /// Most recent first.
    func discoveries(filter: DiscoveryFilter = DiscoveryFilter()) -> [Discovery] {
        let needle = filter.query.trimmingCharacters(in: .whitespaces)
        return discoveries.values.filter { discovery in
            if filter.toBuyOnly, !discovery.wantsToBuy { return false }
            if let tag = filter.tag, !discovery.tags.contains(where: { $0.caseInsensitiveCompare(tag) == .orderedSame }) {
                return false
            }
            guard !needle.isEmpty else { return true }
            let fields = ([discovery.place, discovery.note] + discovery.tags.map(Optional.some)).compactMap { $0 }
            return SearchText.matches(needle, in: (wines[discovery.wineID]?.searchFields ?? []) + fields)
        }
        .sorted { $0.date == $1.date ? $0.id.uuidString < $1.id.uuidString : $0.date > $1.date }
    }

    func discoveries(of wineID: UUID) -> [Discovery] {
        discoveries.values.filter { $0.wineID == wineID }.sorted { $0.date > $1.date }
    }

    /// Tags in use, most used first, for suggestions and filters.
    var discoveryTags: [String] { Cellar.byUse(discoveries.values.flatMap(\.tags)) }

    var shoppingListCount: Int { discoveries.values.filter(\.wantsToBuy).count }

    /// Adds a discovery. If the same wine (name, producer, vintage) is already known,
    /// the discovery is linked to it instead of creating a duplicate. Returns what was stored.
    @discardableResult
    mutating func addDiscovery(_ discovery: Discovery, wine: Wine) -> Discovery {
        var stored = discovery
        if let existing = wines[wine.id] ?? wines.values.first(where: { $0.identityKey == wine.identityKey }) {
            stored.wineID = existing.id
            if existing.photoID == nil, wine.photoID != nil { wines[existing.id]?.photoID = wine.photoID }
        } else {
            wines[wine.id] = wine
            stored.wineID = wine.id
        }
        discoveries[stored.id] = stored
        return stored
    }

    mutating func updateDiscovery(_ discovery: Discovery) throws {
        guard let stored = discoveries[discovery.id] else { throw CellarError.unknownDiscovery }
        guard wines[discovery.wineID] != nil else { throw CellarError.unknownWine }
        var copy = discovery
        copy.unknownFields = copy.unknownFields.orKept(stored.unknownFields)
        copy.tags = Discovery.normalized(copy.tags)
        discoveries[discovery.id] = copy
    }

    /// Removes a discovery, and its wine if nothing else refers to it.
    mutating func deleteDiscovery(_ id: UUID) {
        discoveries[id] = nil
        removeOrphanWines()
    }

    /// Takes the wine off the shopping list once a bottle was bought.
    mutating func markBought(_ id: UUID, at date: Date = .now) throws {
        guard discoveries[id] != nil else { throw CellarError.unknownDiscovery }
        discoveries[id]?.wantsToBuy = false
        discoveries[id]?.boughtAt = date
    }
}
