import Foundation

/// What dropping a wine or a bottle onto a storage does (Mac sidebar).
public enum BottleDrop: Equatable, Sendable {
    /// This bottle goes to this position, the first free one of the storage.
    case place(bottleID: UUID, slot: Slot)
    /// The storage is full: nothing moves.
    case noFreePosition
    /// No bottle to put there: none left in the cellar, or all already in this storage.
    case nothingToPlace
}

public extension Cellar {
    /// The first free position of a storage, in the order it is filled (`Rack.allSlots`).
    func firstFreeSlot(in rack: Rack) -> Slot? {
        freeSlots(in: rack).first
    }

    /// Where one bottle goes when a bottle, or a wine, is dropped onto a storage. A wine
    /// gives the bottle that has no position first, else one kept in another storage;
    /// among those, one not kept for an occasion, then the oldest.
    func bottleDrop(bottleID: UUID? = nil, wineID: UUID? = nil, onto rackID: UUID) -> BottleDrop {
        guard let rack = rack(rackID) else { return .nothingToPlace }
        let candidates: [Bottle]
        if let bottleID {
            candidates = bottles[bottleID].map { [$0] } ?? []
        } else if let wineID {
            candidates = bottlesInCellar.filter { $0.wineID == wineID }
        } else {
            candidates = []
        }
        let movable = candidates
            .filter { $0.status == .inCellar && $0.slot?.rackID != rackID }
            .sorted { a, b in
                if (a.slot == nil) != (b.slot == nil) { return a.slot == nil }
                if (a.reservedFor == nil) != (b.reservedFor == nil) { return a.reservedFor == nil }
                if a.addedAt != b.addedAt { return a.addedAt < b.addedAt }
                return a.id.uuidString < b.id.uuidString
            }
        guard let bottle = movable.first else { return .nothingToPlace }
        guard let slot = firstFreeSlot(in: rack) else { return .noFreePosition }
        return .place(bottleID: bottle.id, slot: slot)
    }
}
