package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class LabelCropTest {
    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    /** A plain wall, a dark bottle in the middle and a pale label with "text" (random marks) on it. */
    private fun bottlePhoto(width: Int = 600, height: Int = 800): IntArray {
        val random = Random(7)
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val inBottle = x in 240 until 360 && y in 150 until 760
            val inLabel = x in 240 until 360 && y in 470 until 640
            when {
                inLabel -> if (y % 12 < 5 && random.nextInt(3) == 0) gray(30) else gray(230)
                inBottle -> gray(40)
                else -> gray(170)
            }
        }
    }

    @Test
    fun aWholeBottleIsFramedOnItsLabel() {
        val box = checkNotNull(LabelCrop.find(bottlePhoto(), 600, 800))
        assertTrue(box.top in 380..480, "a little of the shoulder above the label: $box")
        assertTrue(box.bottom in 630..720, "a small margin below the label: $box")
        assertTrue(box.left <= 240 && box.right >= 360, "the label's full width: $box")
        // The label (170 px) is about 80% of the result, which is centred on it.
        assertTrue(170f / box.height in 0.7f..0.9f, "label share: $box")
        assertTrue(kotlin.math.abs((box.left + box.right) / 2 - 300) <= 10, "centred on the label: $box")
    }

    @Test
    fun aCloseUpOfTheLabelIsLeftWhole() {
        val random = Random(3)
        val pixels = IntArray(600 * 800) { i -> if ((i / 600) % 12 < 5 && random.nextInt(3) == 0) gray(30) else gray(230) }
        assertNull(LabelCrop.find(pixels, 600, 800))
    }

    @Test
    fun aPhotoWithoutEdgesGetsTheCentreBand() {
        val box = checkNotNull(LabelCrop.find(IntArray(600 * 800) { gray(128) }, 600, 800))
        assertTrue(box.top >= (800 * 0.2).toInt() && box.bottom <= 800, "$box")
        assertTrue(kotlin.math.abs((box.left + box.right) / 2 - 300) <= 10, "$box")
    }

    @Test
    fun tinyOrMissingPixelsAreIgnored() {
        assertNull(LabelCrop.find(IntArray(4) { gray(0) }, 2, 2))
        assertNull(LabelCrop.find(IntArray(10), 100, 100))
    }
}
