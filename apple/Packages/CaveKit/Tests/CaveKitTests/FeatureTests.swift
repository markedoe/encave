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

    @Test func reservedBottlesAreNotSuggested() throws {
        var cellar = SampleData.cellar(year: 2026)
        let best = try #require(cellar.pairings(for: .fish, year: 2026).first)
        try cellar.reserve(best.group.bottles.map(\.id), for: "Wedding")
        #expect(!cellar.pairings(for: .fish, year: 2026).contains { $0.group.wine.id == best.group.wine.id })
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

    /// Adding bottles to a wine already in the cellar, as the add form does: the wine as
    /// it is now, with the barcode scanned for these bottles, keeps both codes.
    @Test func addingToAnExistingWineKeepsTheScannedBarcode() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Chablis", vintage: 2022, barcodes: ["3760123456789"])
        let first = try cellar.addBottles(of: wine, quantity: 1)
        var current = try #require(cellar.wines[wine.id])
        let added = current.addBarcode("4006381333931")
        #expect(added)
        try cellar.addBottles(of: current, quantity: 2)
        #expect(cellar.wines[wine.id]?.barcodes == ["3760123456789", "4006381333931"])
        #expect(cellar.wine(barcode: "4006381333931")?.id == wine.id)
        #expect(cellar.wine(barcode: "3760123456789")?.id == wine.id)
        #expect(cellar.bottles[first[0].id] != nil)
        #expect(cellar.bottlesInCellar.count == 3)
    }

    /// Same vectors as BarcodeTest in android/core ParityFeatureTest.kt.
    @Test func oneBarcodeForSeveralVintagesListsTheNewestFirst() throws {
        var cellar = Cellar()
        let code = "3760123456789"
        let old = Wine(name: "Chablis", vintage: 2015, barcodes: [code])
        let young = Wine(name: "Chablis", vintage: 2022, barcodes: [code])
        let plain = Wine(name: "Chablis", barcodes: [code])
        let gone = Wine(name: "Chablis", vintage: 2023, barcodes: [code])
        try cellar.addBottles(of: old, quantity: 1)
        try cellar.addBottles(of: young, quantity: 1)
        try cellar.addBottles(of: plain, quantity: 1)
        let drunk = try cellar.addBottles(of: gone, quantity: 1)
        try cellar.markGone(drunk[0].id)
        #expect(cellar.wines(barcode: code).map(\.id) == [young.id, old.id, plain.id, gone.id])
        #expect(cellar.wine(barcode: code)?.id == young.id)
        #expect(cellar.wines(barcode: "").isEmpty)
    }

    /// Same vectors as WineMatchingTest in android/core ParityFeatureTest.kt.
    @Test func aDraftMatchesTheSameWineIgnoringCaseAccentsAndSpaces() throws {
        var cellar = Cellar()
        let margaux = Wine(name: "Château Margaux", producer: "Château Margaux", vintage: 2015)
        let other = Wine(name: "Château Margaux", producer: "Château Margaux", vintage: 2016)
        let noProducer = Wine(name: "Clos des Tilleuls", vintage: 2019)
        try cellar.addBottles(of: margaux, quantity: 2)
        try cellar.addBottles(of: other, quantity: 1)
        try cellar.addBottles(of: noProducer, quantity: 1)
        #expect(cellar.wine(matching: Wine(name: "  chateau  MARGAUX ", producer: "CHATEAU margaux", vintage: 2015))?.id == margaux.id)
        #expect(cellar.wine(matching: Wine(name: "Chateau Margaux", vintage: 2015))?.id == margaux.id, "no producer on the draft")
        #expect(cellar.wine(matching: Wine(name: "clos des tilleuls", producer: "Domaine Martin", vintage: 2019))?.id == noProducer.id,
                "no producer on the wine")
        #expect(cellar.wine(matching: Wine(name: "Château Margaux", producer: "Château Margaux", vintage: 2017)) == nil, "another vintage")
        #expect(cellar.wine(matching: Wine(name: "Château Margaux", producer: "Other Estate", vintage: 2015)) == nil, "another producer")
        #expect(cellar.wine(matching: Wine(name: "Château Margaux", producer: "Château Margaux")) == nil, "non-vintage")
        #expect(cellar.wine(matching: Wine(name: "  ")) == nil)
        #expect(cellar.wine(matching: margaux) == nil, "not the wine itself")
    }

    @Test func addingToAnExistingWineKeepsTheNewBarcode() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Chablis", vintage: 2022)
        try cellar.addBottles(of: wine, quantity: 1)
        var updated = wine
        updated.addBarcode("3760123456789")
        try cellar.addBottles(of: updated, quantity: 1)
        #expect(cellar.wines.count == 1)
        #expect(cellar.wine(barcode: "3760123456789")?.id == wine.id)
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

