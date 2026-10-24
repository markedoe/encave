package app.encave.core.sync

import app.encave.core.Wine
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Keeps a key as the apps do (`storeRemoteKey`): settled against the current one, afresh only when the key bytes change. */
internal fun RemoteDevice.keep(new: CellarKey, sender: UUID? = null) {
    val settled = RemoteSync.settled(new, key, snapshot.membership, sender)
    if (key?.key?.contentEquals(settled.key) != true) progress = RemoteProgress()
    key = settled
}

/**
 * A round as the apps run it (SyncService, SyncManager): grants still owed, then a key
 * rotation if a removal calls for it, reading the inbox before rotating, then [RemoteDevice.sync].
 */
internal suspend fun RemoteDevice.round(store: MailboxStore): RemoteSync.Result? {
    var current = key
    if (current != null) {
        val owed = pending
        if (owed != null && owed.key.key.contentEquals(current.key)) {
            val done = RemoteSync.grantMissing(owed, store, identity, snapshot, null)
            pending = done.takeIf { it.failed.isNotEmpty() }
        }
        var next = RemoteSync.nextRotation(current, pending, snapshot.membership, identity.id)
        if (next != null) {
            val grant = RemoteSync.receiveGrant(store, identity, snapshot.membership, current)
            if (grant != null) {
                keep(grant.cellarKey, grant.from)
                current = key!!
                next = RemoteSync.nextRotation(current, pending, snapshot.membership, identity.id)
            }
        }
        if (next != null) {
            pending = next
            val done = RemoteSync.rotate(store, identity, current, next, snapshot, null) { pending = it }
            keep(done.key)
            pending = done.takeIf { it.failed.isNotEmpty() }
        } else if (pending?.key?.key?.contentEquals(current.key) == false) {
            pending = null
        }
    }
    val result = sync(store)
    key?.let { keep(it) }
    return result
}

internal fun RemoteDevice.remove(other: RemoteDevice) {
    key?.let { if (it.removed == null) keep(it) }
    val (membership, clock) = snapshot.membership.removing(other.identity.id, snapshot.state.clock, identity.id)
    snapshot = snapshot.copy(membership = membership, state = snapshot.state.copy(clock = clock))
}

/** The membership learned from another device on the local network. */
internal fun RemoteDevice.meet(other: RemoteDevice) {
    snapshot = snapshot.copy(
        membership = snapshot.membership.merged(other.snapshot.membership),
        state = snapshot.state.copy(clock = maxOf(snapshot.state.clock, other.snapshot.state.clock)),
    )
}

/** Every key this device could open from its inbox. */
internal fun RemoteDevice.grantedKeys(store: MemoryMailboxStore, membership: Membership): List<CellarKey> =
    store.allFiles[identity.inboxId].orEmpty().mapNotNull { (path, data) ->
        runCatching { KeyGrant.open(data, path, identity, membership).cellarKey }.getOrNull()
    }

/** Storage whose mailboxes are full ([full]); inboxes still take grants. */
internal class FullMailboxStore : MailboxStore {
    val base = MemoryMailboxStore()

    @Volatile var full = true
    override fun inbox(id: String, token: String): Mailbox = base.inbox(id, token)
    override fun mailbox(id: String, token: String): Mailbox {
        val mailbox = base.mailbox(id, token)
        return object : Mailbox by mailbox {
            override suspend fun create(path: String, data: ByteArray): Boolean {
                if (full) throw RemoteSyncException(RemoteSyncError.QuotaExceeded)
                return mailbox.create(path, data)
            }
        }
    }
}

/** Storage where the inboxes in [refused] are full; everything else works. */
internal class RefusingInboxStore : MailboxStore {
    val base = MemoryMailboxStore()

    @Volatile var refused: Set<String> = emptySet()
    override fun mailbox(id: String, token: String): Mailbox = base.mailbox(id, token)
    override fun inbox(id: String, token: String): Mailbox {
        val inbox = base.inbox(id, token)
        return object : Mailbox by inbox {
            override suspend fun create(path: String, data: ByteArray): Boolean {
                if (id in refused) throw RemoteSyncException(RemoteSyncError.QuotaExceeded)
                return inbox.create(path, data)
            }
        }
    }
}

/** Key rotation after a removal (docs/SYNC.md §8, Removing a device). Mirrors CaveKit's KeyRotationTests. */
class KeyRotationTests {
    /** All devices on the first key, through [store]. */
    private suspend fun start(count: Int, store: MailboxStore): List<RemoteDevice> {
        val devices = household(count)
        devices[0].keep(devices[0].key!!)
        grantAll(devices, store, null)
        for (device in devices) device.round(store)
        return devices
    }

