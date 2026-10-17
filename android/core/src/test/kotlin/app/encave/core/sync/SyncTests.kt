package app.encave.core.sync

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A device that edits its copy of a cellar offline, then syncs. Mirrors CaveKit's SyncTests. */
private class Device(initial: Cellar, device: UUID = UUID.randomUUID()) {
    /** Its own copy, as a Swift value would be. */
    var cellar: Cellar = initial.copy()
    var state = SyncState.starting(cellar, device)

    /** A local change, recorded as the app does after each edit. */
    fun edit(change: (Cellar) -> Unit) {
        val before = cellar.copy()
        change(cellar)
        state = state.recording(before, cellar)
    }

    /** Receives another device's copy. */
    fun sync(other: Device) {
        val result = CellarMerge.merge(cellar, state, other.cellar, other.state)
        cellar = result.cellar
        state = state.copy(stamps = result.stamps, clock = result.clock)
    }

    fun snapshot(): Device = Device(cellar.copy()).also { it.state = state }
}

/** A and B start from the same cellar (B joined A). */
private fun pair(cellar: Cellar): Pair<Device, Device> {
    val a = Device(cellar)
    val b = Device(Cellar())
    b.sync(a)
    return a to b
}

private fun syncBoth(a: Device, b: Device) {
    val copyA = a.snapshot()
    a.sync(b)
    b.sync(copyA)
}

class SyncTests {
    private data class Sample(val cellar: Cellar, val wine: Wine, val rack: Rack)

    private fun sample(): Sample {
        val cellar = Cellar()
        val rack = Rack(name = "Main", rows = 2, columns = 3)
        cellar.addRack(rack)
        val wine = Wine(name = "Clos", vintage = 2017, color = app.encave.core.WineColor.Red)
        cellar.addBottles(wine, 2, listOf(Slot(rack.id, 0, 0)))
        return Sample(cellar, wine, rack)
    }

    private fun Cellar.note(id: UUID, notes: String) = updateWine(wines.getValue(id).copy(notes = notes))

    @Test
    fun joiningDeviceGetsTheWholeCellar() {
        val (a, b) = pair(sample().cellar)
        assertEquals(a.cellar, b.cellar)
    }

