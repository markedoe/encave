import Foundation

/// The whole cellar: wines, bottles, racks and settings. A value type, so the UI
/// layer can observe it and sync can merge copies of it.
public struct Cellar: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 2

    public var schemaVersion: Int
    public var wines: [UUID: Wine]
    public var bottles: [UUID: Bottle]
    public var racks: [Rack]
    public var settings: CellarSettings
    /// Wines tasted or seen elsewhere, to remember or buy.
    public var discoveries: [UUID: Discovery]

    public init(
        wines: [Wine] = [], bottles: [Bottle] = [], racks: [Rack] = [], settings: CellarSettings = CellarSettings(),
        discoveries: [Discovery] = []
    ) {
        schemaVersion = Cellar.currentSchemaVersion
        self.wines = Dictionary(wines.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        self.bottles = Dictionary(bottles.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        self.racks = Cellar.unique(racks)
        self.settings = settings
        self.discoveries = Dictionary(discoveries.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Keyed by each object's own ID, whatever the file says, and racks without
        // duplicates: data from another device or a backup must not break the
        // assumptions of the merge and the UI.
        wines = Cellar.rekeyed(try c.decodeIfPresent([UUID: Wine].self, forKey: .wines) ?? [:])
        bottles = Cellar.rekeyed(try c.decodeIfPresent([UUID: Bottle].self, forKey: .bottles) ?? [:])
        racks = Cellar.unique(try c.decodeIfPresent([Rack].self, forKey: .racks) ?? [])
        settings = try c.decodeIfPresent(CellarSettings.self, forKey: .settings) ?? CellarSettings()
        discoveries = Cellar.rekeyed(try c.decodeIfPresent([UUID: Discovery].self, forKey: .discoveries) ?? [:])
        schemaVersion = Cellar.currentSchemaVersion
    }

    /// The first rack of each ID, in order.
    static func unique(_ racks: [Rack]) -> [Rack] {
        var seen = Set<UUID>()
        return racks.filter { seen.insert($0.id).inserted }
    }

    private static func rekeyed<T: Identifiable>(_ items: [UUID: T]) -> [UUID: T] where T.ID == UUID {
        var result: [UUID: T] = [:]
        for key in items.keys.sorted(by: { $0.uuidString < $1.uuidString }) {
            guard let item = items[key], result[item.id] == nil else { continue }
            result[item.id] = item
        }
        return result
    }

    public var isEmpty: Bool { wines.isEmpty && bottles.isEmpty && racks.isEmpty && discoveries.isEmpty }
}

// MARK: - Queries

/// Bottles of one wine still in the cellar, for list rows like "Château X 2015 ×3".
public struct WineGroup: Identifiable, Hashable, Sendable {
    public var wine: Wine
    public var bottles: [Bottle]
    public var id: UUID { wine.id }
    public var count: Int { bottles.count }
}

public extension Collection where Element == WineGroup {
    /// The picked wines still shown: a search or a filter that hides a wine also unpicks
    /// it, so the selection's actions never act on wines out of sight.
    func visible(_ picked: Set<UUID>) -> Set<UUID> {
        picked.intersection(map(\.wine.id))
    }
}

public extension Bottle {
    /// Oldest first, then by id: a total order, so the bottle Drink and Tonight open is the
    /// same on every launch and on Android (`Bottle.addedOrder` in the Android core).
    static func addedOrder(_ a: Bottle, _ b: Bottle) -> Bool {
        a.addedAt != b.addedAt ? a.addedAt < b.addedAt : a.id.uuidString < b.id.uuidString
    }

    /// Expected soonest first (unknown last), then `addedOrder`.
    static func expectedOrder(_ a: Bottle, _ b: Bottle) -> Bool {
        let ea = a.expectedAt ?? .distantFuture, eb = b.expectedAt ?? .distantFuture
        return ea != eb ? ea < eb : addedOrder(a, b)
    }
}

public extension Collection where Element == Bottle {
    /// The bottle a quick "Drink" opens: one not kept for an occasion when there is one.
    var bottleToOpen: Bottle? { first { $0.reservedFor == nil } ?? first }
}

public struct MaturitySection: Identifiable, Hashable, Sendable {
    public var maturity: Maturity
    public var groups: [WineGroup]
    public var id: Maturity { maturity }
}

/// How the cellar list is sorted.
public enum CellarSort: String, CaseIterable, Sendable {
    case name, vintage, recentlyAdded, readiness, region
}

/// Filters for the cellar list. Empty sets mean "all".
public struct CellarFilter: Equatable, Sendable {
    public var query: String
    public var colors: Set<WineColor>
    public var maturities: Set<Maturity>
    public var rackID: UUID?
    /// Only wines with this tag.
    public var tag: String?
    /// Only bottles reserved for this occasion.
    public var reservation: String?
    /// Only wines made with this grape.
    public var grape: String?

    public init(query: String = "", colors: Set<WineColor> = [], maturities: Set<Maturity> = [], rackID: UUID? = nil,
                tag: String? = nil, reservation: String? = nil, grape: String? = nil) {
        self.query = query; self.colors = colors; self.maturities = maturities; self.rackID = rackID; self.tag = tag
        self.reservation = reservation; self.grape = grape
    }

    public var isActive: Bool {
        !colors.isEmpty || !maturities.isEmpty || rackID != nil || tag != nil || reservation != nil || grape != nil
    }
}

public extension Cellar {
    var bottlesInCellar: [Bottle] { bottles.values.filter { $0.status == .inCellar } }

    /// Occasions bottles in the cellar are reserved for, counted case-insensitively, by name
    /// (`chipOrder`), each with its most used spelling, as in Kotlin.
    var reservations: [String] {
        Cellar.spelled(bottlesInCellar.compactMap(\.reservedFor)).map(\.name).sorted(by: Cellar.chipOrder)
    }

    /// Reserves bottles in the cellar for an occasion, or frees them (nil or blank).
    mutating func reserve(_ bottleIDs: [UUID], for occasion: String?) throws {
        let name = occasion?.trimmingCharacters(in: .whitespacesAndNewlines)
        for id in bottleIDs {
            guard var bottle = bottles[id], bottle.status == .inCellar else { throw CellarError.unknownBottle }
            bottle.reservedFor = (name?.isEmpty ?? true) ? nil : name
            bottles[id] = bottle
        }
    }

    /// Every photo a wine refers to (to sync, and to keep when cleaning up).
    var photoIDs: Set<String> { Set(wines.values.flatMap(\.allPhotoIDs)) }

    /// Saves the current search under a name, replacing one with the same name.
    mutating func saveSearch(_ name: String, filter: CellarFilter) {
        let search = SavedSearch(name: name, filter: filter)
        guard !search.name.isEmpty else { return }
        var list = (settings.savedSearches ?? []).filter { $0.name.caseInsensitiveCompare(search.name) != .orderedSame }
        list.append(search)
        settings.savedSearches = SavedSearch.ordered(list)
    }

    mutating func deleteSearch(_ id: UUID) {
        let list = (settings.savedSearches ?? []).filter { $0.id != id }
        settings.savedSearches = list.isEmpty ? nil : list
    }

    /// Tags of wines with bottles in the cellar, most used first.
    var wineTags: [String] {
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        return Cellar.byUse(wines.values.filter { inCellar.contains($0.id) }.flatMap { $0.tags ?? [] })
    }

    /// Grapes of the wines in the cellar, the most used first (for the filter chips).
    var wineGrapes: [String] {
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        let grapes = wines.values.filter { inCellar.contains($0.id) }
            .flatMap(\.grapes)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return Cellar.byUse(grapes)
    }

    /// Names counted case-insensitively, the most used first, equal counts in `chipOrder`. Each
    /// is shown with its most used spelling (ties: the first in UTF-16 order, as in Kotlin), so the
    /// label does not depend on the order records are stored in, and is the same on every platform.
    static func byUse(_ names: [String]) -> [String] {
        spelled(names)
            .sorted { $0.count == $1.count ? chipOrder($0.name, $1.name) : $0.count > $1.count }
            .map(\.name)
    }

    /// Names counted case-insensitively, each with its most used spelling (ties: the first in UTF-16 order).
    static func spelled(_ names: [String]) -> [(name: String, count: Int)] {
        var spellings: [String: [String: Int]] = [:]
        for name in names { spellings[name.lowercased(), default: [:]][name, default: 0] += 1 }
        return spellings.values.compactMap { counts -> (name: String, count: Int)? in
            let shown = counts.sorted { a, b in
                a.value != b.value ? a.value > b.value : a.key.utf16.lexicographicallyPrecedes(b.key.utf16)
            }.first
            return shown.map { ($0.key, counts.values.reduce(0, +)) }
        }
    }

    /// The order of tags, grapes and occasions, the same in every locale and on Android
    /// (`Cellar.chipOrder`): ignoring case and accents, with runs of digits compared by value
    /// ("Lot 2" before "Lot 10"), then by UTF-16 code units. Spelled out rather than
    /// `localizedStandardCompare`, which depends on the locale and on the Foundation in use.
    static func chipOrder(_ a: String, _ b: String) -> Bool {
        let folded = { (text: String) in Array(text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).utf16) }
        let order = naturalOrder(folded(a), folded(b))
        return order != 0 ? order < 0 : a.utf16.lexicographicallyPrecedes(b.utf16)
    }

    /// Below zero when `a` comes first: UTF-16 code units, runs of ASCII digits by value.
    private static func naturalOrder(_ a: [UInt16], _ b: [UInt16]) -> Int {
        func isDigit(_ unit: UInt16) -> Bool { unit >= 0x30 && unit <= 0x39 }
        var i = 0, j = 0
        while i < a.count, j < b.count {
            if isDigit(a[i]), isDigit(b[j]) {
                var endA = i
                while endA < a.count, isDigit(a[endA]) { endA += 1 }
                var endB = j
                while endB < b.count, isDigit(b[endB]) { endB += 1 }
                let runA = a[i..<endA].drop(while: { $0 == 0x30 }), runB = b[j..<endB].drop(while: { $0 == 0x30 })
                if runA.count != runB.count { return runA.count < runB.count ? -1 : 1 }
                if !runA.elementsEqual(runB) { return runA.lexicographicallyPrecedes(runB) ? -1 : 1 }
                i = endA
                j = endB
            } else {
                if a[i] != b[j] { return a[i] < b[j] ? -1 : 1 }
                i += 1
                j += 1
            }
        }
        let restA = a.count - i, restB = b.count - j
        return restA == restB ? 0 : (restA < restB ? -1 : 1)
    }

    /// Bottles bought but not delivered yet.
    var bottlesOnOrder: [Bottle] { bottles.values.filter { $0.status == .ordered } }

    /// Wines with bottles on order, the soonest expected first (unknown dates last).
    func orderGroups() -> [WineGroup] {
        Dictionary(grouping: bottlesOnOrder, by: \.wineID)
            .compactMap { wineID, bottles -> WineGroup? in
                guard let wine = wines[wineID] else { return nil }
                let sorted = bottles.sorted(by: Bottle.expectedOrder)
                return WineGroup(wine: wine, bottles: sorted)
            }
            .sorted { a, b in
                let da = a.bottles.first?.expectedAt ?? .distantFuture
                let db = b.bottles.first?.expectedAt ?? .distantFuture
                return da != db ? da < db : Cellar.nameOrder(a.wine, b.wine)
            }
    }

    var consumedBottles: [Bottle] {
        bottles.values
            .filter { $0.status == .consumed }
            .sorted { ($0.tasting?.date ?? .distantPast) > ($1.tasting?.date ?? .distantPast) }
    }

    /// Wines that still have bottles in the cellar, sorted by name then vintage.
    func groups(matching query: String = "") -> [WineGroup] {
        groups(filter: CellarFilter(query: query), sort: .name, year: CellarYear.current)
    }

    func groups(filter: CellarFilter, sort: CellarSort, year: Int) -> [WineGroup] {
        let byWine = Dictionary(grouping: bottlesInCellar, by: \.wineID)
        let needle = filter.query.trimmingCharacters(in: .whitespaces)
        let result = byWine.compactMap { wineID, bottles -> WineGroup? in
            guard let wine = wines[wineID] else { return nil }
            if !needle.isEmpty, !wine.matches(needle) { return nil }
            if !filter.colors.isEmpty {
                guard let color = wine.color, filter.colors.contains(color) else { return nil }
            }
            if !filter.maturities.isEmpty, !filter.maturities.contains(Maturity.of(wine, year: year)) { return nil }
            if let tag = filter.tag, !wine.hasTag(tag) { return nil }
            if let grape = filter.grape, !wine.hasGrape(grape) { return nil }
            var kept = bottles
            if let rackID = filter.rackID {
                kept = kept.filter { $0.slot?.rackID == rackID }
                if kept.isEmpty { return nil }
            }
            if let reservation = filter.reservation {
                kept = kept.filter { $0.reservedFor?.caseInsensitiveCompare(reservation) == .orderedSame }
                if kept.isEmpty { return nil }
            }
            return WineGroup(wine: wine, bottles: kept.sorted(by: Bottle.addedOrder))
        }
        return result.sorted { a, b in
            switch sort {
            case .name:
                return Cellar.nameOrder(a.wine, b.wine)
            case .vintage:
                if a.wine.vintage != b.wine.vintage { return (a.wine.vintage ?? .max) < (b.wine.vintage ?? .max) }
                return Cellar.nameOrder(a.wine, b.wine)
            case .recentlyAdded:
                let da = a.bottles.map(\.addedAt).max() ?? .distantPast
                let db = b.bottles.map(\.addedAt).max() ?? .distantPast
                return da != db ? da > db : Cellar.nameOrder(a.wine, b.wine)
            case .readiness:
                let ma = Maturity.of(a.wine, year: year), mb = Maturity.of(b.wine, year: year)
                if ma != mb { return ma < mb }
                let ua = a.wine.drinkUntil ?? .max, ub = b.wine.drinkUntil ?? .max
                return ua != ub ? ua < ub : Cellar.nameOrder(a.wine, b.wine)
            case .region:
                let ra = [a.wine.country ?? "", a.wine.region ?? ""].joined(separator: "/")
                let rb = [b.wine.country ?? "", b.wine.region ?? ""].joined(separator: "/")
                let order = ra.localizedCaseInsensitiveCompare(rb)
                return order != .orderedSame ? order == .orderedAscending : Cellar.nameOrder(a.wine, b.wine)
            }
        }
    }

    /// Groups bucketed by maturity for "Tonight", most urgent first.
    /// Bottles reserved for an occasion are left out: they are not for tonight.
    func groupsByMaturity(year: Int) -> [MaturitySection] {
        let free = groups().compactMap { group -> WineGroup? in
            let bottles = group.bottles.filter { $0.reservedFor == nil }
            return bottles.isEmpty ? nil : WineGroup(wine: group.wine, bottles: bottles)
        }
        return Dictionary(grouping: free) { Maturity.of($0.wine, year: year) }
            .sorted { $0.key < $1.key }
            .map { MaturitySection(maturity: $0.key, groups: $0.value) }
    }

    /// Bottles of a wine still in the cellar.
    func bottles(of wineID: UUID) -> [Bottle] {
        bottlesInCellar.filter { $0.wineID == wineID }.sorted(by: Bottle.addedOrder)
    }

    /// Bottles as their places read: storage in its order, then shelf or row, depth (front
    /// first), layer, position, then id; bottles without a position last, oldest first.
    /// A total order, so lists and "A1 and 6 more" do not change between launches.
    func inPlaceOrder(_ bottles: [Bottle]) -> [Bottle] {
        let rackIndex = Dictionary(racks.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { first, _ in first })
        func key(_ bottle: Bottle) -> [Int]? {
            guard let slot = bottle.slot, let index = rackIndex[slot.rackID] else { return nil }
            if let place = racks[index].shelfPlace(ofRow: slot.row) {
                return [index, place.shelf, slot.depth, place.layer, slot.column]
            }
            return [index, slot.row, slot.depth, 0, slot.column]
        }
        return bottles.map { (key($0), $0) }.sorted { a, b in
            switch (a.0, b.0) {
            case let (ka?, kb?) where ka != kb: return ka.lexicographicallyPrecedes(kb)
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil) where a.1.addedAt != b.1.addedAt: return a.1.addedAt < b.1.addedAt
            default: return a.1.id.uuidString < b.1.id.uuidString
            }
        }.map(\.1)
    }

    /// Opened bottles whose wine (name, producer, origin, grapes, tags) and tasting
    /// (note, occasion, full note, food) contain every word of `query`, most recent first.
    func tastings(matching query: String) -> [Bottle] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return consumedBottles }
        return consumedBottles.filter { bottle in
            let tasting = bottle.tasting
            let fields = [tasting?.note, tasting?.occasion, tasting?.appearance, tasting?.nose, tasting?.palate,
                          tasting?.finish, tasting?.foodPairing].compactMap { $0 }
            return SearchText.matches(needle, in: (wines[bottle.wineID]?.searchFields ?? []) + fields)
        }
    }

    /// Past tastings of a wine, most recent first.
    func tastings(of wineID: UUID) -> [Bottle] {
        consumedBottles.filter { $0.wineID == wineID }
    }

    func bottle(at slot: Slot) -> Bottle? {
        bottlesInCellar.first { $0.slot == slot }
    }

    func freeSlots(in rack: Rack) -> [Slot] {
        let taken = Set(bottlesInCellar.compactMap(\.slot))
        return rack.allSlots.filter { !taken.contains($0) }
    }

    /// Bottles of a rack by cell, front first.
    func bottlesByCell(in rack: Rack) -> [RackCell: [Bottle]] {
        var result: [RackCell: [Bottle]] = [:]
        for bottle in bottlesInCellar {
            guard let slot = bottle.slot, slot.rackID == rack.id else { continue }
            result[slot.cell, default: []].append(bottle)
        }
        return result.mapValues { $0.sorted { ($0.slot?.depth ?? 0) < ($1.slot?.depth ?? 0) } }
    }

    /// Bottles in one cell, front first.
    func bottles(in cell: RackCell) -> [Bottle] {
        bottlesInCellar.filter { $0.slot?.cell == cell }.sorted { ($0.slot?.depth ?? 0) < ($1.slot?.depth ?? 0) }
    }

    /// The first free place in a cell (the front, or the next place in a bin).
    func freeSlot(in cell: RackCell) -> Slot? {
        guard let rack = rack(cell.rackID), rack.contains(cell) else { return nil }
        let taken = Set(bottles(in: cell).compactMap { $0.slot?.depth })
        return (0..<rack.positions(inRow: cell.row)).first { !taken.contains($0) }
            .map { Slot(rackID: cell.rackID, row: cell.row, column: cell.column, depth: $0) }
    }

    func occupancy(of rack: Rack) -> Int {
        bottlesInCellar.filter { $0.slot?.rackID == rack.id }.count
    }

    /// The positions a count of this rack shows: its capacity, plus each place behind a row's
    /// depth holding a bottle an older version put there, so a count never reads more
    /// bottles than positions (the same on Android).
    func capacity(of rack: Rack) -> Int {
        rack.capacity + bottlesInCellar.filter { bottle in
            bottle.slot.map { $0.rackID == rack.id && rack.contains($0) && !rack.hasPosition($0) } ?? false
        }.count
    }

    func rack(_ id: UUID) -> Rack? { racks.first { $0.id == id } }

    /// "Main rack · B3", "Cabinet · Shelf 2, position 3", the storage note, or nil when the
    /// bottle has no location.
    func locationLabel(for bottle: Bottle) -> String? {
        if let slot = bottle.slot, let rack = rack(slot.rackID) {
            return "\(rack.name) · \(rack.placeName(for: slot))"
        }
        if let note = bottle.storageNote, !note.isEmpty { return note }
        return nil
    }

    /// The wine a barcode was seen on, preferring one with bottles in the cellar (see `wines(barcode:)`).
    func wine(barcode: String) -> Wine? {
        wines(barcode: barcode).first
    }

    /// The wines a barcode was seen on: producers often keep one barcode for every vintage.
    /// Those with bottles in the cellar first, then the newest vintage (non-vintage last).
    func wines(barcode: String) -> [Wine] {
        let code = Wine.normalizedBarcode(barcode)
        guard !code.isEmpty else { return [] }
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        return wines.values.filter { ($0.barcodes ?? []).contains(code) }.sorted { a, b in
            let aIn = inCellar.contains(a.id), bIn = inCellar.contains(b.id)
            if aIn != bIn { return aIn }
            if a.vintage != b.vintage { return (a.vintage ?? .min) > (b.vintage ?? .min) }
            if a.displayName != b.displayName { return a.displayName < b.displayName }
            return a.id.uuidString < b.id.uuidString
        }
    }

    /// A wine of the cellar that `draft` would duplicate: the same name and vintage, ignoring
    /// case, accents and extra spaces, and the same producer or no producer on one side.
    /// The draft's own record (same id) is left out. The same producer is preferred, then
    /// a wine with bottles in the cellar.
    func wine(matching draft: Wine) -> Wine? {
        let name = Wine.matchKey(draft.name)
        guard !name.isEmpty else { return nil }
        let producer = Wine.matchKey(draft.producer ?? "")
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        func rank(_ wine: Wine) -> (Int, Int, String) {
            (Wine.matchKey(wine.producer ?? "") == producer ? 0 : 1, inCellar.contains(wine.id) ? 0 : 1, wine.id.uuidString)
        }
        return wines.values.filter { wine in
            guard wine.id != draft.id, wine.vintage == draft.vintage, Wine.matchKey(wine.name) == name else { return false }
            let other = Wine.matchKey(wine.producer ?? "")
            return other == producer || other.isEmpty || producer.isEmpty
        }
        .min { rank($0) < rank($1) }
    }

    /// Existing wines matching a query, for "add another bottle of...".
    func wineSuggestions(matching query: String, limit: Int = 8) -> [Wine] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return [] }
        return wines.values.filter { $0.matches(needle) }
            .sorted { Cellar.nameOrder($0, $1) }
            .prefix(limit).map { $0 }
    }

    /// Distinct values already used, for autocompletion.
    func knownValues(_ keyPath: KeyPath<Wine, String?>) -> [String] {
        Array(Set(wines.values.compactMap { $0[keyPath: keyPath] }.filter { !$0.isEmpty }))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    /// Name, then vintage, then producer, then id: a total order, so wines alike keep
    /// their place whatever order the records are stored in (the Kotlin core does the same).
    static func nameOrder(_ a: Wine, _ b: Wine) -> Bool {
        let order = a.name.localizedCaseInsensitiveCompare(b.name)
        if order != .orderedSame { return order == .orderedAscending }
        if a.vintage != b.vintage { return (a.vintage ?? 0) < (b.vintage ?? 0) }
        let producers = (a.producer ?? "").localizedCaseInsensitiveCompare(b.producer ?? "")
        if producers != .orderedSame { return producers == .orderedAscending }
        return a.id.uuidString < b.id.uuidString
    }
}

