package app.encave.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Counts shown in the Cellar, the rule for deleting, and the layouts of the Cellar figures
 * and of Add Bottles. The same cases are in CaveKit's CellarDecisionsTests.
 */
class CellarDecisionsTest {
    /** Young (to keep) ×2, drink soon ×1, ready ×1 opened, a wine tasted elsewhere and one on order. */
    private fun cellar(): Cellar {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Young", drinkFrom = 2030, drinkUntil = 2040), quantity = 2)
        cellar.addBottles(Wine(name = "Soon", drinkFrom = 2020, drinkUntil = 2026), quantity = 1)
        val ready = cellar.addBottles(Wine(name = "Ready", drinkFrom = 2020, drinkUntil = 2030), quantity = 1)
        cellar.drink(ready[0].id, Tasting(date = Instant.EPOCH))
        val elsewhere = Wine(name = "Elsewhere")
        cellar.addDiscovery(Discovery(wineId = elsewhere.id), elsewhere)
        cellar.addBottles(Wine(name = "Ordered"), quantity = 1, purchase = PurchaseInfo(isOrdered = true))
        return cellar
    }

    @Test
    fun countsBottlesNotWineRecords() {
        val cellar = cellar()
        val counts = cellar.counts(2026)
        assertEquals(5, cellar.wines.size)
        assertEquals(CellarCounts(bottles = 3, bottlesToKeep = 2, bottlesToDrinkSoon = 1, winesWithBottles = 2), counts)
        assertEquals(3, cellar.bottlesInCellarCount)
        assertEquals(2, cellar.winesWithBottlesCount)
        // The figures of the Cellar and of Insights count the same thing.
        val stats = cellar.statistics(2026)
        assertEquals(counts.bottles, stats.bottleCount)
        assertEquals(counts.winesWithBottles, stats.wineCount)
        assertEquals(counts.bottlesToKeep, stats.byMaturity[Maturity.Young])
    }

    @Test
    fun restorePreviewCountsWinesWithBottles() {
        val preview = RestorePreview.of(cellar(), Instant.EPOCH, Cellar())
        assertEquals(2, preview.wines)
        assertEquals(3, preview.bottles)
    }

    @Test
    fun singleUndoableThingsGoWithUndo() {
        for (kind in DeletionKind.entries.filter { it != DeletionKind.Storage }) {
            assertEquals(DeletionGuard.Undo, DeletionRule.guarding(kind, 1, undoable = true), kind.name)
            assertEquals(DeletionGuard.Undo, DeletionRule.guarding(kind, 0, undoable = true), kind.name)
        }
    }

    @Test
    fun severalThingsStoragesAndWhatCannotBeUndoneAreConfirmed() {
        for (kind in DeletionKind.entries) {
            assertEquals(DeletionGuard.Confirm, DeletionRule.guarding(kind, 2, undoable = true), kind.name)
            assertEquals(DeletionGuard.Confirm, DeletionRule.guarding(kind, 1, undoable = false), kind.name)
        }
        assertEquals(DeletionGuard.Confirm, DeletionRule.guarding(DeletionKind.Storage, 1, undoable = true))
        assertEquals(DeletionGuard.Confirm, DeletionRule.guarding(DeletionKind.Storage, 0, undoable = true))
    }

    @Test
    fun wineDeletionCountsItsBottlesAndTastings() {
        val cellar = Cellar()
        val one = Wine(name = "One")
        val three = Wine(name = "Three")
        cellar.addBottles(one, quantity = 1)
        val bottles = cellar.addBottles(three, quantity = 3)
        cellar.drink(bottles[0].id, Tasting(date = Instant.EPOCH))
        val elsewhere = Wine(name = "Elsewhere")
        cellar.addDiscovery(Discovery(wineId = elsewhere.id), elsewhere)

        assertEquals(3, cellar.bottleRecordCount(setOf(three.id)))
        assertEquals(DeletionGuard.Undo, cellar.deletionGuard(setOf(one.id)))
        assertEquals(DeletionGuard.Undo, cellar.deletionGuard(setOf(elsewhere.id)))
        assertEquals(DeletionGuard.Confirm, cellar.deletionGuard(setOf(three.id)))
        // Several wines, even with no bottle at all.
        assertEquals(DeletionGuard.Confirm, cellar.deletionGuard(setOf(one.id, elsewhere.id)))
    }

    @Test
    fun deletedSearchComesBack() {
        val cellar = Cellar()
        cellar.saveSearch("Burgundy", CellarFilter(query = "Bourgogne"))
        cellar.saveSearch("Alsace", CellarFilter(query = "Alsace"))
        val burgundy = cellar.settings.savedSearches!!.first { it.name == "Burgundy" }
        cellar.deleteSearch(burgundy.id)
        cellar.restoreSearch(burgundy)
        assertEquals(listOf("Alsace", "Burgundy"), cellar.settings.savedSearches?.map { it.name })
        assertEquals(burgundy.id, cellar.settings.savedSearches?.last()?.id)
        // Already back (another device, or Undo twice): nothing changes.
        cellar.restoreSearch(burgundy)
        assertEquals(2, cellar.settings.savedSearches?.size)
        // Saved again under its name meanwhile: that one stays.
        cellar.deleteSearch(burgundy.id)
        cellar.saveSearch("burgundy", CellarFilter(query = "Côte"))
        cellar.restoreSearch(burgundy)
        assertEquals(listOf("Alsace", "burgundy"), cellar.settings.savedSearches?.map { it.name })
    }

    @Test
    fun figuresBecomeAShortListAtLargeFontScales() {
        assertEquals(StatTileLayout.Tiles, StatTileLayout.of(1f))
        assertEquals(StatTileLayout.Tiles, StatTileLayout.of(1.3f))
        assertEquals(StatTileLayout.Compact, StatTileLayout.of(1.5f))
        assertEquals(StatTileLayout.Compact, StatTileLayout.of(2f))
    }

    @Test
    fun addingByHandStartsWithTheWine() {
        val order = AddFormLayout.byHand
        assertEquals(AddFormSection.Wine, order.first())
        assertTrue(order.indexOf(AddFormSection.Bottles) < order.indexOf(AddFormSection.Location))
        assertTrue(order.indexOf(AddFormSection.Location) < order.indexOf(AddFormSection.Documents))
        assertEquals(AddFormSection.entries.toSet(), order.toSet())
    }
}

class FirstPlaceTest {
    @Test
    fun firstPlaceAndHowManyOthers() {
        assertEquals(null, FirstPlace.of(emptyList()))
        assertEquals(FirstPlace("Fridge · A1", 0), FirstPlace.of(listOf("Fridge · A1", "Fridge · A1")))
        assertEquals(FirstPlace("Fridge · A1", 2), FirstPlace.of(listOf("Fridge · A1", "Fridge · A2", "Fridge · A1", "Kitchen")))
    }
}