    @Test
    fun editsOnBothSidesMergeAndConverge() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.updateWine(it.wines.getValue(s.wine.id).copy(region = "Bordeaux")) }
        b.edit { it.addBottles(Wine(name = "Other"), 1) }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertEquals("Bordeaux", a.cellar.wines[s.wine.id]?.region)
        assertEquals(3, a.cellar.bottles.size)
    }

    @Test
    fun theLaterEditOfTheSameWineWins() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.note(s.wine.id, "first") }
        // B has seen A's change, so its edit comes later.
        b.sync(a)
        b.edit { it.note(s.wine.id, "second") }
        syncBoth(a, b)
        assertEquals("second", a.cellar.wines[s.wine.id]?.notes)
        assertEquals(a.cellar, b.cellar)
    }

    @Test
    fun concurrentEditsPickTheSameWinnerOnBothDevices() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.note(s.wine.id, "from A") }
        b.edit { it.note(s.wine.id, "from B") }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertTrue(a.cellar.wines[s.wine.id]?.notes in listOf("from A", "from B"))
    }

    @Test
    fun aDeletionSpreads() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.deleteWine(s.wine.id) }
        syncBoth(a, b)
        assertNull(b.cellar.wines[s.wine.id])
        assertTrue(b.cellar.bottles.isEmpty())
        // And does not come back on the next sync.
        syncBoth(a, b)
        assertTrue(a.cellar.wines.isEmpty() && b.cellar.wines.isEmpty())
    }

    @Test
    fun editingABottleOfADeletedWineDoesNotLeaveAnOrphan() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        val bottle = s.cellar.bottles.values.first { it.slot == null }
        a.edit { it.deleteWine(s.wine.id) }
        b.edit { it.markGone(bottle.id) }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertTrue(a.cellar.bottles.values.all { a.cellar.wines[it.wineId] != null })
    }

    @Test
    fun theSameBottleDrunkOnBothDevicesStaysOneBottle() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        val bottle = s.cellar.bottles.values.first()
        a.edit { it.drink(bottle.id, Tasting(rating = 4)) }
        b.edit { it.drink(bottle.id, Tasting(rating = 3)) }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertEquals(1, a.cellar.consumedBottles.size)
        assertEquals(1, a.cellar.bottlesInCellar.size)
    }

    @Test
    fun twoBottlesPutInOnePlaceKeepOnlyOneThere() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        val place = Slot(s.rack.id, 1, 1)
        a.edit { it.addBottles(Wine(name = "From A"), 1, listOf(place)) }
        b.edit { it.addBottles(Wine(name = "From B"), 1, listOf(place)) }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertEquals(1, a.cellar.bottlesInCellar.count { it.slot == place })
        assertEquals(4, a.cellar.bottlesInCellar.size, "the other bottle stays in the cellar, without a place")
    }

    @Test
    fun bottlesOfARemovedRackLoseTheirPlace() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.removeRack(s.rack.id) }
        // B moves a bottle within that rack meanwhile.
        val placed = s.cellar.bottles.values.first { it.slot != null }
        b.edit { it.move(placed.id, Slot(s.rack.id, 1, 2)) }
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertTrue(a.cellar.racks.isEmpty())
        assertTrue(a.cellar.bottles.values.all { it.slot == null })
    }

    @Test
    fun rackOrderAndSettingsFollowTheLaterChange() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.addRack(Rack(name = "Second", rows = 1, columns = 1)) }
        syncBoth(a, b)
        b.edit { it.moveRacks(setOf(1), 0) }
        a.edit { it.settings = it.settings.copy(currency = "CHF") }
        syncBoth(a, b)
        assertEquals(listOf("Second", "Main"), a.cellar.racks.map { it.name })
        assertEquals("CHF", b.cellar.settings.currency)
        assertEquals(a.cellar, b.cellar)
    }

    @Test
    fun twoCellarsThatNeverSyncedCombine() {
        val cellarB = Cellar()
        cellarB.addBottles(Wine(name = "Only on B"), 3)
        val a = Device(sample().cellar)
        val b = Device(cellarB)
        syncBoth(a, b)
        assertEquals(a.cellar, b.cellar)
        assertEquals(5, a.cellar.bottles.size)
    }

    @Test
    fun mergeIsTheSameWhicheverDeviceRunsIt() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        a.edit { it.note(s.wine.id, "A") }
        a.edit { it.addBottles(Wine(name = "A wine"), 1, listOf(Slot(s.rack.id, 0, 2))) }
        b.edit { it.addBottles(Wine(name = "B wine"), 1, listOf(Slot(s.rack.id, 0, 2))) }
        b.edit { it.settings = it.settings.copy(currency = "USD") }
        val ab = CellarMerge.merge(a.cellar, a.state, b.cellar, b.state)
        val ba = CellarMerge.merge(b.cellar, b.state, a.cellar, a.state)
        assertEquals(ab.cellar, ba.cellar)
        assertEquals(ab.stamps, ba.stamps)
    }

    @Test
    fun syncStateRoundTripsThroughJson() {
        val state = SyncState.starting(sample().cellar)
        assertEquals(state, SyncJson.decode(SyncState.serializer(), SyncJson.encode(SyncState.serializer(), state)))
    }

    @Test
    fun aLocalChangeAfterASyncIsNewerThanEverythingReceived() {
        val s = sample()
        val (a, b) = pair(s.cellar)
        // B made many changes: its clock is far ahead.
        repeat(20) { index -> b.edit { it.note(s.wine.id, "B $index") } }
        a.sync(b)
        a.edit { it.note(s.wine.id, "A after sync") }
        val key = SyncKey.of(SyncKey.Kind.Wine, s.wine.id)
        assertTrue(a.state.stamps.getValue(key).counter > b.state.clock)
        syncBoth(a, b)
        assertEquals("A after sync", b.cellar.wines[s.wine.id]?.notes)
    }

    @Test
    fun stampsAreOrderedByTheUppercaseDeviceId() {
        // "a…" sorts after "B…" in lowercase, before it in uppercase (as Swift's uuidString compares).
        val lowerFirst = UUID.fromString("0000000a-0000-4000-8000-000000000000")
        val upperFirst = UUID.fromString("0000000b-0000-4000-8000-000000000000")
        assertTrue(Stamp(1, lowerFirst) < Stamp(1, upperFirst))
        val digit = UUID.fromString("00000009-0000-4000-8000-000000000000")
        assertTrue(Stamp(1, digit) < Stamp(1, lowerFirst))
        assertTrue(Stamp(1, digit) < Stamp(1, digit, deleted = true))
        assertTrue(Stamp(2, digit) > Stamp(1, upperFirst, deleted = true))
        assertEquals(Bottle.Status.InCellar, Bottle(wineId = UUID.randomUUID()).status)
    }
}

