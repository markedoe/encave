package app.encave.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import app.encave.core.sync.CellarKey
import app.encave.core.sync.DeviceIdentity
import app.encave.core.sync.Membership
import app.encave.core.sync.RemoteProgress
import app.encave.core.sync.SyncJson
import app.encave.core.sync.SyncState
import app.encave.data.ai.SecretStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What sync keeps on this device, next to the cellar but apart from it:
 * - `files/sync/`: the change stamps (sync.json), the devices with their public keys
 *   (devices.json), when each device last synced and the remote progress. Left out of
 *   backups and device transfers: a copy on another phone would not be that device.
 * - the identity private key, the remote cellar key and the WebDAV login, encrypted
 *   with an Android Keystore key (as the AI key is);
 * - the device ID and the storage address (relay or WebDAV folder), in private preferences.
 */
class SyncStorage(private val context: Context) {
    private val folder = File(context.filesDir, "sync")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets by lazy { SecretStore(context) }

    private fun file(name: String) = File(folder, name)

    private fun write(name: String, bytes: ByteArray) {
        // Written for another identity than the stored one: kept for when it can be read again.
        if (identityUnreadable) return
        folder.mkdirs()
        val temp = File(folder, "$name.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file(name))) {
            file(name).delete()
            temp.renameTo(file(name))
        }
    }

    private fun read(name: String): ByteArray? = file(name).takeIf { it.exists() }?.readBytes()

    fun loadState(): SyncState? = read(STATE)?.let { runCatching { SyncJson.decode(SyncState.serializer(), it) }.getOrNull() }
    fun saveState(state: SyncState) = write(STATE, SyncJson.encode(SyncState.serializer(), state))

    fun loadMembership(): Membership? = read(DEVICES)?.let { runCatching { SyncJson.decode(Membership.serializer(), it) }.getOrNull() }
    fun saveMembership(membership: Membership) = write(DEVICES, SyncJson.encode(Membership.serializer(), membership))

