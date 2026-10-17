package app.encave.core.sync

import app.encave.core.UuidSerializer
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable

// Remote sync (docs/SYNC.md §8): devices that are apart exchange encrypted files
// through storage that only ever holds ciphertext. Ported from RemoteSync.swift.
// Every file is written once by one device, so any storage that can list, read,
// create and delete files works, without locks or server logic.

// region Storage

/** A namespace of files on some storage. Paths use "/" and never start with it. */
interface Mailbox {
    /** Paths starting with [prefix]. */
    suspend fun list(prefix: String): List<String>

    /** Null if there is no such file. */
    suspend fun get(path: String): ByteArray?

    /** Creates a file. Returns false (and changes nothing) if it already exists. */
    suspend fun create(path: String, data: ByteArray): Boolean

    suspend fun delete(path: String)
}

/** Opens mailboxes on one storage (one relay…). */
interface MailboxStore {
    /** [token] proves the right to read and write (the relay stores only its hash). */
    fun mailbox(id: String, token: String): Mailbox

    /** A device's inbox: anyone may drop a key grant in it, only its owner reads it. */
    fun inbox(id: String, token: String): Mailbox
}

sealed class RemoteSyncError {
    data object Unauthorized : RemoteSyncError()
    data object QuotaExceeded : RemoteSyncError()
    data class Unavailable(val detail: String) : RemoteSyncError()
    data class Corrupted(val path: String) : RemoteSyncError()
}

class RemoteSyncException(val error: RemoteSyncError) : Exception(error.toString())

// endregion

// region Keys

/**
 * The key shared by the devices of a cellar for remote sync. Changes ("epoch")
 * when a device is removed, so the removed device cannot read anything new.
 */
@Serializable
data class CellarKey(
    @kotlinx.serialization.SerialName("cellarID") @Serializable(with = UuidSerializer::class) val cellarId: UUID,
    /** UInt32 on the wire. */
    val epoch: Long,
    @Serializable(with = Base64Serializer::class) val key: ByteArray,
) {
    internal fun derive(info: String, count: Int = 32): ByteArray = KeySchedule.derive(key, ByteArray(0), info, count)

    /**
     * Where this epoch's files are: "c" and the start of the token's SHA-256, so a relay can
     * check the token without storing anything. Changes with the key.
     */
    val mailboxId: String get() = RelayAuth.mailboxId("c", token)

    /**
     * Whether this key replaces [other]: a later epoch, or on the same epoch (two devices
     * turned remote sync on, or rotated, at the same time) the key whose SHA-256 is smaller,
     * so every device settles on the same one.
     */
    fun supersedes(other: CellarKey?): Boolean {
        if (other == null || other.cellarId != cellarId) return other == null
        if (epoch != other.epoch) return epoch > other.epoch
        return java.util.Arrays.compareUnsigned(KeySchedule.sha256(key), KeySchedule.sha256(other.key)) < 0
    }

    /** Proves to a relay the right to use the mailbox. */
    val token: String get() = derive("encave/v1/relay-auth").base64Url()

    internal val dataKey: ByteArray get() = derive("encave/v1/data")

    override fun equals(other: Any?): Boolean =
        other is CellarKey && cellarId == other.cellarId && epoch == other.epoch && key.contentEquals(other.key)

    override fun hashCode(): Int = listOf(cellarId, epoch, key.contentHashCode()).hashCode()

    companion object {
        fun random(cellarId: UUID, epoch: Long = 1): CellarKey = CellarKey(cellarId, epoch, KeySchedule.randomNonce())
    }
}

// endregion

/** How mailbox IDs follow from their tokens (the relay applies the same rule). */
object RelayAuth {
    /** [kind] ("c" or "i") followed by the first 32 hex characters of SHA-256 of the UTF-8 token. */
    fun mailboxId(kind: String, token: String): String = kind + KeySchedule.sha256(token.toByteArray(Charsets.UTF_8)).copyOf(16).hex()
}

// region File envelope

/**
 * `"ENCV" ‖ version ‖ kind ‖ nonce (12) ‖ ciphertext ‖ tag`, ChaCha20-Poly1305 with
 * the header and path as additional data, so storage cannot move a file to another name.
 */
object Envelope {
    enum class Kind(val raw: Byte) { Snapshot(1), Photo(2), Grant(3) }

    private val magic = "ENCV".toByteArray()

    private fun header(kind: Kind) = magic + byteArrayOf(1, kind.raw)

