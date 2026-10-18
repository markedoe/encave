package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoLevelsTest {
    private fun gray(value: Int, alpha: Int = 0xFF) = (alpha shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun aFlatPhotoGetsMoreContrastWithinTheGainCap() {
        val pixels = IntArray(1000) { gray(100 + it % 51) } // 100...150
        val out = AutoLevels.apply(pixels)
        val values = out.map { it and 0xFF }
        assertTrue(values.max() - values.min() > 70, "range widened")
        assertTrue(values.max() - values.min() <= 82, "gain capped at 1.6")
    }

    @Test
    fun aFullRangePhotoStaysTheSameAndAlphaIsKept() {
        val pixels = IntArray(256) { gray(it, alpha = 0x80) }
        val out = AutoLevels.apply(pixels)
        // Only the darkest and brightest 1% are trimmed: a few levels at most.
        assertTrue(pixels.indices.all { kotlin.math.abs((out[it] and 0xFF) - (pixels[it] and 0xFF)) <= 4 })
        assertTrue(out.all { (it ushr 24) == 0x80 })
    }

    @Test
    fun anEmptyPhotoIsFine() {
        assertEquals(0, AutoLevels.apply(IntArray(0)).size)
    }
}
