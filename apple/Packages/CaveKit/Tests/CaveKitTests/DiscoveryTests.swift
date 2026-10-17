import Foundation
import Testing
@testable import CaveKit

@Suite struct DiscoveryTests {
    @Test func discoveredWineIsNotABottleInTheCellar() {
        var cellar = Cellar()
        let wine = Wine(name: "Alvarinho", vintage: 2023, color: .white)
        cellar.addDiscovery(Discovery(wineID: wine.id, wantsToBuy: true), wine: wine)
        #expect(cellar.wines[wine.id] != nil)
        #expect(cellar.bottlesInCellar.isEmpty)
        #expect(cellar.groups(matching: "").isEmpty)
        #expect(cellar.statistics(year: 2026).wineCount == 0)
        #expect(cellar.shoppingListCount == 1)
    }

    @Test func sameWineIsLinkedInsteadOfDuplicated() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Clos X", producer: "Dom Y", vintage: 2018)
        _ = try cellar.addBottles(of: wine, quantity: 1)
        let again = Wine(name: "clos x ", producer: "Dom Y", vintage: 2018, photoID: "photo")
        let stored = cellar.addDiscovery(Discovery(wineID: again.id), wine: again)
        #expect(stored.wineID == wine.id)
        #expect(cellar.wines.count == 1)
        #expect(cellar.wines[wine.id]?.photoID == "photo")
    }

    @Test func deletingTheLastReferenceRemovesTheWine() throws {
        var cellar = Cellar()
        let wine = Wine(name: "Gamay")
        let discovery = cellar.addDiscovery(Discovery(wineID: wine.id), wine: wine)
        let bottle = try #require(try cellar.addBottles(of: wine, quantity: 1).first)
        cellar.deleteBottle(bottle.id)
        #expect(cellar.wines[wine.id] != nil, "still referenced by the discovery")
        cellar.deleteDiscovery(discovery.id)
        #expect(cellar.wines[wine.id] == nil)
    }

    @Test func deletingTheWineRemovesItsDiscoveries() {
        var cellar = Cellar()
        let wine = Wine(name: "Gamay")
        cellar.addDiscovery(Discovery(wineID: wine.id), wine: wine)
        cellar.deleteWine(wine.id)
        #expect(cellar.discoveries.isEmpty)
    }

    @Test func filtersByShoppingListTagAndText() {
        let cellar = SampleData.cellar(year: 2026)
        #expect(cellar.discoveries(filter: DiscoveryFilter(toBuyOnly: true)).count == 2)
        #expect(cellar.discoveries(filter: DiscoveryFilter(tag: "gift idea")).count == 1)
        #expect(cellar.discoveries(filter: DiscoveryFilter(query: "lisbon")).count == 1)
        #expect(cellar.discoveries(filter: DiscoveryFilter(query: "Vinho verde")).count == 1)
        let dates = cellar.discoveries().map(\.date)
        #expect(dates == dates.sorted(by: >))
    }

    @Test func tagsAreTrimmedAndDeduplicated() {
        let discovery = Discovery(wineID: UUID(), tags: [" To buy", "to buy", "", "Gift"])
        #expect(discovery.tags == ["To buy", "Gift"])
    }

    @Test func markBoughtLeavesTheShoppingList() throws {
        var cellar = SampleData.cellar(year: 2026)
        let item = try #require(cellar.discoveries(filter: DiscoveryFilter(toBuyOnly: true)).first)
        try cellar.markBought(item.id)
        #expect(cellar.discoveries[item.id]?.wantsToBuy == false)
        #expect(cellar.discoveries[item.id]?.boughtAt != nil)
    }

    @Test func discoveriesSurviveBackupAndMerge() throws {
        let cellar = SampleData.cellar(year: 2026)
        let restored = try CellarExport.backup(fromJSON: try CellarExport.json(cellar)).cellar
        #expect(Set(restored.discoveries.keys) == Set(cellar.discoveries.keys))
        let restoredTags: Set<String> = Set(restored.discoveries.values.flatMap(\.tags))
        let originalTags: Set<String> = Set(cellar.discoveries.values.flatMap(\.tags))
        #expect(restoredTags == originalTags)
        #expect(restored.shoppingListCount == cellar.shoppingListCount)
        var empty = Cellar()
        empty.merge(cellar)
        #expect(empty.discoveries.count == cellar.discoveries.count)
    }

    @Test func olderFilesWithoutDiscoveriesStillLoad() throws {
        let json = #"{"schemaVersion":2,"wines":[],"bottles":[],"racks":[]}"#
        let cellar = try JSONDecoder().decode(Cellar.self, from: Data(json.utf8))
        #expect(cellar.discoveries.isEmpty)
    }
}