// MARK: - Commands

public enum CellarError: Error, Equatable {
    case unknownWine, unknownBottle, unknownRack, unknownDiscovery, slotTaken, slotOutOfRange, alreadyConsumed, rackNotEmpty
    /// The bottle is on order: receive it before placing or drinking it.
    case notReceived
    /// The bottle was opened or is gone: it has no place any more.
    case notInCellar
}

/// Purchase details applied to every bottle added in one go.
public struct PurchaseInfo: Equatable, Sendable {
    public var date: Date?
    public var price: Decimal?
    public var currency: String?
    public var place: String?
    public var receivedFrom: String?
    public var storageNote: String?
    public var sizeML: Int
    /// The bottles are bought but not delivered yet.
    public var isOrdered: Bool
    /// When ordered bottles should arrive, if known.
    public var expectedAt: Date?

    public init(date: Date? = nil, price: Decimal? = nil, currency: String? = nil, place: String? = nil,
                receivedFrom: String? = nil, storageNote: String? = nil, sizeML: Int = 750,
                isOrdered: Bool = false, expectedAt: Date? = nil) {
        self.date = date; self.price = price; self.currency = currency; self.place = place
        self.receivedFrom = receivedFrom; self.storageNote = storageNote; self.sizeML = sizeML
        self.isOrdered = isOrdered; self.expectedAt = isOrdered ? expectedAt : nil
    }
}

