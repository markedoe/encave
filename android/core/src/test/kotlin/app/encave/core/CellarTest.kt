package app.encave.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Expects a [CellarException] with the given error code. */
internal inline fun expectError(error: CellarError, block: () -> Unit) {
    val thrown = assertThrows<CellarException> { block() }
    assertEquals(error, thrown.error)
}

class CellarTest {
    private val rack = Rack(name = "A", rows = 2, columns = 3)

    private fun cellarWithRack() = Cellar().apply { addRack(rack) }

    @Test
    fun aWineEnteredTwiceIsMergedIntoTheOther() {
        val cellar = cellarWithRack()
        val kept = Wine(name = "Clos Test", vintage = 2019, photoId = "kept")
        val twin = Wine(name = "clos test", vintage = 2019, photoId = "twin", barcodes = listOf("3760000000017"))
        cellar.addBottles(kept, quantity = 1)
        val moved = cellar.addBottles(twin, quantity = 2)
        assertEquals(kept.id, cellar.wine(matching = twin)?.id)
        cellar.mergeWine(twin.id, kept.id)
        assertNull(cellar.wines[twin.id])
        assertTrue(moved.all { cellar.bottles[it.id]?.wineId == kept.id })
        assertEquals("kept", cellar.wines[kept.id]?.photoId)
        assertEquals(listOf("3760000000017"), cellar.wines[kept.id]?.barcodes)
        expectError(CellarError.UnknownWine) { cellar.mergeWine(kept.id, kept.id) }
        // Undo: both wines as they were, the bottles back.
        cellar.unmergeWine(twin, kept, moved.map { it.id }.toSet(), emptySet())
        assertEquals(twin.photoId, cellar.wines[twin.id]?.photoId)
        assertNull(cellar.wines[kept.id]?.barcodes)
        assertTrue(moved.all { cellar.bottles[it.id]?.wineId == twin.id })
    }

    @Test
    fun addBottlesGroupsByWine() {
        val cellar = cellarWithRack()
        cellar.addBottles(Wine(name = "Test", vintage = 2015, color = WineColor.Red), quantity = 3)
        val groups = cellar.groups()
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].count)
        assertEquals("Test 2015", groups[0].wine.displayName)
    }

    @Test
    fun slotsAreAssignedInOrderAndCannotBeShared() {
        val cellar = cellarWithRack()
        val slots = cellar.freeSlots(rack).take(2)
        val added = cellar.addBottles(Wine(name = "W"), quantity = 2, slots = slots)
        assertEquals(slots, added.map { it.slot })
        assertEquals(4, cellar.freeSlots(rack).size)
        expectError(CellarError.SlotTaken) { cellar.addBottles(Wine(name = "X"), slots = listOf(slots[0])) }
    }

    @Test
    fun slotOutsideRackIsRejected() {
        val cellar = cellarWithRack()
        expectError(CellarError.SlotOutOfRange) {
            cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 5, 0)))
        }
    }

    @Test
    fun drinkingFreesTheSlotAndUndoRestoresIt() {
        val cellar = cellarWithRack()
        val slot = Slot(rack.id, 0, 0)
        val bottle = cellar.addBottles(Wine(name = "W"), slots = listOf(slot)).first()
        cellar.drink(bottle.id, Tasting(rating = 9, note = "Great"))
        assertNull(cellar.bottleAt(slot))
        assertEquals(5, cellar.consumedBottles.first().tasting?.rating, "rating is clamped to 1..5")
        expectError(CellarError.AlreadyConsumed) { cellar.drink(bottle.id, Tasting()) }

        cellar.undoDrink(bottle.id, previousSlot = slot)
        assertEquals(bottle.id, cellar.bottleAt(slot)?.id)
        assertTrue(cellar.consumedBottles.isEmpty())

        // The rack was removed in the meantime: the bottle comes back without a place.
        cellar.drink(bottle.id, Tasting())
        cellar.removeRack(rack.id)
        cellar.undoDrink(bottle.id, previousSlot = slot)
        assertEquals(Bottle.Status.InCellar, cellar.bottles[bottle.id]?.status)
        assertNull(cellar.bottles[bottle.id]?.slot)
    }

    @Test
    fun undoDrinkLeavesATakenPlaceAndOnlyUndoesAnOpenedBottle() {
        val cellar = cellarWithRack()
        val slot = Slot(rack.id, 0, 0)
        val x = cellar.addBottles(Wine(name = "X"), slots = listOf(slot)).first()
        cellar.drink(x.id, Tasting())
        val y = cellar.addBottles(Wine(name = "Y"), slots = listOf(slot)).first()
        cellar.undoDrink(x.id, previousSlot = slot)
        assertEquals(Bottle.Status.InCellar, cellar.bottles[x.id]?.status)
        assertNull(cellar.bottles[x.id]?.slot)
        assertEquals(y.id, cellar.bottleAt(slot)?.id)

        // On order or given away: not a drink to undo, nothing changes.
        val ordered = cellar.addBottles(Wine(name = "O"), purchase = PurchaseInfo(isOrdered = true)).first()
        cellar.undoDrink(ordered.id, previousSlot = null)
        assertEquals(Bottle.Status.Ordered, cellar.bottles[ordered.id]?.status)
        cellar.markGone(y.id)
        cellar.undoDrink(y.id, previousSlot = slot)
        assertEquals(Bottle.Status.Gone, cellar.bottles[y.id]?.status)
        assertNull(cellar.bottleAt(slot))
    }

    @Test
    fun moveRejectsOccupiedSlot() {
        val cellar = cellarWithRack()
        val a = Slot(rack.id, 0, 0)
        val b = Slot(rack.id, 0, 1)
        val first = cellar.addBottles(Wine(name = "W"), slots = listOf(a))[0]
        cellar.addBottles(Wine(name = "X"), slots = listOf(b))
        expectError(CellarError.SlotTaken) { cellar.move(first.id, b) }
        cellar.move(first.id, Slot(rack.id, 1, 2))
        assertEquals("A · B3", cellar.locationLabel(cellar.bottles.getValue(first.id)))
    }

    @Test
    fun removingRackKeepsBottlesWithoutSlot() {
        val cellar = cellarWithRack()
        val bottle = cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 0, 0)))[0]
        cellar.removeRack(rack.id)
        assertTrue(cellar.racks.isEmpty())
        assertNull(cellar.bottles[bottle.id]?.slot)
        assertEquals(Bottle.Status.InCellar, cellar.bottles[bottle.id]?.status)
    }

    @Test
    fun searchMatchesNameProducerRegionVintageAndGrapes() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Clos", producer = "Arnaud", vintage = 2015, region = "Bordeaux", grapes = listOf("Merlot")))
        cellar.addBottles(Wine(name = "Other"))
        for (query in listOf("clos", "ARNAUD", "bordeaux", "2015", "merlot")) {
            assertEquals(listOf("Clos"), cellar.groups(query).map { it.wine.name }, "query: $query")
        }
        assertEquals(2, cellar.groups("").size)
    }

    @Test
    fun rowLabelsGoPastZ() {
        assertEquals("A", Rack.rowLetters(0))
        assertEquals("Z", Rack.rowLetters(25))
        assertEquals("AA", Rack.rowLetters(26))
    }

    @Test
    fun copyIsIndependent() {
        val cellar = cellarWithRack()
        val copy = cellar.copy()
        copy.addBottles(Wine(name = "W"))
        assertTrue(cellar.bottles.isEmpty())
        assertEquals(1, copy.bottles.size)
    }

    @Test
    fun rackSizeIsAtLeastOne() {
        val cellar = Cellar(racks = listOf(Rack(name = "A", rows = 0, columns = -2)))
        assertEquals(1, cellar.racks[0].rows)
        assertEquals(1, cellar.racks[0].columns)
    }
}

