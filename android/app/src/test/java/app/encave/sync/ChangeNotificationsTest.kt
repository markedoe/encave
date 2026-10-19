package app.encave.sync

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.MainActivity
import app.encave.core.sync.CellarChangeSummary
import app.encave.core.sync.Member
import app.encave.core.sync.Membership
import app.encave.data.SyncFrequency
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notification of changes from other devices, and the background schedule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ChangeNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val marc = UUID.randomUUID()
    private val membership = Membership(members = mapOf(marc to Member(marc, "Marc’s phone", ByteArray(32), "android")))

    @After
    fun clear() = ChangeNotifications.clear(context)

    @Test
    fun saysWhatChangedAndWhere() {
        val resources = context.resources
        val summary = CellarChangeSummary(opened = 2, added = 3, devices = setOf(marc))
        assertEquals("From Marc’s phone", ChangeNotifications.title(resources, summary, membership))
        assertEquals("2 bottles opened, 3 bottles added.", ChangeNotifications.body(resources, summary))
        assertEquals("Updated from Marc’s phone", ChangeNotifications.updated(resources, summary, membership))
        val several = CellarChangeSummary(removed = 1, winesAdded = 1, other = 2, devices = setOf(marc, UUID.randomUUID()))
        assertEquals("From your other devices", ChangeNotifications.title(resources, several, membership))
        assertEquals("1 bottle removed, 1 wine added, 2 other changes.", ChangeNotifications.body(resources, several))
        assertEquals(MainActivity.LINK_CELLAR, ChangeNotifications.link(several))
        assertEquals(MainActivity.LINK_JOURNAL, ChangeNotifications.link(CellarChangeSummary(opened = 1)))
    }

    @Test
    fun oneNotificationThatAddsUp() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ChangeNotifications.post(context, CellarChangeSummary(opened = 1, devices = setOf(marc)), membership)
        ChangeNotifications.post(context, CellarChangeSummary(added = 2, devices = setOf(marc)), membership)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals(ChangeNotifications.CHANNEL, posted.single().channelId)
        assertNotNull(manager.getNotificationChannel(ChangeNotifications.CHANNEL))
        val text = posted.single().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertEquals("1 bottle opened, 2 bottles added.", text)
        // Opened: seen, and the next one starts afresh.
        ChangeNotifications.clear(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(ChangeNotifications.pending(context).isEmpty)
    }

    @Test
    fun nothingWithoutPermission() {
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ChangeNotifications.post(context, CellarChangeSummary(opened = 1), membership)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun backgroundWorkFollowsTheFrequency() {
        assertEquals(15, RemoteSyncWorker.intervalMinutes(SyncFrequency.EveryMinute))
        assertEquals(15, RemoteSyncWorker.intervalMinutes(SyncFrequency.Every5Minutes))
        assertEquals(15, RemoteSyncWorker.intervalMinutes(SyncFrequency.Every15Minutes))
        assertNull(RemoteSyncWorker.intervalMinutes(SyncFrequency.Manual))
        assertEquals(SyncFrequency.EveryMinute, SyncFrequency.fromRaw(null))
        assertEquals(SyncFrequency.Manual, SyncFrequency.fromRaw("manual"))
    }
}
