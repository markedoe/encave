package app.encave.sync

import app.encave.core.sync.PairingCode
import app.encave.core.sync.PairingHost
import app.encave.core.sync.PairingJoiner
import app.encave.core.sync.SyncError
import app.encave.core.sync.SyncException
import app.encave.core.sync.SyncExchange
import java.io.Closeable
import java.net.ServerSocket
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One attempt to add a device, seen from either side: the device that invites
 * (shows a code) or the device that joins (types or scans it). See docs/SYNC.md §5.
 */
class PairingSession private constructor(
    val role: Role,
    /** Inviting: the code to show and to put in the QR code. Joining: the code typed. */
    val code: PairingCode,
    private val sync: SyncManager,
    private val scope: CoroutineScope,
) {
    enum class Role { Inviting, Joining }

    sealed class Phase {
        /** Inviting: waiting for the other device. Joining: looking for it. */
        data object Waiting : Phase()

        /** Both devices show this code; the user checks they match. */
        data class Confirm(val code: String, val device: String) : Phase()

        /** The user accepted; waiting for the other device to accept too. */
        data object WaitingForOther : Phase()
        data object Syncing : Phase()
        data class Done(val device: String) : Phase()
        data class Failed(val reason: Failure) : Phase()
    }

    enum class Failure { Expired, NotFound, Rejected, NotVerified, OtherVersion, ConnectionLost, CannotListen }

    private val phaseFlow = MutableStateFlow<Phase>(Phase.Waiting)
    val phase: StateFlow<Phase> = phaseFlow.asStateFlow()

    /** Inviting: the QR code contents. */
    var qrPayload: String = ""
        private set

    /** When the code stops working. */
    val expiresAt: Instant = Instant.now().plusSeconds(LIFETIME_SECONDS)

    @Volatile private var decision: CompletableDeferred<Boolean>? = null
    private var job: Job? = null
    private var timer: Job? = null
    private var server: ServerSocket? = null
    private var advertisement: Closeable? = null
    private var browsing: Closeable? = null
    private var transport: SocketTransport? = null
    private val network = LocalNetwork(sync.appContext)

    // region Inviting

    private fun listen(host: PairingHost) {
        val socket = runCatching { ServerSocket(0) }.getOrElse {
            phaseFlow.value = Phase.Failed(Failure.CannotListen)
            return
        }
        server = socket
        advertisement = network.advertise(
            "Encave-pair-" + code.rendezvous.take(6), socket.localPort, mapOf("p" to code.rendezvous),
            onFailure = { phaseFlow.value = Phase.Failed(Failure.CannotListen) },
        )
        // Key exchange, merging and photos run off the main thread.
        job = scope.launch(Dispatchers.Default) {
            // One pairing per code: the first device to connect is the only one.
            val accepted = withContext(Dispatchers.IO) { runCatching { socket.accept() }.getOrNull() } ?: return@launch
            timer?.cancel()
            stopListening()
            val transport = SocketTransport(accepted, SocketTransport.PAIRING_TIMEOUT_MS).also { this@PairingSession.transport = it }
            // Set once the device is added: from then on, a failed first sync is not a failed
            // pairing (the next sync completes it).
            var added: String? = null
            try {
                // A device that connects and stays silent must not hold the code.
                val keyDeadline = transport.closing(scope, KEY_DEADLINE_MS)
                val keys = host.exchangeKeys(transport)
                keyDeadline.cancel()
                val ok = ask(keys.confirmationCode, keys.peer.name)
                val answerDeadline = transport.closing(scope, LIFETIME_SECONDS * 1000)
                val membership = sync.state.value.membership ?: throw SyncException(SyncError.Closed)
                val (channel, member) = host.finish(transport, keys, ok, membership)
                answerDeadline.cancel()
                transport.allowLargeFrames()
                sync.addMember(member)
                added = member.name
                phaseFlow.value = Phase.Syncing
                val local = sync.snapshot() ?: throw SyncException(SyncError.Closed)
                val (result, remote) = SyncExchange.exchange(channel, true, local, syncPhotos(), sync.remoteInfo())
                sync.apply(result, member.id)
                sync.adoptRemote(remote)
                sync.resume()
                phaseFlow.value = Phase.Done(member.name)
            } catch (error: CancellationException) {
                stop()
                throw error
            } catch (error: Exception) {
                added?.let(::finishWithoutSync) ?: fail(error)
            } finally {
                transport.close()
            }
        }
        timer = scope.launch {
            delay(LIFETIME_SECONDS * 1000)
            if (phaseFlow.value == Phase.Waiting) {
                stop()
                phaseFlow.value = Phase.Failed(Failure.Expired)
            }
        }
    }

    // endregion

    // region Joining

    private fun find(commitment: ByteArray?, replace: Boolean) {
        val rendezvous = code.rendezvous
        browsing = network.browse(
            onFound = { service ->
                if (service.attributes["p"] == rendezvous) scope.launch { connect(service, commitment, replace) }
            },
            onLost = {},
        )
        timer = scope.launch {
            delay(FIND_TIMEOUT_MS)
            if (phaseFlow.value == Phase.Waiting && transport == null) {
                stop()
                phaseFlow.value = Phase.Failed(Failure.NotFound)
            }
        }
    }

    private fun connect(service: LocalNetwork.Found, commitment: ByteArray?, replace: Boolean) {
        synchronized(this) {
            if (job != null) return
            job = scope.launch(Dispatchers.Default) {
                timer?.cancel()
                browsing?.close()
                browsing = null
                val joiner = PairingJoiner(sync.identity, sync.deviceName, sync.platform, commitment)
                var transport: SocketTransport? = null
                var added: String? = null
                try {
                    transport = SocketTransport.connect(service.address, SocketTransport.PAIRING_TIMEOUT_MS)
                    this@PairingSession.transport = transport
                    val keyDeadline = transport.closing(scope, KEY_DEADLINE_MS)
                    val keys = joiner.exchangeKeys(transport)
                    keyDeadline.cancel()
                    val ok = ask(keys.confirmationCode, keys.peer.name)
                    val answerDeadline = transport.closing(scope, LIFETIME_SECONDS * 1000)
                    val (channel, received) = joiner.finish(transport, keys, ok)
                    answerDeadline.cancel()
                    transport.allowLargeFrames()
                    sync.adoptCellar(received, replace)
                    added = keys.peer.name
                    phaseFlow.value = Phase.Syncing
                    val local = sync.snapshot() ?: throw SyncException(SyncError.Closed)
                    val (result, remote) = SyncExchange.exchange(channel, false, local, syncPhotos(), sync.remoteInfo())
                    sync.apply(result, keys.peer.device)
                    sync.adoptRemote(remote)
                    sync.resume()
                    phaseFlow.value = Phase.Done(keys.peer.name)
                } catch (error: CancellationException) {
                    stop()
                    throw error
                } catch (error: Exception) {
                    added?.let(::finishWithoutSync) ?: fail(error)
                } finally {
                    transport?.close()
                }
            }
        }
    }

    // endregion

    // region Confirmation

    private suspend fun ask(code: String, device: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        decision = answer
        phaseFlow.value = Phase.Confirm(code, device)
        val accepted = answer.await()
        if (accepted) phaseFlow.value = Phase.WaitingForOther
        return accepted
    }

    /** The user's answer to "Does the other device show the same code?". */
    fun answer(accepted: Boolean) {
        decision?.complete(accepted)
        decision = null
    }

    fun cancel() {
        answer(false)
        stop()
    }

    private fun stopListening() {
        advertisement?.close()
        advertisement = null
        runCatching { server?.close() }
        server = null
    }

    private fun stop() {
        timer?.cancel()
        stopListening()
        browsing?.close()
        browsing = null
        transport?.close()
    }

    /** Paired, but the first sync did not finish: the devices complete it the next time they meet. */
    private fun finishWithoutSync(device: String) {
        stop()
        sync.resume()
        phaseFlow.value = Phase.Done(device)
    }

    private fun fail(error: Exception) {
        stop()
        if (phaseFlow.value is Phase.Done) return
        phaseFlow.value = Phase.Failed(
            when ((error as? SyncException)?.error) {
                SyncError.Rejected -> Failure.Rejected
                SyncError.AuthenticationFailed -> Failure.NotVerified
                is SyncError.UnsupportedVersion -> Failure.OtherVersion
                else -> Failure.ConnectionLost
            },
        )
    }

    // endregion

    private fun syncPhotos() = sync.photoAccess

    /** Screen tour: shows the confirmation step without a network. */
    fun showDemoConfirmation() {
        phaseFlow.value = Phase.Confirm("482 913", "iPad")
    }

    companion object {
        /** A code works for 2 minutes. */
        const val LIFETIME_SECONDS = 120L
        private const val FIND_TIMEOUT_MS = 30_000L
        private const val KEY_DEADLINE_MS = 20_000L

        /** Shows a new code and waits for a device to join with it. */
        suspend fun invite(sync: SyncManager, scope: CoroutineScope): PairingSession {
            sync.setUpIfNeeded()
            val session = PairingSession(Role.Inviting, PairingCode.random(), sync, scope)
            val host = PairingHost(sync.identity, sync.deviceName, sync.platform)
            session.qrPayload = session.code.qrPayload(host.commitment)
            if (!sync.isDemo) session.listen(host)
            return session
        }

        /** Looks for the device showing [code] and joins its cellar. */
        fun join(code: PairingCode, commitment: ByteArray?, replace: Boolean, sync: SyncManager, scope: CoroutineScope): PairingSession {
            val session = PairingSession(Role.Joining, code, sync, scope)
            if (!sync.isDemo) session.find(commitment, replace)
            return session
        }
    }
}