    fun loadLastSync(): Map<UUID, Instant> = read(LAST_SYNC)?.let { bytes ->
        runCatching {
            Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), bytes.decodeToString())
                .map { (id, millis) -> UUID.fromString(id) to Instant.ofEpochMilli(millis) }.toMap()
        }.getOrNull()
    }.orEmpty()

    fun saveLastSync(dates: Map<UUID, Instant>) = write(
        LAST_SYNC,
        Json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), dates.map { (id, date) -> id.toString() to date.toEpochMilli() }.toMap())
            .toByteArray(),
    )

    fun loadRemoteProgress(): RemoteProgress =
        read(REMOTE)?.let { runCatching { Json.decodeFromString(RemoteProgress.serializer(), it.decodeToString()) }.getOrNull() } ?: RemoteProgress()

    fun saveRemoteProgress(progress: RemoteProgress) = write(REMOTE, Json.encodeToString(RemoteProgress.serializer(), progress).toByteArray())

    /** The relay URL, "webdav:" and the folder URL, or null when syncing away from home is off. */
    var remoteStorage: String?
        get() = prefs.getString(KEY_STORAGE, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_STORAGE) else putString(KEY_STORAGE, value) }

    /**
     * The next cellar key, made when a device is removed and kept until the rotation is
     * done, so a retry after a failure uses the same key (grants already left stay valid).
     */
    var pendingKey: CellarKey?
        get() = runCatching { secrets.get(KEY_PENDING_KEY)?.let { SyncJson.decode(CellarKey.serializer(), it.toByteArray()) } }.getOrNull()
        set(value) {
            runCatching { secrets.set(KEY_PENDING_KEY, value?.let { SyncJson.encode(CellarKey.serializer(), it).decodeToString() }) }
        }

    /** The login for a WebDAV folder, on this device only: other devices sign in with their own. */
    var webDavLogin: WebDavLogin?
        get() = runCatching { secrets.get(KEY_WEBDAV_LOGIN)?.let { Json.decodeFromString(WebDavLogin.serializer(), it) } }.getOrNull()
        set(value) {
            runCatching { secrets.set(KEY_WEBDAV_LOGIN, value?.let { Json.encodeToString(WebDavLogin.serializer(), it) }) }
        }

    var remoteKey: CellarKey?
        get() = runCatching { secrets.get(KEY_CELLAR_KEY)?.let { SyncJson.decode(CellarKey.serializer(), it.toByteArray()) } }.getOrNull()
        set(value) {
            runCatching { secrets.set(KEY_CELLAR_KEY, value?.let { SyncJson.encode(CellarKey.serializer(), it).decodeToString() }) }
        }

    /**
     * Set when this device's stored identity could not be read (the Keystore failed): this
     * process uses a temporary one and saves no sync files, so nothing stored is lost or
     * replaced; the identity is read again the next time Encave opens.
     */
    @Volatile var identityUnreadable = false
        private set

    /**
     * This device's identity: a random ID and an X25519 key, created once. If the
     * Keystore cannot be used (tests), the identity lives only as long as the process.
     */
    fun loadIdentity(): DeviceIdentity {
        val id = prefs.getString(KEY_DEVICE, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stored = runCatching { secrets.read(KEY_IDENTITY) }
        if (stored.isFailure) {
            // Stored but unreadable now: never replaced (that would end this device's membership).
            identityUnreadable = true
            return DeviceIdentity.generate().first
        }
        val key = stored.getOrNull()?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        if (id != null && key != null && key.size == 32) return DeviceIdentity(id, key)
        return newIdentity()
    }

    /**
     * A new device ID and key, saved in place of the old ones. Used after leaving a cellar:
     * a removal is final for an ID, so joining again needs a new one.
     */
    fun newIdentity(): DeviceIdentity {
        val (identity, privateKey) = DeviceIdentity.generate()
        val stored = runCatching { secrets.set(KEY_IDENTITY, Base64.encodeToString(privateKey, Base64.NO_WRAP)) }.isSuccess
        if (stored) {
            identityUnreadable = false
            prefs.edit { putString(KEY_DEVICE, identity.id.toString()) }
            // A new identity cannot use files written for another one.
            deleteAll()
        }
        return identity
    }

    /** Stops syncing on this device: forgets the devices, stamps and remote settings. */
    fun deleteAll() {
        folder.deleteRecursively()
        remoteStorage = null
        resetRemote()
    }

    /** Forgets the remote key, the pending one, the WebDAV login and the progress (leaving, or joining another cellar). */
    fun resetRemote() {
        remoteKey = null
        pendingKey = null
        webDavLogin = null
        file(REMOTE).delete()
        prefs.edit { remove(KEY_ROTATION) }
    }

    /** Builds before the pending key kept only a flag. */
    val hadRotationFlag: Boolean get() = prefs.getBoolean(KEY_ROTATION, false)

    fun clearRotationFlag() = prefs.edit { remove(KEY_ROTATION) }

    companion object {
        private const val PREFS = "sync"
        private const val KEY_PENDING_KEY = "sync.pendingCellarKey"
        private const val STATE = "sync.json"
        private const val DEVICES = "devices.json"
        private const val LAST_SYNC = "last-sync.json"
        private const val REMOTE = "remote.json"
        private const val KEY_DEVICE = "deviceID"
        private const val KEY_STORAGE = "remoteStorage"
        private const val KEY_ROTATION = "rotationPending"
        private const val KEY_IDENTITY = "sync.identity"
        private const val KEY_CELLAR_KEY = "sync.cellarKey"
        private const val KEY_WEBDAV_LOGIN = "sync.webDavLogin"

        /** The name other devices show for this one: the name set in system settings, or the model. */
        fun deviceName(context: Context): String =
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
    }
}

/** A username and (app) password for one WebDAV folder. */
@Serializable
data class WebDavLogin(
    /** The folder URL the login belongs to; a login for another folder is not used. */
    val folder: String,
    val username: String,
    val password: String,
)
