import Foundation

/// The whole cellar: wines, bottles, racks and settings. A value type, so the UI
/// layer can observe it and a sync engine can later replace how it is stored.
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
        self.wines = Dictionary(uniqueKeysWithValues: wines.map { ($0.id, $0) })
        self.bottles = Dictionary(uniqueKeysWithValues: bottles.map { ($0.id, $0) })
        self.racks = racks
        self.settings = settings
        self.discoveries = Dictionary(uniqueKeysWithValues: discoveries.map { ($0.id, $0) })
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        wines = try c.decodeIfPresent([UUID: Wine].self, forKey: .wines) ?? [:]
        bottles = try c.decodeIfPresent([UUID: Bottle].self, forKey: .bottles) ?? [:]
        racks = try c.decodeIfPresent([Rack].self, forKey: .racks) ?? []
        settings = try c.decodeIfPresent(CellarSettings.self, forKey: .settings) ?? CellarSettings()
        discoveries = try c.decodeIfPresent([UUID: Discovery].self, forKey: .discoveries) ?? [:]
        schemaVersion = Cellar.currentSchemaVersion
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

    public init(query: String = "", colors: Set<WineColor> = [], maturities: Set<Maturity> = [], rackID: UUID? = nil,
                tag: String? = nil) {
        self.query = query; self.colors = colors; self.maturities = maturities; self.rackID = rackID; self.tag = tag
    }

    public var isActive: Bool { !colors.isEmpty || !maturities.isEmpty || rackID != nil || tag != nil }
}

public extension Cellar {
    var bottlesInCellar: [Bottle] { bottles.values.filter { $0.status == .inCellar } }

    /// Tags of wines with bottles in the cellar, most used first.
    var wineTags: [String] {
        var counts: [String: (name: String, count: Int)] = [:]
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        for wine in wines.values where inCellar.contains(wine.id) {
            for tag in wine.tags ?? [] { counts[tag.lowercased(), default: (tag, 0)].count += 1 }
        }
        return counts.values
            .sorted { $0.count == $1.count ? $0.name.localizedStandardCompare($1.name) == .orderedAscending : $0.count > $1.count }
            .map(\.name)
    }

    /// Bottles bought but not delivered yet.
    var bottlesOnOrder: [Bottle] { bottles.values.filter { $0.status == .ordered } }

    /// Wines with bottles on order, the soonest expected first (unknown dates last).
    func orderGroups() -> [WineGroup] {
        Dictionary(grouping: bottlesOnOrder, by: \.wineID)
            .compactMap { wineID, bottles -> WineGroup? in
                guard let wine = wines[wineID] else { return nil }
                let sorted = bottles.sorted { ($0.expectedAt ?? .distantFuture, $0.addedAt) < ($1.expectedAt ?? .distantFuture, $1.addedAt) }
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
        groups(filter: CellarFilter(query: query), sort: .name, year: Calendar.current.component(.year, from: .now))
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
            var kept = bottles
            if let rackID = filter.rackID {
                kept = bottles.filter { $0.slot?.rackID == rackID }
                if kept.isEmpty { return nil }
            }
            return WineGroup(wine: wine, bottles: kept.sorted { $0.addedAt < $1.addedAt })
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
                return (a.wine.drinkUntil ?? .max) < (b.wine.drinkUntil ?? .max)
            case .region:
                let ra = [a.wine.country ?? "", a.wine.region ?? ""].joined(separator: "/")
                let rb = [b.wine.country ?? "", b.wine.region ?? ""].joined(separator: "/")
                let order = ra.localizedCaseInsensitiveCompare(rb)
                return order != .orderedSame ? order == .orderedAscending : Cellar.nameOrder(a.wine, b.wine)
            }
        }
    }

    /// Groups bucketed by maturity for "Tonight", most urgent first.
    func groupsByMaturity(year: Int) -> [MaturitySection] {
        Dictionary(grouping: groups()) { Maturity.of($0.wine, year: year) }
            .sorted { $0.key < $1.key }
            .map { MaturitySection(maturity: $0.key, groups: $0.value) }
    }

    /// Bottles of a wine still in the cellar.
    func bottles(of wineID: UUID) -> [Bottle] {
        bottlesInCellar.filter { $0.wineID == wineID }.sorted { $0.addedAt < $1.addedAt }
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
        return (0..<rack.positionsPerCell).first { !taken.contains($0) }
            .map { Slot(rackID: cell.rackID, row: cell.row, column: cell.column, depth: $0) }
    }

    func occupancy(of rack: Rack) -> Int {
        bottlesInCellar.filter { $0.slot?.rackID == rack.id }.count
    }

    func rack(_ id: UUID) -> Rack? { racks.first { $0.id == id } }

    /// "Cellar A · B3", the storage note, or nil when the bottle has no location.
    func locationLabel(for bottle: Bottle) -> String? {
        if let slot = bottle.slot, let rack = rack(slot.rackID) {
            return "\(rack.name) · \(rack.label(for: slot))"
        }
        if let note = bottle.storageNote, !note.isEmpty { return note }
        return nil
    }

    /// The wine a barcode was seen on, preferring one with bottles in the cellar.
    func wine(barcode: String) -> Wine? {
        let code = Wine.normalizedBarcode(barcode)
        guard !code.isEmpty else { return nil }
        let matching = wines.values.filter { ($0.barcodes ?? []).contains(code) }
        let inCellar = Set(bottlesInCellar.map(\.wineID))
        return matching.sorted { (inCellar.contains($0.id) ? 0 : 1, $0.displayName) < (inCellar.contains($1.id) ? 0 : 1, $1.displayName) }
            .first
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

    static func nameOrder(_ a: Wine, _ b: Wine) -> Bool {
        let order = a.name.localizedCaseInsensitiveCompare(b.name)
        if order != .orderedSame { return order == .orderedAscending }
        return (a.vintage ?? 0) < (b.vintage ?? 0)
    }
}

