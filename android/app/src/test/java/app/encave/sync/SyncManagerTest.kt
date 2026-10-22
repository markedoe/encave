package app.encave.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Cellar
import app.encave.core.JsonFileCellarStore
import app.encave.core.Wine
import app.encave.core.sync.DeviceIdentity
import app.encave.core.sync.FileAccess
import app.encave.core.sync.Mailbox
import app.encave.core.sync.MailboxStore
import app.encave.core.sync.Member
import app.encave.core.sync.MemoryMailboxStore
import app.encave.core.sync.Membership
import app.encave.core.sync.RemoteSyncError
import app.encave.core.sync.RemoteSyncException
import app.encave.core.sync.SyncHealth
import app.encave.core.sync.SyncSnapshot
import app.encave.core.sync.SyncState
import app.encave.data.CellarRepository
import app.encave.data.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What SyncManager keeps when turning sync on, joining with Replace and leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: CellarRepository
    private lateinit var sync: SyncManager

    @Before
    fun setUp() = runBlocking {
        val file = File(context.filesDir, "cellar-test.json").apply { delete() }
        repository = CellarRepository(JsonFileCellarStore(file), scope)
        repository.loaded.first { it }
        sync = SyncManager(context, repository, FileAccess.NONE, Preferences(context), scope)
        sync.awaitStarted()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Another device of the cellar, with an inbox to receive keys. */
    private fun addOther(): DeviceIdentity {
        val (other, _) = DeviceIdentity.generate()
        sync.addMember(Member(other.id, "iPad", other.publicKey, "ipados", inbox = other.inboxId))
        return other
    }

    @Test
    fun aStorageThatRefusesTheKeyLeavesSyncOff() = runBlocking {
        sync.setUpIfNeeded()
        addOther()
        val refusing = object : MailboxStore {
            val box = object : Mailbox {
                override suspend fun list(prefix: String): List<String> = emptyList()
                override suspend fun get(path: String): ByteArray? = null
                override suspend fun create(path: String, data: ByteArray): Boolean = throw RemoteSyncException(RemoteSyncError.QuotaExceeded)
                override suspend fun delete(path: String) = Unit
            }
            override fun mailbox(id: String, token: String): Mailbox = box
            override fun inbox(id: String, token: String): Mailbox = box
        }
        val failure = runCatching { sync.turnOn(refusing, "https://relay.example") }.exceptionOrNull()
        assertEquals(RemoteSyncError.QuotaExceeded, (failure as? RemoteSyncException)?.error)
        assertNull(sync.storage.remoteStorage)
        assertFalse(sync.state.value.hasRemoteKey)
        assertNull(sync.state.value.remoteStorage)
    }

    @Test
    fun aStorageThatTakesTheKeyIsKept() = runBlocking {
        sync.setUpIfNeeded()
        addOther()
        sync.turnOn(MemoryMailboxStore(), "https://relay.example")
        assertEquals("https://relay.example", sync.storage.remoteStorage)
        // The key itself is in the Keystore, which tests do not have: its status says it is kept.
        assertTrue(sync.state.value.hasRemoteKey)
        assertEquals("https://relay.example", sync.state.value.remoteStorage)
    }

    @Test
    fun replaceKeepsTheBottlesUntilTheOtherCellarArrives() = runBlocking {
        repository.update { it.addBottles(Wine(name = "Mine"), 4) }.getOrThrow()
        // The other device's cellar and devices.
        val (host, _) = DeviceIdentity.generate()
        val (members, clock) = Membership().adding(Member(host.id, "iPad", host.publicKey, "ipados"), 0, host.id)
        val theirs = Cellar().apply { addBottles(Wine(name = "Theirs"), 2) }
        val theirState = SyncState.starting(theirs, host.id).let { it.copy(clock = maxOf(it.clock, clock)) }

        sync.adoptCellar(members, replace = true)
        assertTrue(sync.isReplacePending)
        // Nothing lost if the first sync fails, and nothing of this device's bottles is sent.
        assertEquals(4, repository.cellar.value.bottles.size)
        assertTrue(sync.snapshot()!!.cellar.bottles.isEmpty())

        assertTrue(sync.apply(SyncSnapshot(theirs, theirState, sync.state.value.membership!!), host.id))
        assertFalse(sync.isReplacePending)
        assertEquals(setOf("Theirs"), repository.cellar.value.wines.values.map { it.name }.toSet())
        assertEquals(2, repository.cellar.value.bottles.size)
    }

    @Test
    fun leavingWithNoDeviceReachableSaysSoAndForgetsTheCellar() = runBlocking {
        sync.setUpIfNeeded()
        addOther()
        val before = sync.identity.id
        assertFalse(sync.leave())
        assertNull(sync.state.value.membership)
        assertTrue(before != sync.identity.id || sync.storage.identityUnreadable)
    }

    @Test
    fun healthFollowsWhatSyncKnows() {
        val me = sync.identity.id
        val (other, _) = DeviceIdentity.generate()
        val (members, _) = Membership().adding(Member(me, "Phone", sync.identity.publicKey, "android"), 0, me)
            .let { (m, c) -> m.adding(Member(other.id, "iPad", other.publicKey, "ipados"), c, me) }
        val shared = SyncManager.Status(membership = members)
        assertEquals(SyncHealth.Ok, shared.health(me))
        assertEquals(SyncHealth.NeedsAction(SyncHealth.Reason.SignInNeeded), shared.copy(needsWebDavSignIn = true).health(me))
        assertEquals(
            SyncHealth.NeedsAction(SyncHealth.Reason.LocalNetworkOff),
            shared.copy(problem = SyncManager.Problem.LocalNetworkUnavailable).health(me),
        )
        assertEquals(SyncHealth.Failing, shared.copy(remoteProblem = SyncManager.RemoteProblem.Full).health(me))
        // Alone, nothing to show.
        assertEquals(SyncHealth.Ok, SyncManager.Status().copy(needsWebDavSignIn = true).health(me))
    }
}
