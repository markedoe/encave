package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.Wine
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** An in-memory connection: frames written on one end are read on the other. */
class Pipe private constructor(private val inbox: Channel<ByteArray>) : FrameTransport {
    lateinit var other: Pipe

    /** Lets a test change frames on their way (an attacker on the network). */
    var tamper: ((ByteArray) -> ByteArray)? = null

    override suspend fun send(frame: ByteArray) {
        try {
            other.inbox.send(tamper?.invoke(frame) ?: frame)
        } catch (_: Exception) {
            throw SyncException(SyncError.Closed)
        }
    }

    override suspend fun receive(): ByteArray =
        inbox.receiveCatching().getOrNull() ?: throw SyncException(SyncError.Closed)

    override fun close() {
        inbox.close()
        other.inbox.close()
    }

    companion object {
        fun pair(): Pair<Pipe, Pipe> {
            val a = Pipe(Channel(Channel.UNLIMITED))
            val b = Pipe(Channel(Channel.UNLIMITED))
            a.other = b
            b.other = a
            return a to b
        }
    }
}

/** Photos of a test device. */
class PhotoBox(initial: Map<String, ByteArray> = emptyMap()) : PhotoAccess {
    private val photos = initial.toMutableMap()
    val stored: Map<String, ByteArray> get() = synchronized(photos) { photos.toMap() }
    override fun data(id: String): ByteArray? = synchronized(photos) { photos[id] }
    override fun save(id: String, data: ByteArray) {
        synchronized(photos) { photos[id] = data }
    }
}

internal fun identity(): DeviceIdentity = DeviceIdentity.generate().first

/** Runs [block] and returns the [SyncError] it threw, or null. */
internal suspend fun syncErrorOf(block: suspend () -> Unit): SyncError? = try {
    block()
    null
} catch (error: SyncException) {
    error.error
}

class SyncProtocolTests {
    @Test
    fun pairingGivesBothDevicesTheSameCodeAndTheCellar() = runBlocking {
        val (hostEnd, joinerEnd) = Pipe.pair()
        val hostId = identity()
        val joinerId = identity()
        val host = PairingHost(hostId, "iPhone", "ios")
        val joiner = PairingJoiner(joinerId, "Pixel", "android", expectedCommitment = host.commitment)
        val membership = Membership()
        coroutineScope {
            val hk = async { host.exchangeKeys(hostEnd) }
            val jk = async { joiner.exchangeKeys(joinerEnd) }
            val (hostKeys, joinerKeys) = hk.await() to jk.await()
            assertEquals(hostKeys.confirmationCode, joinerKeys.confirmationCode)
            assertEquals(7, hostKeys.confirmationCode.length)
            assertTrue(hostKeys.peer.name == "Pixel" && joinerKeys.peer.name == "iPhone")

            val hostDone = async { host.finish(hostEnd, hostKeys, true, membership) }
            val joinerDone = async { joiner.finish(joinerEnd, joinerKeys, true) }
            val (_, newMember) = hostDone.await()
            val (_, received) = joinerDone.await()
            assertArrayEquals(joinerId.publicKey, newMember.publicKey)
            assertEquals(membership.cellarId, received.cellarId)
            assertArrayEquals(hostId.publicKey, received.members[hostId.id]?.publicKey)
        }
    }

    @Test
    fun aRejectedCodeStopsThePairing() = runBlocking {
        val (hostEnd, joinerEnd) = Pipe.pair()
        val host = PairingHost(identity(), "A", "ios")
        val joiner = PairingJoiner(identity(), "B", "android")
        coroutineScope {
            val hk = async { host.exchangeKeys(hostEnd) }
            val jk = async { joiner.exchangeKeys(joinerEnd) }
            val hostKeys = hk.await()
            val joinerKeys = jk.await()
            val hostDone = async { syncErrorOf { host.finish(hostEnd, hostKeys, true, Membership()) } }
            val joinerDone = async { syncErrorOf { joiner.finish(joinerEnd, joinerKeys, false) } }
            assertEquals(SyncError.Rejected, hostDone.await())
            assertEquals(SyncError.Rejected, joinerDone.await())
        }
    }

    @Test
    fun aScannedQrCodeDetectsAnotherDevice() = runBlocking {
        val (hostEnd, joinerEnd) = Pipe.pair()
        val real = PairingHost(identity(), "Real", "ios")
        val impostor = PairingHost(identity(), "Impostor", "ios")
        val joiner = PairingJoiner(identity(), "Pixel", "android", expectedCommitment = real.commitment)
        coroutineScope {
            val hk = async { syncErrorOf { impostor.exchangeKeys(hostEnd) } }
            assertEquals(SyncError.AuthenticationFailed, syncErrorOf { joiner.exchangeKeys(joinerEnd) })
            hostEnd.close()
            hk.await()
        }
        Unit
    }

