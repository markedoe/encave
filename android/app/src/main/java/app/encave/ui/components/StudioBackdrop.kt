package app.encave.ui.components

import android.app.UiModeManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.encave.core.StudioRandom
import app.encave.core.WineColor
import app.encave.ui.theme.LocalStudioPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The backdrop behind studio pictures and covers: a cellar in soft focus, its
 * lights blurred into discs of candle light and of the wine's color over a warm
 * base, with a soft wash of the wine's color and a vignette ([StudioBokeh]). The
 * same for a wine at every launch, and as on Apple. With high contrast on, only
 * the base and the wash. Decorative.
 */
@Composable
fun StudioBackdrop(color: WineColor?, seed: String, modifier: Modifier = Modifier) {
    val palette = LocalStudioPalette.current
    val tint = palette.tint(color)
    val context = LocalContext.current
    val plain = (context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f) > 0f
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val bitmap by produceState<Bitmap?>(null, seed, tint, palette, width, height, plain) {
            value = if (plain || width <= 0 || height <= 0 || width == Int.MAX_VALUE || height == Int.MAX_VALUE) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    StudioBokeh.bitmap(seed, palette.base, palette.candle, tint, palette.dark, width, height)
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(palette.base)
            drawRect(
                Brush.radialGradient(
                    listOf(tint.copy(alpha = if (palette.dark) 0.35f else 0.25f), tint.copy(alpha = 0f)),
                    center = Offset(size.width * 0.3f, size.height * 0.25f),
                    radius = max(size.width, size.height) * 1.1f,
                ),
            )
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

/**
 * Draws the bokeh backdrop and keeps it for each wine, theme and size. The numbers
 * and the order of the random draws are the same as on Apple (StudioPhoto.swift,
 * StudioBokeh), so a wine looks the same on both.
 */
internal object StudioBokeh {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun bitmap(seed: String, base: Color, candle: Color, tint: Color, dark: Boolean, width: Int, height: Int): Bitmap {
        val key = "$seed|${base.toArgb()}|${candle.toArgb()}|${tint.toArgb()}|$dark|${width}x$height"
        cache.get(key)?.let { return it }
        val result = draw(seed, base, candle, tint, dark, width, height)
        cache.put(key, result)
        return result
    }

    fun draw(seed: String, base: Color, candle: Color, tint: Color, dark: Boolean, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun argb(color: Color, alpha: Float) = color.copy(alpha = alpha).toArgb()
        fun radial(x: Float, y: Float, radius: Float, colors: IntArray, stops: FloatArray, fill: Boolean) {
            paint.shader = RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP)
            if (fill) canvas.drawPaint(paint) else canvas.drawCircle(x, y, radius, paint)
        }

        canvas.drawColor(base.toArgb())
        // A very soft wash of the wine's color.
        val wash = if (dark) 0.35f else 0.25f
        radial(0.3f * w, 0.25f * h, 1.1f * max(w, h), intArrayOf(argb(tint, wash), argb(tint, 0f)), floatArrayOf(0f, 1f), fill = true)
        // Out-of-focus lights: 60% candle light, 40% the wine's color brightened.
        val bright = Color(min(1f, tint.red * 1.6f), min(1f, tint.green * 1.6f), min(1f, tint.blue * 1.6f))
        val random = StudioRandom(seed)
        val blur = 0.02f * w
        val strength = if (dark) 1f else 0.55f
        paint.blendMode = if (dark) android.graphics.BlendMode.SCREEN else android.graphics.BlendMode.SRC_OVER
        repeat(24) {
            val radius = w * (0.04f + 0.08f * random.next().toFloat())
            val x = w * random.next().toFloat()
            val y = h * 0.8f * random.next().toFloat()
            val light = if (random.next() < 0.6) candle else bright
            val alpha = (0.25f + 0.45f * random.next().toFloat()) * strength
            val outer = radius + blur
            val edge = max(0f, (radius - blur) / outer)
            radial(x, y, outer, intArrayOf(argb(light, alpha), argb(light, alpha * 0.9f), argb(light, 0f)), floatArrayOf(0f, edge, 1f), fill = false)
        }
        paint.blendMode = android.graphics.BlendMode.SRC_OVER
        // A soft vignette.
        radial(
            w / 2, h / 2, 0.75f * max(w, h),
            intArrayOf(argb(Color.Black, 0f), argb(Color.Black, 0f), argb(Color.Black, if (dark) 0.5f else 0.18f)),
            floatArrayOf(0f, 0.55f, 1f), fill = true,
        )
        return bitmap
    }
}
