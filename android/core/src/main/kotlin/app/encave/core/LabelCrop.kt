package app.encave.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frames a label photo on its label for the studio look, on the device and
 * without any model: printed text makes many sharp edges, so the label is the
 * band of the photo where edges are densest, looked for in the central column
 * (where the bottle stands). The band is widened a little (15% of its height
 * above, for the shoulder, 5% below) and shaped like the list pictures.
 *
 * A photo whose edges are spread evenly (a close-up of the label, text everywhere)
 * is left whole; a photo with almost no edges gets the centre band where labels
 * usually sit. Pixels are ARGB ints.
 */
object LabelCrop {
    /** A part of the photo, in pixels; right and bottom excluded. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Width over height of the result: about the list pictures (34 × 46 dp), so the label fills them. */
    const val ASPECT = 0.72f

    /** The centre band used when the photo has almost no edges, from the top. */
    const val FALLBACK_TOP = 0.35f
    const val FALLBACK_BOTTOM = 0.85f

    /** The photo is analysed at about this many cells on its longest side. */
    private const val GRID = 160
    /** A difference of light between neighbouring cells that counts as an edge (0–255). */
    private const val EDGE = 28
    /** The densest band must be this much denser than the average to be a label. */
    private const val CONTRAST = 1.5f

    fun find(pixels: IntArray, width: Int, height: Int): Box? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val step = max(1, max(width, height) / GRID)
        val w = width / step
        val h = height / step
        if (w < 4 || h < 4) return null
        val light = grayGrid(pixels, width, step, w, h)
        val edges = BooleanArray(w * h)
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val here = light[y * w + x]
            edges[y * w + x] = abs(light[y * w + x + 1] - here) + abs(light[(y + 1) * w + x] - here) > EDGE
        }

        val columnLeft = (w * 0.2f).toInt()
        val columnRight = max(columnLeft + 1, (w * 0.8f).toInt())
        val rows = FloatArray(h) { y ->
            var count = 0
            for (x in columnLeft until columnRight) if (edges[y * w + x]) count++
            count.toFloat() / (columnRight - columnLeft)
        }
        val mean = rows.average().toFloat()
        if (mean < 0.01f) return fit(centreBand(w, h), w, h)?.scaled(step, width, height)

        // The densest window of rows, about the height of a label on a whole bottle.
        val window = max(3, (h * 0.22f).roundToInt())
        var sum = 0f
        for (y in 0 until window) sum += rows[y]
        var best = sum
        var bestTop = 0
        for (y in window until h) {
            sum += rows[y] - rows[y - window]
            if (sum > best) { best = sum; bestTop = y - window + 1 }
        }
        val density = best / window
        if (density < mean * CONTRAST) return null

        // Rows next to it that are still busy belong to the label too (its border, small print).
        var top = bestTop
        var bottom = bestTop + window
        val maxRows = (h * 0.45f).toInt()
        while (bottom - top < maxRows && top > 0 && rows[top - 1] >= density * 0.5f) top--
        while (bottom - top < maxRows && bottom < h && rows[bottom] >= density * 0.5f) bottom++
        val band = bottom - top
        top = max(0, top - (band * 0.15f).roundToInt())
        bottom = min(h, bottom + (band * 0.05f).roundToInt())

        // Across: the columns where the band is busy, and a little more for the bottle's sides.
        val columns = FloatArray(w) { x ->
            var count = 0
            for (y in top until bottom) if (edges[y * w + x]) count++
            count.toFloat()
        }
        val threshold = (columns.maxOrNull() ?: 0f) * 0.35f
        var left = columns.indexOfFirst { it >= threshold && it > 0f }.coerceAtLeast(0)
        var right = columns.indexOfLast { it >= threshold && it > 0f } + 1
        if (right <= left) { left = 0; right = w }
        val margin = (w * 0.08f).roundToInt()
        left = max(0, left - margin)
        right = min(w, right + margin)
        return fit(Box(left, top, right, bottom), w, h)?.scaled(step, width, height)
    }

    /** The centre band where labels sit, as wide as the bottle usually is. */
    private fun centreBand(w: Int, h: Int): Box {
        val top = (h * FALLBACK_TOP).toInt()
        val bottom = (h * FALLBACK_BOTTOM).toInt()
        val half = ((bottom - top) * ASPECT / 2).roundToInt()
        return Box(max(0, w / 2 - half), top, min(w, w / 2 + half), bottom)
    }

    /**
     * Grows the box to [ASPECT] (more above than below, for the shoulder), kept
     * inside the photo. Null when it is nearly the whole photo anyway.
     */
    internal fun fit(box: Box, w: Int, h: Int): Box? {
        var (left, top, right, bottom) = box
        val aspect = (right - left).toFloat() / max(1, bottom - top)
        if (aspect > ASPECT) {
            val extra = ((right - left) / ASPECT).roundToInt() - (bottom - top)
            top -= (extra * 0.6f).roundToInt()
            bottom += extra - (extra * 0.6f).roundToInt()
        } else {
            val extra = ((bottom - top) * ASPECT).roundToInt() - (right - left)
            left -= extra / 2
            right += extra - extra / 2
        }
        if (top < 0) { bottom -= top; top = 0 }
        if (bottom > h) { top = max(0, top - (bottom - h)); bottom = h }
        if (left < 0) { right -= left; left = 0 }
        if (right > w) { left = max(0, left - (right - w)); right = w }
        val result = Box(left, top, right, bottom)
        return if (result.width.toLong() * result.height > 0.8 * w * h) null else result
    }

    private fun Box.scaled(step: Int, width: Int, height: Int) =
        Box(left * step, top * step, min(width, right * step), min(height, bottom * step))

    /** The average light of each cell of step × step pixels. */
    private fun grayGrid(pixels: IntArray, width: Int, step: Int, w: Int, h: Int): IntArray {
        val sums = IntArray(w * h)
        for (y in 0 until h * step) {
            val row = (y / step) * w
            val offset = y * width
            for (x in 0 until w * step) {
                val pixel = pixels[offset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sums[row + x / step] += (r * 77 + g * 150 + b * 29) shr 8
            }
        }
        val cell = step * step
        return IntArray(w * h) { sums[it] / cell }
    }
}
