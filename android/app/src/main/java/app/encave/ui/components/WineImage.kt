package app.encave.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.core.AutoLevels
import app.encave.core.Wine
import app.encave.data.PhotoStore
import app.encave.ui.theme.LocalWinePalette
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
 * else its label photo. In the studio look the photo's light is evened out on the
 * device and it is shown whole on a clean background tinted by the wine's color;
 * the stored photo is never changed.
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
    val bitmap by produceState<Bitmap?>(null, id, enhance, maxPixelSize) {
        value = withContext(Dispatchers.Default) {
            if (enhance) StudioBitmaps.get(photos, id, maxPixelSize) else photos.load(id, maxPixelSize)
        }
    }
    val shape = RoundedCornerShape(corner)
    if (!studio) {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        return
    }
    val tint = LocalWinePalette.current[wine.color]
    Box(
        modifier.clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent))),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(), contentDescription, Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(corner / 2)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Studio versions of label photos, made once per size and kept in memory. */
private object StudioBitmaps {
    // Sized by memory, not by count: a large label photo weighs megabytes.
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(photos: PhotoStore, id: String, maxPixelSize: Int): Bitmap? {
        val key = "$id@$maxPixelSize"
        cache.get(key)?.let { return it }
        val source = photos.load(id, maxPixelSize) ?: return null
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val result = Bitmap.createBitmap(AutoLevels.apply(pixels), width, height, Bitmap.Config.ARGB_8888)
        cache.put(key, result)
        return result
    }
}
