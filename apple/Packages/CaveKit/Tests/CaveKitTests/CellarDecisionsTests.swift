import Foundation
import Testing
@testable import CaveKit

/// Counts shown in the Cellar, the rule for deleting, and the layouts of the Cellar
/// figures and of Add Bottles. The same cases are in android/core CellarDecisionsTest.
@Suite struct CellarDecisionsTests {
    /// Young (to keep) ×2, drink soon ×1, ready ×1 opened, and a wine tasted elsewhere.
    private func cellar() throws -> Cellar {
        var cellar = Cellar()
        _ = try cellar.addBottles(of: Wine(name: "Young", drinkFrom: 2030, drinkUntil: 2040), quantity: 2)
        _ = try cellar.addBottles(of: Wine(name: "Soon", drinkFrom: 2020, drinkUntil: 2026), quantity: 1)
        let ready = try cellar.addBottles(of: Wine(name: "Ready", drinkFrom: 2020, drinkUntil: 2030), quantity: 1)
        try cellar.drink(ready[0].id, tasting: Tasting(date: Date(timeIntervalSince1970: 0)))
        let elsewhere = Wine(name: "Elsewhere")
        cellar.addDiscovery(Discovery(wineID: elsewhere.id), wine: elsewhere)
        _ = try cellar.addBottles(of: Wine(name: "Ordered"), quantity: 1, purchase: PurchaseInfo(isOrdered: true))
        return cellar
    }

    @Test func countsBottlesNotWineRecords() throws {
        let cellar = try cellar()
        let counts = cellar.counts(year: 2026)
        #expect(cellar.wines.count == 5)
        #expect(counts.bottles == 3)
        #expect(counts.bottlesToKeep == 2)
        #expect(counts.bottlesToDrinkSoon == 1)
        #expect(counts.winesWithBottles == 2)
        #expect(cellar.bottlesInCellarCount == 3)
        #expect(cellar.winesWithBottlesCount == 2)
        // The figures of the Cellar and of Insights count the same thing.
        let stats = cellar.statistics(year: 2026)
        #expect(stats.bottleCount == counts.bottles)
        #expect(stats.wineCount == counts.winesWithBottles)
        #expect(stats.byMaturity[.young] == counts.bottlesToKeep)
        #expect(CellarOverview(cellar, year: 2026).counts == counts)
    }

    @Test func restorePreviewCountsWinesWithBottles() throws {
        let cellar = try cellar()
        let preview = RestorePreview(of: cellar, date: .now, comparedWith: Cellar())
        #expect(preview.wines == 2)
        #expect(preview.bottles == 3)
    }

    @Test func singleUndoableThingsGoWithUndo() {
        for kind in [DeletionKind.bottle, .wine, .tasting, .discovery, .savedSearch] {
            #expect(DeletionRule.guarding(kind, count: 1, undoable: true) == .undo)
            #expect(DeletionRule.guarding(kind, count: 0, undoable: true) == .undo)
        }
    }

    @Test func severalThingsStoragesAndWhatCannotBeUndoneAreConfirmed() {
        for kind in DeletionKind.allCases {
            #expect(DeletionRule.guarding(kind, count: 2, undoable: true) == .confirm)
            #expect(DeletionRule.guarding(kind, count: 1, undoable: false) == .confirm)
        }
        #expect(DeletionRule.guarding(.storage, count: 1, undoable: true) == .confirm)
        #expect(DeletionRule.guarding(.storage, count: 0, undoable: true) == .confirm)
    }

    @Test func wineDeletionCountsItsBottlesAndTastings() throws {
        var cellar = Cellar()
        let one = Wine(name: "One")
        let three = Wine(name: "Three")
        _ = try cellar.addBottles(of: one, quantity: 1)
        let bottles = try cellar.addBottles(of: three, quantity: 3)
        try cellar.drink(bottles[0].id, tasting: Tasting(date: Date(timeIntervalSince1970: 0)))
        let elsewhere = Wine(name: "Elsewhere")
        cellar.addDiscovery(Discovery(wineID: elsewhere.id), wine: elsewhere)

        #expect(cellar.bottleRecordCount(ofWines: [three.id]) == 3)
        #expect(cellar.deletionGuard(ofWines: [one.id]) == .undo)
        #expect(cellar.deletionGuard(ofWines: [elsewhere.id]) == .undo)
        #expect(cellar.deletionGuard(ofWines: [three.id]) == .confirm)
        // Several wines, even with no bottle at all.
        #expect(cellar.deletionGuard(ofWines: [one.id, elsewhere.id]) == .confirm)
    }

    @Test func deletedSearchComesBack() {
        var cellar = Cellar()
        cellar.saveSearch("Burgundy", filter: CellarFilter(query: "Bourgogne"))
        cellar.saveSearch("Alsace", filter: CellarFilter(query: "Alsace"))
        let burgundy = cellar.settings.savedSearches!.first { $0.name == "Burgundy" }!
        cellar.deleteSearch(burgundy.id)
        cellar.restoreSearch(burgundy)
        #expect(cellar.settings.savedSearches?.map(\.name) == ["Alsace", "Burgundy"])
        #expect(cellar.settings.savedSearches?.last?.id == burgundy.id)
        // Already back (another device, or Undo twice): nothing changes.
        cellar.restoreSearch(burgundy)
        #expect(cellar.settings.savedSearches?.count == 2)
        // Saved again under its name meanwhile: that one stays.
        cellar.deleteSearch(burgundy.id)
        cellar.saveSearch("burgundy", filter: CellarFilter(query: "Côte"))
        cellar.restoreSearch(burgundy)
        #expect(cellar.settings.savedSearches?.map(\.name) == ["Alsace", "burgundy"])
    }

    @Test func figuresBecomeAShortListAtLargeTextSizes() {
        #expect(StatTileLayout.of(textScale: 1) == .tiles)
        #expect(StatTileLayout.of(textScale: 1.35) == .tiles)
        #expect(StatTileLayout.of(textScale: 1.5) == .compact)
        #expect(StatTileLayout.of(textScale: 2) == .compact)
    }

    @Test func addingByHandStartsWithTheWine() {
        let order = AddFormLayout.byHand
        #expect(order.first == .wine)
        #expect(order.firstIndex(of: .wine)! < order.firstIndex(of: .bottles)!)
        #expect(order.firstIndex(of: .bottles)! < order.firstIndex(of: .location)!)
        #expect(order.firstIndex(of: .location)! < order.firstIndex(of: .documents)!)
        #expect(Set(order) == Set(AddFormSection.allCases))
    }
}