@Suite struct YearInWineTests {
    @Test func summarizesTheBottlesOpenedThatYear() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        func date(_ year: Int, _ month: Int) -> Date { calendar.date(from: DateComponents(year: year, month: month, day: 10))! }
        var cellar = Cellar()
        let burgundy = Wine(name: "Volnay", vintage: 2018, color: .red, region: "Burgundy")
        let loire = Wine(name: "Vouvray", vintage: 2020, color: .white, region: "Loire")
        let bottles = try cellar.addBottles(of: burgundy, quantity: 3) + cellar.addBottles(of: loire, quantity: 1)
        try cellar.drink(bottles[0].id, tasting: Tasting(date: date(2026, 3), rating: 4, occasion: "Dinner with friends"))
        try cellar.drink(bottles[1].id, tasting: Tasting(date: date(2026, 3), rating: 5, occasion: "Dinner with friends"))
        try cellar.drink(bottles[3].id, tasting: Tasting(date: date(2026, 6), rating: 3, occasion: "Aperitif"))
        try cellar.drink(bottles[2].id, tasting: Tasting(date: date(2025, 12), rating: 5))
        let year = cellar.yearInWine(2026, calendar: calendar)
        #expect(year.bottlesOpened == 3)
        #expect(year.differentWines == 2)
        #expect(year.favorite?.name == "Volnay 2018" && year.favorite?.rating == 5)
        #expect(year.topRegion == "Burgundy")
        #expect(year.topColor == .red)
        #expect(year.topOccasion == "Dinner with friends")
        #expect(year.busiestMonth == 3)
        #expect(cellar.yearInWine(2024, calendar: calendar).isEmpty)
    }
}

@Suite struct OtherAppsImportTests {
    @Test func importsACellarTrackerExport() throws {
        let csv = """
        iWine,Type,Color,Quantity,Vintage,Wine,Producer,Varietal,Country,Region,Appellation,Location,Bin,Price,Currency,BeginConsume,EndConsume
        123,Red,Red,2,2016,Château Test Pauillac,Château Test,Cabernet Sauvignon,France,Bordeaux,Pauillac,Cave,A3,45.00,EUR,2024,2036
        124,White - Sparkling,White,1,1001,Brut Réserve,Maison X,Chardonnay,France,Champagne,Champagne,,,30,EUR,,
        """
        var cellar = Cellar()
        let summary = try cellar.importCSV(csv)
        #expect(summary.winesAdded == 2)
        #expect(summary.bottlesAdded == 3)
        let pauillac = try #require(cellar.wines.values.first { $0.name == "Château Test Pauillac" })
        #expect(pauillac.vintage == 2016 && pauillac.color == .red && pauillac.appellation == "Pauillac")
        #expect(pauillac.drinkFrom == 2024 && pauillac.drinkUntil == 2036)
        #expect(pauillac.grapes == ["Cabernet Sauvignon"])
        let brut = try #require(cellar.wines.values.first { $0.name == "Brut Réserve" })
        #expect(brut.vintage == nil, "1001 means non-vintage")
        #expect(brut.color == .sparkling)
    }