    @Test
    fun someoneInTheMiddleSeesDifferentCodes() = runBlocking {
        // Host ↔ attacker ↔ joiner: the attacker pairs with each side separately.
        val (hostEnd, attackerToHost) = Pipe.pair()
        val (attackerToJoiner, joinerEnd) = Pipe.pair()
        val host = PairingHost(identity(), "A", "ios")
        val joiner = PairingJoiner(identity(), "B", "android")
        val fakeJoiner = PairingJoiner(identity(), "B", "android")
        val fakeHost = PairingHost(identity(), "A", "ios")
        coroutineScope {
            val h = async { host.exchangeKeys(hostEnd) }
            val fj = async { fakeJoiner.exchangeKeys(attackerToHost) }
            val fh = async { fakeHost.exchangeKeys(attackerToJoiner) }
            val j = async { joiner.exchangeKeys(joinerEnd) }
            fj.await()
            fh.await()
            // Each side shows a code; they differ unless the attacker is very lucky (1 in a million).
            assertNotEquals(h.await().confirmationCode, j.await().confirmationCode)
        }
    }

    @Test
    fun pairingCodeRoundTripsThroughTheQrCode() {
        val code = PairingCode.random()
        val commitment = ByteArray(32) { it.toByte() }
        val parsed = PairingCode.parse(code.qrPayload(commitment))
        assertEquals(code, parsed?.first)
        assertArrayEquals(commitment, parsed?.second)
        assertNull(PairingCode.parse("https://example.com"))
        assertEquals(16, code.rendezvous.length)
        assertEquals("123456", PairingCode("123 456").digits)
    }

    // region Sync sessions

    private data class Members(val a: DeviceIdentity, val b: DeviceIdentity, val membership: Membership)

    private fun members(): Members {
        val a = identity()
        val b = identity()
        var membership = Membership()
        var clock = 0L
        membership.adding(Member(a.id, "A", a.publicKey, "ios"), clock, a.id).let { (m, c) -> membership = m; clock = c }
        membership.adding(Member(b.id, "B", b.publicKey, "android"), clock, a.id).let { (m, _) -> membership = m }
        return Members(a, b, membership)
    }

    @Test
    fun membersOpenASessionAndSyncTheirCellars() = runBlocking {
        val (a, b, membership) = members()
        val cellarA = Cellar().apply { addBottles(Wine(name = "From A", photoId = "photo-a"), 2) }
        val cellarB = Cellar().apply { addBottles(Wine(name = "From B"), 1) }
        val snapshotA = SyncSnapshot(cellarA, SyncState.starting(cellarA, a.id), membership)
        val snapshotB = SyncSnapshot(cellarB, SyncState.starting(cellarB, b.id), membership)
        val photosA = PhotoBox(mapOf("photo-a" to "jpeg".toByteArray()))
        val photosB = PhotoBox()
        val (endA, endB) = Pipe.pair()
        coroutineScope {
            val sideA = async {
                val (channel, peer) = SyncHandshake.initiate(endA, a, membership)
                assertEquals(b.id, peer.id)
                SyncExchange.run(channel, true, snapshotA, photosA)
            }
            val sideB = async {
                val (channel, peer) = SyncHandshake.respond(endB, b, membership)
                assertEquals(a.id, peer.id)
                SyncExchange.run(channel, false, snapshotB, photosB)
            }
            val resultA = sideA.await()
            val resultB = sideB.await()
            assertEquals(resultA.cellar, resultB.cellar)
            assertEquals(3, resultA.cellar.bottles.size)
            assertArrayEquals("jpeg".toByteArray(), photosB.stored["photo-a"])
            assertTrue(resultA.state.device == a.id && resultB.state.device == b.id)
        }
    }

    @Test
    fun theRemoteKeyIsPassedOnDuringASync() = runBlocking {
        val (a, b, membership) = members()
        val snapshotA = SyncSnapshot(Cellar(), SyncState(device = a.id), membership)
        val snapshotB = SyncSnapshot(Cellar(), SyncState(device = b.id), membership)
        val info = RemoteInfo(CellarKey.random(membership.cellarId), "https://relay.example")
        val (endA, endB) = Pipe.pair()
        coroutineScope {
            val sideA = async {
                val (channel, _) = SyncHandshake.initiate(endA, a, membership)
                SyncExchange.exchange(channel, true, snapshotA, PhotoAccess.NONE, info)
            }
            val (channel, _) = SyncHandshake.respond(endB, b, membership)
            val resultB = SyncExchange.exchange(channel, false, snapshotB, PhotoAccess.NONE, null)
            assertEquals(info, resultB.second)
            assertNull(sideA.await().second)
        }
    }

    @Test
    fun aRemovedDeviceCannotConnect() = runBlocking {
        val (a, b, original) = members()
        val (membership, _) = original.removing(b.id, 10, a.id)
        val (endB, endA) = Pipe.pair()
        coroutineScope {
            val attempt = async { syncErrorOf { SyncHandshake.initiate(endB, b, original) } }
            assertEquals(SyncError.RemovedDevice, syncErrorOf { SyncHandshake.respond(endA, a, membership) })
            // The removed device is told why.
            assertEquals(SyncError.RemovedDevice, attempt.await())
        }
    }

