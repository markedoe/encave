package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.Tasting
import app.encave.core.Wine
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a sync brought from other devices, as the notification tells it. */
class CellarChangeSummaryTests {
    private val here = UUID.randomUUID()
    private val marc = UUID.randomUUID()
    private val anna = UUID.randomUUID()

    /** A copy of a cellar edited on a device, with its stamps. */
    private class Copy(var cellar: Cellar, var state: SyncState) {
        fun edit(change: (Cellar) -> Unit) {
            val before = cellar.copy()
            change(cellar)
            state = state.recording(before, cellar)
        }
    }

    private fun copy(cellar: Cellar, device: UUID) = Copy(cellar.copy(), SyncState(device = device))

    /** The summary of taking in [others] one after the other on [local], as the app does. */
    private fun sync(local: Copy, vararg others: Copy): CellarChangeSummary {
        val before = local.cellar.copy()
        var cellar = local.cellar
        var state = local.state
        for (other in others) {
            val result = CellarMerge.merge(cellar, state, other.cellar, other.state)
            cellar = result.cellar
            state = state.copy(stamps = result.stamps, clock = maxOf(result.clock, state.clock))
        }
        return CellarChangeSummary.between(before, cellar, state.stamps, here)
    }

    private fun base(): Triple<Cellar, Wine, List<Bottle>> {
        val cellar = Cellar()
        val wine = Wine(name = "Clos", vintage = 2017)
        val bottles = cellar.addBottles(wine, 3)
        return Triple(cellar, wine, bottles)
    }

    @Test
    fun countsBottlesOpenedAddedAndRemoved() {
        val (start, wine, bottles) = base()
        val local = copy(start, here)
        val other = copy(start, marc)
        other.edit {
            it.drink(bottles[0].id, Tasting(rating = 4))
            it.drink(bottles[1].id, Tasting())
            it.deleteBottle(bottles[2].id)
            it.addBottles(wine, 2)
            it.addBottles(Wine(name = "Barolo", vintage = 2016), 1)
        }
        val summary = sync(local, other)
        assertEquals(2, summary.opened)
        assertEquals(3, summary.added)
        assertEquals(1, summary.removed)
        // The new wine has a bottle: it is told by its bottle, not as a wine added.
        assertEquals(0, summary.winesAdded)
        assertEquals(0, summary.other)
        assertEquals(marc, summary.singleDevice)
        assertFalse(summary.onlyOpened)
    }

    @Test
    fun onlyOpenedOpensTheJournal() {
        val (start, _, bottles) = base()
        val local = copy(start, here)
        val other = copy(start, marc)
        other.edit { it.drink(bottles[0].id, Tasting()) }
        val summary = sync(local, other)
        assertEquals(CellarChangeSummary(opened = 1, devices = setOf(marc)), summary)
        assertTrue(summary.onlyOpened)
    }

    @Test
    fun givenAwayIsRemovedAndOtherEditsAreOther() {
        val (start, wine, bottles) = base()
        val local = copy(start, here)
        val other = copy(start, marc)
        other.edit {
            it.markGone(bottles[0].id)
            it.updateWine(wine.copy(notes = "Decant"))
            it.addRack(Rack(name = "Kitchen", rows = 1, columns = 4))
            it.reserve(listOf(bottles[1].id), "Paul's 40th")
        }
        val summary = sync(local, other)
        assertEquals(1, summary.removed)
        // The wine's notes, the new rack and the reserved bottle.
        assertEquals(3, summary.other)
        assertEquals(0, summary.added)
    }

    @Test
    fun aDeletedWineCountsOnlyItsBottles() {
        val (start, wine, _) = base()
        val local = copy(start, here)
        val other = copy(start, marc)
        other.edit { it.deleteWine(wine.id) }
        val summary = sync(local, other)
        assertEquals(CellarChangeSummary(removed = 3, devices = setOf(marc)), summary)
    }

    @Test
    fun severalDevicesHaveNoSingleName() {
        val (start, _, bottles) = base()
        val local = copy(start, here)
        val first = copy(start, marc)
        first.edit { it.drink(bottles[0].id, Tasting()) }
        val second = copy(start, anna)
        second.edit { it.drink(bottles[1].id, Tasting()) }
        val summary = sync(local, first, second)
        assertEquals(2, summary.opened)
        assertEquals(setOf(marc, anna), summary.devices)
        assertNull(summary.singleDevice)
    }

    @Test
    fun changesWithoutAStampComeFromAnUnknownDevice() {
        val (start, wine, _) = base()
        val after = start.copy().also { it.addBottles(wine, 1) }
        val summary = CellarChangeSummary.between(start, after, emptyMap(), here)
        assertEquals(1, summary.added)
        assertTrue(summary.unknownDevice)
        assertNull(summary.singleDevice)
    }

    @Test
    fun changesStampedByThisDeviceAreNotCounted() {
        val (start, wine, _) = base()
        val after = start.copy().also { it.addBottles(wine, 1) }
        val bottle = after.bottles.keys.first { it !in start.bottles }
        val stamps = mapOf(SyncKey.of(SyncKey.Kind.Bottle, bottle) to Stamp(5, here))
        assertTrue(CellarChangeSummary.between(start, after, stamps, here).isEmpty)
    }

    @Test
    fun nothingNewIsEmpty() {
        val (start, _, _) = base()
        val local = copy(start, here)
        local.edit { it.addBottles(Wine(name = "Mine"), 1) }
        val other = copy(start, marc)
        assertTrue(sync(local, other).isEmpty)
    }

    @Test
    fun reorderingRacksIsOneChange() {
        val start = Cellar()
        val a = Rack(name = "A", rows = 1, columns = 1)
        val b = Rack(name = "B", rows = 1, columns = 1)
        start.addRack(a)
        start.addRack(b)
        val local = copy(start, here)
        val other = copy(start, marc)
        other.edit { it.moveRacks(setOf(1), 0) }
        assertEquals(CellarChangeSummary(other = 1, devices = setOf(marc)), sync(local, other))
    }

    @Test
    fun summariesAddUp() {
        val total = CellarChangeSummary(opened = 1, devices = setOf(marc)) + CellarChangeSummary(added = 2, devices = setOf(anna))
        assertEquals(CellarChangeSummary(opened = 1, added = 2, devices = setOf(marc, anna)), total)
        assertFalse(total.onlyOpened)
    }
}