    @Test func aVivinoHistoryBecomesDiscoveries() throws {
        let csv = """
        Winery,Wine name,Vintage,Region,Country,Regional wine style,Average rating,Scan date,Scan/Review Location,Your rating,Your review,Wine type,Drinking Window,Wine price
        Domaine Y,Morgon Côte du Py,2020,Beaujolais,France,,3.9,2026-05-02 20:15:00,Lyon,4.0,Lovely with charcuterie,Red wine,2022 - 2030,24.5
        """
        var cellar = Cellar()
        let summary = try cellar.importCSV(csv)
        #expect(summary.bottlesAdded == 0)
        let discovery = try #require(cellar.discoveries.values.first)
        let wine = try #require(cellar.wines[discovery.wineID])
        #expect(wine.name == "Morgon Côte du Py" && wine.producer == "Domaine Y" && wine.color == .red)
        #expect(wine.drinkFrom == 2022 && wine.drinkUntil == 2030)
        #expect(discovery.rating == 4 && discovery.place == "Lyon" && discovery.note == "Lovely with charcuterie")
        #expect(discovery.price == 24.5)
    }

    @Test func frenchHeadersAreRecognized() throws {
        var cellar = Cellar()
        _ = try cellar.importCSV("Nom;Millésime;Couleur;Quantité\nMorgon;2020;Rouge;3")
        #expect(cellar.bottles.count == 3)
        #expect(cellar.wines.values.first?.color == .red)
    }

    @Test func frenchPlaceAndWindowHeadersAreRecognized() throws {
        var cellar = Cellar()
        _ = try cellar.importCSV("Nom;Emplacement;Fenêtre de dégustation\nMorgon;Cave du bas;2024-2030")
        let wine = try #require(cellar.wines.values.first)
        #expect(wine.drinkFrom == 2024 && wine.drinkUntil == 2030)
        #expect(cellar.bottles.values.first?.storageNote == "Cave du bas")
    }
}

@Suite struct OnOrderTests {
    @Test func orderedBottlesStayOutOfTheCellarUntilReceived() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Small", rows: 1, columns: 2)
        cellar.addRack(rack)
        let soon = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let wine = Wine(name: "En primeur", vintage: 2024)
        let ordered = try cellar.addBottles(of: wine, quantity: 2, purchase: PurchaseInfo(price: 30, isOrdered: true, expectedAt: soon))
        #expect(ordered.allSatisfy { $0.status == .ordered && $0.expectedAt == soon })
        #expect(cellar.bottlesInCellar.isEmpty)
        #expect(cellar.groups().isEmpty)
        #expect(cellar.orderGroups().map(\.wine.id) == [wine.id])
        #expect(cellar.statistics(year: 2026).bottleCount == 0)
        #expect(cellar.wines[wine.id] != nil, "a wine with only ordered bottles is kept")

