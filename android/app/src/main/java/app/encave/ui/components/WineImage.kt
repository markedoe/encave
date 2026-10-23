package app.encave.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.core.AutoLevels
import app.encave.core.LabelCrop
import app.encave.core.LabelTilt
import app.encave.core.Wine
import app.encave.core.WineLabelFrame
import app.encave.core.labelFrame
import app.encave.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's photo store, for rows and cards that show a wine's picture. */
val LocalPhotoStore = staticCompositionLocalOf<PhotoStore?> { null }

/** The studio look setting (Settings > Appearance), on by default. */
val LocalStudioLook = staticCompositionLocalOf { true }

/** True when the wine has a picture on this device: its cover or its label photo. */
fun PhotoStore.hasPicture(wine: Wine?): Boolean = wine != null && (exists(wine.coverPhotoId) || exists(wine.photoId))

/**
 * The picture shown for a wine: its cover (a picture from the internet) if any,
 * else its label photo. In the studio look the label photo is turned so its text
 * lines are level (see [LabelTilt]), cut to the band of its label (where its
 * edges are densest, see [LabelCrop]) with its light evened out, on the device,
 * and shown with a light vignette: filling the picture, centred on the label, or
 * as tall as the picture on the backdrop when it is narrower; a cover is shown
 * whole on a backdrop in the wine's color. The stored photo is never changed.
 */