    /** [nonce] is random unless given (test vectors only). */
    fun seal(plaintext: ByteArray, kind: Kind, path: String, key: ByteArray, nonce: ByteArray = ChaChaPoly.randomNonce()): ByteArray {
        val header = header(kind)
        return header + nonce + ChaChaPoly.seal(plaintext, key, nonce, header + path.toByteArray(Charsets.UTF_8))
    }

    fun open(file: ByteArray, kind: Kind, path: String, key: ByteArray): ByteArray {
        val header = header(kind)
        if (file.size <= header.size + 28 || !file.copyOf(header.size).contentEquals(header)) {
            throw RemoteSyncException(RemoteSyncError.Corrupted(path))
        }
        val nonce = file.copyOfRange(header.size, header.size + 12)
        val sealed = file.copyOfRange(header.size + 12, file.size)
        return ChaChaPoly.open(sealed, key, nonce, header + path.toByteArray(Charsets.UTF_8))
            ?: throw RemoteSyncException(RemoteSyncError.Corrupted(path))
    }
}

// endregion

// region Key grants

/**
 * A cellar key given by one device to another, left in the recipient's inbox.
 * Sealed with both an ephemeral key and the sender's identity key: only the
 * recipient can open it, and only a current member can have sent it.
 */
@Serializable
data class KeyGrant(
    val cellarKey: CellarKey,
    @Serializable(with = UuidSerializer::class) val from: UUID,
    /** Where the files are (for example a relay URL), so the recipient can follow. */
    val storage: String? = null,
) {
    val path: String get() = "grant/${cellarKey.cellarId.lower}-${"%08d".format(cellarKey.epoch)}-${from.lower}"

    /** Seals for [recipient] (its identity public key). Returns the path and the file. */
    fun sealed(sender: DeviceIdentity, recipient: ByteArray, ephemeral: X25519Key = X25519Key.generate()): Pair<String, ByteArray> {
        val path = path
        val key = key(path, ephemeral.publicKey, recipient, ephemeral.agree(recipient), sender.privateKey.agree(recipient))
        val body = Envelope.seal(SyncJson.encode(serializer(), this), Envelope.Kind.Grant, path, key)
        return path to ephemeral.publicKey + body
    }

    companion object {
        /** Opens a grant found in this device's inbox. [members] gives the senders' keys. */
        fun open(data: ByteArray, path: String, recipient: DeviceIdentity, members: Membership): KeyGrant {
            if (data.size <= 32) throw RemoteSyncException(RemoteSyncError.Corrupted(path))
            val ephemeralPublic = data.copyOf(32)
            // The sender's ID is in the path; it must be a current member.
            val senderId = runCatching { UUID.fromString(path.split("-").takeLast(5).joinToString("-")) }.getOrNull()
            val sender = senderId?.let { members.members[it] } ?: throw SyncException(SyncError.UnknownDevice)
            val key = key(path, ephemeralPublic, recipient.publicKey, recipient.privateKey.agree(ephemeralPublic), recipient.privateKey.agree(sender.publicKey))
            val plain = Envelope.open(data.copyOfRange(32, data.size), Envelope.Kind.Grant, path, key)
            val grant = runCatching { SyncJson.decode(serializer(), plain) }.getOrElse { throw RemoteSyncException(RemoteSyncError.Corrupted(path)) }
            if (grant.from != senderId || grant.cellarKey.cellarId != members.cellarId || path != grant.path) {
                throw RemoteSyncException(RemoteSyncError.Corrupted(path))
            }
            return grant
        }

        internal fun key(path: String, ephemeralPublic: ByteArray, recipient: ByteArray, ee: ByteArray, se: ByteArray): ByteArray =
            KeySchedule.derive(ee + se, KeySchedule.hash("encave-grant-v1", listOf(ephemeralPublic, recipient, path.toByteArray())), "encave grant")
    }
}

// endregion

// region Sync

/** What a device remembers between remote syncs. Stored on the device only. */
@Serializable
data class RemoteProgress(
    /** Newest snapshot read from each device (its clock), by lowercase device ID. */
    val seen: Map<String, Long> = emptyMap(),
    /** Clock of the last snapshot this device wrote, in the current epoch. */
    val uploadedClock: Long? = null,
    /** Photos already uploaded in the current epoch. */
    val uploadedPhotos: Set<String> = emptySet(),
    /** Epoch the progress refers to; a new epoch starts afresh. */
    val epoch: Long = 0,
)

object RemoteSync {
    /** Snapshots kept per device: the newest two (a reader may be fetching the previous one). */
    const val KEPT_SNAPSHOTS = 2