/** Mirrors CaveKit's SyncHardeningTests (SYNC.md §1–2 after the security review). */
class SyncHardeningTests {
    @Test
    fun aRemovalCannotBeUndoneByANewerRecord() {
        val device = UUID.randomUUID()
        val other = UUID.randomUUID()
        val record = Member(device, "R", ByteArray(32), "ios")
        val (added, clock) = Membership().adding(record, 0, other)
        val (removed, _) = added.removing(device, clock, other)
        val (forged, _) = added.adding(record, 1_000, device)
        assertNull(removed.merged(forged).members[device])
        assertNull(forged.merged(removed).members[device])
    }

    @Test
    fun clocksOutOfBoundsAreRefused() {
        val state = SyncState(clock = 5).let { it.copy(stamps = mapOf("settings" to Stamp(9, it.device))) }
        assertTrue(syncErrorOfBlocking { SyncState.check(state) } is SyncError.ProtocolViolation)
        assertTrue(syncErrorOfBlocking { SyncState.check(SyncState(clock = -1)) } is SyncError.ProtocolViolation)
        assertTrue(syncErrorOfBlocking { SyncState.check(SyncState(clock = SyncState.MAX_COUNTER + 1)) } is SyncError.ProtocolViolation)
        val (members, _) = Membership().adding(Member(UUID.randomUUID(), "M", ByteArray(32), "ios"), SyncState.MAX_COUNTER, UUID.randomUUID())
        assertTrue(syncErrorOfBlocking { SyncState.check(SyncState(clock = 7), members) } is SyncError.ProtocolViolation)
        SyncState.check(SyncState(clock = 7))
    }

    @Test
    fun theMergedClockCoversEveryStamp() {
        val remote = SyncState(clock = 3).let { it.copy(stamps = mapOf("settings" to Stamp(3, it.device))) }
        val local = SyncState(clock = 1).let { it.copy(stamps = mapOf("rack-order" to Stamp(2, it.device))) }
        assertTrue(CellarMerge.merge(Cellar(), local, Cellar(), remote).clock >= 3)
    }

    @Test
    fun duplicateRacksInAFileAreDropped() {
        val rack = Rack(name = "Twice", rows = 1, columns = 1)
        val json = SyncJson.encodeCellar(Cellar(racks = listOf(rack))).decodeToString()
        val twice = json.replace(Regex("\"racks\":\\[(\\{.*?\\})\\]")) { "\"racks\":[${it.groupValues[1]},${it.groupValues[1]}]" }
        assertEquals(1, SyncJson.decodeCellar(twice.toByteArray()).racks.size)
        assertEquals(1, Cellar(racks = listOf(rack, rack)).racks.size)
    }