public extension Cellar {
    /// Adds `quantity` bottles of a wine, placing them in the given slots (in order) when provided.
    /// If the wine already exists (same id), its record is updated.
    @discardableResult
    mutating func addBottles(
        of wine: Wine, quantity: Int = 1, slots: [Slot] = [], purchase: PurchaseInfo = PurchaseInfo(),
        addedAt: Date = .now
    ) throws -> [Bottle] {
        guard quantity > 0 else { return [] }
        if purchase.isOrdered, !slots.isEmpty { throw CellarError.notReceived }
        for slot in slots { try validateFree(slot) }
        guard Set(slots).count == slots.count else { throw CellarError.slotTaken }
        wines[wine.id] = wine
        var added: [Bottle] = []
        for index in 0..<quantity {
            var bottle = Bottle(
                wineID: wine.id, slot: index < slots.count ? slots[index] : nil,
                storageNote: purchase.storageNote, addedAt: addedAt, purchaseDate: purchase.date,
                purchasePrice: purchase.price, currency: purchase.currency ?? settings.currency,
                purchasePlace: purchase.place, receivedFrom: purchase.receivedFrom, sizeML: purchase.sizeML
            )
            if purchase.isOrdered {
                bottle.status = .ordered
                bottle.expectedAt = purchase.expectedAt
            }
            bottles[bottle.id] = bottle
            added.append(bottle)
        }
        return added
    }

