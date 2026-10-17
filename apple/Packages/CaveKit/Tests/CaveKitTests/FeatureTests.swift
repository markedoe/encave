import Foundation
import Testing
@testable import CaveKit

@Suite struct FilterAndSortTests {
    let cellar = SampleData.cellar(year: 2026)

    @Test func colorFilterKeepsOnlyThatStyle() {
        let reds = cellar.groups(filter: CellarFilter(colors: [.red]), sort: .name, year: 2026)
        #expect(!reds.isEmpty)
        #expect(reds.allSatisfy { $0.wine.color == .red })
    }

    @Test func maturityFilter() {
        let ready = cellar.groups(filter: CellarFilter(maturities: [.drinkSoon]), sort: .name, year: 2026)
        #expect(ready.allSatisfy { Maturity.of($0.wine, year: 2026) == .drinkSoon })
    }

    @Test func rackFilterKeepsOnlyBottlesInThatRack() throws {
        let rack = try #require(cellar.racks.first)
        let groups = cellar.groups(filter: CellarFilter(rackID: rack.id), sort: .name, year: 2026)
        #expect(groups.flatMap(\.bottles).allSatisfy { $0.slot?.rackID == rack.id })
    }

    @Test func searchIgnoresAccents() {
        #expect(!cellar.groups(matching: "Rose de Provence").isEmpty)
    }

    @Test(arguments: CellarSort.allCases)
    func everySortKeepsAllGroups(sort: CellarSort) {
        #expect(cellar.groups(filter: CellarFilter(), sort: sort, year: 2026).count == cellar.groups().count)
    }

    @Test func vintageSortIsAscendingWithNonVintageLast() {
        let vintages = cellar.groups(filter: CellarFilter(), sort: .vintage, year: 2026).map(\.wine.vintage)
        let known = vintages.compactMap { $0 }
        #expect(known == known.sorted())
        #expect(vintages.last.flatMap { $0 } == nil)
    }
}

@Suite struct CommandTests {
    @Test func purchaseInfoIsAppliedToEveryBottle() throws {
        var cellar = Cellar(settings: CellarSettings(currency: "CHF"))
        let added = try cellar.addBottles(of: Wine(name: "W"), quantity: 2, purchase: PurchaseInfo(price: 20, place: "Shop"))
        #expect(added.allSatisfy { $0.purchasePrice == 20 && $0.currency == "CHF" && $0.purchasePlace == "Shop" })
    }

    @Test func swapExchangesTwoSlots() throws {
        var cellar = Cellar()
        let rack = Rack(name: "A", rows: 1, columns: 2)
        cellar.addRack(rack)
        let a = Slot(rackID: rack.id, row: 0, column: 0), b = Slot(rackID: rack.id, row: 0, column: 1)
        let first = try cellar.addBottles(of: Wine(name: "W"), slots: [a])[0]
        try cellar.swap(a, b)
        #expect(cellar.bottles[first.id]?.slot == b)
        #expect(cellar.bottle(at: a) == nil)
    }

    @Test func shrinkingARackWithBottlesOutsideIsRefused() throws {
        var cellar = Cellar()
        var rack = Rack(name: "A", rows: 3, columns: 3)
        cellar.addRack(rack)
        _ = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 2, column: 2)])
        rack.rows = 2
        #expect(throws: CellarError.rackNotEmpty) { try cellar.updateRack(rack) }
        rack.rows = 3
        rack.kind = .shelves
        try cellar.updateRack(rack)
        #expect(cellar.racks[0].kind == .shelves)
    }

    @Test func deletingLastBottleRemovesTheWine() throws {
        var cellar = Cellar()
        let bottle = try cellar.addBottles(of: Wine(name: "W"))[0]
        cellar.deleteBottle(bottle.id)
        #expect(cellar.wines.isEmpty)
    }

    @Test func goneBottlesLeaveTheCellarButNotTheJournal() throws {
        var cellar = Cellar()
        let bottle = try cellar.addBottles(of: Wine(name: "W"))[0]
        try cellar.markGone(bottle.id)
        #expect(cellar.bottlesInCellar.isEmpty)
        #expect(cellar.consumedBottles.isEmpty)
    }

    @Test func reorderingRacks() {
        var cellar = Cellar(racks: [Rack(name: "A", rows: 1, columns: 1), Rack(name: "B", rows: 1, columns: 1), Rack(name: "C", rows: 1, columns: 1)])
        cellar.moveRacks(fromOffsets: IndexSet(integer: 0), toOffset: 3)
        #expect(cellar.racks.map(\.name) == ["B", "C", "A"])
    }
}

