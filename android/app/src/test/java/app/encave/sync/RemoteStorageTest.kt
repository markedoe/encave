package app.encave.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which storages told by another device this one takes, and in which form it keeps them. */
class RemoteStorageTest {
    @Test
    fun webDavFoldersFromPeersAreNormalized() {
        assertEquals("webdav:https://nas.example.com/encave/", SyncManager.acceptedFromPeer("webdav:https://nas.example.com/encave"))
        assertEquals("webdav:http://nas.local:5005/encave/", SyncManager.acceptedFromPeer("webdav:http://nas.local:5005/encave/"))
        assertNull(SyncManager.acceptedFromPeer("webdav:http://webdav.example.com/encave/"))
        assertNull(SyncManager.acceptedFromPeer("webdav:https://jean:secret@nas.example.com/"))
        assertNull(SyncManager.acceptedFromPeer("webdav:"))
    }

    @Test
    fun relaysMustBeHttpsAndICloudIsAppleOnly() {
        assertEquals("https://relay.example.com", SyncManager.acceptedFromPeer("https://relay.example.com"))
        assertNull(SyncManager.acceptedFromPeer("http://relay.example.com"))
        assertNull(SyncManager.acceptedFromPeer("icloud"))
    }
}