    /// Marks ordered bottles as delivered, placing them in the given slots
    /// (in order) when provided. They count as added on `date`.
    mutating func receive(_ bottleIDs: [UUID], slots: [Slot] = [], at date: Date = .now) throws {
        for id in bottleIDs where bottles[id]?.status != .ordered { throw CellarError.unknownBottle }
        for slot in slots { try validateFree(slot) }
        guard Set(slots).count == slots.count else { throw CellarError.slotTaken }
        for (index, id) in bottleIDs.enumerated() {
            guard var bottle = bottles[id] else { continue }
            bottle.status = .inCellar
            bottle.expectedAt = nil
            bottle.addedAt = date
            bottle.slot = index < slots.count ? slots[index] : nil
            bottles[id] = bottle
        }
    }

    mutating func drink(_ bottleID: UUID, tasting: Tasting) throws {
        guard var bottle = bottles[bottleID] else { throw CellarError.unknownBottle }
        guard bottle.status != .ordered else { throw CellarError.notReceived }
        guard bottle.status == .inCellar else { throw CellarError.alreadyConsumed }
        bottle.status = .consumed
        bottle.slot = nil
        bottle.tasting = tasting
        bottles[bottleID] = bottle
    }

    /// Updates the tasting of a bottle already drunk (adding a full note later).
    mutating func updateTasting(_ bottleID: UUID, tasting: Tasting) throws {
        guard var bottle = bottles[bottleID], bottle.status == .consumed else { throw CellarError.unknownBottle }
        var tasting = tasting
        tasting.unknownFields = tasting.unknownFields.orKept(bottle.tasting?.unknownFields)
        bottle.tasting = tasting
        bottles[bottleID] = bottle
    }

