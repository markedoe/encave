package app.encave.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun anIdentityThatCannotBeReadIsNeverReplaced() {
        val device = UUID.randomUUID()
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("deviceID", device.toString()).commit()
        // Stored, but the Keystore cannot decrypt it (here: there is no Keystore at all).
        context.getSharedPreferences("secrets", Context.MODE_PRIVATE).edit().putString("sync.identity", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        val folder = File(context.filesDir, "sync").apply { mkdirs() }
        File(folder, "devices.json").writeText("{}")

        val storage = SyncStorage(context)
        val identity = storage.loadIdentity()
        assertTrue(storage.identityUnreadable)
        assertNotEquals(device, identity.id)
        // Nothing stored is touched: the identity is read again next time.
        assertEquals(device.toString(), context.getSharedPreferences("sync", Context.MODE_PRIVATE).getString("deviceID", null))
        assertTrue(File(folder, "devices.json").exists())
        storage.saveLastSync(emptyMap())
        assertFalse(File(folder, "last-sync.json").exists())
    }
}