    private fun Map<String, ByteArray>?.ofEpoch(epoch: Int) = orEmpty().keys.count { it.contains("-%08d-".format(epoch)) }

    @Test
    fun anyKeyHolderRotatesAfterARemoval() = runBlocking {
        val store = MemoryMailboxStore()
        val (a, b, d) = start(3, store)
        val oldKey = d.key!!
        // A removes D while it has no storage (it still holds the key from the local network).
        a.remove(d)
        // B gets the membership from A over the local network, then runs a round.
        b.meet(a)
        b.edit { it.addBottles(Wine(name = "After removal"), 1) }
        b.round(store)
        val newKey = b.key!!
        assertEquals(2L, newKey.epoch)
        assertEquals(listOf(d.identity.id), newKey.removed)

        // D cannot open B's next snapshot, and was given no new key.
        val mailbox = store.mailbox(newKey.mailboxId, newKey.token)
        val newest = mailbox.list("snap/${b.identity.id.lower}/").max()
        val file = mailbox.get(newest)!!
        assertThrows<RemoteSyncException> { Envelope.open(file, Envelope.Kind.Snapshot, newest, oldKey.dataKey) }
        d.round(store)
        assertEquals(oldKey, d.key)
        assertFalse(d.snapshot.cellar.wines.values.any { it.name == "After removal" })

        // A follows B without rotating again: the new key leaves D out.
        a.round(store)
        assertEquals(newKey, a.key)
        assertTrue(a.snapshot.cellar.wines.values.any { it.name == "After removal" })
        b.round(store)
        assertEquals(newKey, b.key)
    }

    @Test
    fun aDeviceThatLeftCallsForNoRotation() = runBlocking {
        val store = MemoryMailboxStore()
        val (a, _, c) = start(3, store)
        // C leaves by itself: a deletion stamp of its own.
        c.remove(c)
        a.meet(c)
        assertNull(RemoteSync.nextRotation(a.key!!, null, a.snapshot.membership, a.identity.id))
    }

    @Test
    fun aRetriedRotationLeavesOneGrant() = runBlocking {
        val store = FullMailboxStore()
        store.full = false
        val (a, b, d) = start(3, store)
        a.remove(d)
        store.full = true
        // Each try leaves the grant, then fails to write the snapshots.
        repeat(5) {
            val error = assertThrows<RemoteSyncException> { runBlocking { a.round(store) } }
            assertEquals(RemoteSyncError.QuotaExceeded, error.error)
        }
        assertEquals(1, store.base.allFiles[b.identity.inboxId].ofEpoch(2))
        store.full = false
        a.round(store)
        b.round(store)
        assertEquals(a.key, b.key)
        assertEquals(2L, b.key?.epoch)
    }

    @Test
    fun aRecipientKeepsAtMostOneGrantOfAnEpoch() = runBlocking {
        val store = MemoryMailboxStore()
        val (a, b) = start(2, store)
        val next = CellarKey.random(a.key!!.cellarId, 2)
        // Rotations retried by a build that did not remember who has the key.
        repeat(5) { RemoteSync.grant(next, a.snapshot.membership.active, store, a.identity, null) }
        val inbox = b.identity.inboxId
        assertEquals(5, store.allFiles[inbox].ofEpoch(2))
        b.sync(store)
        assertEquals(next, b.key)
        assertTrue(store.allFiles[inbox].orEmpty().size <= 1)
        b.sync(store)
        assertEquals(0, store.allFiles[inbox].orEmpty().size)
    }