        #expect(throws: CellarError.notReceived) {
            try cellar.addBottles(of: wine, slots: [Slot(rackID: rack.id, row: 0, column: 0)], purchase: PurchaseInfo(isOrdered: true))
        }
        #expect(throws: CellarError.notReceived) { try cellar.drink(ordered[0].id, tasting: Tasting()) }

        let arrival = Date(timeIntervalSinceReferenceDate: 800_100_000)
        try cellar.receive(ordered.map(\.id), slots: [Slot(rackID: rack.id, row: 0, column: 1)], at: arrival)
        let received = cellar.bottles(of: wine.id)
        #expect(received.count == 2)
        #expect(received.allSatisfy { $0.expectedAt == nil && $0.addedAt == arrival })
        #expect(received.compactMap(\.slot) == [Slot(rackID: rack.id, row: 0, column: 1)])
        #expect(cellar.orderGroups().isEmpty)
        #expect(throws: CellarError.unknownBottle) { try cellar.receive([received[0].id]) }
    }

    @Test func ordersSortBySoonestArrival() throws {
        var cellar = Cellar()
        let late = Wine(name: "A late"), early = Wine(name: "B early"), unknown = Wine(name: "C unknown")
        try cellar.addBottles(of: late, purchase: PurchaseInfo(isOrdered: true, expectedAt: Date(timeIntervalSinceReferenceDate: 2_000)))
        try cellar.addBottles(of: early, purchase: PurchaseInfo(isOrdered: true, expectedAt: Date(timeIntervalSinceReferenceDate: 1_000)))
        try cellar.addBottles(of: unknown, purchase: PurchaseInfo(isOrdered: true))
        #expect(cellar.orderGroups().map(\.wine.name) == ["B early", "A late", "C unknown"])
    }

    @Test func ordersSurviveCSVAndJSON() throws {
        var cellar = Cellar()
        let expected = Date(timeIntervalSinceReferenceDate: 800_000_000)
        try cellar.addBottles(of: Wine(name: "Arriving"), purchase: PurchaseInfo(isOrdered: true, expectedAt: expected))
        let csv = CellarExport.csv(cellar, includeHistory: false)
        #expect(csv.contains("ordered"))
        var imported = Cellar()
        try imported.importCSV(csv)
        let bottle = try #require(imported.bottlesOnOrder.first)
        #expect(Calendar(identifier: .gregorian).isDate(try #require(bottle.expectedAt), inSameDayAs: expected))

        let data = try JSONEncoder().encode(cellar)
        let decoded = try JSONDecoder().decode(Cellar.self, from: data)
        #expect(decoded.bottlesOnOrder.first?.expectedAt == expected)
    }
}

@Suite struct DrinkDownPlanTests {
    @Test func paceKeepsUpWithTheEarliestWindows() throws {
        var cellar = Cellar()
        // 6 bottles end this year, 12 next year, 2 have no window, 1 is past.
        try cellar.addBottles(of: Wine(name: "Now", drinkFrom: 2020, drinkUntil: 2026), quantity: 6)
        try cellar.addBottles(of: Wine(name: "Next", drinkFrom: 2020, drinkUntil: 2027), quantity: 12)
        try cellar.addBottles(of: Wine(name: "Unknown"), quantity: 2)
        try cellar.addBottles(of: Wine(name: "Past", drinkFrom: 2010, drinkUntil: 2020))
        try cellar.addBottles(of: Wine(name: "Ordered", drinkUntil: 2026), purchase: PurchaseInfo(isOrdered: true))
        let plan = cellar.drinkDownPlan(year: 2026, month: 10)
        #expect(plan.years.map(\.leaving) == [6, 12])
        #expect(plan.pastPeak == 1)
        #expect(plan.withoutWindow == 2)
        // This year: (1 + 6) / 3 months. By next year: 19 / 15 months. The larger wins.
        #expect(abs(plan.bottlesPerMonth - 7.0 / 3.0) < 0.0001)
        #expect(!plan.isEmpty)
        #expect(Cellar().drinkDownPlan(year: 2026, month: 1).isEmpty)
        #expect(Cellar().drinkDownPlan(year: 2026, month: 1).bottlesPerMonth == 0)
    }
}

@Suite struct WindowSourceTests {
    @Test func explainsEstimatedAndEnteredWindows() {
        var wine = Wine(name: "Red", vintage: 2020, color: .red)
        #expect(wine.windowSource == .missing)
        let estimate = wine.estimatedWindow()!
        wine.drinkFrom = estimate.from
        wine.drinkUntil = estimate.until
        #expect(wine.windowSource == .estimated(yearsAfterHarvest: 3, yearsAtBest: 12))
        let later = wine.movingWindowEnd(by: 2)
        #expect(later.drinkUntil == estimate.until + 2)
        #expect(later.windowSource == .entered)
        #expect(wine.movingWindowEnd(by: -100).drinkUntil == estimate.from, "never ends before it starts")
    }
}

