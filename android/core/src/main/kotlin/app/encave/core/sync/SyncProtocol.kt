package app.encave.core.sync

import app.encave.core.Cellar
import app.encave.core.UuidSerializer
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.serialization.Serializable

// The device-to-device protocol of docs/SYNC.md §4–7: pairing, then sync sessions.
// Ported from CaveKit's SyncProtocol.swift; ProtocolVectorTests checks the bytes.

object SyncProtocol {
    const val VERSION = 1

    /** Bonjour / NSD service type. */
    const val SERVICE_TYPE = "_encave._tcp"

    /** Largest frame accepted (a label photo is well under this). */
    const val MAX_FRAME_SIZE = 16 * 1024 * 1024
}

sealed class SyncError {
    /** The other device sent something unexpected or malformed. */
    data class ProtocolViolation(val detail: String) : SyncError()

    /** A key that does not match its commitment, or a proof that fails: someone may be in the middle. */
    data object AuthenticationFailed : SyncError()

    /** The other device does not know this one (yet), for example added recently on another device. */
    data object UnknownDevice : SyncError()

    /** This device was removed from the cellar. */
    data object RemovedDevice : SyncError()

    /** The other device belongs to another cellar. */
    data object OtherCellar : SyncError()

    /** The user said the codes do not match, or cancelled. */
    data object Rejected : SyncError()

    data class UnsupportedVersion(val version: Int) : SyncError()
    data object FrameTooLarge : SyncError()
    data object Closed : SyncError()
}

class SyncException(val error: SyncError) : Exception(error.toString())

// region Identity

/** This device's long-term key. The private key stays on the device (encrypted with the Android Keystore). */
class DeviceIdentity(val id: UUID, privateKeyData: ByteArray) {
    val privateKey = X25519Key(privateKeyData)
    val publicKey: ByteArray get() = privateKey.publicKey

    /** The device's inbox, where other devices leave key grants for it. */
    val inboxId: String get() = inboxId(publicKey)

    /** Proves to a relay the right to read this inbox. */
    val inboxToken: String
        get() = KeySchedule.derive(privateKey.rawRepresentation, ByteArray(0), "encave/v1/inbox-auth").base64Url()

    companion object {
        fun generate(id: UUID = UUID.randomUUID()): Pair<DeviceIdentity, ByteArray> {
            val key = X25519Key.generate().rawRepresentation
            return DeviceIdentity(id, key) to key
        }

        fun inboxId(publicKey: ByteArray): String =
            "i" + KeySchedule.hash("encave-inbox-v1", listOf(publicKey)).copyOf(16).hex()
    }
}

// endregion

// region Wire messages

/** One JSON object per frame, with a "type". Absent fields are left out. */
@Serializable
data class WireMessage(
    val type: String,
    val version: Int? = null,
    @Serializable(with = UuidSerializer::class) val device: UUID? = null,
    val name: String? = null,
    val platform: String? = null,
    @Serializable(with = UuidSerializer::class) val cellar: UUID? = null,
    /** Base64: a commitment, ephemeral or identity public key, nonce or proof. */
    @Serializable(with = Base64Serializer::class) val commitment: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val key: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val identityKey: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val proof: ByteArray? = null,
    val accepted: Boolean? = null,
    /** Sync payloads: JSON documents, as Base64. */
    @Serializable(with = Base64Serializer::class) val cellarData: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val syncData: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val membersData: ByteArray? = null,
    val photos: List<String>? = null,
    @kotlinx.serialization.SerialName("photoID") val photoId: String? = null,
    @Serializable(with = Base64Serializer::class) val photoData: ByteArray? = null,
    val reason: String? = null,
) {
    fun encoded(): ByteArray = SyncJson.encode(serializer(), this)

    /** This message if it has the expected type; an "error" from the other device becomes its [SyncError]. */
    fun require(expected: String): WireMessage {
        if (type == "error") {
            throw SyncException(
                when (reason) {
                    "unknown-device" -> SyncError.UnknownDevice
                    "removed-device" -> SyncError.RemovedDevice
                    "other-cellar" -> SyncError.OtherCellar
                    "rejected" -> SyncError.Rejected
                    else -> SyncError.ProtocolViolation(reason ?: "the other device stopped")
                },
            )
        }
        if (type != expected) throw SyncException(SyncError.ProtocolViolation("expected $expected, got $type"))
        return this
    }

    // Byte arrays: equality by content, for tests.
    override fun equals(other: Any?): Boolean = other is WireMessage && encoded().contentEquals(other.encoded())
    override fun hashCode(): Int = encoded().contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): WireMessage = try {
            SyncJson.decode(serializer(), bytes)
        } catch (_: Exception) {
            throw SyncException(SyncError.ProtocolViolation("malformed message"))
        }
    }
}

