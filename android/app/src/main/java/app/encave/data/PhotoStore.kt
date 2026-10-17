package app.encave.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Wine photos stored as JPEG files in app-private storage, resized on the device.
 * Re-encoding drops every metadata field (location, camera, date).
 */
class PhotoStore(context: Context) {
    private val folder = File(context.filesDir, "photos").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun file(id: String): File = File(folder, "$id.jpg")

    /** False when a wine refers to a photo that is not on this device (after importing a backup). */
    fun exists(id: String?): Boolean = id != null && file(id).exists()

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

    /** Saves a prepared JPEG; returns the new photo identifier. */
    fun save(jpeg: ByteArray): String? = runCatching {
        val id = UUID.randomUUID().toString().uppercase(Locale.ROOT)
        val temp = File(folder, "$id.tmp")
        temp.writeBytes(jpeg)
        check(temp.renameTo(file(id)))
        id
    }.getOrNull()

    fun delete(id: String?) {
        if (id != null) file(id).delete()
    }

    /** Removes photo files no wine refers to any more. */
    fun removeOrphans(keeping: Set<String>) {
        folder.listFiles().orEmpty().forEach { file ->
            if (file.extension == "tmp" || (file.extension == "jpg" && file.nameWithoutExtension !in keeping)) file.delete()
        }
    }

    /** A downscaled bitmap for display. */
    fun load(id: String, maxPixelSize: Int = 900): Bitmap? = decode(file(id).takeIf { it.exists() }?.readBytes(), maxPixelSize)

    companion object {
        const val MAX_PIXEL_SIZE = 1600
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
