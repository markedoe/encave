package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.Discovery
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.core.addDiscovery
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Leaving a cellar, joining with Replace, sync health and retries. Mirrors CaveKit's SyncDevicesTests. */
class SyncDevicesTests {
    private fun members(): Triple<DeviceIdentity, DeviceIdentity, Membership> {
        val a = identity()
        val b = identity()
        val (one, clock) = Membership().adding(Member(a.id, "A", a.publicKey, "ios"), 0, a.id)
        val (both, _) = one.adding(Member(b.id, "B", b.publicKey, "android"), clock, a.id)
        return Triple(a, b, both)
    }

    // region Leaving

    @Test
    fun aDeviceThatLeavesIsRemovedOnTheOtherDeviceItMeets() = runBlocking {
        val (a, b, membership) = members()
        // B removes itself, then pushes: the handshake uses the list from before the removal.
        val (left, clock) = membership.removing(b.id, 10, b.id)
        val snapshotB = SyncSnapshot(Cellar(), SyncState(device = b.id, clock = clock), left)
        val snapshotA = SyncSnapshot(Cellar(), SyncState(device = a.id, clock = 2), membership)
        val (endB, endA) = Pipe.pair()
        coroutineScope {
            val push = async {
                val (channel, _) = SyncHandshake.initiate(endB, b, membership)
                SyncExchange.run(channel, true, snapshotB, FileAccess.NONE)
            }
            val (channel, peer) = SyncHandshake.respond(endA, a, membership)
            assertEquals(b.id, peer.id)
            val resultA = SyncExchange.run(channel, false, snapshotA, FileAccess.NONE)
            push.await()
            assertNull(resultA.membership.members[b.id])
            assertNotNull(resultA.membership.members[a.id])
            assertEquals(true, resultA.membership.stamps[b.id]?.deleted)
        }
    }

