import Foundation
import Testing
@testable import CaveKit

@Suite struct WidgetSnapshotTests {
    let year = 2026

    @Test func emptyCellarIsEmpty() {
        let snapshot = WidgetSnapshot(cellar: Cellar(), year: year)
        #expect(snapshot.isEmpty)
        #expect(snapshot.bottles == 0 && snapshot.wines == 0 && snapshot.tonight.isEmpty)
    }

    @Test func countsMatchTheCellar() {
        let cellar = SampleData.cellar(year: year)
        let snapshot = WidgetSnapshot(cellar: cellar, year: year)
        #expect(snapshot.bottles == cellar.bottlesInCellar.count)
        #expect(snapshot.wines == cellar.groups().count)
        #expect(snapshot.onOrder == cellar.bottlesOnOrder.count)
        #expect(snapshot.drinkSoon == cellar.drinkingDigest(year: year).drinkSoon)
        #expect(snapshot.pastPeak == cellar.drinkingDigest(year: year).pastPeak)
        #expect(snapshot.drinkSoon + snapshot.ready + snapshot.young + snapshot.pastPeak <= snapshot.bottles)
        #expect(!snapshot.isEmpty)
    }

    @Test func picksAreUrgentFirstAndLimited() {
        let snapshot = WidgetSnapshot(cellar: SampleData.cellar(year: year), year: year)
        #expect(!snapshot.tonight.isEmpty)
        #expect(snapshot.tonight.count <= WidgetSnapshot.pickLimit)
        let maturities = snapshot.tonight.map(\.maturity)
        #expect(maturities == maturities.sorted())
        #expect(maturities.allSatisfy { [.drinkSoon, .ready, .pastPeak].contains($0) })
        #expect(WidgetSnapshot(cellar: SampleData.cellar(year: year), year: year, pickLimit: 2).tonight.count <= 2)
    }

    @Test func pickCarriesNamePlaceAndCount() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Main", rows: 2, columns: 2)
        cellar.addRack(rack)
        let wine = Wine(name: "Clos Test", vintage: 2018, color: .red, drinkFrom: 2020, drinkUntil: year)
        try cellar.addBottles(of: wine, quantity: 2, slots: [Slot(rackID: rack.id, row: 0, column: 0)])
        let snapshot = WidgetSnapshot(cellar: cellar, year: year)
        let pick = try #require(snapshot.tonight.first)
        #expect(pick.wineID == wine.id)
        #expect(pick.name == "Clos Test")
        #expect(pick.vintage == 2018)
        #expect(pick.color == .red)
        #expect(pick.maturity == .drinkSoon)
        #expect(pick.bottles == 2)
        // One bottle has a place, the other none: the pick names that place.
        let placed = try #require(cellar.bottles(of: wine.id).first { $0.slot != nil })
        #expect(pick.place == cellar.locationLabel(for: placed))
        #expect(pick.place != nil)
        #expect(snapshot.drinkSoon == 2)
    }

    @Test func reservedAndYoungBottlesAreNotSuggested() throws {
        var cellar = Cellar()
        let kept = Wine(name: "Kept", drinkFrom: 2020, drinkUntil: 2030)
        let young = Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040)
        let ids = try cellar.addBottles(of: kept).map(\.id)
        try cellar.addBottles(of: young)
        try cellar.reserve(ids, for: "Birthday")
        let snapshot = WidgetSnapshot(cellar: cellar, year: year)
        #expect(snapshot.tonight.isEmpty)
        #expect(snapshot.bottles == 2)
        #expect(snapshot.ready == 1 && snapshot.young == 1)
    }

    @Test func ordersCountButAreNotInTheCellar() throws {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Primeur", drinkFrom: 2020, drinkUntil: 2030), quantity: 3,
                              purchase: PurchaseInfo(isOrdered: true))
        let snapshot = WidgetSnapshot(cellar: cellar, year: year)
        #expect(snapshot.onOrder == 3)
        #expect(snapshot.bottles == 0)
        #expect(snapshot.tonight.isEmpty)
        #expect(!snapshot.isEmpty)
    }

    @Test func roundTripsAndIsStable() throws {
        let snapshot = WidgetSnapshot(cellar: SampleData.cellar(year: year), year: year)
        let data = try snapshot.encoded()
        #expect(WidgetSnapshot.decode(data) == snapshot)
        #expect(try snapshot.encoded() == data)
    }

    @Test func rejectsDamagedOrNewerData() throws {
        #expect(WidgetSnapshot.decode(Data("not json".utf8)) == nil)
        var newer = WidgetSnapshot(cellar: Cellar(), year: year)
        newer.version = WidgetSnapshot.currentVersion + 1
        #expect(WidgetSnapshot.decode(try newer.encoded()) == nil)
    }
}
