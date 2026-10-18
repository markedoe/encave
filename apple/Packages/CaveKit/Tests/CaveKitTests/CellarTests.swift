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

    @Test func journalSearchLooksInTheWineAndTheTasting() throws {
        var cellar = cellarWithRack()
        let wine = Wine(name: "Clos Test", producer: "Domaine Été", grapes: ["Gamay"])
        let bottles = try cellar.addBottles(of: wine, quantity: 2)
        try cellar.drink(bottles[0].id, tasting: Tasting(date: .now, occasion: "Picnic", foodPairing: "Roast duck"))
        try cellar.drink(bottles[1].id, tasting: Tasting(date: .now, note: "Silky"))
        #expect(cellar.tastings(matching: "").count == 2)
        #expect(cellar.tastings(matching: "gamay").count == 2)
        #expect(cellar.tastings(matching: "ete").count == 2, "diacritics are ignored")
        #expect(cellar.tastings(matching: "duck").map(\.id) == [bottles[0].id])
        #expect(cellar.tastings(matching: "picnic").map(\.id) == [bottles[0].id])
        #expect(cellar.tastings(matching: "silky ").map(\.id) == [bottles[1].id])
        #expect(cellar.tastings(matching: "Bordeaux").isEmpty)
    }

    @Test func quickDrinkSkipsBottlesKeptForAnOccasion() throws {
        var cellar = cellarWithRack()
        let wine = Wine(name: "Test", vintage: 2015, color: .red)
        let added = try cellar.addBottles(of: wine, quantity: 2, addedAt: Date(timeIntervalSince1970: 0))
        try cellar.reserve([added[0].id], for: "Birthday")
        let bottles = cellar.bottles(of: wine.id)
        #expect(bottles.bottleToOpen?.id == added[1].id)
        try cellar.reserve([added[1].id], for: "Birthday")
        #expect(cellar.bottles(of: wine.id).bottleToOpen != nil, "all kept: still one to open")
        #expect([Bottle]().bottleToOpen == nil)
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

@Suite struct CellarYearTests {
    @Test func yearsAreGregorianWhateverTheUserCalendar() {
        #expect(CellarYear.calendar.identifier == .gregorian)
        // 2026-07-01 12:00 UTC: 2569 in the Buddhist calendar, Reiwa 8 in the Japanese one.
        let date = Date(timeIntervalSince1970: 1_782_907_200)
        #expect(CellarYear.of(date) == 2026)
        #expect(Calendar(identifier: .buddhist).component(.year, from: date) != 2026)
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

    @Test func unreadableFileIsSetAsideNotOverwritten() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("cellar.json")
        let store = JSONFileCellarStore(fileURL: url)
        let damaged = Data("{\"wines\": [".utf8)
        try damaged.write(to: url)
        let now = Date(timeIntervalSince1970: 1_800_000_000)

        let first = try store.loadSettingAsideUnreadable(now: now)
        #expect(first.cellar.isEmpty)
        let kept = try #require(first.setAside)
        #expect(kept.lastPathComponent.hasPrefix("cellar-unreadable-"))
        #expect(try Data(contentsOf: kept) == damaged)
        #expect(!FileManager.default.fileExists(atPath: url.path))

        // A second damaged file in the same second gets its own name.
        try damaged.write(to: url)
        let second = try #require(try store.loadSettingAsideUnreadable(now: now).setAside)
        #expect(second != kept)
        #expect(store.unreadableFiles() == [kept, second].sorted { $0.lastPathComponent < $1.lastPathComponent })

        // A readable file loads as usual and nothing is moved.
        let cellar = SampleData.cellar(year: 2026)
        try store.save(cellar)
        let loaded = try store.loadSettingAsideUnreadable(now: now)
        #expect(loaded.setAside == nil)
        #expect(loaded.cellar == cellar)
    }

    @Test func sampleDataIsConsistent() {
        let cellar = SampleData.cellar(year: 2026)
        #expect(cellar.racks.count == 6)
        #expect(cellar.consumedBottles.count == 2)
        let slots = cellar.bottlesInCellar.compactMap(\.slot)
        #expect(Set(slots).count == slots.count, "no two bottles share a slot")
    }
}