    @Test
    fun concurrentRemovalsEndOnAKeyNeitherRemovedDeviceHas() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = start(4, store)
        val (a, b, d, e) = devices
        val start = a.key!!
        val membership = d.snapshot.membership
        // A removes D and B removes E at the same time; A's key wins the tie on epoch 2.
        a.remove(d)
        b.remove(e)
        var keys = listOf(CellarKey.random(start.cellarId, 2), CellarKey.random(start.cellarId, 2))
        if (!keys[0].supersedes(keys[1])) keys = keys.reversed()
        for (index in 0..1) {
            val device = devices[index]
            val next = RemoteSync.nextRotation(start, PendingRotation(keys[index]), device.snapshot.membership, device.identity.id)!!
            device.key = RemoteSync.rotate(store, device.identity, start, next, device.snapshot, null).key
            device.progress = RemoteProgress()
        }
        assertEquals(listOf(d.identity.id), a.key?.removed)
        assertEquals(listOf(e.identity.id), b.key?.removed)
        repeat(3) {
            a.round(store)
            b.round(store)
        }
        val final = a.key!!
        assertEquals(final, b.key)
        assertEquals(3L, final.epoch)
        assertEquals(setOf(d.identity.id, e.identity.id), final.removed?.toSet())
        for (removed in listOf(d, e)) {
            assertFalse(removed.grantedKeys(store, membership).any { it.key.contentEquals(final.key) })
        }
    }

    @Test
    fun aStartedRotationLosingToTheAdoptedKeyIsDropped() = runBlocking {
        val store = MemoryMailboxStore()
        val (a, b, d) = start(3, store)
        val start = b.key!!
        a.remove(d)
        b.meet(a)
        // A rotates; B had started its own rotation, given to A only, and failed.
        a.round(store)
        val winner = a.key!!
        var losing = CellarKey.random(start.cellarId, 2)
        while (!winner.supersedes(losing)) losing = CellarKey.random(start.cellarId, 2)
        b.pending = PendingRotation(losing, granted = setOf(a.identity.id))
        // B adopts A's key, which leaves D out: no second rotation.
        b.round(store)
        assertEquals(winner, b.key)
        assertNull(b.pending)
        a.round(store)
        assertEquals(winner, a.key)
    }

    @Test
    fun aStartedRotationGivenToNobodyIsDroppedOnTheSameEpoch() {
        val (a, _, c) = household(3)
        a.remove(c)
        val key = CellarKey(a.snapshot.membership.cellarId, 2, ByteArray(32) { 1 }, listOf(c.identity.id))
        repeat(8) {
            val started = PendingRotation(CellarKey.random(key.cellarId, 2))
            assertNull(RemoteSync.nextRotation(key, started, a.snapshot.membership, a.identity.id))
        }
    }

    @Test
    fun keysFromAnEarlierBuildCallForNoRotation() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(4)
        val (a, b, c, d) = devices
        // A removed D before the update (and rotated then); the key lists no removals.
        val (membership, clock) = a.snapshot.membership.removing(d.identity.id, a.snapshot.state.clock, a.identity.id)
        a.snapshot = a.snapshot.copy(membership = membership, state = a.snapshot.state.copy(clock = clock))
        b.meet(a)
        c.meet(a)
        grantAll(devices, store, null)
        assertNull(a.key!!.removed)
        // A, updated, settles its own key when the app starts.
        a.keep(a.key!!)
        val first = a.key!!
        repeat(3) { for (device in listOf(a, b, c)) device.round(store) }
        for (device in listOf(a, b, c)) {
            assertTrue(device.key!!.key.contentEquals(first.key))
            assertEquals(listOf(d.identity.id), device.key?.removed)
        }
        // A build before the list sends the same key back without it: the list stays.
        val kept = RemoteSync.settled(first.copy(removed = null), b.key, b.snapshot.membership)
        assertEquals(listOf(d.identity.id), kept.removed)
        // Two lists for the same key bytes join.
        val joined = RemoteSync.settled(first.copy(removed = listOf(c.identity.id)), b.key, null)
        assertEquals(setOf(c.identity.id, d.identity.id), joined.removed?.toSet())
    }

    @Test
    fun anEarlierBuildRotatingDoesNotStartAStorm() = runBlocking {
        val store = MemoryMailboxStore()
        val (o, n, m, x) = start(4, store)
        val start = o.key!!
        // O (an earlier build) removes X and rotates the earlier way: a key without a list,
        // given to N, then it fails before M.
        val (membership, clock) = o.snapshot.membership.removing(x.identity.id, o.snapshot.state.clock, o.identity.id)
        o.snapshot = o.snapshot.copy(membership = membership, state = o.snapshot.state.copy(clock = clock))
        val old = CellarKey.random(start.cellarId, 2)
        RemoteSync.grant(old, listOf(o.snapshot.membership.members.getValue(n.identity.id)), store, o.identity, null)
        n.meet(o)
        n.round(store)
        assertTrue(n.key!!.key.contentEquals(old.key))
        assertEquals(listOf(x.identity.id), n.key?.removed)
        // O retries and finishes the earlier way; N and M settle on its key.
        RemoteSync.rotate(store, o.identity, start, old, o.snapshot, null)
        o.key = old
        repeat(3) { for (device in listOf(n, m)) device.round(store) }
        assertTrue(n.key!!.key.contentEquals(old.key))
        assertTrue(m.key!!.key.contentEquals(old.key))
        assertEquals(2L, m.key?.epoch)
    }

    @Test
    fun aDeviceWhoseGrantFailsDoesNotStopTheRotation() = runBlocking {
        val store = RefusingInboxStore()
        val (a, b, c, d) = start(4, store)
        a.remove(d)
        store.refused = setOf(c.identity.inboxId)
        a.edit { it.addBottles(Wine(name = "After removal"), 1) }
        a.round(store)
        val newKey = a.key!!
        assertEquals(2L, newKey.epoch)
        assertEquals(setOf(c.identity.id), a.pending?.failed)
        // B moves on and syncs; C is tried again once its inbox has room.
        b.round(store)
        assertEquals(newKey, b.key)
        assertTrue(b.snapshot.cellar.wines.values.any { it.name == "After removal" })
        // A device left without the key for several rounds is worth telling the user.
        repeat(PendingRotation.RETRIES_BEFORE_PROBLEM) { a.round(store) }
        assertTrue(a.pending?.isStuck == true)
        store.refused = emptySet()
        a.round(store)
        assertNull(a.pending)
        c.round(store)
        assertEquals(newKey, c.key)
    }

    @Test
    fun eachGrantIsReportedAsTheRotationGoes() = runBlocking {
        val store = MemoryMailboxStore()
        val (a, b, c) = start(3, store)
        a.remove(c)
        val next = RemoteSync.nextRotation(a.key!!, null, a.snapshot.membership, a.identity.id)!!
        val log = mutableListOf<PendingRotation>()
        val done = RemoteSync.rotate(store, a.identity, a.key!!, next, a.snapshot, null) { log += it }
        assertEquals(listOf(setOf(b.identity.id)), log.map { it.granted })
        assertEquals(setOf(b.identity.id), done.granted)
    }

    @Test
    fun aStorageChangeForgetsWhoWasGiven() {
        val pending = PendingRotation(CellarKey.random(UUID.randomUUID(), 2), setOf(UUID.randomUUID()), setOf(UUID.randomUUID()))
        val moved = pending.storageChanged()
        assertTrue(moved.granted.isEmpty() && moved.failed.isEmpty())
        // A rotation kept before `failed` existed still reads.
        val older = """{"key":${SyncJson.encode(CellarKey.serializer(), pending.key).decodeToString()},"granted":[]}"""
        assertEquals(pending.key, SyncJson.decode(PendingRotation.serializer(), older.toByteArray()).key)
    }

    @Test
    fun moreThan64RemovalsListTheNewestAndDoNotBounce() {
        val (a, b) = household(2)
        val me = a.identity.id
        val removed = List(70) { UUID.randomUUID() }
        for (id in removed) {
            val (membership, clock) = a.snapshot.membership.removing(id, a.snapshot.state.clock, me)
            a.snapshot = a.snapshot.copy(membership = membership, state = a.snapshot.state.copy(clock = clock))
        }
        val membership = a.snapshot.membership
        assertEquals(70, RemoteSync.shutOut(membership).size)
        val start = CellarKey(membership.cellarId, 1, ByteArray(32) { 1 }, emptyList())
        val next = RemoteSync.nextRotation(start, null, membership, me)!!
        // The newest 64 are listed, in the same order on every device.
        assertEquals(RemoteSync.MAX_REMOVED, next.key.removed?.size)
        assertEquals(removed.takeLast(RemoteSync.MAX_REMOVED).toSet(), next.key.removed?.toSet())
        assertEquals(next.key.removed, RemoteSync.removedList(removed.toSet(), null, membership))
        // That key calls for no other rotation; a new removal does.
        assertNull(RemoteSync.nextRotation(next.key, null, membership, me))
        a.remove(b)
        assertNotNull(RemoteSync.nextRotation(next.key, null, a.snapshot.membership, me))
    }

    @Test
    fun aRemovalLearnedLateWithAnOldCounterStillRotates() {
        val (a, b, c) = household(3)
        val key = CellarKey(a.snapshot.membership.cellarId, 1, ByteArray(32) { 1 }, emptyList())
        // B removed C long ago (a low counter); A learns it only now, after many changes.
        val (membership, _) = b.snapshot.membership.removing(c.identity.id, 1, b.identity.id)
        b.snapshot = b.snapshot.copy(membership = membership)
        a.snapshot = a.snapshot.copy(state = a.snapshot.state.copy(clock = 1000))
        a.meet(b)
        assertNotNull(RemoteSync.nextRotation(key, null, a.snapshot.membership, a.identity.id))
    }

    @Test
    fun anOldDevicesKeyAdoptedAfterAnUnknownRemovalIsRotated() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = start(5, store)
        val (n1, o, n2, x, y) = devices
        val start = n1.key!!
        // N1 (no storage) removes X; at the same time O, an earlier build, removes Y and
        // rotates the earlier way, giving its key to X too (it has not heard of that removal).
        n1.remove(x)
        val (membership, clock) = o.snapshot.membership.removing(y.identity.id, o.snapshot.state.clock, o.identity.id)
        o.snapshot = o.snapshot.copy(membership = membership, state = o.snapshot.state.copy(clock = clock))
        val old = CellarKey.random(start.cellarId, 2)
        RemoteSync.rotate(store, o.identity, start, old, o.snapshot, null)
        val xMembership = x.snapshot.membership
        assertTrue(x.grantedKeys(store, xMembership).any { it.key.contentEquals(old.key) })
        // N2 knows both removals: O's key leaves out only Y, so N2 rotates.
        n2.meet(n1)
        n2.meet(o)
        n2.round(store)
        val final = n2.key!!
        assertEquals(3L, final.epoch)
        assertTrue(final.removed.orEmpty().containsAll(listOf(x.identity.id, y.identity.id)))
        assertFalse(x.grantedKeys(store, xMembership).any { it.key.contentEquals(final.key) })
    }

    @Test
    fun aKeyFromAnEarlierBuildIsSettledBeforeALocalNetworkRemoval() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(3)
        val (a, b, d) = devices
        // A was updated: its stored key, without a list, is settled when the app starts.
        a.keep(a.key!!)
        assertEquals(emptyList<UUID>(), a.key?.removed)
        grantAll(devices, store, null)
        // B removes D; A hears of it on the local network before its first round.
        b.remove(d)
        a.meet(b)
        a.round(store)
        assertEquals(2L, a.key?.epoch)
        assertEquals(listOf(d.identity.id), a.key?.removed)
    }

    @Test
    fun aRemovedDeviceNeverRotates() {
        val (a, _, c) = household(3)
        a.remove(c)
        c.meet(a)
        val key = CellarKey(a.snapshot.membership.cellarId, 1, ByteArray(32) { 1 }, emptyList())
        assertNull(RemoteSync.nextRotation(key, null, c.snapshot.membership, c.identity.id))
        assertNotNull(RemoteSync.nextRotation(key, null, a.snapshot.membership, a.identity.id))
    }

    @Test
    fun noRotationPastTheLastEpoch() {
        val (a, _, c) = household(3)
        a.remove(c)
        val last = CellarKey(a.snapshot.membership.cellarId, CellarKey.MAX_EPOCH, ByteArray(32) { 1 }, emptyList())
        val error = assertThrows<RemoteSyncException> { RemoteSync.nextRotation(last, null, a.snapshot.membership, a.identity.id) }
        assertEquals(RemoteSyncError.Corrupted("epoch"), error.error)
    }

    @Test
    fun anEpochOutsideUInt32IsRefused() {
        val cellar = UUID.fromString("33333333-3333-4333-8333-333333333333")
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        for (epoch in listOf("-1", "4294967296", "9223372036854775807")) {
            val json = """{"cellarID":"$cellar","epoch":$epoch,"key":"$key"}"""
            assertTrue(runCatching { SyncJson.decode(CellarKey.serializer(), json.toByteArray()) }.isFailure, epoch)
        }
        assertEquals(CellarKey.MAX_EPOCH, SyncJson.decode(CellarKey.serializer(), """{"cellarID":"$cellar","epoch":4294967295,"key":"$key"}""".toByteArray()).epoch)
        assertThrows<IllegalArgumentException> { CellarKey(cellar, -1, ByteArray(32)) }
    }

    @Test
    fun theRemovedListTravelsWithTheKey() {
        val removed = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val key = CellarKey(UUID.fromString("33333333-3333-4333-8333-333333333333"), 2, ByteArray(32) { 1 }, listOf(removed))
        val json = SyncJson.encode(CellarKey.serializer(), key).decodeToString()
        assertEquals(
            """{"cellarID":"33333333-3333-4333-8333-333333333333","epoch":2,""" +
                """"key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","removed":["44444444-4444-4444-8444-444444444444"]}""",
            json,
        )
        assertEquals(key, SyncJson.decode(CellarKey.serializer(), json.toByteArray()))
        // A key from a build before the list existed still reads.
        val old = """{"cellarID":"33333333-3333-4333-8333-333333333333","epoch":2,"key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}"""
        assertEquals(key.copy(removed = null), SyncJson.decode(CellarKey.serializer(), old.toByteArray()))
    }
}