// endregion

// region Transport and secure channel

/** A reliable, ordered connection carrying whole frames (length-prefixed on the wire). */
interface FrameTransport {
    suspend fun send(frame: ByteArray)
    suspend fun receive(): ByteArray
    fun close()
}

/**
 * Encrypts frames with ChaCha20-Poly1305. Each direction has its own key and a
 * counter nonce, so frames cannot be replayed, reordered or reflected.
 */
class SecureChannel(private val transport: FrameTransport, private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var sent = 0L
    private var received = 0L

    suspend fun send(message: WireMessage) {
        val sealed = ChaChaPoly.seal(message.encoded(), sendKey, nonce(sent))
        sent += 1
        // The nonce is implied by the counter: only ciphertext and tag are sent.
        transport.send(sealed)
    }

    suspend fun receive(): WireMessage {
        val frame = transport.receive()
        val plain = ChaChaPoly.open(frame, receiveKey, nonce(received)) ?: throw SyncException(SyncError.AuthenticationFailed)
        received += 1
        return WireMessage.decode(plain)
    }

    fun close() = transport.close()

    companion object {
        /** 4 zero bytes, then the frame counter as an 8-byte big-endian integer. */
        fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
    }
}

// endregion

// region Pairing

/**
 * A one-time pairing code: six digits shown by the device that invites, typed or
 * scanned on the new one. It only finds the right device on the network; the
 * security comes from the confirmation code both users compare.
 */
data class PairingCode(private val raw: String) {
    val digits: String = raw.filter { it in '0'..'9' }

    val isValid: Boolean get() = digits.length == 6

    /** Advertised on the network instead of the code itself. */
    val rendezvous: String get() = KeySchedule.hash("encave-pair-rendezvous-v1", listOf(digits.toByteArray())).copyOf(8).hex()

    /** Contents of the QR code: the code and the inviting device's commitment. */
    fun qrPayload(commitment: ByteArray): String = "encave://pair?code=$digits&c=${commitment.base64Url()}"

    override fun equals(other: Any?): Boolean = other is PairingCode && digits == other.digits
    override fun hashCode(): Int = digits.hashCode()