    /// Reverts a drink, putting the bottle back in its previous slot if still free. Only a
    /// bottle opened can be: one on order or given away stays as it is.
    mutating func undoDrink(_ bottleID: UUID, previousSlot: Slot?) {
        guard var bottle = bottles[bottleID], bottle.status == .consumed else { return }
        bottle.status = .inCellar
        bottle.tasting = nil
        // Back in its place if it is still free and still exists (the rack may be gone or smaller).
        if let previousSlot, self.bottle(at: previousSlot) == nil, rack(previousSlot.rackID)?.contains(previousSlot) == true {
            bottle.slot = previousSlot
        }
        bottles[bottleID] = bottle
    }

    /// Marks a bottle as given away, sold or broken.
    mutating func markGone(_ bottleID: UUID) throws {
        guard var bottle = bottles[bottleID] else { throw CellarError.unknownBottle }
        bottle.status = .gone
        bottle.slot = nil
        bottles[bottleID] = bottle
    }

    /// Deletes a bottle record entirely (entered by mistake).
    mutating func deleteBottle(_ bottleID: UUID) {
        bottles[bottleID] = nil
        removeOrphanWines()
    }

    mutating func updateBottle(_ bottle: Bottle) throws {
        guard let stored = bottles[bottle.id] else { throw CellarError.unknownBottle }
        var bottle = bottle
        bottle.unknownFields = bottle.unknownFields.orKept(stored.unknownFields)
        if let fields = bottle.tasting?.unknownFields {
            bottle.tasting?.unknownFields = fields.orKept(stored.tasting?.unknownFields)
        }
        if let slot = bottle.slot {
            guard bottle.status != .ordered else { throw CellarError.notReceived }
            try validateInRange(slot)
            if let occupant = self.bottle(at: slot), occupant.id != bottle.id { throw CellarError.slotTaken }
        }
        bottles[bottle.id] = bottle
    }