@Composable
fun WineImage(
    wine: Wine,
    modifier: Modifier = Modifier,
    maxPixelSize: Int = 900,
    inset: Dp = 12.dp,
    corner: Dp = 16.dp,
    contentDescription: String? = null,
) {
    val photos = LocalPhotoStore.current ?: return
    val studio = LocalStudioLook.current
    val cover = wine.coverPhotoId?.takeIf { photos.exists(it) }
    val id = cover ?: wine.photoId?.takeIf { photos.exists(it) } ?: return
    val enhance = studio && cover == null
    // Where the AI found the label in this photo, if it did: the picture is framed on it.
    val labelFrame = if (enhance) wine.labelFrame(id) else null
    val picture by produceState<StudioPicture?>(null, id, enhance, maxPixelSize, labelFrame) {
        value = withContext(Dispatchers.Default) {
            if (enhance) StudioBitmaps.get(photos, id, maxPixelSize, labelFrame) else photos.load(id, maxPixelSize)?.let { StudioPicture(it, framed = false) }
        }
    }
    val shape = RoundedCornerShape(corner)
    if (!studio) {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            picture?.let {
                Image(it.bitmap.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        return
    }
    BoxWithConstraints(modifier.clip(shape), contentAlignment = Alignment.Center) {
        StudioBackdrop(wine.color, wine.id.toString(), Modifier.matchParentSize())
        picture?.let {
            val bitmap = it.bitmap
            val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val card = if (maxHeight.value > 0f && maxHeight.value.isFinite()) maxWidth.value / maxHeight.value else aspect
            if (enhance && aspect < card) {
                // Narrower than the picture: as tall as it, centred, with the backdrop on its sides.
                Image(bitmap.asImageBitmap(), contentDescription, Modifier.fillMaxHeight().aspectRatio(aspect).vignette(), contentScale = ContentScale.Fit)
            } else if (enhance) {
                // The label photo fills the picture, centred on its label, with a light vignette.
                Image(bitmap.asImageBitmap(), contentDescription, Modifier.fillMaxSize().vignette(), contentScale = ContentScale.Crop)
            } else {
                // A cover is shown whole on the backdrop.
                val image = Modifier.padding(inset).aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
                Image(bitmap.asImageBitmap(), contentDescription, image.clip(RoundedCornerShape(corner / 2)), contentScale = ContentScale.Fit)
            }
        }
    }
}

/** A picture for [WineImage]; [framed] when it was cut to the band of its label. */
internal class StudioPicture(val bitmap: Bitmap, val framed: Boolean)

/** A light vignette: the edges a little darker. */
private fun Modifier.vignette() = drawWithContent {
    drawContent()
    drawRect(
        Brush.radialGradient(
            0.55f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.28f),
            center = center, radius = maxOf(size.width, size.height) * 0.75f,
        ),
    )
}

/** Studio versions of label photos, made once per size and kept in memory. */
internal object StudioBitmaps {
    // Sized by memory, not by count: a large label photo weighs megabytes.
    private val cache = object : LruCache<String, StudioPicture>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: StudioPicture): Int = value.bitmap.byteCount / 1024
    }

    /** The cache's name for a picture: the photo, its size, and the label frame used, if any. */
    fun key(id: String, maxPixelSize: Int, labelFrame: WineLabelFrame?): String =
        "$id@$maxPixelSize" + (labelFrame?.let { "#${it.cacheTag}" } ?: "")

    fun get(photos: PhotoStore, id: String, maxPixelSize: Int, labelFrame: WineLabelFrame? = null): StudioPicture? {
        val key = key(id, maxPixelSize, labelFrame)
        cache.get(key)?.let { return it }
        // Framed from a larger copy than shown, so the label keeps its detail.
        val source = photos.load(id, minOf(1200, maxPixelSize * 3).coerceAtLeast(maxPixelSize)) ?: return null
        val result = studio(source, maxPixelSize, labelFrame)
        cache.put(key, result)
        return result
    }

    /**
     * The photo turned so its text lines are level, framed on its label (or
     * whole), light evened out, at most [maxPixelSize] on its longest side. With
     * the label frame the AI found, cut to that label, turned level, instead.
     */
    fun studio(photo: Bitmap, maxPixelSize: Int, labelFrame: WineLabelFrame? = null): StudioPicture {
        labelFrame?.let { framed(photo, it) }?.let { return finish(it, maxPixelSize, framed = true) }
        var pixels = IntArray(photo.width * photo.height)
        photo.getPixels(pixels, 0, photo.width, 0, 0, photo.width, photo.height)
        val source = upright(photo, pixels)
        if (source !== photo) {
            pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        }
        val box = LabelCrop.find(pixels, source.width, source.height)
        val (left, top, width, height) = box?.let { listOf(it.left, it.top, it.width, it.height) }
            ?: listOf(0, 0, source.width, source.height)
        val part = IntArray(width * height)
        for (y in 0 until height) System.arraycopy(pixels, (top + y) * source.width + left, part, y * width, width)
        var bitmap = Bitmap.createBitmap(AutoLevels.apply(part), width, height, Bitmap.Config.ARGB_8888)
        val longest = maxOf(width, height)
        if (longest > maxPixelSize) {
            val scale = maxPixelSize.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        }
        return StudioPicture(bitmap, framed = box != null)
    }

    /**
     * The photo cut to the label the AI found in it, turned so its text is level (about
     * its centre), with a margin around it. Null when too small to use.
     */
    private fun framed(photo: Bitmap, frame: WineLabelFrame): Bitmap? {
        val crop = frame.crop(photo.width.toDouble(), photo.height.toDouble())
        val width = crop.width.toInt()
        val height = crop.height.toInt()
        if (width < 8 || height < 8) return null
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            translate(width / 2f, height / 2f)
            // Text running uphill is turned clockwise to be level, as Android turns.
            rotate(frame.angle.toFloat())
            translate(-crop.centerX.toFloat(), -crop.centerY.toFloat())
            drawBitmap(photo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }

    /** Light evened out, at most [maxPixelSize] on its longest side. */
    private fun finish(picture: Bitmap, maxPixelSize: Int, framed: Boolean): StudioPicture {
        val width = picture.width
        val height = picture.height
        val part = IntArray(width * height)
        picture.getPixels(part, 0, width, 0, 0, width, height)
        var bitmap = Bitmap.createBitmap(AutoLevels.apply(part), width, height, Bitmap.Config.ARGB_8888)
        val longest = maxOf(width, height)
        if (longest > maxPixelSize) {
            val scale = maxPixelSize.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        }
        return StudioPicture(bitmap, framed = framed)
    }

    /** The photo turned so its text lines are level, without the empty corners; the photo itself when level. */
    private fun upright(photo: Bitmap, pixels: IntArray): Bitmap {
        val degrees = LabelTilt.straightening(pixels, photo.width, photo.height) ?: return photo
        val (width, height) = LabelTilt.upright(photo.width, photo.height, degrees)
        if (width < 8 || height < 8) return photo
        // Android turns clockwise as seen, the angle is counterclockwise.
        val turned = Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, Matrix().apply { setRotate(-degrees.toFloat()) }, true)
        val left = ((turned.width - width) / 2).coerceAtLeast(0)
        val top = ((turned.height - height) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(turned, left, top, minOf(width, turned.width - left), minOf(height, turned.height - top))
    }
}
