package app.encave.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.encave.R
import app.encave.core.Attachment
import app.encave.core.FileKind
import app.encave.core.ReferenceDates
import app.encave.core.sync.FileAccess
import app.encave.core.sync.PhotoId
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Documents of wines and bottles (invoices, certificates, technical sheets), kept as they
 * were added in app-private storage. Each file is named after its content (the attachment
 * ID), like the photos, and synced the same way.
 */
class DocumentStore(context: Context) {
    private val appContext = context.applicationContext
    private val folder = File(context.filesDir, "documents").apply { mkdirs() }
    private val pendingFolder = File(context.cacheDir, "pending-documents")

    /** Copies handed to other apps (open, share), with the document's name. */
    private val sharedFolder = File(context.cacheDir, SHARED_FOLDER)
    private val resolver = context.contentResolver

    /** The document's file; null for an ID that is not a safe file name (IDs come from other devices too). */
    fun file(id: String): File? = if (PhotoId.isValid(id)) File(folder, "$id.$EXTENSION") else null

    /** False when the cellar refers to a document not on this device yet (not synced, or from a backup). */
    fun exists(id: String): Boolean = file(id)?.exists() == true

    fun data(id: String): ByteArray? = file(id)?.takeIf { it.exists() }?.readBytes()

    /** Writes a document under an ID (a received one). False if the ID is not a safe file name. */
    fun store(id: String, data: ByteArray): Boolean = runCatching {
        val target = file(id) ?: return false
        if (target.exists()) return true
        val temp = File(folder, "$id.${UUID.randomUUID()}.tmp")
        temp.writeBytes(data)
        check(temp.renameTo(target))
        true
    }.getOrDefault(false)

    /** Documents no wine or bottle has referred to for the photos' grace period are removed. */
    fun removeOrphans(keeping: Set<String>, now: Instant = Instant.now()) =
        OrphanFiles.remove(folder, EXTENSION, keeping, now)

    /** Why a document could not be added. */
    enum class Problem { TooLarge, Unreadable }

    class AddException(val problem: Problem) : Exception(problem.name)

    /**
     * Copies a file the user picked to a temporary place, at most [Attachment.MAX_BYTE_COUNT]
     * bytes, so it can be attached when the bottles are added or right away.
     */
    fun stage(uri: Uri): PendingDocument {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { cursor.getString(it) }
                    size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
                }
            }
        }
        if ((size ?: 0L) > Attachment.MAX_BYTE_COUNT) throw AddException(Problem.TooLarge)
        val type = resolver.getType(uri) ?: name?.let { mimeType(it) }
        val pending = PendingDocument(name = Attachment.normalizedName(name ?: ""), contentType = type ?: Attachment.GENERIC_CONTENT_TYPE, byteCount = 0)
        val target = pendingFile(pending).apply { parentFile?.mkdirs() }
        // The size a provider gives can be missing or wrong: the copy stops past the limit.
        val copied = try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > Attachment.MAX_BYTE_COUNT) throw AddException(Problem.TooLarge)
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: throw AddException(Problem.Unreadable)
        } catch (error: AddException) {
            discard(pending)
            throw error
        } catch (_: Exception) {
            discard(pending)
            throw AddException(Problem.Unreadable)
        }
        return pending.copy(byteCount = copied)
    }

    /** A photo of a paper, named after the moment it was taken, as a pending image document. */
    fun stagePhoto(jpeg: ByteArray, at: Instant = Instant.now()): PendingDocument {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT).format(at.atZone(ZoneId.systemDefault()))
        return stage(jpeg, appContext.getString(R.string.document_photo_name, time) + ".jpg", "image/jpeg")
    }

    /** Keeps new content in a temporary place, like a picked file. */
    fun stage(data: ByteArray, name: String, contentType: String): PendingDocument {
        if (data.size > Attachment.MAX_BYTE_COUNT) throw AddException(Problem.TooLarge)
        val pending = PendingDocument(name = Attachment.normalizedName(name), contentType = contentType, byteCount = data.size.toLong())
        runCatching { pendingFile(pending).apply { parentFile?.mkdirs() }.writeBytes(data) }
            .onFailure { throw AddException(Problem.Unreadable) }
        return pending
    }

    /**
     * Stores a pending document for good and returns its attachment. The temporary copy
     * stays until [discard], so a failed save can be tried again.
     */
    fun commit(pending: PendingDocument, at: Instant = ReferenceDates.now()): Attachment? {
        val data = runCatching { pendingFile(pending).readBytes() }.getOrNull() ?: return null
        val id = PhotoId.make(data)
        if (!store(id, data)) return null
        return Attachment(id, pending.name.ifEmpty { defaultName }, pending.contentType, data.size.toLong(), at)
    }

    fun discard(pending: PendingDocument) {
        pendingFile(pending).parentFile?.deleteRecursively()
    }

    /** Temporary copies (pending ones and those given to other apps), at launch. */
    fun removeTemporaryCopies() {
        pendingFolder.deleteRecursively()
        sharedFolder.deleteRecursively()
    }

    /**
     * A copy of the document with its name and an extension matching its type, in a folder
     * of its own, as a content URI another app can open or receive. Null if the file is not
     * on this device.
     */
    fun sharedUri(attachment: Attachment): Uri? = runCatching {
        val source = file(attachment.id)?.takeIf { it.exists() } ?: return null
        val target = File(File(sharedFolder, UUID.randomUUID().toString()), fileName(attachment, defaultName))
        target.parentFile?.mkdirs()
        source.copyTo(target)
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", target)
    }.getOrNull()

    /** For a document without a name. */
    private val defaultName: String get() = appContext.getString(R.string.document_default_name)

    private fun pendingFile(pending: PendingDocument) = File(File(pendingFolder, pending.id.toString()), "file")

    companion object {
        /** Files on disk end with this, whatever their type: the type is in the attachment. */
        const val EXTENSION = "document"
        private const val SHARED_FOLDER = "documents"

        private fun mimeType(name: String): String? =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

        /** The document's name as a file name: never a path, with the extension of its type. */
        fun fileName(attachment: Attachment, fallbackName: String): String {
            var name = attachment.name
            if (name.isEmpty() || name == "." || name == "..") name = fallbackName
            if (name.startsWith(".")) name = "_$name"
            val wanted = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.contentType) ?: return name
            val current = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val same = current == wanted || MimeTypeMap.getSingleton().getMimeTypeFromExtension(current) == attachment.contentType
            return if (same) name else "$name.$wanted"
        }
    }
}

/** A document picked or photographed, not attached yet: a copy in a temporary place. */
data class PendingDocument(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val contentType: String,
    val byteCount: Long,
)

/** Photos and documents for a sync session, each read and saved in its own folder. */
class SyncFiles(private val photos: PhotoStore, private val documents: DocumentStore) : FileAccess {
    override fun data(id: String, kind: FileKind): ByteArray? = when (kind) {
        FileKind.Photo -> photos.data(id)
        FileKind.Document -> documents.data(id)
    }

    override fun exists(id: String, kind: FileKind): Boolean = when (kind) {
        FileKind.Photo -> photos.exists(id)
        FileKind.Document -> documents.exists(id)
    }

    override fun save(id: String, kind: FileKind, data: ByteArray) {
        when (kind) {
            FileKind.Photo -> photos.store(id, data)
            FileKind.Document -> documents.store(id, data)
        }
    }
}