    data class Result(
        /** The merged copy, or null if nothing came in. */
        val snapshot: SyncSnapshot?,
        val progress: RemoteProgress,
        /** A newer cellar key received from another device. */
        val newKey: CellarKey?,
        val storage: String?,
        /** Devices whose newest snapshot could not be read (damaged, from a newer version, out of bounds). Skipped. */
        val unreadable: List<UUID> = emptyList(),
    )

    /**
     * One round: picks up key grants, reads the other devices' newest snapshots,
     * merges them, fetches missing photos, then writes this device's snapshot and
     * photos if anything changed.
     */
    suspend fun run(
        store: MailboxStore, identity: DeviceIdentity, key: CellarKey, local: SyncSnapshot,
        progress: RemoteProgress, photos: PhotoAccess,
    ): Result {
        var currentKey = key
        var current = progress
        var storage: String? = null
        var newKey: CellarKey? = null

        val unreadable = mutableListOf<UUID>()

        // 1. Key grants: a key that supersedes this one (a device was removed, or two turned it on).
        receiveGrant(store, identity, local.membership, current = currentKey)?.let { grant ->
            currentKey = grant.cellarKey
            newKey = currentKey
            storage = grant.storage
        }
        if (current.epoch != currentKey.epoch || newKey != null) current = RemoteProgress(epoch = currentKey.epoch)

        // 2. Other devices' newest snapshots.
        val mailbox = store.mailbox(currentKey.mailboxId, currentKey.token)
        var merged = local
        var received = false
        val seen = current.seen.toMutableMap()
        val snapshots = mailbox.list("snap/").mapNotNull { SnapshotPath.parse(it) }
        for ((device, paths) in snapshots.groupBy { it.device }) {
            if (device == identity.id) continue
            // Only current members are read: files of removed or unknown devices are ignored
            // (a device added elsewhere is read once its record arrives).
            if (merged.membership.members[device] == null) continue
            val newest = paths.maxBy { it.clock }
            if (newest.clock <= (seen[device.lower] ?: 0)) continue
            val file = mailbox.get(newest.string) ?: continue
            // One unreadable file must not stop the round for the other devices.
            val remote = try {
                decodeSnapshot(Envelope.open(file, Envelope.Kind.Snapshot, newest.string, currentKey.dataKey)).also {
                    if (it.state.device != device || it.membership.cellarId != merged.membership.cellarId) {
                        throw RemoteSyncException(RemoteSyncError.Corrupted(newest.string))
                    }
                    SyncState.check(it.state, it.membership)
                }
            } catch (_: Exception) {
                unreadable += device
                continue
            }
            val merge = CellarMerge.merge(merged.cellar, merged.state, remote.cellar, remote.state)
            val membership = merged.membership.merged(remote.membership)
            val clock = maxOf(merged.state.clock, merge.clock, membership.stamps.values.maxOfOrNull { it.counter } ?: 0)
            merged = SyncSnapshot(merge.cellar, merged.state.copy(stamps = merge.stamps, clock = clock), membership)
            seen[device.lower] = newest.clock
            received = true
        }
        val uploaded = current.uploadedPhotos.toMutableSet()

        // 3. Missing photos.
        val referenced = merged.cellar.wines.values.mapNotNull { it.photoId }.toSortedSet()
        for (id in referenced) {
            if (!PhotoId.isValid(id) || photos.data(id) != null) continue
            val path = photoPath(id)
            val file = runCatching { mailbox.get(path) }.getOrNull() ?: continue
            val data = runCatching { Envelope.open(file, Envelope.Kind.Photo, path, currentKey.dataKey) }.getOrNull() ?: continue
            if (!PhotoId.matches(id, data)) continue
            photos.save(id, data)
            uploaded += id
        }

        // 4. This device's snapshot and photos, if something changed since the last upload.
        var uploadedClock = current.uploadedClock
        if (uploadedClock != merged.state.clock || newKey != null) {
            for (id in referenced) {
                if (!PhotoId.isValid(id) || id in uploaded) continue
                val data = photos.data(id) ?: continue
                val path = photoPath(id)
                mailbox.create(path, Envelope.seal(data, Envelope.Kind.Photo, path, currentKey.dataKey))
                uploaded += id
            }
            write(merged, mailbox, currentKey, identity.id)
            uploadedClock = merged.state.clock
        }
        return Result(
            if (received) merged else null,
            current.copy(seen = seen, uploadedClock = uploadedClock, uploadedPhotos = uploaded),
            newKey,
            storage,
            unreadable,
        )
    }