    @Test
    fun aDictionaryKeyedByTheWrongIdIsReKeyed() {
        val wine = Wine(name = "Mine")
        val json = SyncJson.encodeCellar(Cellar(wines = listOf(wine))).decodeToString()
            .replace("\"wines\":[\"${wine.id.upper}\"", "\"wines\":[\"${UUID.randomUUID().upper}\"")
        assertEquals(setOf(wine.id), SyncJson.decodeCellar(json.toByteArray()).wines.keys)
    }

    @Test
    fun rackOrderConvergesOnEqualStamps() {
        val r0 = Rack(name = "R0", rows = 1, columns = 1)
        val a = Rack(name = "a", rows = 1, columns = 1)
        val b = Rack(name = "b", rows = 1, columns = 1)
        val device = UUID.randomUUID()
        val stamps = mapOf("rack-order" to Stamp(10, device)) + listOf(r0, a, b).associate { SyncKey.of(SyncKey.Kind.Rack, it.id) to Stamp(1, device) }
        val state = SyncState(device = device, clock = 10, stamps = stamps)
        val stateY = state.copy(device = UUID.randomUUID())
        val x = Cellar(racks = listOf(r0, a, b))
        val y = Cellar(racks = listOf(r0, b, a))
        val one = CellarMerge.merge(x, state, y, stateY).cellar.racks.map { it.id }
        val two = CellarMerge.merge(y, stateY, x, state).cellar.racks.map { it.id }
        assertEquals(one, two)
    }

    @Test
    fun aBottleAddedWhileItsWineWasDeletedBringsTheWineBack() {
        val wine = Wine(name = "Shared")
        val cellar = Cellar().apply { addBottles(wine, 1) }
        val deviceA = UUID.randomUUID()
        var stateA = SyncState.starting(cellar, deviceA)
        var stateB = stateA.copy(device = UUID.randomUUID())
        // A deletes the wine; B, later in Lamport order, adds a bottle of it.
        val cellarA = cellar.copy()
        val cellarB = cellar.copy()
        val beforeA = cellarA.copy()
        cellarA.deleteWine(wine.id)
        stateA = stateA.recording(beforeA, cellarA)
        stateB = stateB.copy(clock = stateB.clock + 5)
        val beforeB = cellarB.copy()
        cellarB.addBottles(wine, 1)
        stateB = stateB.recording(beforeB, cellarB)
        val onA = CellarMerge.merge(cellarA, stateA, cellarB, stateB)
        val onB = CellarMerge.merge(cellarB, stateB, cellarA, stateA)
        assertEquals(onA.cellar, onB.cellar)
        assertEquals(onA.stamps, onB.stamps)
        assertTrue(onA.cellar.wines[wine.id] != null)
        assertEquals(1, onA.cellar.bottles.size)
    }

    @Test
    fun equalStampsKeepTheBottleWithoutAPlace() {
        val rack = Rack(name = "R", rows = 1, columns = 1)
        val cellar = Cellar(racks = listOf(rack)).apply { addBottles(Wine(name = "W"), 1, listOf(Slot(rack.id, 0, 0))) }
        val state = SyncState.starting(cellar, UUID.randomUUID())
        val bottle = cellar.bottles.values.single()
        // Repair took the place on one side without a stamp.
        val unplaced = Cellar(cellar.wines.values, listOf(bottle.copy(slot = null)), cellar.racks)
        val one = CellarMerge.merge(cellar, state, unplaced, state.copy(device = UUID.randomUUID()))
        val two = CellarMerge.merge(unplaced, state, cellar, state)
        assertNull(one.cellar.bottles.values.single().slot)
        assertNull(two.cellar.bottles.values.single().slot)
    }
}

private fun syncErrorOfBlocking(block: () -> Unit): SyncError? = try {
    block()
    null
} catch (error: SyncException) {
    error.error
}
