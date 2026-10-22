package app.encave.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How much a label photo leans, on the device and without any model: printed
 * lines make rows of sharp edges, so the angle at which the edges line up best
 * (the rows of edges, counted along that angle, are most uneven) is the angle of
 * the text lines. Only the central column is looked at, where the bottle stands.
 * Pixels are ARGB ints.
 */
object LabelTilt {
    /** Smaller leans are left alone, larger ones are not sure (the same as Apple's LabelFraming). */
    const val MINIMUM_TILT = 1.5
    const val MAXIMUM_TILT = 25.0

    /** The photo is analysed at about this many cells on its longest side. */
    private const val GRID = 400
    /** A difference of light between neighbouring cells that counts as an edge (0–255). */
    private const val EDGE = 28
    /** The angles tried, in degrees, every half degree. */
    private const val SEARCH = 30.0
    private const val STEP = 0.5
    /** The best angle must line the edges up this much better than a typical one. */
    private const val CLEAR = 1.2

    /**
     * The angle of the text lines in degrees, counterclockwise as seen (a line
     * rising to the right is positive), or null when the photo has too few
     * edges or no clear lines.
     */
    fun lineAngle(pixels: IntArray, width: Int, height: Int): Double? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val step = max(1, max(width, height) / GRID)
        val w = width / step
        val h = height / step
        if (w < 8 || h < 8) return null
        val light = LabelCrop.grayGrid(pixels, width, step, w, h)

        // The edges of the central column, as points.
        val left = (w * 0.2f).toInt()
        val right = max(left + 2, (w * 0.8f).toInt())
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until h - 1) for (x in left until right - 1) {
            val here = light[y * w + x]
            if (abs(light[y * w + x + 1] - here) + abs(light[(y + 1) * w + x] - here) > EDGE) { xs += x; ys += y }
        }
        val area = (right - left) * h
        if (xs.size < 50 || xs.size < area / 200) return null

        // Points on a line rising at angle a share y·cos(a) + x·sin(a) (y down).
        val count = (2 * SEARCH / STEP).roundToInt() + 1
        val scores = DoubleArray(count)
        val span = w + h + 2
        for (i in 0 until count) {
            val radians = Math.toRadians(-SEARCH + i * STEP)
            val c = cos(radians)
            val s = sin(radians)
            val bins = IntArray(2 * span)
            for (k in xs.indices) bins[floor(ys[k] * c + xs[k] * s).toInt() + span]++
            var sum = 0.0
            for (n in bins) sum += n.toDouble() * n
            scores[i] = sum
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        val typical = scores.sorted()[count / 2]
        if (scores[best] < typical * CLEAR) return null
        return -SEARCH + best * STEP
    }

    /**
     * The angle to turn the photo by (degrees, counterclockwise as seen) so its
     * text lines are level: minus their angle, null when they are level already
     * or lean too much to be sure.
     */
    fun straightening(pixels: IntArray, width: Int, height: Int): Double? {
        val angle = lineAngle(pixels, width, height) ?: return null
        if (abs(angle) < MINIMUM_TILT || abs(angle) > MAXIMUM_TILT) return null
        return -angle
    }

    /**
     * The size of the part of a width × height photo that is left, centred,
     * once it is turned by [degrees] and the empty corners are cut off (as on Apple).
     */
    fun upright(width: Int, height: Int, degrees: Double): Pair<Int, Int> {
        val slant = abs(sin(Math.toRadians(degrees)))
        return Pair((width - height * slant).toInt().coerceAtLeast(0), (height - width * slant).toInt().coerceAtLeast(0))
    }
}
