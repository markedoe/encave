import Foundation

/// The whole cellar: wines, bottles and racks. A value type, so the UI layer
/// can observe it and a sync engine can later replace how it is stored.
public struct Cellar: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    public var wines: [UUID: Wine]
    public var bottles: [UUID: Bottle]
    public var racks: [Rack]

    public init(wines: [Wine] = [], bottles: [Bottle] = [], racks: [Rack] = []) {
        schemaVersion = Cellar.currentSchemaVersion
        self.wines = Dictionary(uniqueKeysWithValues: wines.map { ($0.id, $0) })
        self.bottles = Dictionary(uniqueKeysWithValues: bottles.map { ($0.id, $0) })
        self.racks = racks
    }

    public var isEmpty: Bool { wines.isEmpty && bottles.isEmpty && racks.isEmpty }
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

public extension Cellar {
    var bottlesInCellar: [Bottle] { bottles.values.filter { $0.status == .inCellar } }

    var consumedBottles: [Bottle] {
        bottles.values
            .filter { $0.status == .consumed }
            .sorted { ($0.tasting?.date ?? .distantPast) > ($1.tasting?.date ?? .distantPast) }
    }

    /// Wines that still have bottles in the cellar, sorted by name then vintage.
    func groups(matching query: String = "") -> [WineGroup] {
        let byWine = Dictionary(grouping: bottlesInCellar, by: \.wineID)
        let needle = query.trimmingCharacters(in: .whitespaces)
        return byWine.compactMap { wineID, bottles -> WineGroup? in
            guard let wine = wines[wineID] else { return nil }
            guard needle.isEmpty || wine.matches(needle) else { return nil }
            return WineGroup(wine: wine, bottles: bottles.sorted { $0.addedAt < $1.addedAt })
        }
        .sorted {
            let order = $0.wine.name.localizedCaseInsensitiveCompare($1.wine.name)
            if order != .orderedSame { return order == .orderedAscending }
            return ($0.wine.vintage ?? 0) < ($1.wine.vintage ?? 0)
        }
    }

    /// Groups bucketed by maturity for "Tonight", most urgent first.
    func groupsByMaturity(year: Int) -> [MaturitySection] {
        Dictionary(grouping: groups()) { Maturity.of($0.wine, year: year) }
            .sorted { $0.key < $1.key }
            .map { MaturitySection(maturity: $0.key, groups: $0.value) }
    }

    func bottle(at slot: Slot) -> Bottle? {
        bottlesInCellar.first { $0.slot == slot }
    }

    func freeSlots(in rack: Rack) -> [Slot] {
        let taken = Set(bottlesInCellar.compactMap(\.slot))
        var result: [Slot] = []
        for row in 0..<rack.rows {
            for column in 0..<rack.columns {
                let slot = Slot(rackID: rack.id, row: row, column: column)
                if !taken.contains(slot) { result.append(slot) }
            }
        }
        return result
    }

    /// "Cellar A · B3", or nil when the bottle has no slot.
    func locationLabel(for bottle: Bottle) -> String? {
        guard let slot = bottle.slot, let rack = racks.first(where: { $0.id == slot.rackID }) else { return nil }
        return "\(rack.name) · \(rack.label(row: slot.row, column: slot.column))"
    }
}

// MARK: - Commands

public enum CellarError: Error, Equatable {
    case unknownWine, unknownBottle, unknownRack, slotTaken, slotOutOfRange, alreadyConsumed
}

public extension Cellar {
    /// Adds `quantity` bottles of a wine, placing them in the given slots (in order) when provided.
    @discardableResult
    mutating func addBottles(of wine: Wine, quantity: Int = 1, slots: [Slot] = [], addedAt: Date = .now) throws -> [Bottle] {
        guard quantity > 0 else { return [] }
        for slot in slots { try validateFree(slot) }
        guard Set(slots).count == slots.count else { throw CellarError.slotTaken }
        wines[wine.id] = wine
        var added: [Bottle] = []
        for index in 0..<quantity {
            let bottle = Bottle(wineID: wine.id, slot: index < slots.count ? slots[index] : nil, addedAt: addedAt)
            bottles[bottle.id] = bottle
            added.append(bottle)
        }
        return added
    }

    mutating func drink(_ bottleID: UUID, tasting: Tasting) throws {
        guard var bottle = bottles[bottleID] else { throw CellarError.unknownBottle }
        guard bottle.status == .inCellar else { throw CellarError.alreadyConsumed }
        bottle.status = .consumed
        bottle.slot = nil
        bottle.tasting = tasting
        bottles[bottleID] = bottle
    }

    /// Reverts a drink, putting the bottle back in its previous slot if still free.
    mutating func undoDrink(_ bottleID: UUID, previousSlot: Slot?) {
        guard var bottle = bottles[bottleID], bottle.status == .consumed else { return }
        bottle.status = .inCellar
        bottle.tasting = nil
        if let previousSlot, self.bottle(at: previousSlot) == nil { bottle.slot = previousSlot }
        bottles[bottleID] = bottle
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

    mutating func updateWine(_ wine: Wine) throws {
        guard wines[wine.id] != nil else { throw CellarError.unknownWine }
        wines[wine.id] = wine
    }

    mutating func addRack(_ rack: Rack) { racks.append(rack) }

    /// Removes a rack; its bottles stay in the cellar without a slot.
    mutating func removeRack(_ rackID: UUID) {
        racks.removeAll { $0.id == rackID }
        for (id, bottle) in bottles where bottle.slot?.rackID == rackID {
            bottles[id]?.slot = nil
        }
    }

    private func validateInRange(_ slot: Slot) throws {
        guard let rack = racks.first(where: { $0.id == slot.rackID }) else { throw CellarError.unknownRack }
        guard (0..<rack.rows).contains(slot.row), (0..<rack.columns).contains(slot.column) else {
            throw CellarError.slotOutOfRange
        }
    }

    private func validateFree(_ slot: Slot) throws {
        try validateInRange(slot)
        if bottle(at: slot) != nil { throw CellarError.slotTaken }
    }
}

extension Wine {
    func matches(_ needle: String) -> Bool {
        let fields = [name, producer, region, country, appellation, vintage.map(String.init)] + grapes.map(Optional.some)
        return fields.compactMap { $0 }.contains { $0.localizedCaseInsensitiveContains(needle) }
    }
}
