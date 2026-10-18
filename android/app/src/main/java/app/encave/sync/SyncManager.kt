package app.encave.sync

import android.content.Context
import app.encave.core.Cellar
import app.encave.core.sync.CellarKey
import app.encave.core.sync.CellarMerge
import app.encave.core.sync.DeviceIdentity
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
import app.encave.data.CellarRepository
import app.encave.data.PhotoStore
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * identity keys and encrypted), and away from home through a relay that only holds
 * encrypted files. The protocol is in `app.encave.core.sync` (docs/SYNC.md).
 *
 * Nothing touches the network until the user adds or joins a device.
 */
class SyncManager(
    private val context: Context,
    private val repository: CellarRepository,
    private val photos: PhotoStore,
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
        /** This device was removed from the cellar by another device. */
        val wasRemoved: Boolean = false,
        /** The relay URL when syncing away from home is on. */
        val remoteStorage: String? = null,
        val hasRemoteKey: Boolean = false,
        val lastRemoteSync: Instant? = null,
        val syncingRemotely: Boolean = false,
        val remoteProblem: RemoteProblem? = null,
    )

    enum class Problem { Removed, SaysRemoved, NotVerified, OtherVersion, Unfinished, CannotListen }
    enum class RemoteProblem { Refused, Full, FileTooLarge, Unreachable, RemovalPending, Unreadable }

    val storage = SyncStorage(context)
    internal val appContext: Context get() = context
    internal val photoAccess get() = photos.syncAccess
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
                    hasRemoteKey = storage.remoteKey != null,
                    wasRemoved = membership != null && !membership.members.containsKey(identity.id),
                )
            }
            repository.onLocalChange = { old, new -> record(old, new) }
            publishInbox()
            ready.value = true
            if (status.value.remoteStorage != null) RemoteSyncWorker.schedule(context)
        }
    }

    // region Lifecycle

    /** Starts listening and looking for the other devices. Called when the app comes to the foreground. */
    fun resume() {
        if (!isSetUp || isDemo || otherDevices.isEmpty() || status.value.wasRemoved) return
        startListener()
        startBrowser()
        startRemoteLoop()
    }

    /** Stops the network (the app goes to the background). Relay rounds continue with WorkManager. */
    fun pause() {
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
        status.update { it.copy(nearby = emptySet()) }
    }

    /** Syncs now with every device of the cellar on the network. */
    fun syncNow() {
        val targets = synchronized(endpoints) { endpoints.toMap() }
        for ((device, address) in targets) sync(device, address)
    }

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
        pause()
        generation++
        synchronized(this) {
            syncState = null
            progress = RemoteProgress()
            if (!isDemo) currentIdentity = storage.newIdentity()
        }
        storage.deleteAll()
        RemoteSyncWorker.cancel(context)
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
            status.update { it.copy(remoteStorage = null, hasRemoteKey = false, remoteProblem = null, lastRemoteSync = null) }
        }
        if (replace) {
            repository.applySynced { Cellar(settings = it.settings) }
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
    suspend fun apply(result: SyncSnapshot, from: UUID?) {
        var membership: Membership? = null
        repository.applySynced { current ->
            synchronized(this) {
                val state = syncState ?: return@applySynced current
                val merge = CellarMerge.merge(current, state, result.cellar, result.state)
                syncState = state.copy(stamps = merge.stamps, clock = maxOf(merge.clock, state.clock))
                membership = status.value.membership?.merged(result.membership)
                merge.cellar
            }
        }
        val members = membership ?: return
        // Learned from an authenticated list of devices: this device was removed.
        val removed = !members.members.containsKey(identity.id)
        status.update {
            it.copy(
                membership = members,
                lastSync = if (from != null) it.lastSync + (from to Instant.now()) else it.lastSync,
                wasRemoved = removed,
                problem = if (removed) Problem.Removed else null,
            )
        }
        save()
        if (removed) pause()
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
        val (result, remote) = SyncExchange.exchange(channel, isInitiator, local, photos.syncAccess, remoteInfo())
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
                peer = member.id
                if (!markSyncing(member.id)) return@launch
                exchange(channel, false, member.id)
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
        status.update { if (it.wasRemoved) it else it.copy(problem = problem) }
    }

    private fun startListener() {
        if (listener != null) return
        val server = runCatching { ServerSocket(0) }.getOrElse {
            status.update { it.copy(problem = Problem.CannotListen) }
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
            remoteLoop?.cancel()
            remoteLoop = null
            RemoteSyncWorker.cancel(context)
            status.update { it.copy(remoteStorage = null, remoteProblem = null) }
            return
        }
        val store = RelayMailboxStore(url)
        store.check()
        setUpIfNeeded()
        storage.remoteStorage = store.baseUrl
        status.update { it.copy(remoteStorage = store.baseUrl) }
        val membership = status.value.membership
        if (storage.remoteKey == null && membership != null) {
            // Maybe another device already turned it on and left a key for this one.
            val grant = runCatching { RemoteSync.receiveGrant(store, identity, membership, null) }.getOrNull()
            if (grant != null) {
                storeRemoteKey(grant.cellarKey)
            } else {
                val key = CellarKey.random(membership.cellarId)
                storeRemoteKey(key)
                RemoteSync.grant(key, membership.active, store, identity, store.baseUrl)
            }
        }
        RemoteSyncWorker.schedule(context)
        startRemoteLoop()
        remoteRound()
    }

    /** A key and storage received from a device on the network. */
    fun adoptRemote(info: RemoteInfo?) {
        val membership = status.value.membership ?: return
        if (info == null || info.key.cellarId != membership.cellarId) return
        if (info.key.supersedes(storage.remoteKey)) storeRemoteKey(info.key)
        // Only a storage this device can use: an HTTPS relay. iCloud ("icloud") is Apple-only.
        val offered = info.storage
        if (storage.remoteStorage == null && offered != null && offered.lowercase(Locale.ROOT).startsWith("https://") &&
            RelayMailboxStore.isAllowed(offered)
        ) {
            storage.remoteStorage = offered
            status.update { it.copy(remoteStorage = offered) }
            RemoteSyncWorker.schedule(context)
            startRemoteLoop()
        }
    }

    /** Soon after a change, sends it away from home. */
    fun syncRemotely() {
        if (storage.remoteStorage == null) return
        pendingRemote?.cancel()
        pendingRemote = scope.launch {
            delay(REMOTE_DELAY_MS)
            remoteRound()
        }
    }

    /** While the app is open: a round every minute, to pick up changes from devices away. */
    private fun startRemoteLoop() {
        if (storage.remoteStorage == null || remoteLoop != null || isDemo) return
        remoteLoop = scope.launch {
            while (isActive) {
                remoteRound()
                delay(REMOTE_INTERVAL_MS)
            }
        }
    }

    /** One round through the relay. Also run by [RemoteSyncWorker] in the background. */
    suspend fun remoteRound() {
        val url = storage.remoteStorage ?: return
        if (status.value.wasRemoved || isDemo || !remoteMutex.tryLock()) return
        status.update { it.copy(syncingRemotely = true) }
        val round = generation
        try {
            val local = snapshot() ?: return
            val store = RelayMailboxStore(url)
            var key = storage.remoteKey
            if (key == null) {
                key = RemoteSync.receiveGrant(store, identity, local.membership, null)?.cellarKey
                if (round != generation) return
                key?.let(::storeRemoteKey)
            }
            if (key == null || key.cellarId != local.membership.cellarId) return
            key = try {
                rotateIfNeeded(store, key, local, url)
            } catch (_: Exception) {
                status.update { it.copy(remoteProblem = RemoteProblem.RemovalPending) }
                return
            }
            val current = synchronized(this) { progress }
            val result = RemoteSync.run(store, identity, key, local, current, photos.syncAccess)
            // The device left or joined another cellar meanwhile: drop the result.
            if (round != generation) return
            result.newKey?.let(::storeRemoteKey)
            // The device that gave the new key moved the files: follow it (an HTTPS relay only).
            val moved = result.storage
            if (result.newKey != null && moved != null && moved != url && moved.lowercase(Locale.ROOT).startsWith("https://") &&
                RelayMailboxStore.isAllowed(moved)
            ) {
                storage.remoteStorage = moved
                status.update { it.copy(remoteStorage = moved) }
            }
            synchronized(this) { progress = result.progress }
            withContext(Dispatchers.IO) { storage.saveRemoteProgress(result.progress) }
            result.snapshot?.let { apply(it, null) }
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
            status.update {
                it.copy(lastRemoteSync = Instant.now(), remoteProblem = if (result.unreadable.isEmpty()) null else RemoteProblem.Unreadable)
            }
        } catch (error: RemoteSyncException) {
            val problem = when (error.error) {
                RemoteSyncError.Unauthorized -> RemoteProblem.Refused
                RemoteSyncError.QuotaExceeded -> RemoteProblem.Full
                RemoteSyncError.FileTooLarge -> RemoteProblem.FileTooLarge
                else -> RemoteProblem.Unreachable
            }
            status.update { it.copy(remoteProblem = problem) }
        } catch (_: Exception) {
            status.update { it.copy(remoteProblem = RemoteProblem.Unreachable) }
        } finally {
            status.update { it.copy(syncingRemotely = false) }
            remoteMutex.unlock()
        }
    }

    /** Rotates the remote key if a removal is waiting for it, and returns the key to use. */
    private suspend fun rotateIfNeeded(store: RelayMailboxStore, key: CellarKey, local: SyncSnapshot, url: String): CellarKey {
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
        const val LOCAL_DELAY_MS = 2_000L
        private const val HANDSHAKE_DEADLINE_MS = 10_000L
        private const val SESSION_DEADLINE_MS = 300_000L
        private const val MAX_INBOUND_SESSIONS = 4
        const val REMOTE_DELAY_MS = 3_000L
        const val REMOTE_INTERVAL_MS = 60_000L
    }
}
