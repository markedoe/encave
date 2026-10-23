import Foundation
import Testing
@testable import CaveKit

@Suite struct BottleDropTests {
    @Test func aWineGivesItsBottleWithoutAPositionToTheFirstFreeOne() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Small", rows: 1, columns: 3)
        let other = Rack(name: "Other", rows: 1, columns: 2)
        cellar.addRack(rack)
        cellar.addRack(other)
        let wine = Wine(name: "A"), filler = Wine(name: "B")
        try cellar.addBottles(of: filler, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        let placed = try cellar.addBottles(of: wine, slots: [Slot(rackID: other.id, row: 0, column: 0)], addedAt: Date(timeIntervalSince1970: 0))
        let loose = try cellar.addBottles(of: wine, addedAt: Date(timeIntervalSince1970: 100))
        let drop = cellar.bottleDrop(wineID: wine.id, onto: rack.id)
        #expect(drop == .place(bottleID: loose[0].id, slot: Slot(rackID: rack.id, row: 0, column: 1)))
        // Then the one kept elsewhere.
        try cellar.move(loose[0].id, to: Slot(rackID: rack.id, row: 0, column: 1))
        #expect(cellar.bottleDrop(wineID: wine.id, onto: rack.id) == .place(bottleID: placed[0].id, slot: Slot(rackID: rack.id, row: 0, column: 2)))
    }

    @Test func aBottleGoesToTheFirstFreePositionInFillingOrder() throws {
        var cellar = Cellar()
        let fridge = Rack(name: "Fridge", rows: 2, columns: 2, depth: 2)
        cellar.addRack(fridge)
        let wine = Wine(name: "A")
        try cellar.addBottles(of: wine, slots: [Slot(rackID: fridge.id, row: 0, column: 0)])
        let bottle = try cellar.addBottles(of: wine)[0]
        #expect(cellar.firstFreeSlot(in: fridge) == fridge.allSlots[1])
        #expect(cellar.bottleDrop(bottleID: bottle.id, onto: fridge.id) == .place(bottleID: bottle.id, slot: fridge.allSlots[1]))
    }

    @Test func neverIntoAFullStorage() throws {
        var cellar = Cellar()
        let rack = Rack(name: "One", rows: 1, columns: 1)
        cellar.addRack(rack)
        try cellar.addBottles(of: Wine(name: "Full"), slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        let wine = Wine(name: "A")
        let bottle = try cellar.addBottles(of: wine)[0]
        #expect(cellar.firstFreeSlot(in: rack) == nil)
        #expect(cellar.bottleDrop(bottleID: bottle.id, onto: rack.id) == .noFreePosition)
        #expect(cellar.bottleDrop(wineID: wine.id, onto: rack.id) == .noFreePosition)
    }

    @Test func nothingToPlaceWhenAlreadyThereOrGone() throws {
        var cellar = Cellar()
        let rack = Rack(name: "R", rows: 1, columns: 3)
        cellar.addRack(rack)
        let wine = Wine(name: "A")
        let bottle = try cellar.addBottles(of: wine, slots: [Slot(rackID: rack.id, row: 0, column: 0)])[0]
        #expect(cellar.bottleDrop(bottleID: bottle.id, onto: rack.id) == .nothingToPlace)
        #expect(cellar.bottleDrop(wineID: wine.id, onto: rack.id) == .nothingToPlace)
        #expect(cellar.bottleDrop(wineID: UUID(), onto: rack.id) == .nothingToPlace)
        #expect(cellar.bottleDrop(bottleID: bottle.id, onto: UUID()) == .nothingToPlace)
    }

    @Test func aBottleKeptForAnOccasionComesLast() throws {
        var cellar = Cellar()
        let rack = Rack(name: "R", rows: 1, columns: 3)
        cellar.addRack(rack)
        let wine = Wine(name: "A")
        let kept = try cellar.addBottles(of: wine, addedAt: Date(timeIntervalSince1970: 0))[0]
        let free = try cellar.addBottles(of: wine, addedAt: Date(timeIntervalSince1970: 100))[0]
        cellar.bottles[kept.id]?.reservedFor = "Birthday"
        #expect(cellar.bottleDrop(wineID: wine.id, onto: rack.id) == .place(bottleID: free.id, slot: Slot(rackID: rack.id, row: 0, column: 0)))
    }
}
