package app.encave.sync

import android.content.Context
import app.encave.core.Cellar
import app.encave.core.sync.CellarChangeSummary
import app.encave.core.sync.CellarKey
import app.encave.core.sync.CellarMerge
import app.encave.core.sync.DeviceIdentity
import app.encave.core.sync.FileAccess
import app.encave.core.sync.MailboxStore
import app.encave.core.sync.Member
import app.encave.core.sync.Membership
import app.encave.core.sync.RelayMailboxStore
import app.encave.core.sync.RemoteInfo
import app.encave.core.sync.RemoteProgress
import app.encave.core.sync.RemoteSync
import app.encave.core.sync.RemoteSyncError
import app.encave.core.sync.RemoteSyncException
import app.encave.core.sync.SecureChannel
import app.encave.core.sync.SyncError
import app.encave.core.sync.SyncException
import app.encave.core.sync.SyncExchange
import app.encave.core.sync.SyncHandshake
import app.encave.core.sync.SyncProtocol
import app.encave.core.sync.SyncSnapshot
import app.encave.core.sync.SyncState
import app.encave.core.sync.WebDavMailboxStore
import app.encave.data.CellarRepository
import app.encave.data.Preferences
import app.encave.data.SyncFrequency
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Keeps this device's cellar in step with the user's other devices: on the same
 * network directly (found with NSD, sessions authenticated with the devices'
 * identity keys and encrypted), and away from home through a WebDAV folder or a relay
 * that only holds encrypted files. The protocol is in `app.encave.core.sync` (docs/SYNC.md).
 *
 * Nothing touches the network until the user adds or joins a device.
 */