class MaturityTest {
    @ParameterizedTest
    @CsvSource("2020,Young", "2022,Ready", "2025,Ready", "2026,DrinkSoon", "2027,PastPeak")
    fun windowBoundaries(year: Int, expected: Maturity) {
        val wine = Wine(name = "W", drinkFrom = 2022, drinkUntil = 2026)
        assertEquals(expected, Maturity.of(wine, year))
    }

    @Test
    fun unknownWithoutWindow() {
        assertEquals(Maturity.Unknown, Maturity.of(Wine(name = "W"), 2026))
    }

    @Test
    fun tonightOrdersMostUrgentFirst() {
        val cellar = SampleData.cellar(year = 2026)
        val order = cellar.groupsByMaturity(2026).map { it.maturity }
        assertEquals(order.sorted(), order)
        assertEquals(Maturity.DrinkSoon, order.first())
    }

    @Test
    fun estimatedWindowUsesColor() {
        assertEquals(2023..2035, Wine(name = "W", vintage = 2020, color = WineColor.Red).estimatedWindow())
        assertNull(Wine(name = "W", color = WineColor.Red).estimatedWindow())
    }
}

class StoreTest {
    @Test
    fun jsonStoreRoundTrips() {
        val dir = Files.createTempDirectory("encave").toFile()
        try {
            val store = JsonFileCellarStore(File(dir, "cellar-${UUID.randomUUID()}.json"))
            assertTrue(store.load().isEmpty)

            // Dates are stored to the second, like the Apple backup format.
            val cellar = CellarJson.decode(CellarJson.encode(SampleData.cellar(year = 2026)))
            store.save(cellar)
            assertEquals(cellar, store.load())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "temporary file is renamed")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unreadableFileIsMovedAsideNotOverwritten() {
        val dir = Files.createTempDirectory("encave").toFile()
        try {
            val file = File(dir, "cellar.json")
            file.writeText("{ damaged")
            val store = JsonFileCellarStore(file) { java.time.LocalDateTime.of(2026, 10, 18, 9, 5, 7) }
            assertThrows<Exception> { store.load() }
            assertEquals("cellar-unreadable-20261018-090507.json", store.setAside())
            assertTrue(!file.exists())
            assertEquals("{ damaged", File(dir, "cellar-unreadable-20261018-090507.json").readText())
            // The same second again: a second name, nothing replaced.
            file.writeText("{ also damaged")
            assertEquals("cellar-unreadable-20261018-090507-2.json", store.setAside())
            assertEquals(listOf("cellar-unreadable-20261018-090507-2.json", "cellar-unreadable-20261018-090507.json").sorted(), store.keptAside().map { it.name })
            assertTrue(store.load().isEmpty)
            assertNull(store.setAside())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sampleDataIsConsistent() {
        val cellar = SampleData.cellar(year = 2026)
        assertEquals(8, cellar.racks.size)
        assertEquals(2, cellar.consumedBottles.size)
        val slots = cellar.bottlesInCellar.mapNotNull { it.slot }
        assertEquals(slots.size, slots.toSet().size, "no two bottles share a slot")
    }

    @Test
    fun inMemoryStoreKeepsItsOwnCopy() {
        val store = InMemoryCellarStore()
        val cellar = Cellar()
        store.save(cellar)
        cellar.addBottles(Wine(name = "W"))
        assertTrue(store.load().isEmpty)
    }
}
