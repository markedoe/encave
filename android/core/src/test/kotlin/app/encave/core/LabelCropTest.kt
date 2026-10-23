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

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** A bottle with a label from 300 to 600 that has print only in its lower part, from 480. */
    private fun labelWithScript(): IntArray {
        val random = Random(5)
        return IntArray(600 * 800) { i ->
            val x = i % 600
            val y = i / 600
            when {
                x in 180 until 420 && y in 300 until 600 ->
                    if (y in 480 until 590 && x in 190 until 410 && y % 12 < 5 && random.nextInt(3) == 0) rgb(25, 25, 25) else rgb(240, 236, 226)
                x in 180 until 420 && y >= 40 -> rgb(30, 50, 35)
                else -> rgb(110, 125, 140)
            }
        }
    }

    @Test
    fun theStudioFrameKeepsTheWholePaper() {
        val box = checkNotNull(LabelCrop.studioFrame(labelWithScript(), 600, 800))
        val margin = (LabelCrop.PAPER_MARGIN * 800).toInt()
        assertTrue(box.top <= 300 - margin + 4 && box.bottom >= 600 + margin - 4, "the whole label and its margin: $box")
        assertTrue(box.left <= 180 && box.right >= 420, "$box")
    }

    @Test
    fun theStudioFrameIsNeverABlindCrop() {
        assertNull(LabelCrop.studioFrame(IntArray(600 * 800) { gray(128) }, 600, 800))
    }

    @Test
    fun thePaperFrameKeepsItsMargin() {
        val frame = checkNotNull(LabelCrop.paperFrame(0.5..0.6, 0.4..0.75))
        assertTrue(frame.start <= 0.4 - LabelCrop.PAPER_MARGIN + 1e-9 && frame.endInclusive >= 0.75 + LabelCrop.PAPER_MARGIN - 1e-9, "$frame")
        assertTrue(checkNotNull(LabelCrop.paperFrame(0.3..0.6, 0.4..0.75)).start < 0.3)
        assertEquals(LabelCrop.paperFrame(null, 0.4..0.75), LabelCrop.paperFrame(0.05..0.1, 0.4..0.75))
        assertNull(LabelCrop.paperFrame(null, 0.05..0.9))
    }

    @Test
    fun tinyOrMissingPixelsAreIgnored() {
        assertNull(LabelCrop.find(IntArray(4) { gray(0) }, 2, 2))
        assertNull(LabelCrop.find(IntArray(10), 100, 100))
    }
}