@Suite struct WineTagTests {
    @Test func grapeAndTagChipsShowTheMostUsedSpelling() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "A", grapes: ["Pinot noir"], tags: ["gift"]))
        try cellar.addBottles(of: Wine(name: "B", grapes: ["Pinot Noir"], tags: ["Gift"]))
        try cellar.addBottles(of: Wine(name: "C", grapes: [" Pinot Noir ", "Gamay"], tags: ["Gift"]))
        #expect(cellar.wineGrapes == ["Pinot Noir", "Gamay"])
        #expect(cellar.wineTags == ["Gift"])
        // A tie: the same spelling whatever order the wines are stored in.
        var tie = Cellar()
        try tie.addBottles(of: Wine(name: "A", grapes: ["syrah"]))
        try tie.addBottles(of: Wine(name: "B", grapes: ["Syrah"]))
        #expect(tie.wineGrapes == ["Syrah"])
    }

    @Test func tagsFilterSearchAndStayOutOfOldFiles() throws {
        var cellar = Cellar()
        let gift = Wine(name: "Gift wine", tags: [" Gift ", "gift", "Birth year"])
        #expect(gift.tags == ["Gift", "Birth year"])
        #expect(Wine(name: "Plain", tags: ["  "]).tags == nil)
        try cellar.addBottles(of: gift)
        try cellar.addBottles(of: Wine(name: "Plain"))
        #expect(cellar.groups(filter: CellarFilter(tag: "gift"), sort: .name, year: 2026).map(\.wine.name) == ["Gift wine"])
        #expect(cellar.groups(matching: "birth").map(\.wine.name) == ["Gift wine"])
        #expect(cellar.wineTags == ["Birth year", "Gift"])
        var copy = Cellar()
        try copy.importCSV(CellarExport.csv(cellar))
        #expect(copy.wines.values.first { $0.name == "Gift wine" }?.tags == ["Gift", "Birth year"])
        let plain = try JSONEncoder().encode(Wine(name: "Plain"))
        #expect(!String(decoding: plain, as: UTF8.self).contains("tags"))
    }
}

@Suite struct AssistantCatalogTests {
    @Test func listsTheMostUrgentWinesWithNumbers() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Young", vintage: 2024, color: .red, drinkFrom: 2030, drinkUntil: 2040), quantity: 2)
        try cellar.addBottles(of: Wine(name: "Soon", vintage: 2018, color: .white, region: "Loire", drinkFrom: 2020, drinkUntil: 2026))
        try cellar.addBottles(of: Wine(name: "Ordered", drinkUntil: 2026), purchase: PurchaseInfo(isOrdered: true))
        let catalog = cellar.assistantCatalog(year: 2026, limit: 1)
        #expect(catalog.entries.count == 1)
        #expect(catalog.omitted == 1)
        #expect(catalog.text == "1. Soon 2018 | – | white | Loire | – | drink soon | 1 bottle | –")
        #expect(catalog.wineID(number: 1) == cellar.wines.values.first { $0.name == "Soon" }?.id)
        #expect(catalog.wineID(number: 2) == nil)
    }
}

@Suite struct ReservationTests {
    @Test func reservedBottlesAreKeptForTheirOccasion() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Party", vintage: 2018, drinkFrom: 2020, drinkUntil: 2030)
        let bottles = try cellar.addBottles(of: wine, quantity: 3)
        try cellar.reserve([bottles[0].id, bottles[1].id], for: "  Paul's 40th ")
        #expect(cellar.reservations == ["Paul's 40th"])
        // Tonight offers only the free bottle.
        #expect(cellar.groupsByMaturity(year: 2026).flatMap(\.groups).map(\.count) == [1])
        #expect(cellar.groups(filter: CellarFilter(reservation: "paul's 40th"), sort: .name, year: 2026).map(\.count) == [2])
        try cellar.reserve([bottles[0].id], for: " ")
        #expect(cellar.bottles[bottles[0].id]?.reservedFor == nil)
        // All reserved: the wine is not suggested at all.
        try cellar.reserve(bottles.map(\.id), for: "Wedding")
        #expect(cellar.groupsByMaturity(year: 2026).isEmpty)
        var copy = Cellar()
        try copy.importCSV(CellarExport.csv(cellar))
        #expect(copy.reservations == ["Wedding"])
    }
}

