package app.encave.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.encave.core.sync.PhotoAccess
import app.encave.core.sync.PhotoId
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Wine photos stored as JPEG files in app-private storage, resized on the device.
 * Re-encoding drops every metadata field (location, camera, date).
 */
class PhotoStore(context: Context) {
    private val folder = File(context.filesDir, "photos").apply { mkdirs() }
    private val resolver = context.contentResolver

    /** The photo's file; null for an ID that is not a safe file name (IDs come from other devices too). */
    fun file(id: String): File? = if (PhotoId.isValid(id)) File(folder, "$id.jpg") else null

    /** False when a wine refers to a photo that is not on this device (after importing a backup). */
    fun exists(id: String?): Boolean = id != null && file(id)?.exists() == true

    /** Reads an image (picker or camera), applies its orientation and returns a resized JPEG. */
    fun prepare(uri: Uri, maxPixelSize: Int = MAX_PIXEL_SIZE): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxPixelSize) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxPixelSize.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
    }.getOrNull()

    /** A downloaded picture as a resized JPEG, re-encoded so no metadata is kept; null if it is not an image. */
    fun prepare(bytes: ByteArray, maxPixelSize: Int = MAX_PIXEL_SIZE): ByteArray? = runCatching {
        val decoded = decode(bytes, maxPixelSize) ?: return null
        val scale = minOf(1f, maxPixelSize.toFloat() / maxOf(decoded.width, decoded.height))
        val output = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        ByteArrayOutputStream().use { stream ->
            output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            stream.toByteArray()
        }
    }.getOrNull()

    /**
     * Saves a prepared JPEG; returns the new photo identifier, named after its content
     * ("sha256-…") so other devices can check a synced photo is the one they asked for.
     */
    fun save(jpeg: ByteArray): String? {
        val id = PhotoId.make(jpeg)
        return if (store(id, jpeg)) id else null
    }

    /** Writes a photo under an ID (a received one). False if the ID is not a safe file name. */
    fun store(id: String, jpeg: ByteArray): Boolean = runCatching {
        val target = file(id) ?: return false
        if (target.exists()) return true
        val temp = File(folder, "$id.${UUID.randomUUID()}.tmp")
        temp.writeBytes(jpeg)
        check(temp.renameTo(target))
        true
    }.getOrDefault(false)

    /** The photo's bytes, for sync. */
    fun data(id: String): ByteArray? = file(id)?.takeIf { it.exists() }?.readBytes()

    /** Photos for a sync session: read and saved by ID. */
    val syncAccess: PhotoAccess = object : PhotoAccess {
        override fun data(id: String): ByteArray? = this@PhotoStore.data(id)
        override fun exists(id: String): Boolean = this@PhotoStore.exists(id)
        override fun save(id: String, data: ByteArray) {
            store(id, data)
        }
    }

    /**
     * Removes photo files no wine has referred to for [ORPHAN_GRACE_DAYS]. A wine can refer to
     * one again in the meantime: an Undo, or a device that has not synced yet sending an older
     * copy of the wine. When each file was first seen unreferenced is kept in
     * `unreferenced.json` next to the photos (as on Apple devices).
     */
    fun removeOrphans(keeping: Set<String>, now: Instant = Instant.now()) {
        val list = File(folder, UNREFERENCED)
        val seen = runCatching {
            Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), list.readText())
        }.getOrDefault(emptyMap())
        val unreferenced = HashMap<String, Long>()
        val grace = Duration.ofDays(ORPHAN_GRACE_DAYS)
        folder.listFiles().orEmpty().forEach { file ->
            when {
                file.extension == "tmp" -> file.delete()
                file.extension == "jpg" && file.nameWithoutExtension !in keeping -> {
                    val id = file.nameWithoutExtension
                    val since = seen[id] ?: now.epochSecond
                    if (Duration.between(Instant.ofEpochSecond(since), now) >= grace) file.delete() else unreferenced[id] = since
                }
            }
        }
        runCatching {
            if (unreferenced.isEmpty()) {
                list.delete()
            } else if (unreferenced != seen) {
                val temp = File(folder, "$UNREFERENCED.${UUID.randomUUID()}.tmp")
                temp.writeText(Json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), unreferenced))
                if (!temp.renameTo(list)) temp.delete()
            }
        }
    }

    /** A downscaled bitmap for display. */
    fun load(id: String, maxPixelSize: Int = 900): Bitmap? = decode(file(id)?.takeIf { it.exists() }?.readBytes(), maxPixelSize)

    companion object {
        const val MAX_PIXEL_SIZE = 1600

        /** How long a photo no wine refers to is kept before its file is removed. */
        const val ORPHAN_GRACE_DAYS = 30L
        private const val UNREFERENCED = "unreferenced.json"
        private const val JPEG_QUALITY = 80

        fun decode(bytes: ByteArray?, maxPixelSize: Int): Bitmap? {
            if (bytes == null) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixelSize) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
