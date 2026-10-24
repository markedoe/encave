package app.encave.core

import java.io.File
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Undos and checks of a rack that leave alone what changed since. Shared with the Swift StorageUndoTests. */
class StorageUndoTest {
    @TempDir
    lateinit var folder: File

    @Test
    fun undoOfAShelfRemovedKeepsBottlesPlacedSince() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 3, columns = 2)
        cellar.addRack(rack)
        val wine = Wine(name = "W")
        val out = cellar.addBottles(wine, slots = listOf(Slot(rack.id, 0, 0)))[0]
        val kept = cellar.addBottles(wine, slots = listOf(Slot(rack.id, 2, 1)))[0]
        val before = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }
        // Shelf 1 removed: shelves 2 and 3 move up.
        val map = mapOf(0 to null, 1 to 0, 2 to 1)
        cellar.updateRack(Rack(id = rack.id, name = "G", rows = 2, columns = 2), map, takingOut = true)
        // A new bottle on the new first shelf: the old shelf 2.
        val new = cellar.addBottles(wine, slots = listOf(Slot(rack.id, 0, 1)))[0]

        cellar.undoRackUpdate(rack, map, before)

        assertEquals(3, cellar.rack(rack.id)?.rows)
        assertEquals(Slot(rack.id, 1, 1), cellar.bottles[new.id]?.slot, "stays on its shelf")
        assertEquals(Slot(rack.id, 2, 1), cellar.bottles[kept.id]?.slot)
        assertEquals(Slot(rack.id, 0, 0), cellar.bottles[out.id]?.slot)
    }

    @Test
    fun undoOfAShelfRemovedLeavesBottlesMovedOrOpenedSince() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 2, columns = 2)
        val other = Rack(name = "O", rows = 1, columns = 2)
        cellar.addRack(rack)
        cellar.addRack(other)
        val bottles = cellar.addBottles(Wine(name = "W"), 3, listOf(Slot(rack.id, 0, 0), Slot(rack.id, 1, 0), Slot(rack.id, 1, 1)))
        val before = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }
        val map = mapOf(0 to null, 1 to 0)
        cellar.updateRack(Rack(id = rack.id, name = "G", rows = 1, columns = 2), map, takingOut = true)
        // Taken out, then put in another rack; another one opened.
        cellar.move(bottles[0].id, Slot(other.id, 0, 0))
        cellar.drink(bottles[2].id, Tasting())

        cellar.undoRackUpdate(rack, map, before)

        assertEquals(Slot(other.id, 0, 0), cellar.bottles[bottles[0].id]?.slot)
        assertEquals(Slot(rack.id, 1, 0), cellar.bottles[bottles[1].id]?.slot)
        assertEquals(Bottle.Status.Consumed, cellar.bottles[bottles[2].id]?.status)
        assertNull(cellar.bottles[bottles[2].id]?.slot)
    }

    @Test
    fun undoOfASwapLeavesABottlePutThereSince() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 1, columns = 3)
        cellar.addRack(rack)
        val a = Slot(rack.id, 0, 0)
        val b = Slot(rack.id, 0, 1)
        val first = cellar.addBottles(Wine(name = "W"), slots = listOf(a))[0]
        cellar.swap(a, b)
        val third = cellar.addBottles(Wine(name = "X"), slots = listOf(a))[0]

        assertFalse(cellar.undoSwap(a, b, first.id, null))
        assertEquals(a, cellar.bottles[third.id]?.slot, "the third bottle did not move")
        assertEquals(b, cellar.bottles[first.id]?.slot)
    }

    @Test
    fun undoOfASwapSwapsBack() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 1, columns = 2)
        cellar.addRack(rack)
        val a = Slot(rack.id, 0, 0)
        val b = Slot(rack.id, 0, 1)
        val bottles = cellar.addBottles(Wine(name = "W"), 2, listOf(a, b))
        cellar.swap(a, b)
        assertTrue(cellar.undoSwap(a, b, bottles[0].id, bottles[1].id))
        assertEquals(a, cellar.bottles[bottles[0].id]?.slot)
        assertEquals(b, cellar.bottles[bottles[1].id]?.slot)
    }

    @Test
    fun aBottleNoLongerInTheCellarCannotBeMovedToAPlace() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 1, columns = 2)
        cellar.addRack(rack)
        val bottles = cellar.addBottles(Wine(name = "W"), 2)
        cellar.drink(bottles[0].id, Tasting())
        cellar.markGone(bottles[1].id)
        val slot = Slot(rack.id, 0, 0)
        assertEquals(CellarError.NotInCellar, assertThrows<CellarException> { cellar.move(bottles[0].id, slot) }.error)
        assertEquals(CellarError.NotInCellar, assertThrows<CellarException> { cellar.move(bottles[1].id, slot) }.error)
        assertNull(cellar.bottleAt(slot))
    }

    @Test
    fun aBottleFoundAndThenSwappedIsNotMissing() {
        val cellar = Cellar()
        val rack = Rack(name = "G", rows = 1, columns = 2)
        cellar.addRack(rack)
        val a = Slot(rack.id, 0, 0)
        val b = Slot(rack.id, 0, 1)
        val bottles = cellar.addBottles(Wine(name = "W"), 2, listOf(a, b))
        val found: Set<UUID> = setOf(bottles[0].id)
        cellar.swap(a, b)
        assertEquals(listOf(bottles[1].id), cellar.missingBottles(rack.id, found).map { it.id })
    }

    @Test
    fun reservingWithTheSameNameChangesNothing() {
        val cellar = Cellar()
        val bottle = cellar.addBottles(Wine(name = "W"))[0]
        assertFalse(cellar.changesReservation(listOf(bottle.id), "  "), "an empty name frees a bottle never kept")
        assertFalse(cellar.changesReservation(listOf(bottle.id), null))
        assertTrue(cellar.changesReservation(listOf(bottle.id), "Birthday"))
        cellar.reserve(listOf(bottle.id), "Birthday")
        assertFalse(cellar.changesReservation(listOf(bottle.id), " Birthday "))
        assertTrue(cellar.changesReservation(listOf(bottle.id), null))
    }

    @Test
    fun replacingKeepsTheCellarAsAVersionFirst() {
        val versions = CellarVersionFolder(File(folder, "versions"))
        val mine = Cellar().apply { addBottles(Wine(name = "Mine"), 2) }
        // Nothing to keep: replacing may go on.
        assertTrue(versions.keepBeforeReplacingIfNeeded(Cellar()))
        assertTrue(versions.versions().isEmpty())

        assertTrue(versions.keepBeforeReplacingIfNeeded(mine))
        val first = versions.versions().first()
        assertTrue(first.isBeforeReplacing)
        assertEquals(mine, versions.cellar(first))
    }

    @Test
    fun aVersionThatCannotBeSavedStopsTheReplace() {
        // A file where the folder of versions should be: nothing can be saved there.
        val blocked = File(folder, "versions").apply { writeText("x") }
        val versions = CellarVersionFolder(blocked)
        val mine = Cellar().apply { addBottles(Wine(name = "Mine")) }
        assertFalse(versions.keepBeforeReplacingIfNeeded(mine))
    }
}
