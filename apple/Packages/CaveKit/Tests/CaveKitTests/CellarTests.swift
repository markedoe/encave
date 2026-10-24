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

    @Test func bottlesAreListedAsTheirPlacesRead() {
        let grid = Rack(name: "Grid", rows: 2, columns: 3, depth: 2)
        let fridge = Rack(name: "Fridge", kind: .staggered, rows: 1, columns: 6, depth: 2, shelfLayers: [2, 1])
        let cellar = Cellar(racks: [grid, fridge])
        let wineID = UUID()
        let day = Date(timeIntervalSince1970: 1_000_000)
        func bottle(_ rack: Rack?, _ row: Int = 0, _ column: Int = 0, depth: Int = 0, note: String? = nil,
                    added: TimeInterval = 0) -> Bottle {
            Bottle(wineID: wineID, slot: rack.map { Slot(rackID: $0.id, row: row, column: column, depth: depth) },
                   storageNote: note, addedAt: day.addingTimeInterval(added))
        }
        let expected = [
            bottle(grid, 0, 2), bottle(grid, 0, 0, depth: 1), bottle(grid, 1, 0),
            // Fridge: shelf 1 front (bottom layer B, then top layer A), shelf 1 back, then shelf 2.
            bottle(fridge, 1, 4), bottle(fridge, 0, 0), bottle(fridge, 1, 0, depth: 1), bottle(fridge, 2, 0),
            bottle(nil, note: "Garage", added: -10), bottle(nil, note: "Garage", added: 5),
        ]
        // Whatever order the bottles come in, newest first included, the order is the same.
        let shuffled = [expected[8], expected[4], expected[1], expected[7], expected[6], expected[0],
                        expected[5], expected[3], expected[2]]
        #expect(cellar.inPlaceOrder(shuffled).map(\.id) == expected.map(\.id))
        #expect(cellar.inPlaceOrder(expected.reversed()).map(\.id) == expected.map(\.id))
        // Two bottles alike but for their id: by id.
        let twins = [bottle(nil, added: 1), bottle(nil, added: 1)]
        let byID = twins.sorted { $0.id.uuidString < $1.id.uuidString }.map(\.id)
        #expect(cellar.inPlaceOrder(twins).map(\.id) == byID)
        #expect(cellar.inPlaceOrder(twins.reversed()).map(\.id) == byID)
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

    @Test func aWineEnteredTwiceIsMergedIntoTheOther() throws {
        var cellar = cellarWithRack()
        let kept = Wine(name: "Clos Test", vintage: 2019, photoID: "kept")
        let twin = Wine(name: "clos test", vintage: 2019, photoID: "twin", barcodes: ["3760000000017"])
        try cellar.addBottles(of: kept, quantity: 1)
        let moved = try cellar.addBottles(of: twin, quantity: 2)
        #expect(cellar.wine(matching: twin)?.id == kept.id)
        try cellar.mergeWine(twin.id, into: kept.id)
        #expect(cellar.wines[twin.id] == nil)
        #expect(moved.allSatisfy { cellar.bottles[$0.id]?.wineID == kept.id })
        #expect(cellar.wines[kept.id]?.photoID == "kept")
        #expect(cellar.wines[kept.id]?.barcodes == ["3760000000017"])
        #expect(throws: CellarError.self) { try cellar.mergeWine(kept.id, into: kept.id) }
    }

    @Test func aMergeTakesTheShoppingListAndThePhotoWhenMissing() throws {
        var cellar = cellarWithRack()
        let kept = Wine(name: "Clos Test", vintage: 2019)
        let twin = Wine(name: "Clos Test", vintage: 2019, photoID: "twin")
        try cellar.addBottles(of: kept, quantity: 1)
        try cellar.addBottles(of: twin, quantity: 1)
        let wish = Discovery(wineID: twin.id, wantsToBuy: true)
        cellar.discoveries[wish.id] = wish
        try cellar.mergeWine(twin.id, into: kept.id)
        #expect(cellar.discoveries[wish.id]?.wineID == kept.id)
        #expect(cellar.wines[kept.id]?.photoID == "twin")
        #expect(cellar.bottles.values.allSatisfy { $0.wineID == kept.id })
        #expect(throws: CellarError.self) { try cellar.mergeWine(twin.id, into: kept.id) } // gone already
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

        // The rack was removed in the meantime: the bottle comes back without a place.
        try cellar.drink(bottle.id, tasting: Tasting())
        cellar.removeRack(rack.id)
        cellar.undoDrink(bottle.id, previousSlot: slot)
        #expect(cellar.bottles[bottle.id]?.status == .inCellar)
        #expect(cellar.bottles[bottle.id]?.slot == nil)
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

    @Test func fileThatCannotBeOpenedIsNotSetAside() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("store-\(UUID())")
        defer { try? FileManager.default.removeItem(at: folder) }
        // A directory where the file should be: it exists but cannot be read as a file,
        // like a protected file before the first unlock.
        let url = folder.appendingPathComponent("cellar.json")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        let store = JSONFileCellarStore(fileURL: url)
        #expect(throws: (any Error).self) { try store.loadSettingAsideUnreadable() }
        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(store.unreadableFiles().isEmpty)
    }

    @Test func sampleDataIsConsistent() {
        let cellar = SampleData.cellar(year: 2026)
        #expect(cellar.racks.count == 8)
        #expect(cellar.consumedBottles.count == 2)
        let slots = cellar.bottlesInCellar.compactMap(\.slot)
        #expect(Set(slots).count == slots.count, "no two bottles share a slot")
    }
}
