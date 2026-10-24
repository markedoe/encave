package app.encave.core.sync

import app.encave.core.Wine
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What encryption must refuse (docs/SYNC.md §4 and §8): damaged or moved files, forged
 * grants, snapshots under another device's name, and replayed or reflected frames.
 * Mirrors CaveKit's CryptoFailureTests.swift.
 */
class CryptoFailureTests {
    /** Frames sent are kept; frames received come from a queue the test fills. */
    private class FrameQueue(vararg incoming: ByteArray) : FrameTransport {
        val sent = mutableListOf<ByteArray>()
        val incoming = ArrayDeque(incoming.toList())
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun receive(): ByteArray = incoming.removeFirstOrNull() ?: throw SyncException(SyncError.Closed)
        override fun close() {}
    }

    private fun assertCorrupted(path: String, open: () -> Unit) {
        val error = assertThrows<RemoteSyncException> { open() }
        assertEquals(RemoteSyncError.Corrupted(path), error.error)
    }

    @Test
    fun envelopeFailures() {
        val key = CellarKey.random(UUID.randomUUID())
        val other = CellarKey.random(key.cellarId)
        val sealed = Envelope.seal("x".toByteArray(), Envelope.Kind.Photo, "photo/a", key.dataKey)
        assertArrayEquals("x".toByteArray(), Envelope.open(sealed, Envelope.Kind.Photo, "photo/a", key.dataKey))

        assertCorrupted("photo/a") { Envelope.open(sealed, Envelope.Kind.Photo, "photo/a", other.dataKey) }
        assertCorrupted("photo/a") { Envelope.open(sealed, Envelope.Kind.Snapshot, "photo/a", key.dataKey) }
        assertCorrupted("photo/a") { Envelope.open(sealed.copyOf(sealed.size - 1), Envelope.Kind.Photo, "photo/a", key.dataKey) }
        val version = sealed.copyOf().also { it[4] = 2 }
        assertCorrupted("photo/a") { Envelope.open(version, Envelope.Kind.Photo, "photo/a", key.dataKey) }
        // Header (6) and nonce and tag (28), nothing else: refused before decrypting.
        assertCorrupted("photo/a") { Envelope.open(sealed.copyOf(34), Envelope.Kind.Photo, "photo/a", key.dataKey) }
        // A longer file with its last byte dropped passes the size check and fails the tag.
        val hello = Envelope.seal("hello".toByteArray(), Envelope.Kind.Photo, "photo/a", key.dataKey)
        assertCorrupted("photo/a") { Envelope.open(hello.copyOf(hello.size - 1), Envelope.Kind.Photo, "photo/a", key.dataKey) }
    }

    /** A grant file built by hand, as KeyGrant.sealed does, for any path and content. */
    private fun sealGrant(grant: KeyGrant, path: String, sender: DeviceIdentity, recipient: ByteArray): ByteArray {
        val ephemeral = X25519Key.generate()
        val key = KeyGrant.key(path, ephemeral.publicKey, recipient, ephemeral.agree(recipient), sender.privateKey.agree(recipient))
        return ephemeral.publicKey + Envelope.seal(SyncJson.encode(KeyGrant.serializer(), grant), Envelope.Kind.Grant, path, key)
    }

    @Test
    fun grantForgedByAnotherMember() {
        val devices = household()
        val (m, s, recipient) = devices.map { it.identity }
        val membership = devices[2].snapshot.membership
        val cellarKey = CellarKey.random(membership.cellarId, 2)
        fun open(path: String, data: ByteArray, by: DeviceIdentity = recipient) = KeyGrant.open(data, path, by, membership)

        // The hand-built grant opens, so the failures below come from what was changed.
        val path = GrantPath(membership.cellarId, 2, m.id).string
        assertEquals(KeyGrant(cellarKey, m.id, null), open(path, sealGrant(KeyGrant(cellarKey, m.id, null), path, m, recipient.publicKey)))

        // M seals a grant claiming to be from S: the path names S, whose key did not seal it.
        val (forgedPath, forged) = KeyGrant(cellarKey, s.id, null).sealed(m, recipient.publicKey)
        assertTrue(forgedPath.contains(s.id.toString()))
        assertCorrupted(forgedPath) { open(forgedPath, forged) }
        // Under M's own name, with S in the content.
        assertCorrupted(path) { open(path, sealGrant(KeyGrant(cellarKey, s.id, null), path, m, recipient.publicKey)) }

        // Sealed for S, opened by the recipient.
        val (forS, toS) = KeyGrant(cellarKey, m.id, null).sealed(m, s.publicKey)
        assertCorrupted(forS) { open(forS, toS) }

        // A grant moved to another name (another epoch, another cellar) no longer opens.
        val (grantPath, grant) = KeyGrant(cellarKey, m.id, null).sealed(m, recipient.publicKey)
        val suffix = grantPath.takeLast(8)
        val otherEpoch = GrantPath(membership.cellarId, 3, m.id, suffix).string
        assertCorrupted(otherEpoch) { open(otherEpoch, grant) }
        // Names that disagree with the content, sealed under those names.
        val epochName = GrantPath(membership.cellarId, 3, m.id).string
        assertCorrupted(epochName) { open(epochName, sealGrant(KeyGrant(cellarKey, m.id, null), epochName, m, recipient.publicKey)) }
        val otherCellar = UUID.randomUUID()
        val cellarName = GrantPath(otherCellar, 2, m.id).string
        assertCorrupted(cellarName) { open(cellarName, sealGrant(KeyGrant(cellarKey, m.id, null), cellarName, m, recipient.publicKey)) }
        // Name and content agree, but for another cellar than the recipient's.
        val foreign = CellarKey.random(otherCellar, 2)
        assertCorrupted(cellarName) { open(cellarName, sealGrant(KeyGrant(foreign, m.id, null), cellarName, m, recipient.publicKey)) }
    }