class SyncManager(
    private val context: Context,
    private val repository: CellarRepository,
    /** Photos and documents, each in its own folder. */
    private val files: FileAccess,
    private val preferences: Preferences,
    private val scope: CoroutineScope,
) {
    /** What the Devices screens show. */
    data class Status(
        val membership: Membership? = null,
        /** Devices of the cellar seen on the network right now. */
        val nearby: Set<UUID> = emptySet(),
        val syncing: Set<UUID> = emptySet(),
        val lastSync: Map<UUID, Instant> = emptyMap(),
        val problem: Problem? = null,
        /** When [problem] happened; cleared with it by the next sync that goes through. */
        val problemAt: Instant? = null,
        /** This device was removed from the cellar by another device. */
        val wasRemoved: Boolean = false,
        /** The relay URL, or "webdav:" and the folder URL, when syncing away from home is on. */
        val remoteStorage: String? = null,
        /**
         * The WebDAV folder this device syncs through has no login here (its address came
         * from another device): the Devices screen asks to sign in instead of failing silently.
         */
        val needsWebDavSignIn: Boolean = false,
        val hasRemoteKey: Boolean = false,
        val lastRemoteSync: Instant? = null,
        val syncingRemotely: Boolean = false,
        val remoteProblem: RemoteProblem? = null,
        val remoteProblemAt: Instant? = null,
        /** Changes were made on this device after the last round away from home that went through. */
        val unsentChanges: Boolean = false,
        /** When the in-app loop runs its next round away from home; null when it does not run. */
        val nextRemoteSync: Instant? = null,
    )

    enum class Problem { Removed, SaysRemoved, NotVerified, OtherVersion, Unfinished, CannotListen }
    enum class RemoteProblem { Refused, Full, FileTooLarge, Unreachable, RemovalPending, Unreadable }

    val storage = SyncStorage(context)
    internal val appContext: Context get() = context
    internal val photoAccess get() = files
    private val network = LocalNetwork(context)
    val deviceName: String = SyncStorage.deviceName(context)
    val platform = "android"

    /** Screen tour and tests: shows the screens, never touches the network. */
    @Volatile var isDemo = false

    /**
     * This device's ID and key. Replaced when it leaves a cellar: a removal is final for an
     * ID, so joining again needs a new one.
     */
    val identity: DeviceIdentity
        get() = synchronized(this) { currentIdentity ?: storage.loadIdentity().also { currentIdentity = it } }
    private var currentIdentity: DeviceIdentity? = null

    /** Changes when the device leaves or joins another cellar, so a round running then stores nothing. */
    @Volatile private var generation = 0

    /** Sessions opened by other devices right now; a few at most. */
    private val inboundSessions = java.util.concurrent.atomic.AtomicInteger(0)

    private val status = MutableStateFlow(Status())
    val state: StateFlow<Status> = status.asStateFlow()

    private val changes = MutableSharedFlow<CellarChangeSummary>(extraBufferCapacity = 4)

    /**
     * Changes from other devices taken in while the app is in the foreground, when the user
     * asked to be told about them (the app shows a short message; in the background, a
     * notification is posted instead).
     */
    val changesFromOthers: SharedFlow<CellarChangeSummary> = changes.asSharedFlow()

    /** How often this device syncs away from home (a device-only setting). */
    val frequency: SyncFrequency get() = preferences.syncFrequency.value

    // Guarded by `this`.
    private var syncState: SyncState? = null
    private var progress = RemoteProgress()

    private var listener: ServerSocket? = null
    private var advertisement: Closeable? = null
    private var browsing: Closeable? = null
    private val endpoints = HashMap<UUID, InetSocketAddress>()
    private val serviceDevices = HashMap<String, UUID>()
    private var pendingSync: Job? = null
    private var pendingRemote: Job? = null
    private var remoteLoop: Job? = null
    private val remoteMutex = Mutex()
    private var started = false

    /** Counts local changes, so a round only clears [Status.unsentChanges] if none came during it. */
    private val localChanges = java.util.concurrent.atomic.AtomicLong(0)

    val isSetUp: Boolean get() = status.value.membership != null
    val me: Member get() = Member(identity.id, deviceName, identity.publicKey, platform, inbox = identity.inboxId)
    val otherDevices: List<Member> get() = status.value.membership?.active.orEmpty().filter { it.id != identity.id }

    private val ready = MutableStateFlow(false)

    /** Waits until [start] has read what was saved. */
    suspend fun awaitStarted() {
        start()
        ready.first { it }
    }

    /** Loads what was saved and starts recording local changes. Call once, early. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            repository.loaded.first { it }
            val membership = storage.loadMembership()?.takeIf { it.members.containsKey(identity.id) || it.stamps.containsKey(identity.id) }
            var loaded = storage.loadState()?.takeIf { it.device == identity.id }
            if (membership != null && loaded == null) loaded = repository.read { SyncState.starting(it, identity.id) }
            // A key of another cellar (left from before joining this one) is never used.
            if (storage.remoteKey?.let { it.cellarId != membership?.cellarId } == true) storage.resetRemote()
            synchronized(this@SyncManager) {
                syncState = if (membership != null) loaded else null
                progress = storage.loadRemoteProgress()
            }
            status.update {
                it.copy(
                    membership = membership,
                    lastSync = storage.loadLastSync(),
                    remoteStorage = storage.remoteStorage,
                    needsWebDavSignIn = needsSignIn(storage.remoteStorage),
                    hasRemoteKey = storage.remoteKey != null,
                    wasRemoved = membership != null && !membership.members.containsKey(identity.id),
                    lastRemoteSync = storage.lastRemoteSync,
                    unsentChanges = storage.remoteStorage != null && storage.unsentChanges,
                )
            }
            repository.onLocalChange = { old, new -> record(old, new) }
            publishInbox()
            ready.value = true
            if (status.value.remoteStorage != null) scheduleBackground()
            // The app came to the foreground before this was read (cold start): start now.
            if (foreground) {
                resume()
                syncRemotely()
            }
        }
    }

    // region Lifecycle

    /** The app is in the foreground (between onStart and onStop). */
    @Volatile private var foreground = false

    /**
     * Starts listening and looking for the other devices. Called when the app comes to the
     * foreground; before [start] has read what was saved, it starts once that is done.
     */
    @Synchronized
    fun resume() {
        foreground = true
        // The changes told in the notification are now in sight.
        ChangeNotifications.clear(context)
        if (!ready.value || !isSetUp || isDemo || otherDevices.isEmpty() || status.value.wasRemoved) return
        startListener()
        startBrowser()
        startRemoteLoop()
    }

    /** Stops the network (the app goes to the background). Relay rounds continue with WorkManager. */
    fun pause() {
        foreground = false
        stopNetwork()
    }

    /** Stops listening, looking and the remote loop, without changing [foreground]. */
    @Synchronized
    private fun stopNetwork() {
        remoteLoop?.cancel()
        remoteLoop = null
        advertisement?.close()
        advertisement = null
        browsing?.close()
        browsing = null
        runCatching { listener?.close() }
        listener = null
        synchronized(endpoints) {
            endpoints.clear()
            serviceDevices.clear()
        }
        status.update { it.copy(nearby = emptySet(), nextRemoteSync = null) }
    }

    /** Syncs now with every device of the cellar on the network. */
    fun syncNow() {
        val targets = synchronized(endpoints) { endpoints.toMap() }
        for ((device, address) in targets) sync(device, address)
    }

    /** Sync now (Devices): with the devices on the network, and away from home if it is set up. */
    fun syncEverywhereNow() {
        syncNow()
        if (storage.remoteStorage != null) scope.launch { remoteRound() }
    }

    /**
     * Changes how often this device syncs away from home: the in-app loop starts again with
     * the new interval, and the background work follows (none for Sync now only).
     */
    fun setFrequency(value: SyncFrequency) {
        if (value == frequency) return
        preferences.setSyncFrequency(value)
        if (storage.remoteStorage != null) scheduleBackground()
        synchronized(this) {
            remoteLoop?.cancel()
            remoteLoop = null
        }
        status.update { it.copy(nextRemoteSync = null) }
        if (foreground && ready.value && isSetUp && otherDevices.isNotEmpty() && !status.value.wasRemoved) startRemoteLoop(sinceLastRound = true)
    }

    /** The background work, as often as the frequency allows (none for Sync now only). */
    private fun scheduleBackground() = RemoteSyncWorker.schedule(context, frequency)

    // endregion

    // region Setting up and leaving

    /** First device of a cellar: creates the list of devices with this one. */
    suspend fun setUpIfNeeded() {
        if (isSetUp) return
        val starting = repository.read { SyncState.starting(it, identity.id) }
        val membership = synchronized(this) {
            val current = syncState ?: starting
            val (members, clock) = Membership().adding(me, current.clock, identity.id)
            syncState = current.copy(clock = maxOf(current.clock, clock))
            members
        }
        status.update { it.copy(membership = membership, wasRemoved = false, problem = null) }
        save()
    }

    /** Makes sure this device's record names its relay inbox, so others can leave it keys. */
    private fun publishInbox() {
        val membership = synchronized(this) {
            val state = syncState ?: return
            val (members, clock) = status.value.membership?.publishingInbox(identity, state.clock) ?: return
            syncState = state.copy(clock = clock)
            members
        }
        status.update { it.copy(membership = membership) }
        save()
    }

    /** Removes another device: it can no longer sync with any device of the cellar. */
    fun remove(device: UUID) {
        val membership = synchronized(this) {
            val state = syncState ?: return
            val members = status.value.membership ?: return
            val (removed, clock) = members.removing(device, state.clock, identity.id)
            syncState = state.copy(clock = clock)
            removed
        }
        status.update { it.copy(membership = membership, lastSync = it.lastSync - device) }
        save()
        syncNow()
        // Away from home: a new key, so the removed device cannot read anything new. It is
        // kept until the rotation is done, in case the storage cannot be reached now: a retry
        // uses the same key, so grants already left stay valid.
        val key = storage.remoteKey
        if (key != null) {
            if (storage.pendingKey.let { it == null || it.epoch <= key.epoch }) {
                storage.pendingKey = CellarKey.random(key.cellarId, key.epoch + 1)
            }
            scope.launch { remoteRound() }
        }
    }

    /**
     * Stops syncing on this device. The cellar stays here as it is. The device gets a new ID
     * and key, so it can join a cellar again later.
     */
    fun leave() {
        stopNetwork()
        generation++
        synchronized(this) {
            syncState = null
            progress = RemoteProgress()
            if (!isDemo) currentIdentity = storage.newIdentity()
        }
        storage.deleteAll()
        RemoteSyncWorker.cancel(context)
        ChangeNotifications.clear(context)
        status.value = Status()
    }

    /**
     * Adopts the cellar and devices received from the device that invited this one.
     * [replace]: start from that cellar instead of combining it with this one.
     */
    suspend fun adoptCellar(received: Membership, replace: Boolean) {
        // Another cellar: nothing of the old one's remote sync applies.
        if (status.value.membership?.cellarId != received.cellarId) {
            generation++
            storage.resetRemote()
            storage.remoteStorage = null
            synchronized(this) { progress = RemoteProgress() }
            status.update {
                it.copy(
                    remoteStorage = null, needsWebDavSignIn = false, hasRemoteKey = false, remoteProblem = null, remoteProblemAt = null,
                    lastRemoteSync = null, unsentChanges = false, nextRemoteSync = null,
                )
            }
        }
        if (replace) {
            repository.applySynced(merge = { Cellar(settings = it.settings) })
        }
        val starting = repository.read { SyncState.starting(it, identity.id) }
        val membership = synchronized(this) {
            val state = if (replace) SyncState(device = identity.id) else syncState ?: starting
            val start = maxOf(state.clock, received.stamps.values.maxOfOrNull { it.counter } ?: 0)
            val (members, clock) = received.adding(me, start, identity.id)
            syncState = state.copy(clock = clock)
            members
        }
        status.update { it.copy(membership = membership, wasRemoved = false, problem = null) }
        save()
    }

    /** Adds the device that just joined. */
    fun addMember(member: Member) {
        val membership = synchronized(this) {
            val state = syncState ?: return
            val (members, clock) = (status.value.membership ?: return).adding(member, state.clock, identity.id)
            syncState = state.copy(clock = clock)
            members
        }
        status.update { it.copy(membership = membership) }
        save()
    }

    // endregion

    /** Screen tour: no network, and a second device that synced a moment ago. */
    suspend fun showDemo() {
        isDemo = true
        setUpIfNeeded()
        val (other, _) = DeviceIdentity.generate()
        addMember(Member(other.id, "iPad", other.publicKey, "ipados"))
        status.update { it.copy(lastSync = it.lastSync + (other.id to Instant.now().minusSeconds(120))) }
    }

    // region Recording and applying

    /** Called by the repository after each local change. */
    private fun record(old: Cellar, new: Cellar) {
        val state = synchronized(this) {
            val current = syncState ?: return
            current.recording(old, new).also { syncState = it }
        }
        runCatching { storage.saveState(state) }
        localChanges.incrementAndGet()
        if (storage.remoteStorage != null && !status.value.unsentChanges) {
            runCatching { storage.unsentChanges = true }
            status.update { it.copy(unsentChanges = true) }
        }
        // Wait for a pause in editing, then send the change to the devices around.
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(LOCAL_DELAY_MS)
            syncNow()
            syncRemotely()
        }
    }

    /** This device's copy, read while no change is running. */
    suspend fun snapshot(): SyncSnapshot? = repository.read { cellar ->
        synchronized(this) {
            val state = syncState ?: return@read null
            val membership = status.value.membership ?: return@read null
            SyncSnapshot(cellar, state, membership)
        }
    }

    /**
     * Takes in the result of a session. The cellar may have changed during it, so the
     * result is merged again with what is here now: no local edit is lost.
     */
    suspend fun apply(result: SyncSnapshot, from: UUID?): Boolean {
        var merged: CellarMerge.Result? = null
        var before: Cellar? = null
        var membership: Membership? = null
        val saved = repository.applySynced(
            merge = { current ->
                val state = synchronized(this) { syncState } ?: return@applySynced current
                before = current
                CellarMerge.merge(current, state, result.cellar, result.state).also { merged = it }.cellar
            },
            // The stamps follow the saved cellar: if saving fails, nothing is taken in.
            onSaved = {
                val merge = merged
                synchronized(this) {
                    val state = syncState
                    if (merge != null && state != null) {
                        syncState = state.copy(stamps = merge.stamps, clock = maxOf(merge.clock, state.clock))
                        membership = status.value.membership?.merged(result.membership)
                    }
                }
            },
        ).isSuccess
        val members = membership ?: return false
        // Learned from an authenticated list of devices: this device was removed.
        val removed = !members.members.containsKey(identity.id)
        status.update {
            it.copy(
                membership = members,
                lastSync = if (from != null) it.lastSync + (from to Instant.now()) else it.lastSync,
                wasRemoved = removed,
                problem = if (removed) Problem.Removed else null,
                problemAt = if (removed) Instant.now() else null,
            )
        }
        save()
        if (removed) stopNetwork()
        val merge = merged
        val old = before
        if (saved && merge != null && old != null) tell(old, merge, members)
        return saved
    }

    /**
     * When the user asked for it: what came from other devices, in a short message while the
     * app is open, in a notification otherwise (also from [RemoteSyncWorker]).
     */
    private fun tell(before: Cellar, merge: CellarMerge.Result, membership: Membership) {
        if (!preferences.syncNotifications.value || isDemo) return
        val summary = runCatching { CellarChangeSummary.between(before, merge.cellar, merge.stamps, identity.id) }.getOrNull() ?: return
        if (summary.isEmpty) return
        if (foreground) changes.tryEmit(summary) else ChangeNotifications.post(context, summary, membership)
    }

    private fun save() {
        val (state, membership) = synchronized(this) { syncState to status.value.membership }
        runCatching {
            membership?.let(storage::saveMembership)
            state?.let(storage::saveState)
            storage.saveLastSync(status.value.lastSync)
        }
    }

    // endregion

    // region Sessions on the local network

    private fun sync(device: UUID, address: InetSocketAddress) {
        val membership = status.value.membership ?: return
        if (status.value.wasRemoved || isDemo) return
        if (!markSyncing(device)) return
        scope.launch {
            var transport: SocketTransport? = null
            var deadline: Job? = null
            try {
                transport = SocketTransport.connect(address)
                // A session never hangs: the whole exchange gets a few minutes at most.
                deadline = transport.closing(scope, SESSION_DEADLINE_MS)
                val handshake = transport.closing(scope, HANDSHAKE_DEADLINE_MS)
                val (channel, peer) = SyncHandshake.initiate(transport, identity, membership)
                handshake.cancel()
                transport.allowLargeFrames()
                exchange(channel, true, peer.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report(error)
            } finally {
                deadline?.cancel()
                transport?.close()
                status.update { it.copy(syncing = it.syncing - device) }
            }
        }
    }

    private fun markSyncing(device: UUID): Boolean {
        var marked = false
        status.update {
            if (device in it.syncing) it else it.copy(syncing = it.syncing + device).also { marked = true }
        }
        return marked
    }

    private suspend fun exchange(channel: SecureChannel, isInitiator: Boolean, peer: UUID) {
        val local = snapshot() ?: return
        val (result, remote) = SyncExchange.exchange(channel, isInitiator, local, files, remoteInfo())
        apply(result, peer)
        adoptRemote(remote)
    }

    private fun respond(socket: Socket) {
        val membership = status.value.membership
        if (membership == null || status.value.wasRemoved || inboundSessions.get() >= MAX_INBOUND_SESSIONS) {
            runCatching { socket.close() }
            return
        }
        inboundSessions.incrementAndGet()
        scope.launch {
            val transport = SocketTransport(socket)
            val deadline = transport.closing(scope, SESSION_DEADLINE_MS)
            var peer: UUID? = null
            try {
                val handshake = transport.closing(scope, HANDSHAKE_DEADLINE_MS)
                val (channel, member) = SyncHandshake.respond(transport, identity, membership)
                handshake.cancel()
                transport.allowLargeFrames()
                // Already syncing with it: leave that session's mark alone.
                if (!markSyncing(member.id)) return@launch
                peer = member.id
                exchange(channel, false, member.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Someone else's connection failing is not this device's problem to show.
                val code = (error as? SyncException)?.error
                if (code != SyncError.UnknownDevice && code != SyncError.RemovedDevice) report(error)
            } finally {
                deadline.cancel()
                transport.close()
                inboundSessions.decrementAndGet()
                peer?.let { id -> status.update { it.copy(syncing = it.syncing - id) } }
            }
        }
    }

    /**
     * Shows what went wrong. Error frames arrive before authentication, so anyone on the
     * network could send one: a "removed-device" refusal is only a hint, never a reason to
     * stop. Removal is learned from an authenticated list of devices ([apply]).
     */
    fun report(error: Exception) {
        val problem = when ((error as? SyncException)?.error) {
            SyncError.RemovedDevice -> Problem.SaysRemoved
            // The other device has not heard of this one yet: it will after syncing with the
            // device that added this one. Nothing to show.
            SyncError.UnknownDevice -> return
            SyncError.AuthenticationFailed -> Problem.NotVerified
            is SyncError.UnsupportedVersion -> Problem.OtherVersion
            else -> Problem.Unfinished
        }
        status.update { if (it.wasRemoved) it else it.copy(problem = problem, problemAt = Instant.now()) }
    }

    private fun startListener() {
        if (listener != null) return
        val server = runCatching { ServerSocket(0) }.getOrElse {
            status.update { it.copy(problem = Problem.CannotListen, problemAt = Instant.now()) }
            return
        }
        listener = server
        scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                respond(socket)
            }
        }
        val tag = status.value.membership?.networkTag ?: return
        // The name others see on the network: not the device name, which may be personal.
        advertisement = network.advertise(
            "Encave-" + identity.id.toString().take(8),
            server.localPort,
            mapOf("d" to identity.id.toString().lowercase(Locale.ROOT), "c" to tag, "v" to SyncProtocol.VERSION.toString()),
        )
    }

    private fun startBrowser() {
        if (browsing != null) return
        browsing = network.browse(onFound = ::found, onLost = ::lost)
    }

    private fun found(service: LocalNetwork.Found) {
        val membership = status.value.membership ?: return
        val id = service.attributes["d"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        if (service.attributes["c"] != membership.networkTag || id == identity.id || !membership.members.containsKey(id)) return
        val appeared = synchronized(endpoints) {
            val isNew = id !in endpoints
            endpoints[id] = service.address
            serviceDevices[service.name] = id
            isNew
        }
        status.update { it.copy(nearby = it.nearby + id) }
        // One side starts the session: the device with the smaller ID.
        if (appeared && identity.id.toString().lowercase(Locale.ROOT) < id.toString().lowercase(Locale.ROOT)) sync(id, service.address)
    }

    private fun lost(name: String) {
        val id = synchronized(endpoints) {
            serviceDevices.remove(name)?.also { endpoints.remove(it) }
        } ?: return
        status.update { it.copy(nearby = it.nearby - id) }
    }

    // endregion

    // region Away from home

    fun remoteInfo(): RemoteInfo? = storage.remoteKey?.let { RemoteInfo(it, storage.remoteStorage) }

    /**
     * Turns syncing away from home on with a relay (or off with null). Checks the relay
     * first. The first device to turn it on creates the cellar's key and gives it to the others.
     */
    suspend fun setRemoteStorage(url: String?) {
        if (url == null) {
            storage.remoteStorage = null
            storage.webDavLogin = null
            synchronized(this) {
                remoteLoop?.cancel()
                remoteLoop = null
            }
            RemoteSyncWorker.cancel(context)
            runCatching {
                storage.unsentChanges = false
                storage.lastRemoteSync = null
            }
            status.update {
                it.copy(
                    remoteStorage = null, needsWebDavSignIn = false, remoteProblem = null, remoteProblemAt = null,
                    unsentChanges = false, lastRemoteSync = null, nextRemoteSync = null,
                )
            }
            return
        }
        val store = RelayMailboxStore(url)
        store.check()
        turnOn(store, store.baseUrl)
    }

    /**
     * Turns syncing away from home on with a WebDAV folder. Checks the folder and the login
     * first; the login stays on this device, only the folder's address goes to the others.
     */
    suspend fun setWebDav(folder: String, username: String, password: String) {
        val url = WebDavMailboxStore.folderUrl(folder) ?: throw RemoteSyncException(RemoteSyncError.Unavailable("address"))
        val store = WebDavMailboxStore(url, username, password)
        store.check()
        storage.webDavLogin = WebDavLogin(url, username, password)
        turnOn(store, WEBDAV_PREFIX + url)
    }

    /** Signs in to the WebDAV folder this device already syncs through (its address came from another device). */
    suspend fun signInToWebDav(username: String, password: String) {
        val url = storage.remoteStorage?.takeIf { it.startsWith(WEBDAV_PREFIX) }?.removePrefix(WEBDAV_PREFIX) ?: return
        WebDavMailboxStore(url, username, password).check()
        storage.webDavLogin = WebDavLogin(url, username, password)
        status.update { it.copy(needsWebDavSignIn = false, remoteProblem = null, remoteProblemAt = null) }
        scheduleBackground()
        startRemoteLoop()
        remoteRound()
    }

    private suspend fun turnOn(store: MailboxStore, storageString: String) {
        setUpIfNeeded()
        storage.remoteStorage = storageString
        status.update { it.copy(remoteStorage = storageString, needsWebDavSignIn = needsSignIn(storageString)) }
        val membership = status.value.membership
        if (storage.remoteKey == null && membership != null) {
            // Maybe another device already turned it on and left a key for this one.
            val grant = runCatching { RemoteSync.receiveGrant(store, identity, membership, null) }.getOrNull()
            if (grant != null) {
                storeRemoteKey(grant.cellarKey)
            } else {
                val key = CellarKey.random(membership.cellarId)
                storeRemoteKey(key)
                RemoteSync.grant(key, membership.active, store, identity, storageString)
            }
        }
        scheduleBackground()
        startRemoteLoop()
        remoteRound()
    }

    /** A key and storage received from a device on the network. */
    fun adoptRemote(info: RemoteInfo?) {
        val membership = status.value.membership ?: return
        if (info == null || info.key.cellarId != membership.cellarId) return
        if (info.key.supersedes(storage.remoteKey)) storeRemoteKey(info.key)
        // Only a storage this device can use: an HTTPS relay or WebDAV folder. iCloud ("icloud")
        // is Apple-only. A WebDAV folder then asks for this device's own login.
        val offered = info.storage?.let(::acceptedFromPeer)
        if (storage.remoteStorage == null && offered != null) {
            storage.remoteStorage = offered
            status.update { it.copy(remoteStorage = offered, needsWebDavSignIn = needsSignIn(offered)) }
            scheduleBackground()
            startRemoteLoop()
        }
    }

    /** Soon after a change (or back in the foreground), sends it away from home; not when syncing only with Sync now. */
    fun syncRemotely() {
        if (storage.remoteStorage == null || frequency == SyncFrequency.Manual) return
        pendingRemote?.cancel()
        pendingRemote = scope.launch {
            delay(REMOTE_DELAY_MS)
            remoteRound()
        }
    }

    /**
     * While the app is open: a round at the chosen frequency (every minute by default), to
     * pick up changes from devices away. None when syncing only with Sync now.
     * [sinceLastRound]: the first round comes one interval after the last one, not now.
     */
    @Synchronized
    private fun startRemoteLoop(sinceLastRound: Boolean = false) {
        val interval = frequency.intervalMs
        if (storage.remoteStorage == null || remoteLoop != null || isDemo || interval == null) return
        val first = if (sinceLastRound) {
            val last = status.value.lastRemoteSync?.toEpochMilli() ?: 0
            (last + interval - System.currentTimeMillis()).coerceIn(0, interval)
        } else {
            0
        }
        remoteLoop = scope.launch {
            var wait = first
            while (isActive) {
                if (wait > 0) {
                    status.update { it.copy(nextRemoteSync = Instant.now().plusMillis(wait)) }
                    delay(wait)
                }
                remoteRound()
                wait = interval
            }
        }
    }

    /** The store for a storage string; null for a WebDAV folder without a login on this device. */
    private fun storeFor(storageString: String): MailboxStore? {
        if (!storageString.startsWith(WEBDAV_PREFIX)) return RelayMailboxStore(storageString)
        val folder = storageString.removePrefix(WEBDAV_PREFIX)
        val login = storage.webDavLogin?.takeIf { it.folder == folder } ?: return null
        return runCatching { WebDavMailboxStore(folder, login.username, login.password) }.getOrNull()
    }

    private fun needsSignIn(storageString: String?): Boolean =
        storageString != null && storageString.startsWith(WEBDAV_PREFIX) && storage.webDavLogin?.folder != storageString.removePrefix(WEBDAV_PREFIX)

    /**
     * One round through the relay or WebDAV folder, off the main thread. Also run by
     * [RemoteSyncWorker] in the background.
     */
    suspend fun remoteRound() {
        awaitStarted()
        withContext(Dispatchers.Default) { runRemoteRound() }
    }

    private suspend fun runRemoteRound() {
        val url = storage.remoteStorage ?: return
        val store = storeFor(url)
        if (store == null) {
            status.update { it.copy(needsWebDavSignIn = true) }
            return
        }
        if (status.value.wasRemoved || isDemo || !remoteMutex.tryLock()) return
        status.update { it.copy(syncingRemotely = true) }
        val round = generation
        val changesBefore = localChanges.get()
        try {
            val local = snapshot() ?: return
            var key = storage.remoteKey
            if (key == null) {
                key = RemoteSync.receiveGrant(store, identity, local.membership, null)?.cellarKey
                if (round != generation) return
                key?.let(::storeRemoteKey)
            }
            if (key == null || key.cellarId != local.membership.cellarId) return
            key = try {
                rotateIfNeeded(store, key, local, url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                status.update { it.copy(remoteProblem = RemoteProblem.RemovalPending, remoteProblemAt = Instant.now()) }
                return
            }
            val current = synchronized(this) { progress }
            val result = RemoteSync.run(store, identity, key, local, current, files, currentStorage = url)
            // The device left or joined another cellar meanwhile: drop the result.
            if (round != generation) return
            result.newKey?.let(::storeRemoteKey)
            // The device that gave the new key moved the files: follow it (an HTTPS relay or
            // WebDAV folder only; a WebDAV folder then asks for a login on this device).
            val moved = result.storage?.let(::acceptedFromPeer)
            if (result.newKey != null && moved != null && moved != url) {
                storage.remoteStorage = moved
                status.update { it.copy(remoteStorage = moved, needsWebDavSignIn = needsSignIn(moved)) }
            }
            // What was read is taken in first; the progress moves on only once it is saved, so
            // changes that could not be saved are read again next round.
            val applied = result.snapshot?.let { apply(it, null) } ?: true
            if (!applied || round != generation) return
            synchronized(this) { progress = result.progress }
            withContext(Dispatchers.IO) { storage.saveRemoteProgress(result.progress) }
            // Raised when the content changed without the clock moving: keep it, so the next
            // local change is newer than the snapshot just written.
            val raised = synchronized(this) {
                val state = syncState
                if (state != null && java.lang.Long.compareUnsigned(result.clock, state.clock) > 0) {
                    syncState = state.copy(clock = result.clock)
                    true
                } else {
                    false
                }
            }
            if (raised) save()
            val now = Instant.now()
            // Everything recorded before the round was sent; changes made during it wait for the next.
            val sent = localChanges.get() == changesBefore
            runCatching {
                storage.lastRemoteSync = now
                if (sent) storage.unsentChanges = false
            }
            status.update {
                // The cellar synced; files the storage refused stay on this device for now.
                val problem = when {
                    result.unreadable.isNotEmpty() -> RemoteProblem.Unreadable
                    result.refusedFiles == RemoteSyncError.QuotaExceeded -> RemoteProblem.Full
                    result.refusedFiles == RemoteSyncError.FileTooLarge -> RemoteProblem.FileTooLarge
                    else -> null
                }
                it.copy(
                    lastRemoteSync = now,
                    remoteProblem = problem,
                    remoteProblemAt = if (problem != null) now else null,
                    unsentChanges = it.unsentChanges && !sent,
                )
            }
        } catch (error: RemoteSyncException) {
            val problem = when (error.error) {
                RemoteSyncError.Unauthorized -> RemoteProblem.Refused
                RemoteSyncError.QuotaExceeded -> RemoteProblem.Full
                RemoteSyncError.FileTooLarge -> RemoteProblem.FileTooLarge
                else -> RemoteProblem.Unreachable
            }
            status.update { it.copy(remoteProblem = problem, remoteProblemAt = Instant.now()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status.update { it.copy(remoteProblem = RemoteProblem.Unreachable, remoteProblemAt = Instant.now()) }
        } finally {
            status.update { it.copy(syncingRemotely = false) }
            remoteMutex.unlock()
        }
    }

    /** Rotates the remote key if a removal is waiting for it, and returns the key to use. */
    private suspend fun rotateIfNeeded(store: MailboxStore, key: CellarKey, local: SyncSnapshot, url: String): CellarKey {
        // Builds before this one kept only a flag.
        if (storage.hadRotationFlag) {
            storage.clearRotationFlag()
            if (storage.pendingKey == null) storage.pendingKey = CellarKey.random(key.cellarId, key.epoch + 1)
        }
        var next = storage.pendingKey?.takeIf { it.cellarId == key.cellarId } ?: return key
        // Another device moved on meanwhile: rotate from where the cellar is now.
        if (next.epoch <= key.epoch) {
            next = CellarKey.random(key.cellarId, key.epoch + 1)
            storage.pendingKey = next
        }
        RemoteSync.rotate(store, identity, key, next, local, url)
        storeRemoteKey(next)
        storage.pendingKey = null
        return next
    }

    private fun storeRemoteKey(key: CellarKey?) {
        // Progress belongs to a mailbox: a different key starts afresh.
        if (key != storage.remoteKey) {
            synchronized(this) { progress = RemoteProgress() }
            runCatching { storage.saveRemoteProgress(RemoteProgress()) }
        }
        storage.remoteKey = key
        status.update { it.copy(hasRemoteKey = key != null) }
    }

    // endregion

    companion object {
        /** A WebDAV folder's storage string: this prefix and the folder URL (docs/SYNC.md §8). */
        const val WEBDAV_PREFIX = "webdav:"

        /**
         * A storage told by another device, as this device keeps it, if it can use it: an
         * HTTPS relay or WebDAV folder (WebDAV over HTTP only on the local network). Null
         * otherwise; iCloud ("icloud") is Apple-only.
         */
        fun acceptedFromPeer(storage: String): String? =
            if (storage.startsWith(WEBDAV_PREFIX)) {
                WebDavMailboxStore.folderUrl(storage.removePrefix(WEBDAV_PREFIX))?.let { WEBDAV_PREFIX + it }
            } else {
                storage.takeIf { it.lowercase(Locale.ROOT).startsWith("https://") && RelayMailboxStore.isAllowed(it) }
            }

        const val LOCAL_DELAY_MS = 2_000L
        private const val HANDSHAKE_DEADLINE_MS = 10_000L
        private const val SESSION_DEADLINE_MS = 300_000L
        private const val MAX_INBOUND_SESSIONS = 4
        const val REMOTE_DELAY_MS = 3_000L
    }
}