    /**
     * The key left in this device's inbox by a current member that supersedes [current]
     * (null: any), if any. Grants that are older or no longer useful are deleted.
     */
    suspend fun receiveGrant(store: MailboxStore, identity: DeviceIdentity, membership: Membership, current: CellarKey?): KeyGrant? {
        val newerThan = current?.epoch ?: 0
        val inbox = store.inbox(identity.inboxId, identity.inboxToken)
        val ownPrefix = "grant/${membership.cellarId.lower}-"
        var best: KeyGrant? = null
        for (path in inbox.list("grant/")) {
            val data = inbox.get(path) ?: continue
            val grant = try {
                KeyGrant.open(data, path, identity, membership)
            } catch (error: SyncException) {
                // From a device of this cellar this one has not heard of yet: kept for later.
                if (error.error == SyncError.UnknownDevice && path.startsWith(ownPrefix) && !isFromRemovedDevice(path, membership)) continue
                runCatching { inbox.delete(path) }
                continue
            } catch (_: Exception) {
                // Forged, damaged or for another cellar: never usable, and it takes room.
                runCatching { inbox.delete(path) }
                continue
            }
            if (grant.cellarKey.supersedes(best?.cellarKey ?: current)) best = grant
        }
        // Grants up to the one kept are done with.
        val kept = best?.cellarKey?.epoch ?: newerThan.takeIf { it > 0 }
        if (kept != null) {
            for (path in inbox.list(ownPrefix)) {
                val epoch = path.split("-").drop(5).firstOrNull()?.toLongOrNull() ?: continue
                if (epoch < kept) runCatching { inbox.delete(path) }
            }
        }
        return best
    }

    /** A grant path ends with the sender's ID; true if that device was removed (or the path is unreadable). */
    internal fun isFromRemovedDevice(path: String, membership: Membership): Boolean {
        val sender = runCatching { UUID.fromString(path.takeLast(36)) }.getOrNull() ?: return true
        return membership.stamps[sender]?.deleted == true
    }

    /**
     * After removing devices: a new key for the remaining ones. The new key is left in
     * each remaining device's inbox, the updated list of devices is written to the
     * old mailbox too (for devices that have not switched yet), and the cellar to the
     * new mailbox. The removed devices cannot find or read the new mailbox.
     *
     * [newKey] is made once by the caller and kept until the rotation succeeds, so a retry
     * after a failure gives the same key: grants already left stay valid.
     */
    suspend fun rotate(store: MailboxStore, identity: DeviceIdentity, oldKey: CellarKey, newKey: CellarKey, local: SyncSnapshot, storage: String?) {
        grant(newKey, local.membership.active, store, identity, storage)
        // The removed device can still read the old mailbox: only the devices and the clock go there.
        val notice = local.copy(cellar = app.encave.core.Cellar(), state = local.state.copy(stamps = emptyMap()))
        write(notice, store.mailbox(oldKey.mailboxId, oldKey.token), oldKey, identity.id)
        write(local, store.mailbox(newKey.mailboxId, newKey.token), newKey, identity.id)
    }

    /**
     * Gives the current key to devices that may not have it (a device that joined
     * while remote sync was already on, met only on the network). Devices whose record
     * has no inbox yet are skipped: they get the key on the local network.
     */
    suspend fun grant(key: CellarKey, members: List<Member>, store: MailboxStore, identity: DeviceIdentity, storage: String?) {
        for (member in members) {
            if (member.id == identity.id) continue
            // Devices whose record has no inbox yet get the key on the local network.
            val inbox = member.inbox?.takeIf { RelayPath.isValidMailbox(it) && it.startsWith("i") } ?: continue
            val (path, data) = KeyGrant(key, identity.id, storage).sealed(identity, member.publicKey)
            store.inbox(inbox, "").create(path, data)
        }
    }

    /** Photo IDs from before sync are UUIDs in uppercase; paths are lowercase. */
    internal fun photoPath(id: String) = "photo/${id.lowercase()}"

    internal suspend fun write(snapshot: SyncSnapshot, mailbox: Mailbox, key: CellarKey, device: UUID) {
        val path = SnapshotPath(device, snapshot.state.clock).string
        mailbox.create(path, Envelope.seal(encodeSnapshot(snapshot), Envelope.Kind.Snapshot, path, key.dataKey))
        // Older snapshots of this device: keep the newest few.
        val mine = mailbox.list("snap/${device.lower}/").mapNotNull { SnapshotPath.parse(it) }.sortedByDescending { it.clock }
        for (old in mine.drop(KEPT_SNAPSHOTS)) runCatching { mailbox.delete(old.string) }
    }

    fun encodeSnapshot(snapshot: SyncSnapshot): ByteArray = WireMessage(
        "snapshot",
        cellarData = SyncJson.encodeCellar(snapshot.cellar),
        syncData = SyncJson.encode(SyncState.serializer(), snapshot.state),
        membersData = SyncJson.encode(Membership.serializer(), snapshot.membership),
    ).encoded()