    mutating func move(_ bottleID: UUID, to slot: Slot?) throws {
        guard var bottle = bottles[bottleID] else { throw CellarError.unknownBottle }
        if let slot {
            // Only a bottle in the cellar takes a place (one opened meanwhile, by sync or
            // in another window, never does).
            guard bottle.status != .ordered else { throw CellarError.notReceived }
            guard bottle.status == .inCellar else { throw CellarError.notInCellar }
            if let occupant = self.bottle(at: slot), occupant.id != bottleID { throw CellarError.slotTaken }
            try validateInRange(slot)
        }
        bottle.slot = slot
        bottles[bottleID] = bottle
    }

    /// Swaps the contents of two slots (either may be empty).
    mutating func swap(_ a: Slot, _ b: Slot) throws {
        try validateInRange(a)
        try validateInRange(b)
        let first = bottle(at: a), second = bottle(at: b)
        if let first { bottles[first.id]?.slot = b }
        if let second { bottles[second.id]?.slot = a }
    }

    /// The Undo of `swap(a, b)` that found `first` at `a` and `second` at `b`: swaps them
    /// back only if both are still where the swap put them, so a bottle put in one of the
    /// positions since never moves. Returns whether it swapped them back.
    @discardableResult
    mutating func undoSwap(_ a: Slot, _ b: Slot, first: UUID?, second: UUID?) -> Bool {
        guard first != nil || second != nil,
              bottle(at: b)?.id == first, bottle(at: a)?.id == second,
              (try? swap(a, b)) != nil else { return false }
        return true
    }