    @Test
    fun snapshotUnderAnotherDevicesName() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household()
        grantAll(devices, store, null)
        repeat(2) { devices.forEach { it.sync(store) } }
        val (a, b, c) = devices
        val key = a.key!!
        val mailbox = store.mailbox(key.mailboxId, key.token)
        // A has read everything there is.
        a.sync(store)
        val before = a.snapshot.cellar

        // C writes, with the right key, a snapshot of its own under B's name.
        c.edit { it.addBottles(Wine(name = "Planted by C"), 1) }
        val clock = c.snapshot.state.clock + 100
        val planted = c.snapshot.copy(state = c.snapshot.state.copy(clock = clock))
        RemoteSync.write(planted, mailbox, key, b.identity.id)
        assertUnreadable(store, a, b.identity.id, before)

        // B's name and device, with the membership of another cellar.
        val otherCellar = planted.copy(
            state = planted.state.copy(device = b.identity.id, clock = clock + 1),
            membership = planted.membership.copy(cellarId = UUID.randomUUID()),
        )
        RemoteSync.write(otherCellar, mailbox, key, b.identity.id)
        assertUnreadable(store, a, b.identity.id, before)

        // The same snapshot under C's own name is read.
        RemoteSync.write(planted, mailbox, key, c.identity.id)
        val result = RemoteSync.run(store, a.identity, key, a.snapshot, a.progress, a.photos)
        assertEquals(listOf(b.identity.id), result.unreadable)
        assertTrue(result.snapshot!!.cellar.wines.values.any { it.name == "Planted by C" })
    }

    private suspend fun assertUnreadable(store: MailboxStore, reader: RemoteDevice, device: UUID, before: app.encave.core.Cellar) {
        val result = RemoteSync.run(store, reader.identity, reader.key!!, reader.snapshot, reader.progress, reader.photos)
        assertEquals(listOf(device), result.unreadable)
        assertNull(result.snapshot)
        assertEquals(before, reader.snapshot.cellar)
        assertEquals(reader.progress.seen[device.lower], result.progress.seen[device.lower])
    }

    @Test
    fun aReplayedFrameIsRefused() = runBlocking {
        val i2r = KeySchedule.randomNonce()
        val r2i = KeySchedule.randomNonce()
        val sender = FrameQueue()
        val initiator = SecureChannel(sender, i2r, r2i)
        initiator.send(WireMessage("photos-end"))
        initiator.send(WireMessage("photos-want"))
        val (first, second) = sender.sent

        // In order, both arrive.
        val inOrder = SecureChannel(FrameQueue(first, second), r2i, i2r)
        assertEquals("photos-end", inOrder.receive().type)
        assertEquals("photos-want", inOrder.receive().type)

        fun refused(channel: SecureChannel) = runBlocking {
            val error = assertThrows<SyncException> { runBlocking { channel.receive() } }
            assertEquals(SyncError.AuthenticationFailed, error.error)
        }
        // Replayed: the first frame again.
        val replayed = SecureChannel(FrameQueue(first, first), r2i, i2r)
        assertEquals("photos-end", replayed.receive().type)
        refused(replayed)
        // Reordered: frame 1 before frame 0.
        refused(SecureChannel(FrameQueue(second, first), r2i, i2r))
        // Reflected: the initiator's own frame fed back to it as if the responder sent it.
        sender.incoming.addLast(first)
        refused(initiator)
    }
}
