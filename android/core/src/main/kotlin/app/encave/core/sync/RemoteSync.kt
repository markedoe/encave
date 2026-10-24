package app.encave.core.sync

import app.encave.core.UuidSerializer
import app.encave.core.parseUuid
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
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

    /** The login is right but the server refused access to the folder (WebDAV 403). */
    data object Forbidden : RemoteSyncError()
    data object QuotaExceeded : RemoteSyncError()

    /** One file is larger than the storage accepts (a very large cellar). */
    data object FileTooLarge : RemoteSyncError()
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
    /**
     * Devices removed by another device before this key was made: it was given to none of
     * them. Null for keys made before this list existed (none known).
     */
    val removed: List<@Serializable(with = UuidSerializer::class) UUID>? = null,
) {
    init {
        // A key received from another device must be 32 bytes, like the ones devices make.
        require(key.size == 32) { "A cellar key is 32 bytes" }
        // A UInt32, as Swift decodes it: a larger or negative epoch would supersede every real key.
        require(epoch in 0..MAX_EPOCH) { "A cellar key's epoch is a UInt32" }
    }

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
        other is CellarKey && cellarId == other.cellarId && epoch == other.epoch && key.contentEquals(other.key) &&
            removed == other.removed

    override fun hashCode(): Int = listOf(cellarId, epoch, key.contentHashCode(), removed).hashCode()

    companion object {
        /** The last epoch (UInt32 max): no rotation goes past it. */
        const val MAX_EPOCH = 0xFFFF_FFFFL

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
    /**
     * Seals for [recipient] (its identity public key). Returns the path and the file. The
     * path ends with a random part, so nobody who can write to the inbox can take the name first.
     */
    fun sealed(sender: DeviceIdentity, recipient: ByteArray, ephemeral: X25519Key = X25519Key.generate()): Pair<String, ByteArray> {
        val path = GrantPath(cellarKey.cellarId, cellarKey.epoch, from).string
        val key = key(path, ephemeral.publicKey, recipient, ephemeral.agree(recipient), sender.privateKey.agree(recipient))
        val body = Envelope.seal(SyncJson.encode(serializer(), this), Envelope.Kind.Grant, path, key)
        return path to ephemeral.publicKey + body
    }

    companion object {
        /** Grants are at most this large; larger files in an inbox are deleted unopened. */
        const val MAX_SIZE = 4096

        /** Opens a grant found in this device's inbox. [members] gives the senders' keys. */
        fun open(data: ByteArray, path: String, recipient: DeviceIdentity, members: Membership): KeyGrant {
            val name = GrantPath.parse(path)
            if (data.size <= 32 || data.size > MAX_SIZE || name == null) throw RemoteSyncException(RemoteSyncError.Corrupted(path))
            val ephemeralPublic = data.copyOf(32)
            // The sender's ID is in the path; it must be a current member.
            val senderId = name.sender
            val sender = members.members[senderId] ?: throw SyncException(SyncError.UnknownDevice)
            val key = key(path, ephemeralPublic, recipient.publicKey, recipient.privateKey.agree(ephemeralPublic), recipient.privateKey.agree(sender.publicKey))
            val plain = Envelope.open(data.copyOfRange(32, data.size), Envelope.Kind.Grant, path, key)
            val grant = runCatching { SyncJson.decode(serializer(), plain) }.getOrElse { throw RemoteSyncException(RemoteSyncError.Corrupted(path)) }
            if (grant.from != senderId || grant.cellarKey.cellarId != members.cellarId ||
                name.cellarId != grant.cellarKey.cellarId || name.epoch != grant.cellarKey.epoch
            ) {
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
    /**
     * Digest of the stamps of the last snapshot written: a merge on the local
     * network can change the cellar without moving the clock.
     */
    val uploadedDigest: String? = null,
    /** Photos already uploaded in the current epoch. */
    val uploadedPhotos: Set<String> = emptySet(),
    /**
     * Files the storage refused (too large, or no room left): the cellar still syncs without
     * them, and they are tried again with the next snapshot this device writes.
     */
    val refusedFiles: Set<String> = emptySet(),
    /** The last refusal was for lack of room rather than size. */
    val storageFull: Boolean = false,
    /** Epoch the progress refers to; a new epoch starts afresh. */
    val epoch: Long = 0,
)

/**
 * A key rotation not done yet, kept on the device only, so a retry after a failure gives
 * the same key and leaves no second grant for the devices that have one.
 */
@Serializable
data class PendingRotation(
    val key: CellarKey,
    /** Devices already given [key]. */
    val granted: Set<@Serializable(with = UuidSerializer::class) UUID> = emptySet(),
)

object RemoteSync {
    /** Snapshots kept per device: the newest two (a reader may be fetching the previous one). */
    const val KEPT_SNAPSHOTS = 2

    /**
     * Whether a round may run: after a refused WebDAV login, only when the user asks (Sync now)
     * or signs in again, never automatically (a server may lock the account).
     */
    fun mayRun(userAsked: Boolean, webDavLoginRefused: Boolean): Boolean = userAsked || !webDavLoginRefused

    data class Result(
        /** The merged copy, or null if nothing came in. */
        val snapshot: SyncSnapshot?,
        val progress: RemoteProgress,
        /** A newer cellar key received from another device. */
        val newKey: CellarKey?,
        val storage: String?,
        /** Devices whose newest snapshot could not be read (damaged, from a newer version, out of bounds). Skipped. */
        val unreadable: List<UUID> = emptyList(),
        /**
         * This device's clock after the round: at least the one passed in. Raised when the
         * content changed without the clock moving (a merge on the local network), so
         * readers see a newer snapshot. Keep it.
         */
        val clock: Long = 0,
        /**
         * Why the storage refused some of this device's files ([RemoteSyncError.FileTooLarge] or
         * [RemoteSyncError.QuotaExceeded]), if it did: the cellar synced, those files stay here for now.
         */
        val refusedFiles: RemoteSyncError? = null,
    )

    /**
     * One round: picks up key grants, reads the other devices' newest snapshots,
     * merges them, fetches missing photos, then writes this device's snapshot and
     * photos if anything changed.
     *
     * [currentStorage] is where [store] keeps the files, if the caller knows it: a new key
     * granted with another storage then ends the round, so nothing is read from or written
     * to the old storage under the new key (the result has the key, the storage and a fresh
     * progress for the next round).
     */
    suspend fun run(
        store: MailboxStore, identity: DeviceIdentity, key: CellarKey, local: SyncSnapshot,
        progress: RemoteProgress, files: FileAccess, currentStorage: String? = null,
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
        val granted = storage
        if (newKey != null && granted != null && currentStorage != null && granted != currentStorage) {
            return Result(null, current, newKey, granted, emptyList(), local.state.clock)
        }

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
                    // The path's clock is the one readers compare: it must be the snapshot's.
                    if (it.state.device != device || it.state.clock != newest.clock ||
                        it.membership.cellarId != merged.membership.cellarId
                    ) {
                        throw RemoteSyncException(RemoteSyncError.Corrupted(newest.string))
                    }
                    SyncState.check(it.state, it.membership)
                }
            } catch (error: CancellationException) {
                throw error
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
        val refused = current.refusedFiles.toMutableSet()
        var storageFull = current.storageFull

        // 3. Missing files: photos and documents, both under "photo/".
        val refs = merged.cellar.fileRefs
        val referenced = refs.map { it.id }.toSortedSet()
        for (id in files.missing(refs)) {
            val path = photoPath(id)
            val file = try {
                mailbox.get(path)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: continue
            val data = runCatching { Envelope.open(file, Envelope.Kind.Photo, path, currentKey.dataKey) }.getOrNull() ?: continue
            if (!PhotoId.matches(id, data)) continue
            files.save(id, data, refs)
            uploaded += id
        }

        // 4. This device's snapshot and files, if something changed since the last upload.
        var uploadedClock = current.uploadedClock
        var uploadedDigest = current.uploadedDigest
        val digest = stampDigest(merged)
        if (uploadedClock != merged.state.clock || uploadedDigest != digest || newKey != null) {
            // Other devices read a snapshot only if its clock is newer than the last one,
            // including one written by a round that failed after writing it.
            val last = listOfNotNull(uploadedClock, snapshots.filter { it.device == identity.id }.maxOfOrNull { it.clock }).maxOrNull()
            if (last != null && java.lang.Long.compareUnsigned(merged.state.clock, last) <= 0) {
                merged = merged.copy(state = merged.state.copy(clock = last + 1))
            }
            var full = false
            for (id in referenced) {
                if (full || !PhotoId.isValid(id) || id in uploaded) continue
                val data = files.data(id, refs) ?: continue
                val path = photoPath(id)
                // A file the storage refuses (a large document) must not stop the cellar from syncing.
                try {
                    mailbox.create(path, Envelope.seal(data, Envelope.Kind.Photo, path, currentKey.dataKey))
                } catch (error: RemoteSyncException) {
                    if (error.error != RemoteSyncError.FileTooLarge && error.error != RemoteSyncError.QuotaExceeded) throw error
                    refused += id
                    storageFull = error.error == RemoteSyncError.QuotaExceeded
                    // No room: the other files would be refused too.
                    full = storageFull
                    continue
                }
                uploaded += id
                refused -= id
            }
            write(merged, mailbox, currentKey, identity.id)
            uploadedClock = merged.state.clock
            uploadedDigest = digest
        }
        refused.retainAll(referenced)
        return Result(
            if (received) merged else null,
            current.copy(
                seen = seen, uploadedClock = uploadedClock, uploadedDigest = uploadedDigest, uploadedPhotos = uploaded,
                refusedFiles = refused, storageFull = storageFull,
            ),
            newKey,
            storage,
            unreadable,
            merged.state.clock,
            refusedFiles = when {
                refused.isEmpty() -> null
                storageFull -> RemoteSyncError.QuotaExceeded
                else -> RemoteSyncError.FileTooLarge
            },
        )
    }

    /** Changes whenever any object or device stamp changes. Same text as CaveKit hashes. */
    internal fun stampDigest(snapshot: SyncSnapshot): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        fun line(text: String) = hash.update(text.toByteArray(Charsets.UTF_8))
        for ((key, stamp) in snapshot.state.stamps.toSortedMap()) {
            line("$key ${stamp.counter.toULong()} ${stamp.device.lower} ${stamp.deleted}\n")
        }
        for ((id, stamp) in snapshot.membership.stamps.entries.sortedBy { it.key.lower }) {
            line("member ${id.lower} ${stamp.counter.toULong()} ${stamp.device.lower} ${stamp.deleted}\n")
        }
        return hash.digest().hex()
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
        var bestPath: String? = null
        val opened = mutableListOf<String>()
        var waiting = 0
        for (path in inbox.list("grant/")) {
            val data = inbox.get(path) ?: continue
            val grant = try {
                KeyGrant.open(data, path, identity, membership)
            } catch (error: SyncException) {
                // From a device of this cellar this one has not heard of yet: kept for later.
                // Only a few, since anyone can write to an inbox.
                if (error.error == SyncError.UnknownDevice && GrantPath.parse(path)?.cellarId == membership.cellarId &&
                    !isFromRemovedDevice(path, membership) && waiting < MAX_WAITING_GRANTS
                ) {
                    waiting++
                    continue
                }
                deleteQuietly(inbox, path)
                continue
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Forged, damaged or for another cellar: never usable, and it takes room.
                deleteQuietly(inbox, path)
                continue
            }
            opened += path
            if (grant.cellarKey.supersedes(best?.cellarKey ?: current)) {
                best = grant
                bestPath = path
            }
        }
        // Every other grant opened is done with: the current key, or one that loses to the
        // key kept (such as duplicates left by a rotation retried after a failure).
        for (path in opened) if (path != bestPath) deleteQuietly(inbox, path)
        // Grants up to the one kept are done with.
        val kept = best?.cellarKey?.epoch ?: newerThan.takeIf { it > 0 }
        if (kept != null) {
            for (path in inbox.list(ownPrefix)) {
                val name = GrantPath.parse(path) ?: continue
                if (name.cellarId == membership.cellarId && name.epoch < kept) deleteQuietly(inbox, path)
            }
        }
        return best
    }

    /** Grants from devices not known yet that an inbox keeps; the others are deleted. */
    const val MAX_WAITING_GRANTS = 8

    /** A grant path names the sender; true if that device was removed (or the path is not a grant's). */
    internal fun isFromRemovedDevice(path: String, membership: Membership): Boolean {
        val sender = GrantPath.parse(path)?.sender ?: return true
        return membership.stamps[sender]?.deleted == true
    }

    /** Deletes a file that is of no use; a failure is retried at the next round, a cancellation is not swallowed. */
    private suspend fun deleteQuietly(mailbox: Mailbox, path: String) {
        try {
            mailbox.delete(path)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /**
     * Devices a key must not have been given to: those removed by another device. A device
     * that left removed itself and forgot the key (docs/SYNC.md §2).
     */
    fun shutOut(membership: Membership): Set<UUID> =
        membership.stamps.filter { (id, stamp) -> stamp.deleted && stamp.device != id }.keys

    /**
     * The rotation to run before this round, if any (docs/SYNC.md §8, Removing a device): one
     * already started, or a new one when the membership has a removal the key does not list,
     * so it may have been given to that device. Every key holder that runs rounds checks this,
     * not only the device that removed another. Keep the result before rotating, so a retry
     * uses the same key.
     *
     * A started rotation gets a new key above every epoch seen when the cellar moved on
     * meanwhile, or when it was given to a device removed since. [device] is this device: once
     * removed itself, it never rotates.
     */
    fun nextRotation(key: CellarKey, pending: PendingRotation?, membership: Membership, device: UUID): PendingRotation? {
        if (membership.members[device] == null) return null
        var started = pending?.takeIf { it.key.cellarId == key.cellarId }
        val removed = shutOut(membership)
        val excluded = removed + key.removed.orEmpty()
        if (started == null && key.removed.orEmpty().containsAll(removed)) return null
        val top = maxOf(key.epoch, started?.key?.epoch ?: 0)
        if (started != null && (started.key.epoch <= key.epoch || started.granted.any { it in excluded })) started = null
        val next = started ?: run {
            // Only a misbehaving device sends a key at the last epoch: refuse rather than go past it.
            if (top >= CellarKey.MAX_EPOCH) throw RemoteSyncException(RemoteSyncError.Corrupted("epoch"))
            PendingRotation(CellarKey.random(key.cellarId, top + 1))
        }
        val list = (excluded + next.key.removed.orEmpty()).sortedBy { it.toString().uppercase(Locale.ROOT) }
        return next.copy(key = next.key.copy(removed = list))
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
        rotate(store, identity, oldKey, PendingRotation(newKey), local, storage)
    }

    /**
     * The same, calling [granted] with [pending] updated after each device given the key, so
     * a retry after a failure leaves no second grant for it. Devices in the key's `removed`
     * get nothing. Returns the rotation as it ends.
     */
    suspend fun rotate(
        store: MailboxStore, identity: DeviceIdentity, oldKey: CellarKey, pending: PendingRotation, local: SyncSnapshot,
        storage: String?, granted: (PendingRotation) -> Unit = {},
    ): PendingRotation {
        val newKey = pending.key
        val removed = newKey.removed.orEmpty().toSet()
        var done = pending
        for (member in local.membership.active) {
            if (member.id == identity.id || member.id in removed || member.id in done.granted) continue
            if (member.inbox?.let { RelayPath.isValidMailbox(it) && it.startsWith("i") } != true) continue
            grant(newKey, listOf(member), store, identity, storage)
            done = done.copy(granted = done.granted + member.id)
            granted(done)
        }
        // The removed device can still read the old mailbox: only the devices and the clock go there.
        val notice = local.copy(cellar = app.encave.core.Cellar(), state = local.state.copy(stamps = emptyMap()))
        write(notice, store.mailbox(oldKey.mailboxId, oldKey.token), oldKey, identity.id)
        write(local, store.mailbox(newKey.mailboxId, newKey.token), newKey, identity.id)
        return done
    }

    /**
     * Gives the current key to devices that may not have it (a device that joined
     * while remote sync was already on, met only on the network). Devices whose record
     * has no inbox yet are skipped: they get the key on the local network.
     */
    suspend fun grant(key: CellarKey, members: List<Member>, store: MailboxStore, identity: DeviceIdentity, storage: String?) {
        val removed = key.removed.orEmpty().toSet()
        for (member in members) {
            if (member.id == identity.id || member.id in removed) continue
            // Devices whose record has no inbox yet get the key on the local network.
            val inbox = member.inbox?.takeIf { RelayPath.isValidMailbox(it) && it.startsWith("i") } ?: continue
            val (path, data) = KeyGrant(key, identity.id, storage).sealed(identity, member.publicKey)
            // The name has a random part: a file already there is not this grant.
            if (!store.inbox(inbox, "").create(path, data)) {
                throw RemoteSyncException(RemoteSyncError.Unavailable("grant not created"))
            }
        }
    }

    /** Photo IDs from before sync are UUIDs in uppercase; paths are lowercase. */
    internal fun photoPath(id: String) = "photo/${id.lowercase()}"

    internal suspend fun write(snapshot: SyncSnapshot, mailbox: Mailbox, key: CellarKey, device: UUID) {
        val path = SnapshotPath(device, snapshot.state.clock).string
        mailbox.create(path, Envelope.seal(encodeSnapshot(snapshot), Envelope.Kind.Snapshot, path, key.dataKey))
        // Older snapshots of this device: keep the newest few. The snapshot is written,
        // so a failure here is left for the next round.
        val listed = try {
            mailbox.list("snap/${device.lower}/")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val mine = listed.mapNotNull { SnapshotPath.parse(it) }.sortedByDescending { it.clock }
        for (old in mine.drop(KEPT_SNAPSHOTS)) deleteQuietly(mailbox, old.string)
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
 * `grant/<cellar>-<epoch, 8 digits>-<sender>-<8 random hex>` (lowercase UUIDs). The random
 * part keeps anyone who can write to an inbox from creating a grant's file before its sender does.
 */
internal class GrantPath(val cellarId: UUID, val epoch: Long, val sender: UUID, val suffix: String = randomHex(4)) {
    val string: String get() = "grant/${cellarId.lower}-${"%08d".format(Locale.ROOT, epoch)}-${sender.lower}-$suffix"

    companion object {
        fun parse(string: String): GrantPath? {
            if (!string.startsWith("grant/")) return null
            val parts = string.removePrefix("grant/").split("-")
            // Cellar (a UUID: 5 parts), epoch, sender (5 parts), random part.
            if (parts.size != 12) return null
            val cellarText = parts.subList(0, 5).joinToString("-")
            val senderText = parts.subList(6, 11).joinToString("-")
            val epochText = parts[5]
            val suffix = parts[11]
            val cellar = parseUuid(cellarText)?.takeIf { it.lower == cellarText } ?: return null
            val sender = parseUuid(senderText)?.takeIf { it.lower == senderText } ?: return null
            if (epochText.length !in 8..10 || !epochText.all { it in '0'..'9' }) return null
            val epoch = epochText.toLong().takeIf { it <= 0xFFFF_FFFFL } ?: return null
            if (suffix.length != 8 || !suffix.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return GrantPath(cellar, epoch, sender, suffix)
        }
    }
}

internal fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.hex()

/**
 * `snap/<device>/<clock, 16 digits>-<8 random hex>`: the random part keeps two
 * snapshots with the same clock (after a new epoch, say) from colliding.
 */
internal class SnapshotPath(val device: UUID, val clock: Long, val suffix: String = randomHex(4)) {
    val string: String get() = "snap/${device.lower}/${"%016d".format(Locale.ROOT, clock)}-$suffix"

    companion object {
        fun parse(string: String): SnapshotPath? {
            val parts = string.split("/")
            if (parts.size != 3 || parts[0] != "snap") return null
            val device = parseUuid(parts[1]) ?: return null
            val name = parts[2].split("-")
            if (name.size != 2 || name[0].length != 16 || !name[0].all { it in '0'..'9' }) return null
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
