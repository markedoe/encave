import Foundation
import Testing
@testable import CaveKit

@Suite struct CellarTests {
    let rack = Rack(name: "A", rows: 2, columns: 3)

    func cellarWithRack() -> Cellar {
        var cellar = Cellar()
        cellar.addRack(rack)
        return cellar
    }

    @Test func addBottlesGroupsByWine() throws {
        var cellar = cellarWithRack()
        let wine = Wine(name: "Test", vintage: 2015, color: .red)
        try cellar.addBottles(of: wine, quantity: 3)
        let groups = cellar.groups()
        #expect(groups.count == 1)
        #expect(groups[0].count == 3)
        #expect(groups[0].wine.displayName == "Test 2015")
    }

    @Test func slotsAreAssignedInOrderAndCannotBeShared() throws {
        var cellar = cellarWithRack()
        let slots = Array(cellar.freeSlots(in: rack).prefix(2))
        let added = try cellar.addBottles(of: Wine(name: "W"), quantity: 2, slots: slots)
        #expect(added.map(\.slot) == slots)
        #expect(cellar.freeSlots(in: rack).count == 4)
        #expect(throws: CellarError.slotTaken) {
            try cellar.addBottles(of: Wine(name: "X"), slots: [slots[0]])
        }
    }

    @Test func slotOutsideRackIsRejected() {
        var cellar = cellarWithRack()
        #expect(throws: CellarError.slotOutOfRange) {
            try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 5, column: 0)])
        }
    }

    @Test func drinkingFreesTheSlotAndUndoRestoresIt() throws {
        var cellar = cellarWithRack()
        let slot = Slot(rackID: rack.id, row: 0, column: 0)
        let bottle = try #require(try cellar.addBottles(of: Wine(name: "W"), slots: [slot]).first)
        try cellar.drink(bottle.id, tasting: Tasting(rating: 9, note: "Great"))
        #expect(cellar.bottle(at: slot) == nil)
        #expect(cellar.consumedBottles.first?.tasting?.rating == 5, "rating is clamped to 1...5")
        #expect(throws: CellarError.alreadyConsumed) { try cellar.drink(bottle.id, tasting: Tasting()) }

        cellar.undoDrink(bottle.id, previousSlot: slot)
        #expect(cellar.bottle(at: slot)?.id == bottle.id)
        #expect(cellar.consumedBottles.isEmpty)
    }

    @Test func moveRejectsOccupiedSlot() throws {
        var cellar = cellarWithRack()
        let a = Slot(rackID: rack.id, row: 0, column: 0)
        let b = Slot(rackID: rack.id, row: 0, column: 1)
        let first = try cellar.addBottles(of: Wine(name: "W"), slots: [a])[0]
        _ = try cellar.addBottles(of: Wine(name: "X"), slots: [b])
        #expect(throws: CellarError.slotTaken) { try cellar.move(first.id, to: b) }
        try cellar.move(first.id, to: Slot(rackID: rack.id, row: 1, column: 2))
        #expect(cellar.locationLabel(for: cellar.bottles[first.id]!) == "A · B3")
    }

    @Test func removingRackKeepsBottlesWithoutSlot() throws {
        var cellar = cellarWithRack()
        let bottle = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 0, column: 0)])[0]
        cellar.removeRack(rack.id)
        #expect(cellar.racks.isEmpty)
        #expect(cellar.bottles[bottle.id]?.slot == nil)
        #expect(cellar.bottles[bottle.id]?.status == .inCellar)
    }

    @Test func searchMatchesNameProducerRegionVintageAndGrapes() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Clos", producer: "Arnaud", vintage: 2015, region: "Bordeaux", grapes: ["Merlot"]))
        try cellar.addBottles(of: Wine(name: "Other"))
        for query in ["clos", "ARNAUD", "bordeaux", "2015", "merlot"] {
            #expect(cellar.groups(matching: query).map(\.wine.name) == ["Clos"], "query: \(query)")
        }
        #expect(cellar.groups(matching: "").count == 2)
    }

    @Test func rowLabelsGoPastZ() {
        #expect(Rack.rowLetters(0) == "A")
        #expect(Rack.rowLetters(25) == "Z")
        #expect(Rack.rowLetters(26) == "AA")
    }
}

@Suite struct MaturityTests {
    @Test(arguments: [
        (2020, Maturity.young), (2022, .ready), (2025, .ready), (2026, .drinkSoon), (2027, .pastPeak),
    ])
    func windowBoundaries(year: Int, expected: Maturity) {
        let wine = Wine(name: "W", drinkFrom: 2022, drinkUntil: 2026)
        #expect(Maturity.of(wine, year: year) == expected)
    }

    @Test func unknownWithoutWindow() {
        #expect(Maturity.of(Wine(name: "W"), year: 2026) == .unknown)
    }

    @Test func tonightOrdersMostUrgentFirst() {
        let cellar = SampleData.cellar(year: 2026)
        let order = cellar.groupsByMaturity(year: 2026).map(\.maturity)
        #expect(order == order.sorted())
        #expect(order.first == .drinkSoon)
    }

    @Test func estimatedWindowUsesColor() {
        #expect(Wine(name: "W", vintage: 2020, color: .red).estimatedWindow()! == (2023, 2035))
        #expect(Wine(name: "W", color: .red).estimatedWindow() == nil)
    }
}

@Suite struct StoreTests {
    @Test func jsonStoreRoundTrips() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cellar-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = JSONFileCellarStore(fileURL: url)
        #expect(try store.load().isEmpty)

        let cellar = SampleData.cellar(year: 2026)
        try store.save(cellar)
        #expect(try store.load() == cellar)
    }

    @Test func sampleDataIsConsistent() {
        let cellar = SampleData.cellar(year: 2026)
        #expect(cellar.racks.count == 3)
        #expect(cellar.consumedBottles.count == 2)
        let slots = cellar.bottlesInCellar.compactMap(\.slot)
        #expect(Set(slots).count == slots.count, "no two bottles share a slot")
    }
}