    /// The Undo of `updateRack(rack, rowMap:, takingOut: true)` on the rack `old`: the rack
    /// takes its old shape back. The bottles in it now go back with their rows (the inverse
    /// of `rowMap`), so one placed since stays on its shelf; a bottle in a row the old shape
    /// did not have loses its place. The bottles taken out (`takenOut`, as they were before)
    /// go back to their place only if they still have none and it is free. Bottles moved to
    /// another storage or opened since keep what they have now.
    mutating func undoRackUpdate(back old: Rack, rowMap: [Int: Int?], takenOut: [Bottle]) {
        guard let index = racks.firstIndex(where: { $0.id == old.id }) else { return }
        var inverse: [Int: Int?] = [:]
        // A row the edit moved away from and nothing moved into is a new row: no old place.
        for from in rowMap.keys { inverse[from] = .some(nil) }
        for (from, to) in rowMap { if let to { inverse[to] = .some(from) } }
        for bottle in bottlesInCellar {
            guard let slot = bottle.slot, slot.rackID == old.id else { continue }
            let back = Rack.moved(slot, rowMap: inverse)
            bottles[bottle.id]?.slot = back.flatMap { old.contains($0) ? $0 : nil }
        }
        var rack = old
        rack.unknownFields = rack.unknownFields.orKept(racks[index].unknownFields)
        racks[index] = rack
        for bottle in takenOut {
            guard let slot = bottle.slot, let now = bottles[bottle.id], now.status == .inCellar,
                  now.slot == nil, self.bottle(at: slot) == nil, old.contains(slot) else { continue }
            bottles[bottle.id]?.slot = slot
        }
    }

    /// Bottles of a storage not found while checking it: those still in it whose ID is not
    /// in `found`, so a bottle seen and then moved within the storage stays found.
    func missingBottles(in rackID: UUID, found: Set<UUID>) -> [Bottle] {
        bottlesInCellar.filter { $0.slot?.rackID == rackID && !found.contains($0.id) }
    }

    /// Whether `reserve(bottleIDs, for: occasion)` would change anything.
    func changesReservation(_ bottleIDs: [UUID], for occasion: String?) -> Bool {
        let name = occasion?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let reservedFor = name.isEmpty ? nil : name
        return bottleIDs.contains { bottles[$0].map { $0.reservedFor != reservedFor } ?? false }
    }

    mutating func updateWine(_ wine: Wine) throws {
        guard let stored = wines[wine.id] else { throw CellarError.unknownWine }
        var wine = wine
        wine.unknownFields = wine.unknownFields.orKept(stored.unknownFields)
        wines[wine.id] = wine
    }

    /// The same wine entered twice: the bottles and shopping-list entries of `source`
    /// go to `target`, with its barcodes and documents, and its label photo when
    /// `target` has none; then `source` is removed.
    mutating func mergeWine(_ source: UUID, into target: UUID) throws {
        guard source != target, let from = wines[source], var into = wines[target] else { throw CellarError.unknownWine }
        for (id, bottle) in bottles where bottle.wineID == source { bottles[id]?.wineID = target }
        for (id, discovery) in discoveries where discovery.wineID == source { discoveries[id]?.wineID = target }
        from.barcodes?.forEach { into.addBarcode($0) }
        if let documents = from.attachments { into.attachments = (into.attachments ?? []) + documents }
        if into.photoID == nil { into.photoID = from.photoID }
        wines[target] = into
        wines[source] = nil
    }

    /// Deletes a wine with every bottle and discovery of it.
    mutating func deleteWine(_ wineID: UUID) {
        wines[wineID] = nil
        for (id, bottle) in bottles where bottle.wineID == wineID { bottles[id] = nil }
        for (id, discovery) in discoveries where discovery.wineID == wineID { discoveries[id] = nil }
    }

    mutating func addRack(_ rack: Rack) { racks.append(rack) }

    /// Updates name, layout or size. `rowMap` moves the bottles of a row to another row
    /// (nil: the row is gone), keeping their column and depth; rows it leaves out stay as
    /// they are. A bottle whose new place is not in the rack is displaced: with `takingOut`
    /// it stays in the cellar without a place, otherwise the update is refused.
    mutating func updateRack(_ rack: Rack, rowMap: [Int: Int?] = [:], takingOut: Bool = false) throws {
        guard let index = racks.firstIndex(where: { $0.id == rack.id }) else { throw CellarError.unknownRack }
        let displaced = Set(displacedBottles(by: rack, rowMap: rowMap).map(\.id))
        if !displaced.isEmpty, !takingOut { throw CellarError.rackNotEmpty }
        for bottle in bottlesInCellar {
            guard let slot = bottle.slot, slot.rackID == rack.id else { continue }
            bottles[bottle.id]?.slot = displaced.contains(bottle.id) ? nil : Rack.moved(slot, rowMap: rowMap)
        }
        var rack = rack
        rack.unknownFields = rack.unknownFields.orKept(racks[index].unknownFields)
        racks[index] = rack
    }

