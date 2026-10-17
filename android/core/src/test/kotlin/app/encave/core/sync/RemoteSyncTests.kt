package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.Wine
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A device for remote sync tests: its cellar, identity and remote state. Mirrors CaveKit's RemoteSyncTests. */
private class RemoteDevice(val identity: DeviceIdentity, var snapshot: SyncSnapshot, var key: CellarKey?) {
    var progress = RemoteProgress()
    val photos = PhotoBox()

    fun edit(change: (Cellar) -> Unit) {
        val before = snapshot.cellar.copy()
        val cellar = snapshot.cellar.copy().also(change)
        snapshot = snapshot.copy(cellar = cellar, state = snapshot.state.recording(before, cellar))
    }

    suspend fun sync(store: MailboxStore) {
        if (key == null) key = RemoteSync.receiveGrant(store, identity, snapshot.membership, 0)?.cellarKey
        val key = key ?: return
        val result = RemoteSync.run(store, identity, key, snapshot, progress, photos)
        result.snapshot?.let { snapshot = it }
        result.newKey?.let { this.key = it }
        progress = result.progress
    }
}

/** Devices of one cellar, the first holding the key. */
private fun household(count: Int = 3): List<RemoteDevice> {
    val identities = List(count) { identity() }
    var membership = Membership()
    var clock = 0L
    identities.forEachIndexed { index, identity ->
        val (m, c) = membership.adding(Member(identity.id, "Device $index", identity.publicKey, "android"), clock, identities[0].id)
        membership = m
        clock = c
    }
    val key = CellarKey.random(membership.cellarId)
    return identities.mapIndexed { index, identity ->
        RemoteDevice(identity, SyncSnapshot(Cellar(), SyncState(device = identity.id, clock = clock), membership), if (index == 0) key else null)
    }
}

private suspend fun grantAll(devices: List<RemoteDevice>, store: MailboxStore, storage: String?) =
    RemoteSync.grant(devices[0].key!!, devices[0].snapshot.membership.active, store, devices[0].identity, storage)

