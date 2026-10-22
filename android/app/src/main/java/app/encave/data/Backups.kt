package app.encave.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.encave.EncaveApplication
import app.encave.core.Cellar
import app.encave.core.CellarArchive
import app.encave.core.CellarExport
import app.encave.core.CellarVersion
import app.encave.core.CellarVersionSource
import app.encave.core.FileKind
import app.encave.core.FileRef
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Something that can be restored, read and checked: an earlier version, an archive or a JSON backup. */
data class RestoreSource(
    val kind: Kind,
    /** When the backup was made, or when the version was saved. */
    val date: Instant,
    val cellar: Cellar,
    /** The archive's photos and documents, checked, in [workingFolder]. */
    val files: Map<FileRef, File> = emptyMap(),
    /** Removed once restored, and at the next launch otherwise. */
    val workingFolder: File? = null,
) {
    enum class Kind { Version, Archive, Json }
}

/** Where the weekly archives are written. */
interface ArchiveDestination {
    /** Writes a new archive named [name]; returns an ID to find or delete it later, or null on failure. */
    fun write(name: String, content: (OutputStream) -> Unit): String?
    fun delete(id: String)
}

/**
 * Download/Encave, through MediaStore: the Files app shows it, and no permission is needed for
 * the app's own files. Removing Encave does not remove them.
 */
class DownloadsDestination(context: Context) : ArchiveDestination {
    private val resolver: ContentResolver = context.contentResolver

    override fun write(name: String, content: (OutputStream) -> Unit): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return runCatching {
            requireNotNull(resolver.openOutputStream(uri, "w")).use(content)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    override fun delete(id: String) {
        runCatching { resolver.delete(Uri.parse(id), null, null) }
    }

    companion object {
        const val FOLDER = "Encave"
    }
}

/** A folder of files (tests). */
class FolderDestination(private val folder: File) : ArchiveDestination {
    override fun write(name: String, content: (OutputStream) -> Unit): String? = runCatching {
        folder.mkdirs()
        val target = File(folder, name)
        val temp = File(folder, "$name.${UUID.randomUUID()}.tmp")
        temp.outputStream().use(content)
        check(temp.renameTo(target))
        target.path
    }.getOrNull()

    override fun delete(id: String) {
        File(id).delete()
    }
}

/**
 * Backups made on this device: the previous versions of the cellar (kept by the store),
 * archives made with Back up now, and the weekly archive kept in Download/Encave. Nothing
 * here uses the network. Mirrors the Apple app's `BackupCenter`.
 */
class BackupManager(
    private val repository: CellarRepository,
    private val photos: PhotoStore,
    private val documents: DocumentStore,
    val versions: CellarVersionSource,
    private val prefs: SharedPreferences,
    private val destination: ArchiveDestination,
    /** Archives being written and files read for a restore: removed at each launch. */
    private val temporaryFolder: File,
    private val clock: () -> Instant = Instant::now,
) {
    private val weekly = MutableStateFlow(prefs.getBoolean(KEY_WEEKLY, true))

    /** The weekly archive on this device (on by default). */
    val weeklyEnabled: StateFlow<Boolean> = weekly.asStateFlow()

    private val last = MutableStateFlow(prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli))

    /** The last archive made, by Back up now or the weekly backup. */
    val lastBackup: StateFlow<Instant?> = last.asStateFlow()

    private val busy = MutableStateFlow(false)
    val working: StateFlow<Boolean> = busy.asStateFlow()
    private val mutex = Mutex()

    init {
        if (!prefs.contains(KEY_FIRST_USE)) prefs.edit { putLong(KEY_FIRST_USE, clock().toEpochMilli()) }
    }