    fun decodeSnapshot(data: ByteArray): SyncSnapshot {
        val message = WireMessage.decode(data).require("snapshot")
        return runCatching {
            SyncSnapshot(
                SyncJson.decodeCellar(message.cellarData!!),
                SyncJson.decode(SyncState.serializer(), message.syncData!!),
                SyncJson.decode(Membership.serializer(), message.membersData!!),
            )
        }.getOrElse { throw RemoteSyncException(RemoteSyncError.Corrupted("snapshot")) }
    }
}

/**
 * `snap/<device>/<clock, 16 digits>-<8 random hex>`: the random part keeps two
 * snapshots with the same clock (after a new epoch, say) from colliding.
 */
internal class SnapshotPath(val device: UUID, val clock: Long, val suffix: String = ByteArray(4).also { random.nextBytes(it) }.hex()) {
    val string: String get() = "snap/${device.lower}/${"%016d".format(clock)}-$suffix"

    companion object {
        fun parse(string: String): SnapshotPath? {
            val parts = string.split("/")
            if (parts.size != 3 || parts[0] != "snap") return null
            val device = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return null
            val name = parts[2].split("-")
            if (name.size != 2 || name[0].length != 16) return null
            val clock = name[0].toLongOrNull() ?: return null
            return SnapshotPath(device, clock, name[1])
        }
    }
}

// endregion

// region Simple storages

/** Files in memory. For tests and previews. */
class MemoryMailboxStore : MailboxStore {
    private val files = HashMap<String, MutableMap<String, ByteArray>>()

    override fun mailbox(id: String, token: String): Mailbox = Box(id)
    override fun inbox(id: String, token: String): Mailbox = Box(id)

    /** Every file, for tests that look at what the storage holds. */
    val allFiles: Map<String, Map<String, ByteArray>> get() = synchronized(files) { files.mapValues { it.value.toMap() } }

    private inner class Box(val id: String) : Mailbox {
        override suspend fun list(prefix: String): List<String> =
            synchronized(files) { files[id].orEmpty().keys.filter { it.startsWith(prefix) }.sorted() }

        override suspend fun get(path: String): ByteArray? = synchronized(files) { files[id]?.get(path) }

        override suspend fun create(path: String, data: ByteArray): Boolean = synchronized(files) {
            val box = files.getOrPut(id) { mutableMapOf() }
            if (box.containsKey(path)) false else true.also { box[path] = data }
        }

        override suspend fun delete(path: String) {
            synchronized(files) { files[id]?.remove(path) }
        }
    }
}

/** Files in a folder: one subfolder per mailbox. For tests and for any synced folder. */
class FolderMailboxStore(val root: File) : MailboxStore {
    override fun mailbox(id: String, token: String): Mailbox = Folder(File(root, id))
    override fun inbox(id: String, token: String): Mailbox = Folder(File(root, id))

    private class Folder(val dir: File) : Mailbox {
        private fun file(path: String): File {
            if (!RelayPath.isValid(path)) throw RemoteSyncException(RemoteSyncError.Corrupted(path))
            return File(dir, path)
        }

        override suspend fun list(prefix: String): List<String> {
            if (!dir.isDirectory) return emptyList()
            return dir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(dir).invariantSeparatorsPath }
                .filter { it.startsWith(prefix) && RelayPath.isValid(it) }
                .sorted().toList()
        }

        override suspend fun get(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

        override suspend fun create(path: String, data: ByteArray): Boolean {
            val target = file(path)
            target.parentFile?.mkdirs()
            return try {
                java.nio.file.Files.write(target.toPath(), data, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
                true
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                false
            }
        }

        override suspend fun delete(path: String) {
            file(path).delete()
        }
    }
}

/**
 * Rules for paths, shared with the relay: lowercase letters, digits, "-", "_"
 * and "/" between parts, no empty or dot parts, at most 200 characters.
 */
object RelayPath {
    private val part = Regex("^[a-z0-9_-]+$")
    private val mailbox = Regex("^[ci][0-9a-f]{32}$")

    /** "c" or "i" followed by 32 lowercase hex characters. */
    fun isValidMailbox(id: String): Boolean = mailbox.matches(id)

    fun isValid(path: String): Boolean {
        if (path.isEmpty() || path.length > 200 || path.startsWith("/") || path.endsWith("/")) return false
        return path.split("/").all { it.isNotEmpty() && it != "." && it != ".." && part.matches(it) }
    }
}

// endregion