    @Test
    fun aDeviceThatLeavesIsRemovedThroughStorage() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        grantAll(devices, store, null)
        devices[1].sync(store)
        // Device 1 leaves: removes itself, then one round.
        val leaving = devices[1].identity.id
        val (left, clock) = devices[1].snapshot.membership.removing(leaving, devices[1].snapshot.state.clock, leaving)
        devices[1].snapshot = devices[1].snapshot.copy(membership = left, state = devices[1].snapshot.state.copy(clock = clock))
        devices[1].sync(store)
        devices[0].sync(store)
        assertNull(devices[0].snapshot.membership.members[leaving])
        assertEquals(1, devices[0].snapshot.membership.active.size)
    }

    // endregion

    // region Joining with Replace

    @Test
    fun replaceSendsOnlyChangesMadeSinceJoining() {
        val cellar = Cellar()
        val rack = Rack(name = "Fridge", rows = 1, columns = 2)
        cellar.addRack(rack)
        val old = cellar.addBottles(Wine(name = "Old"), 2)
        // Joined: the stamps start empty; one bottle added since, in the rack.
        val before = cellar.copy()
        val added = cellar.addBottles(Wine(name = "New"), 1, listOf(Slot(rack.id, 0, 0)))
        val state = SyncState(device = UUID.randomUUID(), clock = 40).recording(before, cellar)

        val sent = CellarMerge.onlyStamped(cellar, state)
        assertEquals(added.map { it.id }.toSet(), sent.bottles.keys)
        assertNull(sent.bottles[old[0].id])
        assertEquals(listOf("New"), sent.wines.values.map { it.name })
        // The rack it is in comes along, the settings too.
        assertEquals(listOf(rack.id), sent.racks.map { it.id })
        assertEquals(cellar.settings, sent.settings)
    }

    @Test
    fun replaceTakesTheOtherCellarOnceItArrives() {
        val theirs = Cellar().apply { addBottles(Wine(name = "Theirs"), 3) }
        val theirState = SyncState.starting(theirs, UUID.randomUUID())
        val mine = Cellar().apply { addBottles(Wine(name = "Mine"), 5) }
        val myState = SyncState(device = UUID.randomUUID(), clock = theirState.clock)

        val merge = CellarMerge.merge(CellarMerge.onlyStamped(mine, myState), myState, theirs, theirState)
        assertEquals(3, merge.cellar.bottles.size)
        assertEquals(listOf("Theirs"), merge.cellar.wines.values.map { it.name })
        // Nothing of this device's old bottles is stamped as deleted for the others.
        assertFalse(merge.stamps.values.any { it.deleted })
    }

    // endregion

    // region Health and retries

    @Test
    fun syncHealth() {
        val now = Instant.ofEpochSecond(1_000_000)
        fun health(
            shared: Boolean = true,
            removed: Boolean = false,
            key: Boolean = false,
            signIn: Boolean = false,
            network: Boolean = false,
            local: Long? = null,
            remote: Long? = null,
            problem: Boolean = false,
        ) = SyncHealth.evaluate(
            shared, removed, key, signIn, network,
            local?.let { now.minusSeconds(it) }, remote?.let { now.minusSeconds(it) }, problem, now,
        )
        assertEquals(SyncHealth.Ok, health())
        assertEquals(SyncHealth.Ok, health(shared = false, signIn = true, local = 7200))
        assertEquals(SyncHealth.NeedsAction(SyncHealth.Reason.Removed), health(shared = false, removed = true))
        assertEquals(SyncHealth.NeedsAction(SyncHealth.Reason.KeyUnavailable), health(key = true))
        assertEquals(SyncHealth.NeedsAction(SyncHealth.Reason.SignInNeeded), health(signIn = true, local = 7200))
        assertEquals(SyncHealth.NeedsAction(SyncHealth.Reason.LocalNetworkOff), health(network = true))
        // Failing for a few minutes is not shown; for over an hour it is.
        assertEquals(SyncHealth.Ok, health(local = 600, remote = 600))
        assertEquals(SyncHealth.Failing, health(local = 3700))
        assertEquals(SyncHealth.Failing, health(remote = 3600))
        assertEquals(SyncHealth.Failing, health(problem = true))
    }

    @Test
    fun retriesComeAfter15SecondsThenAMinuteThenFive() {
        assertNull(SyncRetry.delayMs(0))
        assertEquals(15_000L, SyncRetry.delayMs(1))
        assertEquals(60_000L, SyncRetry.delayMs(2))
        assertEquals(300_000L, SyncRetry.delayMs(3))
        assertNull(SyncRetry.delayMs(4))
    }

    // endregion

    // region Changes told in the notification

    private fun summary(change: (Cellar) -> Unit): CellarChangeSummary {
        val here = UUID.randomUUID()
        val marc = UUID.randomUUID()
        val before = Cellar()
        val after = before.copy().also(change)
        val state = SyncState(device = marc).recording(before, after)
        val merged = CellarMerge.merge(before, SyncState(device = here), after, state)
        return CellarChangeSummary.between(before, merged.cellar, merged.stamps, here)
    }

    @Test
    fun aNewWineWithBottlesIsToldByItsBottles() {
        val told = summary { it.addBottles(Wine(name = "Barolo"), 2) }
        assertEquals(2, told.added)
        assertEquals(0, told.winesAdded)
        assertEquals(0, told.other)
    }

    @Test
    fun aWineToBuyIsAddedToTheShoppingList() {
        val told = summary { it.addDiscovery(Discovery(wineId = UUID.randomUUID(), wantsToBuy = true), Wine(name = "Riesling")) }
        assertEquals(1, told.winesAdded)
        assertEquals(0, told.other)
        val tasted = summary { it.addDiscovery(Discovery(wineId = UUID.randomUUID()), Wine(name = "Riesling")) }
        assertEquals(0, tasted.winesAdded)
        assertEquals(1, tasted.other)
        assertTrue(tasted.singleDevice != null)
    }

    // endregion
}