@Suite struct SavedSearchTests {
    @Test func aSearchIsSavedRestoredAndReplacedByName() throws {
        var cellar = Cellar()
        let filter = CellarFilter(query: " Bourgogne ", colors: [.white, .red], maturities: [.ready], tag: "Gift")
        cellar.saveSearch(" Ready burgundy ", filter: filter)
        let saved = try #require(cellar.settings.savedSearches?.first)
        #expect(saved.name == "Ready burgundy")
        #expect(saved.colors == [.red, .white])
        #expect(saved.filter == CellarFilter(query: "Bourgogne", colors: [.white, .red], maturities: [.ready], tag: "Gift"))
        cellar.saveSearch("ready BURGUNDY", filter: CellarFilter(query: "Beaune"))
        #expect(cellar.settings.savedSearches?.count == 1)
        #expect(cellar.settings.savedSearches?.first?.query == "Beaune")
        cellar.deleteSearch(try #require(cellar.settings.savedSearches?.first?.id))
        #expect(cellar.settings.savedSearches == nil)
        // Older files without saved searches still read.
        let old = try JSONDecoder().decode(CellarSettings.self, from: Data(#"{"currency":"CHF"}"#.utf8))
        #expect(old.savedSearches == nil && old.currency == "CHF")
    }
}

@Suite struct CellarReportTests {
    private func cellar() throws -> Cellar {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Young", vintage: 2024, color: .red, drinkFrom: 2030, drinkUntil: 2040), quantity: 2)
        let soon = try cellar.addBottles(of: Wine(name: "Soon", producer: "Dom. A", vintage: 2018, color: .white, region: "Loire",
                                                  grapes: ["Chenin"], drinkFrom: 2020, drinkUntil: 2026),
                                          quantity: 2, purchase: PurchaseInfo(storageNote: "Kitchen | shelf"))
        try cellar.reserve([soon[0].id], for: "Christmas")
        let day = DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: .current, year: 2026, month: 12, day: 1).date!
        try cellar.addBottles(of: Wine(name: "Ordered", drinkUntil: 2028), purchase: PurchaseInfo(isOrdered: true, expectedAt: day))
        return cellar
    }

    @Test func textHasOneLinePerWine() throws {
        let report = try cellar().report(scope: .all, year: 2026)
        #expect(report.rows.map(\.name) == ["Soon", "Young"])
        #expect(report.bottleCount == 4)
        let lines = report.text.components(separatedBy: "\n")
        #expect(lines[0] == "Encave cellar, 2026, all bottles: 2 wines, 4 bottles.")
        #expect(lines[2] == "1. Soon 2018 | Dom. A | white | Loire | Chenin | 2020-2026 | drink soon | 2 | Kitchen / shelf; Kitchen / shelf |  | Christmas")
    }

    @Test func scopesPickTheirBottles() throws {
        let cellar = try cellar()
        let toOpen = cellar.report(scope: .toOpen, year: 2026)
        #expect(toOpen.rows.map(\.name) == ["Soon"])
        #expect(toOpen.bottleCount == 1)
        #expect(cellar.report(scope: .reserved, year: 2026).rows.map(\.keptFor) == [["Christmas"]])
        #expect(cellar.report(scope: .young, year: 2026).rows.map(\.name) == ["Young"])
        let ordered = cellar.report(scope: .onOrder, year: 2026)
        #expect(ordered.rows.map(\.expected) == [["2026-12-01"]])
        #expect(ordered.text.hasSuffix("| 2026-12-01"))
        #expect(cellar.report(scope: .all, search: "chenin", year: 2026).rows.map(\.name) == ["Soon"])
        #expect(cellar.report(scope: .pastPeak, year: 2026).text == "Encave cellar, 2026, bottles past their peak: 0 wines, 0 bottles.")
        #expect(cellar.report(scope: .all, year: 2026, limit: 1).text.hasPrefix(
            "Encave cellar, 2026, all bottles: 1 wine, 2 bottles (1 more wine left out)."))
    }