    fun setWeeklyEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WEEKLY, enabled) }
        weekly.value = enabled
    }

    /** No backup for 30 days (or since Encave was first used), and a cellar to keep. */
    fun reminderDue(cellar: Cellar, now: Instant = clock()): Boolean {
        if (cellar.isEmpty) return false
        val since = last.value ?: Instant.ofEpochMilli(prefs.getLong(KEY_FIRST_USE, now.toEpochMilli()))
        return Duration.between(since, now) >= REMINDER_INTERVAL
    }

    private fun fileOf(ref: FileRef): File? = when (ref.kind) {
        FileKind.Photo -> photos.file(ref.id)
        FileKind.Document -> documents.file(ref.id)
    }

    /** Back up now: the archive of the cellar, written to [output] (a file the user chose). */
    suspend fun writeArchive(output: OutputStream): Result<CellarArchive.WriteResult> = mutex.withLock {
        busy.value = true
        try {
            val cellar = repository.read { it.copy() }
            val now = clock()
            withContext(Dispatchers.IO) { runCatching { CellarArchive.write(cellar, now, output, ::fileOf) } }
                .onSuccess { noteBackup(now) }
        } finally {
            busy.value = false
        }
    }

    private fun noteBackup(at: Instant) {
        prefs.edit { putLong(KEY_LAST, at.toEpochMilli()) }
        last.value = at
    }

    /**
     * The weekly archive, when it is due: on, a cellar to keep, and none made for 7 days. The
     * oldest beyond the last two are then removed. Returns whether an archive was written.
     */
    suspend fun runAutomaticIfDue(now: Instant = clock()): Boolean {
        if (!weekly.value || mutex.isLocked) return false
        return mutex.withLock {
            val lastAutomatic = prefs.getLong(KEY_LAST_AUTOMATIC, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
            if (lastAutomatic != null && !lastAutomatic.isAfter(now) && Duration.between(lastAutomatic, now) < AUTOMATIC_INTERVAL) {
                return@withLock false
            }
            val cellar = repository.read { it.copy() }
            if (cellar.isEmpty) return@withLock false
            busy.value = true
            try {
                val name = CellarArchive.fileName(now)
                val id = withContext(Dispatchers.IO) {
                    destination.write(name) { CellarArchive.write(cellar, now, it, ::fileOf) }
                } ?: return@withLock false
                val ids = (automaticIds() + id).toMutableList()
                while (ids.size > AUTOMATIC_COUNT) withContext(Dispatchers.IO) { destination.delete(ids.removeAt(0)) }
                prefs.edit {
                    putString(KEY_AUTOMATIC_FILES, ids.joinToString("\n"))
                    putLong(KEY_LAST_AUTOMATIC, now.toEpochMilli())
                }
                noteBackup(now)
                true
            } finally {
                busy.value = false
            }
        }
    }

    /** The weekly archives written (IDs of the destination), oldest first. */
    fun automaticIds(): List<String> = prefs.getString(KEY_AUTOMATIC_FILES, null).orEmpty().split("\n").filter { it.isNotBlank() }

    /** An earlier version, ready to preview. */
    suspend fun source(version: CellarVersion): RestoreSource =
        withContext(Dispatchers.IO) { RestoreSource(RestoreSource.Kind.Version, version.date, versions.cellar(version)) }

    /** The newest earlier version, or null if there is none. */
    suspend fun latestVersion(): RestoreSource? =
        withContext(Dispatchers.IO) { versions.versions().firstOrNull() }?.let { runCatching { source(it) }.getOrNull() }

    /**
     * Reads a backup the user picked: an archive (by its first bytes, a ZIP file) or a JSON
     * backup. Throws [app.encave.core.ImportException] for a file that is not one.
     */
    suspend fun source(resolver: ContentResolver, uri: Uri): RestoreSource = withContext(Dispatchers.IO) {
        val folder = File(temporaryFolder, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            BufferedInputStream(requireNotNull(resolver.openInputStream(uri))).use { input ->
                input.mark(8)
                val head = ByteArray(4)
                val read = input.read(head)
                input.reset()
                if (read == 4 && head.contentEquals(ZIP_SIGNATURE)) {
                    val contents = CellarArchive.read(input, folder)
                    RestoreSource(RestoreSource.Kind.Archive, contents.backup.exportedAt, contents.backup.cellar, contents.files, folder)
                } else {
                    val backup = CellarExport.backupFromJson(input.readBytes().toString(Charsets.UTF_8))
                    folder.deleteRecursively()
                    RestoreSource(RestoreSource.Kind.Json, backup.exportedAt, backup.cellar)
                }
            }
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    /** Copies an archive's photos and documents into the stores, one at a time. */
    fun install(files: Map<FileRef, File>) {
        for ((ref, file) in files) {
            val data = runCatching { file.readBytes() }.getOrNull() ?: continue
            when (ref.kind) {
                FileKind.Photo -> photos.store(ref.id, data)
                FileKind.Document -> documents.store(ref.id, data)
            }
        }
    }

    fun removeTemporaryFiles() {
        temporaryFolder.deleteRecursively()
    }

    companion object {
        private const val KEY_WEEKLY = "weekly"
        private const val KEY_LAST = "last"
        private const val KEY_LAST_AUTOMATIC = "last_automatic"
        private const val KEY_AUTOMATIC_FILES = "automatic_files"
        private const val KEY_FIRST_USE = "first_use"
        private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** How often the weekly archive is made. */
        val AUTOMATIC_INTERVAL: Duration = Duration.ofDays(7)

        /** Settings suggest a backup when none was made for this long. */
        val REMINDER_INTERVAL: Duration = Duration.ofDays(30)

        /** Weekly archives kept on the device (each holds every photo). */
        const val AUTOMATIC_COUNT = 2
    }
}

/** Once a day, makes the weekly archive if it is due (it also runs when Encave opens). */
class BackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as EncaveApplication
        app.repository.loaded.first { it }
        app.backups.runAutomaticIfDue()
        return Result.success()
    }

    companion object {
        private const val NAME = "encave.weekly-backup"

        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
