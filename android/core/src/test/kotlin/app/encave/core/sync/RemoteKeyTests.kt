package app.encave.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The key a device keeps when turning remote sync on. */
class RemoteKeyTests {
    /** Takes every file, except grants: as a storage that fails while turning on. */
    private class NoGrantStore : MailboxStore {
        private val base = MemoryMailboxStore()
        override fun mailbox(id: String, token: String): Mailbox = base.mailbox(id, token)
        override fun inbox(id: String, token: String): Mailbox {
            val inbox = base.inbox(id, token)
            return object : Mailbox by inbox {
                override suspend fun create(path: String, data: ByteArray): Boolean = false
            }
        }
    }

    @Test
    fun aNewKeyIsKeptOnlyOnceEveryDeviceWasGivenIt() = runBlocking {
        val devices = household()
        val first = devices[1]
        // A grant fails: no key to keep, so none that the others never received.
        assertThrows<RemoteSyncException> {
            runBlocking { RemoteSync.keyForTurningOn(NoGrantStore(), first.identity, first.snapshot.membership, null, "https://relay.example.com") }
        }

        val store = MemoryMailboxStore()
        val key = RemoteSync.keyForTurningOn(store, first.identity, first.snapshot.membership, null, "https://relay.example.com")
        val other = devices[2]
        val received = RemoteSync.receiveGrant(store, other.identity, other.snapshot.membership, null)
        assertNotNull(received)
        assertArrayEquals(key.key, received!!.cellarKey.key)
        // The next device to turn it on takes the key left for it rather than making another.
        assertArrayEquals(key.key, RemoteSync.keyForTurningOn(store, other.identity, other.snapshot.membership, null, "https://relay.example.com").key)
    }

    @Test
    fun aKeyAlreadyHeldIsKept() = runBlocking {
        val device = household()[0]
        val key = device.key!!
        assertSame(key, RemoteSync.keyForTurningOn(NoGrantStore(), device.identity, device.snapshot.membership, key, null))
    }
}