@Suite struct StatisticsTests {
    @Test func countsAndValue() {
        let cellar = SampleData.cellar(year: 2026)
        let stats = cellar.statistics(year: 2026)
        #expect(stats.bottleCount == cellar.bottlesInCellar.count)
        #expect(stats.consumedCount == 2)
        #expect(stats.averageRating == 4.5)
        #expect((stats.valueByCurrency["EUR"] ?? 0) > 0)
        #expect(stats.consumptionByMonth.count == 12)
        #expect(stats.byColor.map(\.count).reduce(0, +) == stats.bottleCount)
    }
}

@Suite struct PairingTests {
    @Test func fishPrefersWhites() throws {
        let cellar = SampleData.cellar(year: 2026)
        let best = try #require(cellar.pairings(for: .fish, year: 2026).first)
        #expect(best.group.wine.color == .white)
    }

    @Test func redMeatPrefersReadyReds() throws {
        let cellar = SampleData.cellar(year: 2026)
        let best = try #require(cellar.pairings(for: .redMeat, year: 2026).first)
        #expect(best.group.wine.color == .red)
        #expect(best.reasons.contains(.readyToDrink))
    }
}

@Suite struct ImportExportTests {
    @Test func jsonBackupRoundTrips() throws {
        let cellar = SampleData.cellar(year: 2026)
        let data = try CellarExport.json(cellar)
        let restored = try CellarExport.backup(fromJSON: data)
        #expect(restored.cellar.bottles.count == cellar.bottles.count)
        #expect(restored.cellar.racks == cellar.racks)
    }

    @Test func rejectsForeignJSON() {
        #expect(throws: ImportError.notAnEncaveBackup) { try CellarExport.backup(fromJSON: Data("{}".utf8)) }
    }

    @Test func csvRoundTripKeepsWinesBottlesAndSlots() throws {
        let original = SampleData.cellar(year: 2026)
        let csv = CellarExport.csv(original)
        var target = Cellar(racks: original.racks)
        let summary = try target.importCSV(csv)
        #expect(summary.bottlesAdded == original.bottles.count)
        // The CSV lists bottles: wines only known from discoveries are not in it.
        #expect(target.wines.count == Set(original.bottles.values.map(\.wineID)).count)
        #expect(target.bottlesInCellar.count == original.bottlesInCellar.count)
        #expect(target.consumedBottles.count == original.consumedBottles.count)
        #expect(Set(target.bottlesInCellar.compactMap(\.slot)) == Set(original.bottlesInCellar.compactMap(\.slot)))
    }

    @Test func csvHandlesQuotesSemicolonsAndQuantity() throws {
        let csv = "Name;Vintage;Quantity;Note\n\"Château \"\"X\"\", grand\";2015;3;\"multi\nline\"\n"
        var cellar = Cellar()
        let summary = try cellar.importCSV(csv)
        #expect(summary.bottlesAdded == 3)
        #expect(cellar.wines.values.first?.name == "Château \"X\", grand")
    }

    @Test func csvWithoutNameColumnFails() {
        var cellar = Cellar()
        #expect(throws: ImportError.missingNameColumn) { try cellar.importCSV("producer\nX\n") }
    }

    @Test func slotLabelsParse() {
        let rack = Rack(name: "A", rows: 30, columns: 5)
        #expect(rack.slot(forLabel: "B3") == Slot(rackID: rack.id, row: 1, column: 2))
        #expect(rack.slot(forLabel: "AA1") == Slot(rackID: rack.id, row: 26, column: 0))
        #expect(rack.slot(forLabel: "Z9") == nil)
    }