    @Test func jsonUsesFixedKeys() throws {
        let json = try cellar().report(scope: .all, search: "Young", year: 2026).json()
        #expect(json.contains("\"maturity\" : \"too young\""))
        #expect(json.contains("\"bottles\" : 2"))
    }
}

@Suite struct CoverPhotoTests {
    @Test func coverIsShownKeptAndRoundTrips() throws {
        var wine = Wine(name: "Cover", photoID: "label")
        wine.coverPhotoID = "cover"
        #expect(wine.shownPhotoID == "cover")
        #expect(wine.allPhotoIDs == ["label", "cover"])
        var cellar = Cellar()
        try cellar.addBottles(of: wine)
        #expect(cellar.photoIDs == ["label", "cover"])
        let decoded = try JSONDecoder().decode(Wine.self, from: JSONEncoder().encode(wine))
        #expect(decoded.coverPhotoID == "cover")
        let old = try JSONDecoder().decode(Wine.self, from: Data(#"{"id":"\#(UUID())","name":"Old"}"#.utf8))
        #expect(old.coverPhotoID == nil && old.shownPhotoID == nil)
    }
}

@Suite struct HoneycombDepthTests {
    @Test func honeycombCellsHoldUpToTwoBottlesOneBehindTheOther() throws {
        let one = Rack(name: "H", kind: .staggered, rows: 3, columns: 4)
        let deep = Rack(name: "H", kind: .staggered, rows: 3, columns: 4, depth: 3)
        #expect(one.positionsPerCell == 1)
        #expect(deep.depth == 2, "a honeycomb is at most two deep")
        #expect(deep.allSlots.count == one.allSlots.count * 2)
        let back = Slot(rackID: deep.id, row: 1, column: 0, depth: 1)
        #expect(deep.contains(back))
        #expect(!deep.contains(Slot(rackID: deep.id, row: 1, column: 0, depth: 2)))
        #expect(deep.label(for: back).hasSuffix("-2"))
        let json = #"{"id":"\#(UUID())","name":"H","kind":"staggered","rows":2,"columns":3,"depth":3}"#
        #expect(try JSONDecoder().decode(Rack.self, from: Data(json.utf8)).depth == 2)
        #expect(Rack(name: "S", kind: .shelves, rows: 1, columns: 1, depth: 3).depth == 3)
    }
}

@Suite struct ShelfLayerTests {
    /// A fridge: top shelf with 6 bottles and 5 on top, then a shelf of one layer of 6.
    let fridge = Rack(name: "F", kind: .staggered, rows: 1, columns: 6, depth: 2, shelfLayers: [2, 1])

    @Test func rowsAreTheLayersOfEachShelf() {
        #expect(fridge.rows == 3)
        #expect(fridge.shelfRows == [0..<2, 2..<3])
        // Row A is the top layer of shelf 1, row B its bottom layer.
        #expect(fridge.shelfPlace(ofRow: 0)! == (0, 1, 2))
        #expect(fridge.shelfPlace(ofRow: 1)! == (0, 0, 2))
        #expect(fridge.shelfPlace(ofRow: 2)! == (1, 0, 1))
        #expect([0, 1, 2].map(fridge.columns(inRow:)) == [5, 6, 6])
        #expect([0, 1, 2].map(fridge.isOffset(row:)) == [true, false, false])
        #expect(fridge.capacity == (5 + 6 + 6) * 2)
        let three = Rack(name: "T", kind: .staggered, rows: 1, columns: 7, shelfLayers: [3])
        #expect([0, 1, 2].map(three.columns(inRow:)) == [7, 6, 7], "quincunx: N, N-1, N from the bottom")
    }

    @Test func eachShelfFillsFromItsBottomLayer() {
        let order = fridge.allSlots
        #expect(order.count == fridge.capacity)
        // Shelf 1: bottom layer front, then behind, then the top layer.
        #expect(order.prefix(6).allSatisfy { $0.row == 1 && $0.depth == 0 })
        #expect(order[6..<12].allSatisfy { $0.row == 1 && $0.depth == 1 })
        #expect(order[12..<17].allSatisfy { $0.row == 0 && $0.depth == 0 })
        #expect(order.last == Slot(rackID: fridge.id, row: 2, column: 5, depth: 1))
        var cellar = Cellar()
        cellar.addRack(fridge)
        #expect(cellar.freeSlots(in: fridge).first == Slot(rackID: fridge.id, row: 1, column: 0))
    }

    @Test func layersAreBoundedAndOnlyForHoneycombs() throws {
        let wild = Rack(name: "W", kind: .staggered, rows: 1, columns: 3, shelfLayers: [0, 9, 2])
        #expect(wild.shelfLayers == [1, 3, 2])
        #expect(wild.rows == 6)
        let many = Rack(name: "M", kind: .staggered, rows: 1, columns: 3, shelfLayers: Array(repeating: 3, count: 50))
        #expect(many.rows <= Rack.maxSize)
        #expect(many.shelfLayers?.count == 33)
        #expect(Rack(name: "G", kind: .grid, rows: 2, columns: 2, shelfLayers: [2]).shelfLayers == nil)
        #expect(Rack(name: "E", kind: .staggered, rows: 2, columns: 2, shelfLayers: []).shelfLayers == nil)
        // Decoding applies the same rules: the rows are the layers.
        let json = #"{"id":"\#(UUID())","name":"F","kind":"staggered","rows":40,"columns":6,"depth":1,"shelfLayers":[2,1]}"#
        let decoded = try JSONDecoder().decode(Rack.self, from: Data(json.utf8))
        #expect(decoded.rows == 3 && decoded.shelfLayers == [2, 1])
        let again = try JSONDecoder().decode(Rack.self, from: JSONEncoder().encode(fridge))
        #expect(again == fridge)
        // Zones: breaks between kept shelves only, sorted, once.
        let zoned = Rack(name: "Z", kind: .staggered, rows: 1, columns: 6, shelfLayers: [1, 2, 1, 1], zoneBreaks: [3, 0, 2, 2, 9])
        #expect(zoned.zoneBreaks == [2, 3])
        #expect([0, 1, 2, 3].map(zoned.zone(ofShelf:)) == [0, 0, 1, 2])
        #expect(zoned.zoneCount == 3)
        #expect(Rack(name: "N", kind: .staggered, rows: 1, columns: 6, shelfLayers: [1, 1], zoneBreaks: [0]).zoneBreaks == nil)
        #expect(Rack(name: "C", kind: .staggered, rows: 2, columns: 6, zoneBreaks: [1]).zoneBreaks == nil)
        let zonedAgain = try JSONDecoder().decode(Rack.self, from: JSONEncoder().encode(zoned))
        #expect(zonedAgain == zoned)
        // The sample fridge: two zones, shelves 1–4 then 5–10, as the editor's preset.
        let sample = SampleData.cellar(year: 2026).racks.first { $0.hasShelfLayers }!
        #expect(sample.shelfLayers?.count == 10 && sample.zoneBreaks == [4] && sample.depth == 2)
        #expect(sample.rows == SampleData.twoZoneFridgeRows.count)
        // A continuous honeycomb writes no shelf layers.
        let plain = try JSONEncoder().encode(Rack(name: "H", kind: .staggered, rows: 2, columns: 2))
        #expect(!String(decoding: plain, as: UTF8.self).contains("shelfLayers"))
    }
}
