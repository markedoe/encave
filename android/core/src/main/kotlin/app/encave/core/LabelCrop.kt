package app.encave.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frames a label photo on its label for the studio look, on the device and
 * without any model: printed text makes many sharp edges, so the label is the
 * band of the photo where edges are densest, looked for in the central column
 * (where the bottle stands). The band gets 15% of its height above and 10% below
 * (the label about 80% of the result), and is as wide as the photo allows while
 * centred on the label, so it fills any picture with the label in the middle.
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

    /** Above and below the label, as a share of its height (the same as Apple's LabelFraming). */
    const val ABOVE = 0.15f
    const val BELOW = 0.10f

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
        val band = textBand(pixels, width, height, fallback = true) ?: return null
        return frame(band.top, band.bottom, band.centre, band.w, band.h)?.scaled(band.step, width, height)
    }

    /**
     * The part of the photo to show in the studio look, in this order: the label's
     * paper (see [LabelPaper], looked for around the text band when there is one)
     * with the text band, the paper keeping [PAPER_MARGIN] on each side; else the
     * text band as [find] gives it; else null, the whole photo, never the centre
     * band where labels usually sit. The same order as Apple's StudioPhoto.
     */
    fun studioFrame(pixels: IntArray, width: Int, height: Int): Box? {
        val text = textBand(pixels, width, height, fallback = false)
        val paper = LabelPaper.find(pixels, width, height, text?.let { (it.top + it.bottom) / 2.0 / it.h })
            ?: return text?.let { frame(it.top, it.bottom, it.centre, it.w, it.h)?.scaled(it.step, width, height) }
        val rows = paperFrame(text?.let { it.top.toDouble() / it.h..it.bottom.toDouble() / it.h }, paper.top..paper.bottom)
            ?: return null
        val top = (rows.start * height).toInt()
        val bottom = min(height, kotlin.math.ceil(rows.endInclusive * height).toInt())
        val centre = (paper.left + paper.right) / 2 * width
        val half = max(1, min(centre, width - centre).roundToInt())
        val middle = centre.roundToInt()
        return Box(max(0, middle - half), top, min(width, middle + half), bottom)
    }

    /** Kept around a label found from its paper, as a share of the photo (the same as Apple's LabelFraming). */
    const val PAPER_MARGIN = 0.06

    /**
     * The rows to keep (fractions from the top) for a label at [text] (from its
     * text, if any) and [paper]: 15% above and 10% below both together (text away
     * from the paper is not on this label and is left out), widened so the paper
     * keeps [PAPER_MARGIN] on each side. Null when that is nearly the whole photo.
     * The same as Apple's LabelFraming.frame(text:paper:).
     */
    fun paperFrame(text: ClosedFloatingPointRange<Double>?, paper: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double>? {
        val onLabel = text?.takeIf { it.start < paper.endInclusive && it.endInclusive > paper.start }
        val from = if (onLabel == null) paper.start else min(onLabel.start, paper.start)
        val to = if (onLabel == null) paper.endInclusive else max(onLabel.endInclusive, paper.endInclusive)
        val band = to - from
        val top = max(0.0, min(from - band * ABOVE, paper.start - PAPER_MARGIN))
        val bottom = min(1.0, max(to + band * BELOW, paper.endInclusive + PAPER_MARGIN))
        return if (bottom - top > 0.9) null else top..bottom
    }

    /** The label's rows and centre on the analysis grid of [step] × [step] cells. */
    private class Band(val top: Int, val bottom: Int, val centre: Float, val w: Int, val h: Int, val step: Int)

    /**
     * The band where edges are densest; with almost no edges, the centre band when
     * [fallback], else null.
     */
    private fun textBand(pixels: IntArray, width: Int, height: Int, fallback: Boolean): Band? {
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
        if (mean < 0.01f) return if (fallback) Band((h * FALLBACK_TOP).toInt(), (h * FALLBACK_BOTTOM).toInt(), w / 2f, w, h, step) else null

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

        // Across: the centre of the columns where the band is busy.
        val columns = FloatArray(w) { x ->
            var count = 0
            for (y in top until bottom) if (edges[y * w + x]) count++
            count.toFloat()
        }
        val threshold = (columns.maxOrNull() ?: 0f) * 0.35f
        val left = columns.indexOfFirst { it >= threshold && it > 0f }
        val right = columns.indexOfLast { it >= threshold && it > 0f } + 1
        val centre = if (right > left && left >= 0) (left + right) / 2f else w / 2f
        return Band(top, bottom, centre, w, h, step)
    }

    /**
     * The label rows [top, bottom) with 15% above and 10% below, as wide as the
     * photo allows while centred on [centre]. Null when that is nearly the whole
     * height anyway.
     */
    internal fun frame(top: Int, bottom: Int, centre: Float, w: Int, h: Int): Box? {
        val band = bottom - top
        val from = max(0, top - (band * ABOVE).roundToInt())
        val to = min(h, bottom + (band * BELOW).roundToInt())
        if (to - from > 0.9f * h) return null
        val half = min(centre, w - centre).roundToInt().coerceAtLeast(1)
        val middle = centre.roundToInt()
        return Box(max(0, middle - half), from, min(w, middle + half), to)
    }

    private fun Box.scaled(step: Int, width: Int, height: Int) =
        Box(left * step, top * step, min(width, right * step), min(height, bottom * step))

    /** The average light of each cell of step × step pixels. */
    internal fun grayGrid(pixels: IntArray, width: Int, step: Int, w: Int, h: Int): IntArray {
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