    companion object {
        fun random(): PairingCode = PairingCode("%06d".format(random.nextInt(1_000_000)))

        /** Reads a QR code made by [qrPayload]: the code and the commitment, if any. */
        fun parse(qr: String): Pair<PairingCode, ByteArray?>? {
            val uri = runCatching { URI(qr.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "encave" || uri.host != "pair") return null
            val query = uri.rawQuery?.split("&").orEmpty().mapNotNull { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            val code = PairingCode(query["code"] ?: return null)
            if (!code.isValid) return null
            return code to query["c"]?.base64UrlBytes()
        }
    }
}

/** Who a device is, as told during pairing. */
data class PeerInfo(val device: UUID, val name: String, val platform: String)

/** Result of the key exchange, before the users confirm. */
class PairingKeys internal constructor(
    /** Shown on both devices; the users check they are the same. */
    val confirmationCode: String,
    val peer: PeerInfo,
    internal val hostToJoiner: ByteArray,
    internal val joinerToHost: ByteArray,
)

internal object PairingCrypto {
    data class Keys(val code: String, val h2j: ByteArray, val j2h: ByteArray)

    fun keys(
        ephemeral: X25519Key, otherKey: ByteArray, hostKey: ByteArray, joinerKey: ByteArray,
        hostNonce: ByteArray, joinerNonce: ByteArray, host: PeerInfo, joiner: PeerInfo,
    ): Keys {
        val shared = ephemeral.agree(otherKey)
        val transcript = KeySchedule.hash(
            "encave-pair-v1",
            listOf(hostKey, joinerKey, hostNonce, joinerNonce, host.device.lower.toByteArray(), joiner.device.lower.toByteArray()),
        )
        return Keys(
            KeySchedule.confirmationCode(KeySchedule.derive(shared, transcript, "encave pair code", 4)),
            KeySchedule.derive(shared, transcript, "encave pair host to joiner"),
            KeySchedule.derive(shared, transcript, "encave pair joiner to host"),
        )
    }

    fun commitment(key: ByteArray, nonce: ByteArray) = KeySchedule.hash("encave-pair-commit-v1", listOf(key, nonce))
}

private suspend fun FrameTransport.sendMessage(message: WireMessage) = send(message.encoded())
private suspend fun FrameTransport.receiveMessage(): WireMessage = WireMessage.decode(receive())

/**
 * The device that invites (shows the code). One instance per code.
 *
 * Messages: host → commit; joiner → key; host → reveal. Both then show the
 * confirmation code; after both users accept, each sends "confirm" over the
 * encrypted channel, then the host sends "welcome" (cellar ID and members) and
 * the joiner answers with its member record.
 */
class PairingHost(
    private val identity: DeviceIdentity,
    name: String,
    platform: String,
    private val ephemeral: X25519Key = X25519Key.generate(),
    private val nonce: ByteArray = KeySchedule.randomNonce(),
) {
    private val info = PeerInfo(identity.id, name, platform)

    /** Hash of the key and nonce the host will reveal. Also in the QR code. */
    val commitment: ByteArray get() = PairingCrypto.commitment(ephemeral.publicKey, nonce)

    /** Runs the key exchange. Returns the keys and the confirmation code to show. */
    suspend fun exchangeKeys(transport: FrameTransport): PairingKeys {
        transport.sendMessage(WireMessage("pair-commit", version = SyncProtocol.VERSION, commitment = commitment))
        val key = transport.receiveMessage().require("pair-key")
        if (key.version != SyncProtocol.VERSION) throw SyncException(SyncError.UnsupportedVersion(key.version ?: 0))
        val joinerKey = key.key
        val joinerNonce = key.nonce
        val device = key.device
        if (joinerKey == null || joinerNonce == null || joinerNonce.size != 32 || device == null) {
            throw SyncException(SyncError.ProtocolViolation("incomplete pair-key"))
        }
        val joiner = PeerInfo(device, key.name ?: "", key.platform ?: "")
        transport.sendMessage(
            WireMessage(
                "pair-reveal", key = ephemeral.publicKey, nonce = nonce,
                device = info.device, name = info.name, platform = info.platform,
            ),
        )
        val result = PairingCrypto.keys(ephemeral, joinerKey, ephemeral.publicKey, joinerKey, nonce, joinerNonce, info, joiner)
        return PairingKeys(result.code, joiner, result.h2j, result.j2h)
    }

    /**
     * After the user accepted the code: waits for the other device's answer, then
     * sends the cellar ID and members. Returns the channel, ready for the first sync,
     * and the new member.
     */
    suspend fun finish(transport: FrameTransport, keys: PairingKeys, accepted: Boolean, membership: Membership): Pair<SecureChannel, Member> {
        val channel = SecureChannel(transport, keys.hostToJoiner, keys.joinerToHost)
        channel.send(WireMessage("pair-confirm", accepted = accepted))
        val answer = channel.receive().require("pair-confirm")
        if (!accepted || answer.accepted != true) throw SyncException(SyncError.Rejected)
        val me = Member(info.device, info.name, identity.publicKey, info.platform)
        val members = if (membership.members.containsKey(me.id)) membership else membership.copy(members = membership.members + (me.id to me))
        channel.send(
            WireMessage(
                "pair-welcome", cellar = membership.cellarId, identityKey = identity.publicKey,
                membersData = SyncJson.encode(Membership.serializer(), members),
            ),
        )
        val reply = channel.receive().require("pair-member")
        val identityKey = reply.identityKey
        if (identityKey == null || identityKey.size != 32) throw SyncException(SyncError.ProtocolViolation("missing identity key"))
        return channel to Member(keys.peer.device, keys.peer.name, identityKey, keys.peer.platform)
    }
}

/** The new device (types or scans the code). */
class PairingJoiner(
    private val identity: DeviceIdentity,
    name: String,
    platform: String,
    /** From the QR code, if scanned: the host must reveal a key matching it. */
    private val expectedCommitment: ByteArray? = null,
    private val ephemeral: X25519Key = X25519Key.generate(),
    private val nonce: ByteArray = KeySchedule.randomNonce(),
) {
    private val info = PeerInfo(identity.id, name, platform)

    suspend fun exchangeKeys(transport: FrameTransport): PairingKeys {
        val commit = transport.receiveMessage().require("pair-commit")
        if (commit.version != SyncProtocol.VERSION) throw SyncException(SyncError.UnsupportedVersion(commit.version ?: 0))
        val commitment = commit.commitment ?: throw SyncException(SyncError.ProtocolViolation("missing commitment"))
        if (expectedCommitment != null && !expectedCommitment.contentEquals(commitment)) throw SyncException(SyncError.AuthenticationFailed)
        transport.sendMessage(
            WireMessage(
                "pair-key", version = SyncProtocol.VERSION, key = ephemeral.publicKey, nonce = nonce,
                device = info.device, name = info.name, platform = info.platform,
            ),
        )
        val reveal = transport.receiveMessage().require("pair-reveal")
        val hostKey = reveal.key
        val hostNonce = reveal.nonce
        val device = reveal.device
        if (hostKey == null || hostNonce == null || hostNonce.size != 32 || device == null) {
            throw SyncException(SyncError.ProtocolViolation("incomplete pair-reveal"))
        }
        // The host committed to this key before seeing ours: it cannot have chosen it
        // to make the confirmation codes match.
        if (!KeySchedule.constantTimeEquals(PairingCrypto.commitment(hostKey, hostNonce), commitment)) {
            throw SyncException(SyncError.AuthenticationFailed)
        }
        val host = PeerInfo(device, reveal.name ?: "", reveal.platform ?: "")
        val result = PairingCrypto.keys(ephemeral, hostKey, hostKey, ephemeral.publicKey, hostNonce, nonce, host, info)
        return PairingKeys(result.code, host, result.h2j, result.j2h)
    }

    /** After the user accepted the code: receives the cellar and its members. */
    suspend fun finish(transport: FrameTransport, keys: PairingKeys, accepted: Boolean): Pair<SecureChannel, Membership> {
        val channel = SecureChannel(transport, keys.joinerToHost, keys.hostToJoiner)
        channel.send(WireMessage("pair-confirm", accepted = accepted))
        val answer = channel.receive().require("pair-confirm")
        if (!accepted || answer.accepted != true) throw SyncException(SyncError.Rejected)
        val welcome = channel.receive().require("pair-welcome")
        val data = welcome.membersData
        val hostKey = welcome.identityKey
        var membership = data?.let { runCatching { SyncJson.decode(Membership.serializer(), it) }.getOrNull() }
        if (membership == null || hostKey == null || hostKey.size != 32) throw SyncException(SyncError.ProtocolViolation("incomplete welcome"))
        // The host's own record must carry the key it just proved it holds.
        membership.members[keys.peer.device]?.let { host ->
            membership = membership!!.copy(members = membership!!.members + (host.id to host.copy(publicKey = hostKey)))
        }
        channel.send(WireMessage("pair-member", identityKey = identity.publicKey))
        return channel to membership!!
    }
}

// endregion

// region Sync session handshake

/**
 * Opens an authenticated session between two devices of the same cellar, in the
 * style of the Noise KK pattern: ephemeral keys for forward secrecy, identity keys
 * to prove each side is a current member.
 */
object SyncHandshake {
    class Keys(val auth: ByteArray, val i2r: ByteArray, val r2i: ByteArray)

    /** The device that connects. */
    suspend fun initiate(
        transport: FrameTransport, identity: DeviceIdentity, membership: Membership,
        ephemeral: X25519Key = X25519Key.generate(), nonce: ByteArray = KeySchedule.randomNonce(),
    ): Pair<SecureChannel, Member> {
        transport.sendMessage(
            WireMessage(
                "sync-hello", version = SyncProtocol.VERSION, cellar = membership.cellarId, device = identity.id,
                key = ephemeral.publicKey, nonce = nonce,
            ),
        )
        val accept = transport.receiveMessage().require("sync-accept")
        val device = accept.device
        val otherKey = accept.key
        val otherNonce = accept.nonce
        val proof = accept.proof
        if (device == null || otherKey == null || otherNonce == null || otherNonce.size != 32 || proof == null) {
            throw SyncException(SyncError.ProtocolViolation("incomplete sync-accept"))
        }
        val peer = membership.members[device] ?: throw SyncException(SyncError.UnknownDevice)
        val keys = sessionKeys(
            initiator = identity.id, responder = device, initiatorKey = ephemeral.publicKey, responderKey = otherKey,
            initiatorNonce = nonce, responderNonce = otherNonce, cellar = membership.cellarId,
            ee = ephemeral.agree(otherKey), es = ephemeral.agree(peer.publicKey), se = identity.privateKey.agree(otherKey),
        )
        if (!KeySchedule.constantTimeEquals(KeySchedule.hmac(keys.auth, "responder".toByteArray()), proof)) {
            throw SyncException(SyncError.AuthenticationFailed)
        }
        transport.sendMessage(WireMessage("sync-confirm", proof = KeySchedule.hmac(keys.auth, "initiator".toByteArray())))
        return SecureChannel(transport, keys.i2r, keys.r2i) to peer
    }

    /** The device that accepts the connection. */
    suspend fun respond(transport: FrameTransport, identity: DeviceIdentity, membership: Membership): Pair<SecureChannel, Member> {
        val hello = transport.receiveMessage().require("sync-hello")
        // Refusals are told to the other device, so it can say why (for example "removed").
        suspend fun refuse(reason: String, error: SyncError): SyncException {
            runCatching { transport.sendMessage(WireMessage("error", reason = reason)) }
            return SyncException(error)
        }
        if (hello.version != SyncProtocol.VERSION) throw refuse("unsupported-version", SyncError.UnsupportedVersion(hello.version ?: 0))
        if (hello.cellar != membership.cellarId) throw refuse("other-cellar", SyncError.OtherCellar)
        val device = hello.device
        val otherKey = hello.key
        val otherNonce = hello.nonce
        if (device == null || otherKey == null || otherNonce == null || otherNonce.size != 32) {
            throw SyncException(SyncError.ProtocolViolation("incomplete sync-hello"))
        }
        val peer = membership.members[device]
        if (peer == null || device == identity.id) {
            if (membership.stamps[device]?.deleted == true) throw refuse("removed-device", SyncError.RemovedDevice)
            throw refuse("unknown-device", SyncError.UnknownDevice)
        }
        val ephemeral = X25519Key.generate()
        val nonce = KeySchedule.randomNonce()
        val keys = sessionKeys(
            initiator = device, responder = identity.id, initiatorKey = otherKey, responderKey = ephemeral.publicKey,
            initiatorNonce = otherNonce, responderNonce = nonce, cellar = membership.cellarId,
            ee = ephemeral.agree(otherKey), es = identity.privateKey.agree(otherKey), se = ephemeral.agree(peer.publicKey),
        )
        transport.sendMessage(
            WireMessage(
                "sync-accept", device = identity.id, key = ephemeral.publicKey, nonce = nonce,
                proof = KeySchedule.hmac(keys.auth, "responder".toByteArray()),
            ),
        )
        val confirm = transport.receiveMessage().require("sync-confirm")
        val proof = confirm.proof
        if (proof == null || !KeySchedule.constantTimeEquals(KeySchedule.hmac(keys.auth, "initiator".toByteArray()), proof)) {
            throw SyncException(SyncError.AuthenticationFailed)
        }
        return SecureChannel(transport, keys.r2i, keys.i2r) to peer
    }

    /** `es` is the initiator's ephemeral with the responder's identity, `se` the initiator's identity with the responder's ephemeral. */
    fun sessionKeys(
        initiator: UUID, responder: UUID, initiatorKey: ByteArray, responderKey: ByteArray,
        initiatorNonce: ByteArray, responderNonce: ByteArray, cellar: UUID,
        ee: ByteArray, es: ByteArray, se: ByteArray,
    ): Keys {
        val transcript = KeySchedule.hash(
            "encave-sync-v1",
            listOf(
                cellar.lower.toByteArray(), initiator.lower.toByteArray(), responder.lower.toByteArray(),
                initiatorKey, responderKey, initiatorNonce, responderNonce,
            ),
        )
        val secret = ee + es + se
        return Keys(
            KeySchedule.derive(secret, transcript, "encave sync auth"),
            KeySchedule.derive(secret, transcript, "encave sync initiator to responder"),
            KeySchedule.derive(secret, transcript, "encave sync responder to initiator"),
        )
    }
}

// endregion

// region Exchange

/** What a device brings to a sync: its copy of the cellar, its sync state and the devices. */
data class SyncSnapshot(val cellar: Cellar, val state: SyncState, val membership: Membership)

/** Access to label photos for a sync (content-addressed by their ID). */
interface PhotoAccess {
    fun data(id: String): ByteArray?
    fun save(id: String, data: ByteArray)

    companion object {
        val NONE = object : PhotoAccess {
            override fun data(id: String): ByteArray? = null
            override fun save(id: String, data: ByteArray) = Unit
        }
    }
}

/**
 * Remote sync settings shared between devices that meet on the network: the cellar
 * key and where the files are. Sent only over the encrypted channel, never stored
 * in a snapshot.
 */
@Serializable
data class RemoteInfo(val key: CellarKey, val storage: String? = null)

object SyncExchange {
    /**
     * Sends this device's copy, receives the other's, returns the merge (the same on
     * both sides) and swaps the photos each side is missing. The initiator sends first
     * at every step, so neither side waits on a full buffer.
     */
    suspend fun run(channel: SecureChannel, isInitiator: Boolean, local: SyncSnapshot, photos: PhotoAccess): SyncSnapshot =
        exchange(channel, isInitiator, local, photos, remote = null).first

    /**
     * [run], then a last step where each side sends its remote sync key and storage
     * (or nothing), so a device that turned remote sync on passes it to the others.
     */
    suspend fun exchange(
        channel: SecureChannel, isInitiator: Boolean, local: SyncSnapshot, photos: PhotoAccess, remote: RemoteInfo?,
    ): Pair<SyncSnapshot, RemoteInfo?> {
        val snapshot = mergeAndSwapPhotos(channel, isInitiator, local, photos)
        val mine = WireMessage("sync-keys", syncData = remote?.let { SyncJson.encode(RemoteInfo.serializer(), it) })
        val theirs = swap(channel, isInitiator, mine, "sync-keys")
        val received = theirs.syncData?.let { runCatching { SyncJson.decode(RemoteInfo.serializer(), it) }.getOrNull() }
        // A key for another cellar is not this cellar's.
        return snapshot to received?.takeIf { it.key.cellarId == snapshot.membership.cellarId }
    }

    private suspend fun swap(channel: SecureChannel, isInitiator: Boolean, mine: WireMessage, type: String): WireMessage =
        if (isInitiator) {
            channel.send(mine)
            channel.receive().require(type)
        } else {
            channel.receive().require(type).also { channel.send(mine) }
        }

    private suspend fun mergeAndSwapPhotos(channel: SecureChannel, isInitiator: Boolean, local: SyncSnapshot, photos: PhotoAccess): SyncSnapshot {
        val mine = WireMessage(
            "sync-state",
            cellarData = SyncJson.encodeCellar(local.cellar),
            syncData = SyncJson.encode(SyncState.serializer(), local.state),
            membersData = SyncJson.encode(Membership.serializer(), local.membership),
        )
        val theirs = swap(channel, isInitiator, mine, "sync-state")
        val remote = runCatching {
            SyncSnapshot(
                SyncJson.decodeCellar(theirs.cellarData!!),
                SyncJson.decode(SyncState.serializer(), theirs.syncData!!),
                SyncJson.decode(Membership.serializer(), theirs.membersData!!),
            )
        }.getOrElse { throw SyncException(SyncError.ProtocolViolation("unreadable sync-state")) }
        if (remote.membership.cellarId != local.membership.cellarId) throw SyncException(SyncError.OtherCellar)

        val merge = CellarMerge.merge(local.cellar, local.state, remote.cellar, remote.state)
        val result = SyncSnapshot(
            merge.cellar,
            local.state.copy(stamps = merge.stamps, clock = merge.clock),
            local.membership.merged(remote.membership),
        )

        // Photos: each side asks for the ones it is missing.
        val known = merge.cellar.wines.values.mapNotNull { it.photoId }.toSet()
        val wanted = known.filter { photos.data(it) == null }.sorted()
        val request = swap(channel, isInitiator, WireMessage("photos-want", photos = wanted), "photos-want")
        // Only photos of the merged cellar are sent, whatever is asked.
        val toSend = request.photos.orEmpty().filter { it in known }

        suspend fun sendPhotos() {
            for (id in toSend) {
                val data = photos.data(id) ?: continue
                channel.send(WireMessage("photo", photoId = id, photoData = data))
            }
            channel.send(WireMessage("photos-end"))
        }
        suspend fun receivePhotos() {
            val expected = wanted.toSet()
            while (true) {
                val message = channel.receive()
                if (message.type == "photos-end") break
                val photo = message.require("photo")
                val id = photo.photoId ?: continue
                val data = photo.photoData ?: continue
                if (id in expected && PhotoId.matches(id, data)) photos.save(id, data)
            }
        }
        if (isInitiator) {
            sendPhotos()
            receivePhotos()
        } else {
            receivePhotos()
            sendPhotos()
        }
        return result
    }
}

/**
 * Label photos are named after their content when synced, so a device can check
 * that a photo it receives is the one it asked for.
 */
object PhotoId {
    private val allowed = Regex("^[A-Za-z0-9-]{1,80}$")

    /** IDs of photos taken before sync existed are not content hashes: accepted as they are. */
    fun matches(id: String, data: ByteArray): Boolean {
        if (!allowed.matches(id)) return false
        if (!id.startsWith("sha256-")) return true
        return id == make(data)
    }

    fun make(data: ByteArray): String = "sha256-" + KeySchedule.sha256(data).hex()

    /** Letters, digits and dashes, at most 80 characters (SYNC.md §7): safe as a file name. */
    fun isValid(id: String): Boolean = allowed.matches(id)
}

// endregion