    /// Bottles of this rack that an update with `rowMap` would leave without a place,
    /// in the order of their places.
    func displacedBottles(by rack: Rack, rowMap: [Int: Int?] = [:]) -> [Bottle] {
        let old = self.rack(rack.id)
        return bottlesInCellar.filter { bottle in
            guard let slot = bottle.slot, slot.rackID == rack.id else { return false }
            guard let moved = Rack.moved(slot, rowMap: rowMap) else { return true }
            if !rack.contains(moved) { return true }
            // A position the edit removes; a bottle already behind its row's depth (put there
            // by an older version) stays where it is while the edit keeps that place.
            return !rack.hasPosition(moved) && old?.hasPosition(slot) != false
        }
        .sorted { a, b in
            let x = a.slot!, y = b.slot!
            return (x.row, x.column, x.depth) < (y.row, y.column, y.depth)
        }
    }

    mutating func moveRacks(fromOffsets source: IndexSet, toOffset destination: Int) {
        let moving = source.sorted().map { racks[$0] }
        for index in source.sorted(by: >) { racks.remove(at: index) }
        let adjusted = destination - source.filter { $0 < destination }.count
        racks.insert(contentsOf: moving, at: min(max(0, adjusted), racks.count))
    }

    /// Puts the bottles of these wines that have no place into the free places of a
    /// rack, in order. Returns how many were placed.
    @discardableResult
    mutating func place(wines wineIDs: Set<UUID>, in rackID: UUID) throws -> Int {
        try place(bottles: Set(bottles.values.filter { wineIDs.contains($0.wineID) }.map(\.id)), in: rackID)
    }

    /// Puts these bottles, those in the cellar without a place, into the free places
    /// of a rack, in order. Returns how many were placed.
    @discardableResult
    mutating func place(bottles bottleIDs: Set<UUID>, in rackID: UUID) throws -> Int {
        guard let rack = rack(rackID) else { throw CellarError.unknownRack }
        let waiting = bottlesInCellar
            .filter { bottleIDs.contains($0.id) && $0.slot == nil }
            .sorted { a, b in
                let wa = wines[a.wineID], wb = wines[b.wineID]
                if a.wineID != b.wineID, let wa, let wb { return Cellar.nameOrder(wa, wb) }
                return a.addedAt < b.addedAt
            }
        let free = freeSlots(in: rack)
        for (bottle, slot) in zip(waiting, free) {
            bottles[bottle.id]?.slot = slot
            bottles[bottle.id]?.storageNote = nil
        }
        return min(waiting.count, free.count)
    }

    /// Takes the bottles of these wines out of their places (they stay in the cellar).
    /// Returns how many moved.
    @discardableResult
    mutating func unplace(wines wineIDs: Set<UUID>) -> Int {
        unplace(bottles: Set(bottles.values.filter { wineIDs.contains($0.wineID) }.map(\.id)))
    }

    /// Takes these bottles out of their places (they stay in the cellar). Returns how many moved.
    @discardableResult
    mutating func unplace(bottles bottleIDs: Set<UUID>) -> Int {
        var count = 0
        for id in bottleIDs {
            guard let bottle = bottles[id], bottle.status == .inCellar, bottle.slot != nil else { continue }
            bottles[id]?.slot = nil
            count += 1
        }
        return count
    }

    /// Removes a rack; its bottles stay in the cellar without a slot.
    mutating func removeRack(_ rackID: UUID) {
        racks.removeAll { $0.id == rackID }
        for (id, bottle) in bottles where bottle.slot?.rackID == rackID {
            bottles[id]?.slot = nil
        }
    }

    internal mutating func removeOrphanWines() {
        let used = Set(bottles.values.map(\.wineID)).union(discoveries.values.map(\.wineID))
        for id in wines.keys where !used.contains(id) { wines[id] = nil }
    }

    private func validateInRange(_ slot: Slot) throws {
        guard let rack = racks.first(where: { $0.id == slot.rackID }) else { throw CellarError.unknownRack }
        guard rack.contains(slot) else { throw CellarError.slotOutOfRange }
    }

    private func validateFree(_ slot: Slot) throws {
        try validateInRange(slot)
        if bottle(at: slot) != nil { throw CellarError.slotTaken }
    }
}

extension Wine {
    /// A name or producer compared for `Cellar.wine(matching:)`: without case, accents or extra spaces.
    static func matchKey(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    /// What a search looks in: the label fields, grapes, barcodes and tags.
    var searchFields: [String] {
        ([name, producer, region, country, appellation, vintage.map(String.init)] + grapes.map(Optional.some)
            + (barcodes ?? []).map(Optional.some) + (tags ?? []).map(Optional.some)).compactMap { $0 }
    }

    /// Every word of `needle` is in one of the fields ("Margaux 2015", "syrah guigal").
    func matches(_ needle: String) -> Bool {
        SearchText.matches(needle, in: searchFields)
    }
}

/// Searches by words: each word of the query must appear, ignoring case and accents, in
/// one of the fields (not necessarily the same one). The Kotlin core does the same.
public enum SearchText {
    public static func words(_ query: String) -> [Substring] {
        query.split(whereSeparator: \.isWhitespace)
    }

    /// True for a blank query.
    public static func matches(_ query: String, in fields: [String]) -> Bool {
        words(query).allSatisfy { word in
            fields.contains { $0.range(of: word, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
        }
    }
}