class RemoteSyncTests {
    @Test
    fun aChangeReachesTheOtherDevicesThroughStorage() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household()
        grantAll(devices, store, "test")
        val label = "label".toByteArray()
        val hashed = PhotoId.make(label)
        val legacy = UUID.randomUUID().toString().uppercase()
        devices[0].edit { it.addBottles(Wine(name = "Château Test", photoId = hashed), 2) }
        devices[0].edit { it.addBottles(Wine(name = "Older photo", photoId = legacy), 1) }
        devices[0].photos.save(hashed, label)
        devices[0].photos.save(legacy, "old".toByteArray())
        devices.forEach { it.sync(store) }
        assertEquals(devices[0].snapshot.cellar, devices[1].snapshot.cellar)
        assertEquals(3, devices[2].snapshot.cellar.bottles.size)
        assertArrayEquals(label, devices[1].photos.stored[hashed])
        // A photo from before sync (uppercase UUID) round-trips too.
        assertArrayEquals("old".toByteArray(), devices[1].photos.stored[legacy])
    }

    @Test
    fun editsMadeApartMerge() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        grantAll(devices, store, null)
        devices[0].edit { it.addBottles(Wine(name = "From A"), 1) }
        devices[1].edit { it.addBottles(Wine(name = "From B"), 1) }
        repeat(2) { devices.forEach { it.sync(store) } }
        assertEquals(devices[0].snapshot.cellar, devices[1].snapshot.cellar)
        assertEquals(2, devices[0].snapshot.cellar.wines.size)
    }

    @Test
    fun storageHoldsOnlyCiphertext() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        devices[0].edit { it.addBottles(Wine(name = "Very Secret Cuvée", producer = "Domaine Privé"), 1) }
        devices[0].sync(store)
        for ((_, files) in store.allFiles) {
            for ((path, data) in files) {
                assertFalse(path.contains("secret"))
                val text = data.toString(Charsets.ISO_8859_1)
                assertFalse(text.contains("Secret") || text.contains("Domaine"))
            }
        }
    }

    @Test
    fun aRemovedDeviceIsLeftBehind() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household()
        grantAll(devices, store, null)
        devices.forEach { it.sync(store) }
        val oldKey = devices[2].key!!

        // A removes C and rotates the key.
        val a = devices[0]
        val (membership, clock) = a.snapshot.membership.removing(devices[2].identity.id, a.snapshot.state.clock, a.identity.id)
        a.snapshot = a.snapshot.copy(membership = membership, state = a.snapshot.state.copy(clock = clock))
        a.key = RemoteSync.rotate(store, a.identity, oldKey, a.snapshot, null)
        a.edit { it.addBottles(Wine(name = "After removal"), 1) }
        a.sync(store)

        // B finds the new key and the new bottle.
        devices[1].sync(store)
        assertEquals(2L, devices[1].key?.epoch)
        assertTrue(devices[1].snapshot.cellar.wines.values.any { it.name == "After removal" })

        // C only has the old key: it learns it was removed, and sees nothing new.
        devices[2].sync(store)
        assertEquals(oldKey, devices[2].key)
        assertNull(devices[2].snapshot.membership.members[devices[2].identity.id])
        assertFalse(devices[2].snapshot.cellar.wines.values.any { it.name == "After removal" })
    }

    @Test
    fun grantsFromStrangersAreIgnored() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(2)
        val stranger = identity()
        val fake = CellarKey.random(devices[1].snapshot.membership.cellarId, 9)
        RemoteSync.grant(fake, listOf(devices[1].snapshot.membership.members.getValue(devices[1].identity.id)), store, stranger, "https://evil.example")
        assertNull(RemoteSync.receiveGrant(store, devices[1].identity, devices[1].snapshot.membership, 0))
    }

    @Test
    fun aFileMovedToAnotherNameIsRejected() {
        val key = CellarKey.random(UUID.randomUUID())
        val sealed = Envelope.seal("x".toByteArray(), Envelope.Kind.Photo, "photo/a", key.dataKey)
        val error = assertThrows<RemoteSyncException> { Envelope.open(sealed, Envelope.Kind.Photo, "photo/b", key.dataKey) }
        assertEquals(RemoteSyncError.Corrupted("photo/b"), error.error)
        assertArrayEquals("x".toByteArray(), Envelope.open(sealed, Envelope.Kind.Photo, "photo/a", key.dataKey))
    }

    @Test
    fun aFolderWorksAsStorage() = runBlocking {
        val root = Files.createTempDirectory("encave").toFile()
        try {
            val store = FolderMailboxStore(root)
            val devices = household(2)
            grantAll(devices, store, null)
            devices[0].edit { it.addBottles(Wine(name = "In a folder"), 3) }
            devices[0].sync(store)
            devices[1].sync(store)
            assertEquals(3, devices[1].snapshot.cellar.bottles.size)
            val mailbox = store.mailbox("x", "")
            assertTrue(mailbox.create("a/b", byteArrayOf(1)))
            assertFalse(mailbox.create("a/b", byteArrayOf(2)), "files are never overwritten")
            assertTrue(runCatching { mailbox.create("../escape", ByteArray(0)) }.exceptionOrNull() is RemoteSyncException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pathsAreRestricted() {
        assertTrue(RelayPath.isValid("snap/0a-b/0000000000000001-ab12cd34"))
        assertFalse(RelayPath.isValid("../x"))
        assertFalse(RelayPath.isValid("a//b"))
        assertFalse(RelayPath.isValid("/a"))
        assertFalse(RelayPath.isValid("A"))
    }

    @Test
    fun oldSnapshotsAreCleanedUp() = runBlocking {
        val store = MemoryMailboxStore()
        val devices = household(1)
        repeat(5) { index ->
            devices[0].edit { it.addBottles(Wine(name = "W$index"), 1) }
            devices[0].sync(store)
        }
        val key = devices[0].key!!
        assertEquals(RemoteSync.KEPT_SNAPSHOTS, store.mailbox(key.mailboxId, key.token).list("snap/").size)
    }

    @Test
    fun relayAddressesMustBeHttpsOrLocal() {
        assertTrue(RelayMailboxStore.isAllowed("https://relay.example"))
        assertTrue(RelayMailboxStore.isAllowed("http://localhost:8787"))
        assertTrue(RelayMailboxStore.isAllowed("http://cellar.local"))
        assertFalse(RelayMailboxStore.isAllowed("http://relay.example"))
        assertFalse(RelayMailboxStore.isAllowed("ftp://relay.example"))
        assertFalse(RelayMailboxStore.isAllowed("relay"))
    }

    /** Runs against a real relay when ENCAVE_RELAY_URL is set (the CI starts relay/server.mjs). */
    @Test
    fun syncThroughARelay() = runBlocking {
        val url = System.getenv("ENCAVE_RELAY_URL")
        assumeTrue(url != null, "ENCAVE_RELAY_URL is not set")
        val store = RelayMailboxStore(url!!)
        store.check()
        val devices = household(2)
        grantAll(devices, store, url)
        val label = "relay label".toByteArray()
        val photo = PhotoId.make(label)
        devices[0].photos.save(photo, label)
        devices[0].edit { it.addBottles(Wine(name = "Through the relay", photoId = photo), 2) }
        devices[0].sync(store)
        devices[1].sync(store)
        assertEquals(devices[0].snapshot.cellar, devices[1].snapshot.cellar)
        assertArrayEquals(label, devices[1].photos.stored[photo])
        // Someone without the key cannot read the mailbox.
        val key = devices[0].key!!
        val error = assertThrows<RemoteSyncException> {
            runBlocking { store.mailbox(key.mailboxId, "wrong-token-0123456789").list("") }
        }
        assertEquals(RemoteSyncError.Unauthorized, error.error)
    }
}