// MARK: - Commands

public enum CellarError: Error, Equatable {
    case unknownWine, unknownBottle, unknownRack, unknownDiscovery, slotTaken, slotOutOfRange, alreadyConsumed, rackNotEmpty
    /// The bottle is on order: receive it before placing or drinking it.
    case notReceived
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
        bottle.tasting = tasting
        bottles[bottleID] = bottle
    }

    /// Reverts a drink, putting the bottle back in its previous slot if still free.
    mutating func undoDrink(_ bottleID: UUID, previousSlot: Slot?) {
        guard var bottle = bottles[bottleID], bottle.status != .inCellar else { return }
        bottle.status = .inCellar
        bottle.tasting = nil
        if let previousSlot, self.bottle(at: previousSlot) == nil { bottle.slot = previousSlot }
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
        guard bottles[bottle.id] != nil else { throw CellarError.unknownBottle }
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

    mutating func updateWine(_ wine: Wine) throws {
        guard wines[wine.id] != nil else { throw CellarError.unknownWine }
        wines[wine.id] = wine
    }

    /// Deletes a wine with every bottle and discovery of it.
    mutating func deleteWine(_ wineID: UUID) {
        wines[wineID] = nil
        for (id, bottle) in bottles where bottle.wineID == wineID { bottles[id] = nil }
        for (id, discovery) in discoveries where discovery.wineID == wineID { discoveries[id] = nil }
    }

    mutating func addRack(_ rack: Rack) { racks.append(rack) }

    /// Updates name, layout or size. Shrinking is refused if it would drop bottles.
    mutating func updateRack(_ rack: Rack) throws {
        guard let index = racks.firstIndex(where: { $0.id == rack.id }) else { throw CellarError.unknownRack }
        let outside = bottlesInCellar.contains {
            guard let slot = $0.slot, slot.rackID == rack.id else { return false }
            return !rack.contains(slot)
        }
        if outside { throw CellarError.rackNotEmpty }
        racks[index] = rack
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
        guard let rack = rack(rackID) else { throw CellarError.unknownRack }
        let waiting = bottlesInCellar
            .filter { wineIDs.contains($0.wineID) && $0.slot == nil }
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
        var count = 0
        for (id, bottle) in bottles where wineIDs.contains(bottle.wineID) && bottle.status == .inCellar && bottle.slot != nil {
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
    func matches(_ needle: String) -> Bool {
        let fields = [name, producer, region, country, appellation, vintage.map(String.init)] + grapes.map(Optional.some)
            + (barcodes ?? []).map(Optional.some) + (tags ?? []).map(Optional.some)
        return fields.compactMap { $0 }.contains {
            $0.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive]) != nil
        }
    }
}
