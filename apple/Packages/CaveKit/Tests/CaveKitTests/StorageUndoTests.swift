import Foundation
import Testing
@testable import CaveKit

/// Undos and checks of a storage that must leave alone what changed since. Shared with the
/// Kotlin StorageUndoTest.
@Suite struct StorageUndoTests {
    @Test func undoOfAShelfRemovedKeepsBottlesPlacedSince() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 3, columns: 2)
        cellar.addRack(rack)
        let wine = Wine(name: "W")
        let out = try cellar.addBottles(of: wine, slots: [Slot(rackID: rack.id, row: 0, column: 0)])[0]
        let kept = try cellar.addBottles(of: wine, slots: [Slot(rackID: rack.id, row: 2, column: 1)])[0]
        let before = cellar.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        // Shelf 1 removed: shelves 2 and 3 move up.
        let edited = Rack(id: rack.id, name: "G", rows: 2, columns: 2)
        let map: [Int: Int?] = [0: nil, 1: 0, 2: 1]
        try cellar.updateRack(edited, rowMap: map, takingOut: true)
        // A new bottle on the new first shelf: the old shelf 2.
        let new = try cellar.addBottles(of: wine, slots: [Slot(rackID: rack.id, row: 0, column: 1)])[0]

        cellar.undoRackUpdate(back: rack, rowMap: map, takenOut: before)

        #expect(cellar.rack(rack.id)?.rows == 3)
        #expect(cellar.bottles[new.id]?.slot == Slot(rackID: rack.id, row: 1, column: 1), "stays on its shelf")
        #expect(cellar.bottles[kept.id]?.slot == Slot(rackID: rack.id, row: 2, column: 1))
        #expect(cellar.bottles[out.id]?.slot == Slot(rackID: rack.id, row: 0, column: 0))
    }

    @Test func undoOfAShelfRemovedLeavesBottlesMovedOrOpenedSince() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 2, columns: 2), other = Rack(name: "O", rows: 1, columns: 2)
        cellar.addRack(rack)
        cellar.addRack(other)
        let wine = Wine(name: "W")
        let bottles = try cellar.addBottles(of: wine, quantity: 3, slots: [
            Slot(rackID: rack.id, row: 0, column: 0), Slot(rackID: rack.id, row: 1, column: 0),
            Slot(rackID: rack.id, row: 1, column: 1),
        ])
        let before = cellar.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        let map: [Int: Int?] = [0: nil, 1: 0]
        try cellar.updateRack(Rack(id: rack.id, name: "G", rows: 1, columns: 2), rowMap: map, takingOut: true)
        // Taken out, then put in another storage; another one opened.
        try cellar.move(bottles[0].id, to: Slot(rackID: other.id, row: 0, column: 0))
        try cellar.drink(bottles[2].id, tasting: Tasting(date: .now))

        cellar.undoRackUpdate(back: rack, rowMap: map, takenOut: before)

        #expect(cellar.bottles[bottles[0].id]?.slot == Slot(rackID: other.id, row: 0, column: 0))
        #expect(cellar.bottles[bottles[1].id]?.slot == Slot(rackID: rack.id, row: 1, column: 0))
        #expect(cellar.bottles[bottles[2].id]?.status == .consumed)
        #expect(cellar.bottles[bottles[2].id]?.slot == nil)
    }

    @Test func undoOfASwapLeavesABottlePutThereSince() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 1, columns: 3)
        cellar.addRack(rack)
        let a = Slot(rackID: rack.id, row: 0, column: 0), b = Slot(rackID: rack.id, row: 0, column: 1)
        let first = try cellar.addBottles(of: Wine(name: "W"), slots: [a])[0]
        try cellar.swap(a, b)
        let third = try cellar.addBottles(of: Wine(name: "X"), slots: [a])[0]

        let undone = cellar.undoSwap(a, b, first: first.id, second: nil)
        #expect(!undone)
        #expect(cellar.bottles[third.id]?.slot == a, "the third bottle did not move")
        #expect(cellar.bottles[first.id]?.slot == b)
    }

    @Test func undoOfASwapSwapsBack() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 1, columns: 2)
        cellar.addRack(rack)
        let a = Slot(rackID: rack.id, row: 0, column: 0), b = Slot(rackID: rack.id, row: 0, column: 1)
        let bottles = try cellar.addBottles(of: Wine(name: "W"), quantity: 2, slots: [a, b])
        try cellar.swap(a, b)
        let undone = cellar.undoSwap(a, b, first: bottles[0].id, second: bottles[1].id)
        #expect(undone)
        #expect(cellar.bottles[bottles[0].id]?.slot == a)
        #expect(cellar.bottles[bottles[1].id]?.slot == b)
    }

    @Test func aBottleNoLongerInTheCellarCannotBeMovedToAPosition() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 1, columns: 2)
        cellar.addRack(rack)
        let bottles = try cellar.addBottles(of: Wine(name: "W"), quantity: 2)
        try cellar.drink(bottles[0].id, tasting: Tasting(date: .now))
        try cellar.markGone(bottles[1].id)
        let slot = Slot(rackID: rack.id, row: 0, column: 0)
        #expect(throws: CellarError.notInCellar) { try cellar.move(bottles[0].id, to: slot) }
        #expect(throws: CellarError.notInCellar) { try cellar.move(bottles[1].id, to: slot) }
        #expect(cellar.bottle(at: slot) == nil)
    }

    @Test func aBottleFoundAndThenSwappedIsNotMissing() throws {
        var cellar = Cellar()
        let rack = Rack(name: "G", rows: 1, columns: 2)
        cellar.addRack(rack)
        let a = Slot(rackID: rack.id, row: 0, column: 0), b = Slot(rackID: rack.id, row: 0, column: 1)
        let bottles = try cellar.addBottles(of: Wine(name: "W"), quantity: 2, slots: [a, b])
        let found: Set<UUID> = [bottles[0].id]
        try cellar.swap(a, b)
        #expect(cellar.missingBottles(in: rack.id, found: found).map(\.id) == [bottles[1].id])
    }

    @Test func reservingWithTheSameNameChangesNothing() throws {
        var cellar = Cellar()
        let bottle = try cellar.addBottles(of: Wine(name: "W"))[0]
        #expect(!cellar.changesReservation([bottle.id], for: "  "), "an empty name frees a bottle never kept")
        #expect(!cellar.changesReservation([bottle.id], for: nil))
        #expect(cellar.changesReservation([bottle.id], for: "Birthday"))
        try cellar.reserve([bottle.id], for: "Birthday")
        #expect(!cellar.changesReservation([bottle.id], for: " Birthday "))
        #expect(cellar.changesReservation([bottle.id], for: nil))
    }
}