    @Test
    fun aDeviceNotKnownYetIsNotToldItWasRemoved() = runBlocking {
        val (a, b, membership) = members()
        // C was added on A, but B has not heard of it yet.
        val c = identity()
        val (newer, _) = membership.adding(Member(c.id, "C", c.publicKey, "android"), 10, a.id)
        val (endC, endB) = Pipe.pair()
        coroutineScope {
            val attempt = async { syncErrorOf { SyncHandshake.initiate(endC, c, newer) } }
            assertEquals(SyncError.UnknownDevice, syncErrorOf { SyncHandshake.respond(endB, b, membership) })
            assertEquals(SyncError.UnknownDevice, attempt.await())
        }
    }

    @Test
    fun aDeviceWithAnotherKeyIsRefused() = runBlocking {
        val (a, b, membership) = members()
        // Someone who knows B's device ID but not its key.
        val fakeB = DeviceIdentity(b.id, X25519Key.generate().rawRepresentation)
        val (endB, endA) = Pipe.pair()
        coroutineScope {
            val attacker = async { syncErrorOf { SyncHandshake.initiate(endB, fakeB, membership) } }
            val responder = async { runCatching { SyncHandshake.respond(endA, a, membership) } }
            // The impostor cannot check A's proof (it fails first), then the session is closed.
            assertEquals(SyncError.AuthenticationFailed, attacker.await())
            endA.close()
            assertTrue(responder.await().isFailure)
        }
    }

    @Test
    fun aChangedFrameIsDetected() = runBlocking {
        val (a, b, membership) = members()
        val (endA, endB) = Pipe.pair()
        coroutineScope {
            val sideA = async { SyncHandshake.initiate(endA, a, membership) }
            val (channelB, _) = SyncHandshake.respond(endB, b, membership)
            val (channelA, _) = sideA.await()
            endA.tamper = { frame -> frame.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } }
            channelA.send(WireMessage("photos-end"))
            assertEquals(SyncError.AuthenticationFailed, syncErrorOf { channelB.receive() })
        }
    }

    @Test
    fun afterPairingAndTheFirstSyncBothDevicesListTheSameDevices() = runBlocking {
        val (hostEnd, joinerEnd) = Pipe.pair()
        val hostId = identity()
        val joinerId = identity()
        val host = PairingHost(hostId, "iPhone", "ios")
        val joiner = PairingJoiner(joinerId, "Pixel", "android")
        // The host's cellar already lists the host.
        var hostState = SyncState(device = hostId.id)
        val (hostMembers, clock) = Membership().adding(Member(hostId.id, "iPhone", hostId.publicKey, "ios"), hostState.clock, hostId.id)
        hostState = hostState.copy(clock = clock)
        val cellar = Cellar().apply { addBottles(Wine(name = "Shared"), 1) }
        hostState = hostState.recording(Cellar(), cellar)
        coroutineScope {
            val hk = async { host.exchangeKeys(hostEnd) }
            val jk = async { joiner.exchangeKeys(joinerEnd) }
            val hostKeys = hk.await()
            val joinerKeys = jk.await()
            val hostSide = async {
                val (channel, member) = host.finish(hostEnd, hostKeys, true, hostMembers)
                val (members, next) = hostMembers.adding(member, hostState.clock, hostId.id)
                SyncExchange.run(channel, true, SyncSnapshot(cellar, hostState.copy(clock = next), members), PhotoAccess.NONE)
            }
            val joinerSide = async {
                val (channel, received) = joiner.finish(joinerEnd, joinerKeys, true)
                val start = received.stamps.values.maxOfOrNull { it.counter } ?: 0
                val (members, next) = received.adding(Member(joinerId.id, "Pixel", joinerId.publicKey, "android"), start, joinerId.id)
                SyncExchange.run(channel, false, SyncSnapshot(Cellar(), SyncState(device = joinerId.id, clock = next), members), PhotoAccess.NONE)
            }
            val resultHost = hostSide.await()
            val resultJoiner = joinerSide.await()
            assertEquals(resultHost.membership, resultJoiner.membership)
            assertEquals(setOf(hostId.id, joinerId.id), resultHost.membership.members.keys)
            assertArrayEquals(joinerId.publicKey, resultHost.membership.members[joinerId.id]?.publicKey)
            assertEquals(resultHost.cellar, resultJoiner.cellar)
        }
    }

    @Test
    fun membershipMergeKeepsRemovals() {
        val (a, b, membership) = members()
        val (removed, _) = membership.removing(b.id, 5, a.id)
        assertNull(membership.merged(removed).members[b.id])
        assertNull(removed.merged(membership).members[b.id])
        assertTrue(membership.merged(removed).members[a.id] != null)
    }

    @Test
    fun photoIdsMatchTheirContent() {
        val data = "label".toByteArray()
        val id = PhotoId.make(data)
        assertTrue(PhotoId.matches(id, data))
        assertFalse(PhotoId.matches(id, "other".toByteArray()))
        assertTrue(PhotoId.matches("legacy-uuid", data))
        assertFalse(PhotoId.matches("../escape", data))
        assertNotEquals(UUID.randomUUID(), UUID.randomUUID())
    }

    // endregion
}