    @Test func mergeAddsOnlyMissingRecords() {
        var cellar = SampleData.cellar(year: 2026)
        let copy = cellar
        let summary = cellar.merge(copy)
        #expect(summary.bottlesAdded == 0)
    }
}

@Suite struct CompatibilityTests {
    /// Files written by version 0.1 (schema 1) must still load.
    @Test func decodesSchemaOne() throws {
        let wineID = UUID(), bottleID = UUID(), rackID = UUID()
        let json = """
        {"schemaVersion":1,"racks":[{"id":"\(rackID)","name":"A","rows":2,"columns":2}],
         "wines":["\(wineID)",{"id":"\(wineID)","name":"Old","grapes":[]}],
         "bottles":["\(bottleID)",{"id":"\(bottleID)","wineID":"\(wineID)","status":"inCellar","addedAt":700000000}]}
        """
        let cellar = try JSONDecoder().decode(Cellar.self, from: Data(json.utf8))
        #expect(cellar.racks[0].kind == .grid)
        #expect(cellar.bottles[bottleID]?.sizeML == 750)
        #expect(cellar.settings.currency == "EUR")
    }
}

@Suite struct DrinkingDigestTests {
    @Test func countsBottlesByWhatToDoThisYear() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Soon", vintage: 2015, drinkFrom: 2020, drinkUntil: 2026), quantity: 2)
        try cellar.addBottles(of: Wine(name: "Opening", vintage: 2020, drinkFrom: 2026, drinkUntil: 2032), quantity: 1)
        try cellar.addBottles(of: Wine(name: "Old", vintage: 2005, drinkFrom: 2008, drinkUntil: 2015), quantity: 3)
        try cellar.addBottles(of: Wine(name: "Young", vintage: 2024, drinkFrom: 2030, drinkUntil: 2040), quantity: 1)
        let digest = cellar.drinkingDigest(year: 2026)
        #expect(digest.drinkSoon == 2)
        #expect(digest.newlyReady == 1)
        #expect(digest.pastPeak == 3)
        #expect(digest.firstWine == "Soon 2015")
        #expect(Cellar().drinkingDigest(year: 2026).isEmpty)
    }
}

@Suite struct BarcodeTests {
    @Test func aScannedBarcodeFindsItsWine() throws {
        var cellar = Cellar()
        var wine = Wine(name: "Chablis", vintage: 2022)
        let first = wine.addBarcode("3 760123 456789")
        let again = wine.addBarcode("3760123456789")
        let short = wine.addBarcode("12")
        #expect(first)
        #expect(!again, "the same code with spaces is not added twice")
        #expect(!short, "too short to be a barcode")
        try cellar.addBottles(of: wine, quantity: 1)
        #expect(cellar.wine(barcode: "3760123456789")?.id == wine.id)
        #expect(cellar.wine(barcode: "0000000000000") == nil)
        #expect(cellar.groups(matching: "3760123456789").count == 1)
    }

    @Test func wineFilesWithoutBarcodesAreUnchanged() throws {
        let data = try JSONEncoder().encode(Wine(name: "Plain"))
        #expect(String(decoding: data, as: UTF8.self).contains("barcodes") == false)
        let decoded = try JSONDecoder().decode(Wine.self, from: #"{"id":"3D759362-EEC4-4657-8014-4757C8FB5FF3","name":"X","barcodes":["123456"]}"#.data(using: .utf8)!)
        #expect(decoded.barcodes == ["123456"])
    }
}

@Suite struct BulkActionTests {
    @Test func placingSeveralWinesFillsFreePlacesInOrder() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Small", rows: 1, columns: 3)
        cellar.addRack(rack)
        let a = Wine(name: "A"), b = Wine(name: "B")
        try cellar.addBottles(of: a, quantity: 2, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        try cellar.addBottles(of: b, quantity: 2)
        let placed = try cellar.place(wines: [a.id, b.id], in: rack.id)
        #expect(placed == 2, "two free places for three waiting bottles")
        #expect(cellar.bottlesInCellar.filter { $0.slot == nil }.count == 1)
        #expect(cellar.unplace(wines: [a.id]) == 2)
        #expect(cellar.bottles(of: a.id).allSatisfy { $0.slot == nil })
    }
}
