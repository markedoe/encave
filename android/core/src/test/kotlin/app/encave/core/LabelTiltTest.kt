package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class LabelTiltTest {
    private fun gray(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    /** Dark lines 5 pixels thick every 14, rising to the right at [degrees] (counterclockwise as seen). */
    private fun lines(degrees: Double, width: Int = 600, height: Int = 800): IntArray {
        val radians = Math.toRadians(degrees)
        return IntArray(width * height) { i ->
            val x = i % width - width / 2.0
            val y = i / width - height / 2.0
            val across = (y * cos(radians) + x * sin(radians)).toInt()
            if (across.mod(14) < 5) gray(30) else gray(230)
        }
    }

    @Test
    fun linesRisingToTheRightAreTurnedClockwise() {
        val angle = checkNotNull(LabelTilt.lineAngle(lines(20.0), 600, 800))
        assertEquals(20.0, angle, 1.0)
        assertEquals(-20.0, checkNotNull(LabelTilt.straightening(lines(20.0), 600, 800)), 1.0)
    }

    @Test
    fun linesFallingToTheRightAreTurnedCounterclockwise() {
        assertEquals(8.0, checkNotNull(LabelTilt.straightening(lines(-8.0), 600, 800)), 1.0)
    }

    @Test
    fun aTiltedLabelOnABottleIsFound() {
        val random = Random(3)
        val radians = Math.toRadians(12.0)
        val pixels = IntArray(600 * 800) { i ->
            val x = i % 600
            val y = i / 600
            // The label's own lines, turned around its centre.
            val across = (y - 550) * cos(radians) + (x - 300) * sin(radians)
            val along = (x - 300) * cos(radians) - (y - 550) * sin(radians)
            val inLabel = along in -70.0..70.0 && across in -80.0..80.0
            when {
                inLabel -> if (across.toInt().mod(12) < 5 && random.nextInt(3) > 0) gray(30) else gray(230)
                x in 230 until 370 && y in 150 until 760 -> gray(40)
                else -> gray(170)
            }
        }
        assertEquals(-12.0, checkNotNull(LabelTilt.straightening(pixels, 600, 800)), 1.5)
    }

    @Test
    fun levelLinesAreLeftAlone() {
        assertEquals(0.0, checkNotNull(LabelTilt.lineAngle(lines(0.0), 600, 800)), 0.5)
        assertNull(LabelTilt.straightening(lines(0.0), 600, 800))
        assertNull(LabelTilt.straightening(lines(1.0), 600, 800))
    }

    @Test
    fun aPlainOrNoisyPhotoIsLeftAlone() {
        assertNull(LabelTilt.straightening(IntArray(600 * 800) { gray(128) }, 600, 800))
        val random = Random(5)
        assertNull(LabelTilt.straightening(IntArray(600 * 800) { gray(random.nextInt(256)) }, 600, 800))
        assertNull(LabelTilt.straightening(IntArray(4) { gray(0) }, 2, 2))
    }

    @Test
    fun theTurnedPhotoLosesItsEmptyCorners() {
        assertEquals(Pair(600, 800), LabelTilt.upright(600, 800, 0.0))
        val (width, height) = LabelTilt.upright(600, 800, 10.0)
        assertEquals((600 - 800 * sin(Math.toRadians(10.0))).toInt(), width)
        assertEquals((800 - 600 * sin(Math.toRadians(10.0))).toInt(), height)
    }
}
