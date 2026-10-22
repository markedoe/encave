package app.encave.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/** The studio look's label framing on synthetic photos (no real labels). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioBitmapsTest {
    private val paint = Paint()

    /** Lines of small dark marks, like printed text, in [left, right) × [top, bottom). */
    private fun Canvas.text(left: Int, top: Int, right: Int, bottom: Int, seed: Int) {
        val random = Random(seed)
        paint.color = Color.rgb(25, 25, 25)
        var y = top
        while (y + 6 < bottom) {
            var x = left
            while (x + 8 < right) {
                if (random.nextInt(3) > 0) drawRect(x.toFloat(), y.toFloat(), (x + 5).toFloat(), (y + 6).toFloat(), paint)
                x += 8
            }
            y += 14
        }
    }

    private fun photo(width: Int, height: Int, draw: Canvas.() -> Unit): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { Canvas(this).draw() }

    @Test
    fun aWholeBottleIsFramedOnItsLabel() {
        val source = photo(900, 1200) {
            drawColor(Color.rgb(200, 192, 184))
            paint.color = Color.rgb(30, 45, 30)
            drawRect(360f, 220f, 540f, 1140f, paint) // the bottle
            paint.color = Color.rgb(240, 232, 214)
            drawRect(360f, 700f, 540f, 960f, paint) // its label
            text(372, 720, 528, 950, seed = 1)
        }
        val result = StudioBitmaps.studio(source, maxPixelSize = 900)
        assertTrue(result.framed)
        // A band around the label, much less tall than the photo: the label fills the picture.
        assertTrue(result.bitmap.height < source.height / 2)
        assertTrue(result.bitmap.width > result.bitmap.height)
    }

    @Test
    fun aCloseUpOfALabelIsShownWhole() {
        val source = photo(800, 600) {
            drawColor(Color.rgb(240, 232, 214))
            text(20, 20, 780, 580, seed = 2)
        }
        val result = StudioBitmaps.studio(source, maxPixelSize = 400)
        assertFalse(result.framed)
        assertEquals(400, result.bitmap.width)
        assertEquals(300, result.bitmap.height)
    }

    @Test
    fun aPlainPhotoGetsTheCentreBand() {
        val source = photo(600, 800) { drawColor(Color.rgb(128, 128, 128)) }
        val result = StudioBitmaps.studio(source, maxPixelSize = 900)
        assertTrue(result.framed)
        assertTrue(result.bitmap.height < source.height)
    }

    @Test
    fun theBokehBackdropIsTheSameForAWineAndDiffersBetweenWines() {
        val base = androidx.compose.ui.graphics.Color(0xFF141010)
        val candle = androidx.compose.ui.graphics.Color(0xFFFFC478)
        val tint = androidx.compose.ui.graphics.Color(0xFFDEB45A)
        fun pixels(seed: String): IntArray {
            val bitmap = StudioBokeh.draw(seed, base, candle, tint, dark = true, width = 120, height = 160)
            return IntArray(120 * 160).also { bitmap.getPixels(it, 0, 120, 0, 0, 120, 160) }
        }
        val first = pixels("8f0e3c1a-0000-4000-8000-000000000001")
        assertTrue(first.contentEquals(pixels("8F0E3C1A-0000-4000-8000-000000000001")))
        assertFalse(first.contentEquals(pixels("8f0e3c1a-0000-4000-8000-000000000002")))
        // Lights over the dark base: not one flat color.
        assertTrue(first.toSet().size > 50)
    }
}
