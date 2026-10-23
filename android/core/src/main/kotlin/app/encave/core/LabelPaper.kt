package app.encave.core

import kotlin.math.max
import kotlin.math.min

/**
 * Finds a label on a bottle from its paper, not its text, so a frame built on it
 * keeps script, logos, drawings and blurry lines that cannot be read. The paper's
 * colour is taken from the lightest pixels in the middle of the photo (where the
 * bottle stands, or around the text when its row is given); pixels of that
 * colour, lighter or in shadow, are paper; the label is the block of rows and then
 * columns where most pixels are paper. Null when no clear, light block of paper is
 * found (no label, a dark label, a white wall).
 *
 * The Swift twin (CaveKit's LabelPaper.swift) does the same; both are checked with
 * `fixtures/label-paper-vectors.json`. Pixels are ARGB ints.
 */
object LabelPaper {
    /** The label, as fractions of the photo from its top left (right and bottom excluded), and the share of paper inside it. */
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double, val confidence: Double) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
    }

    /** The photo is analysed at most this many cells on its longest side. */
    private const val GRID = 320
    private const val MINIMUM_CONFIDENCE = 0.6
    private const val MINIMUM_HEIGHT = 0.15
    private const val MINIMUM_WIDTH = 0.25
    /** A box over more than this on both sides is a wall, not a label. */
    private const val WALL = 0.9
    /** Paper darker than this is glass or a shadow, not a label. */
    private const val MINIMUM_PAPER_LIGHT = 0.3
    /** Rows and columns with at least this share of paper belong to the label. */
    private const val THRESHOLD = 0.35

    /** The label in a [width] × [height] photo. [seedRow] (a fraction from the top) is the middle of the text read on it, when any. */
    fun find(pixels: IntArray, width: Int, height: Int, seedRow: Double? = null): Box? {
        if (width < 8 || height < 8 || pixels.size < width * height) return null
        val step = max(1, (max(width, height) + GRID - 1) / GRID)
        val w = width / step
        val h = height / step
        if (w < 8 || h < 8) return null
        // The average colour of each cell, 0 to 255 per channel.
        val count = w * h
        val cell = step * step
        val red = IntArray(count)
        val green = IntArray(count)
        val blue = IntArray(count)
        for (y in 0 until h * step) {
            val row = (y / step) * w
            val offset = y * width
            for (x in 0 until w * step) {
                val pixel = pixels[offset + x]
                val i = row + x / step
                red[i] += (pixel shr 16) and 0xFF
                green[i] += (pixel shr 8) and 0xFF
                blue[i] += pixel and 0xFF
            }
        }
        for (i in 0 until count) {
            red[i] /= cell
            green[i] /= cell
            blue[i] /= cell
        }
        fun light(i: Int) = maxOf(red[i], green[i], blue[i]) + minOf(red[i], green[i], blue[i])

        // The paper's colour: the median of the lightest 40% of the middle.
        val x0 = (w * 0.3).toInt()
        val x1 = max(x0 + 1, (w * 0.7).toInt())
        val seed = seedRow?.let { min(h - 1, max(0, (it * h).toInt())) }
        val y0: Int
        val y1: Int
        if (seed != null) {
            y0 = max(0, seed - h / 10)
            y1 = min(h, seed + h / 10 + 1)
        } else {
            y0 = (h * 0.3).toInt()
            y1 = max(y0 + 1, (h * 0.7).toInt())
        }
        val lights = IntArray(511)
        for (y in y0 until y1) for (x in x0 until x1) lights[light(y * w + x)]++
        val cut = rank(lights, (y1 - y0) * (x1 - x0) * 6 / 10)
        val reds = IntArray(256)
        val greens = IntArray(256)
        val blues = IntArray(256)
        var kept = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (light(i) < cut) continue
            reds[red[i]]++
            greens[green[i]]++
            blues[blue[i]]++
            kept++
        }
        val pr = rank(reds, kept / 2)
        val pg = rank(greens, kept / 2)
        val pb = rank(blues, kept / 2)
        val pMax = maxOf(pr, pg, pb)
        val pMin = minOf(pr, pg, pb)
        val pSum = pr + pg + pb
        val paperLight = (pMax + pMin).toDouble() / 510
        if (pSum <= 0 || paperLight < MINIMUM_PAPER_LIGHT) return null
        val paperSaturation = if (pMax > 0) (pMax - pMin).toDouble() / pMax else 0.0
        val cr = pr.toDouble() / pSum
        val cg = pg.toDouble() / pSum
        val cb = pb.toDouble() / pSum

        // Paper: as light as half the paper or more, no more saturated, and of its
        // colour (a shadow keeps the colour of the paper).
        val paper = BooleanArray(count)
        for (i in 0 until count) {
            val r = red[i]
            val g = green[i]
            val b = blue[i]
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            val sum = r + g + b
            if (sum <= 0) continue
            val l = (high + low).toDouble() / 510
            val s = (high - low).toDouble() / high
            val dr = r.toDouble() / sum - cr
            val dg = g.toDouble() / sum - cg
            val db = b.toDouble() / sum - cb
            paper[i] = l > 0.5 * paperLight && s < paperSaturation + 0.15 && dr * dr + dg * dg + db * db < 0.05 * 0.05
        }

        // The rows, then the columns of those rows, where most of it is paper.
        val rows = DoubleArray(h) { y ->
            var n = 0
            for (x in x0 until x1) if (paper[y * w + x]) n++
            n.toDouble() / (x1 - x0)
        }
        val (top, bottom) = grow(rows, seed ?: argmax(smoothed(rows, 4)), max(2, h / 12))
        val columns = DoubleArray(w) { x ->
            var n = 0
            for (y in top..bottom) if (paper[y * w + x]) n++
            n.toDouble() / (bottom - top + 1)
        }
        val middle = if (columns[w / 2] >= THRESHOLD) w / 2 else argmax(smoothed(columns, 2))
        val (left, right) = grow(columns, middle, max(2, w / 25))
        var inside = 0
        for (y in top..bottom) for (x in left..right) if (paper[y * w + x]) inside++
        val box = Box(
            left.toDouble() / w, top.toDouble() / h, (right + 1).toDouble() / w, (bottom + 1).toDouble() / h,
            inside.toDouble() / ((bottom - top + 1) * (right - left + 1)),
        )
        if (box.confidence < MINIMUM_CONFIDENCE || box.height < MINIMUM_HEIGHT || box.width < MINIMUM_WIDTH) return null
        if (box.width > WALL && box.height > WALL) return null
        return box
    }

    /** The value at [rank] (from 0) of the values counted in [histogram]. */
    private fun rank(histogram: IntArray, rank: Int): Int {
        var total = 0
        for (value in histogram.indices) {
            total += histogram[value]
            if (total > rank) return value
        }
        return histogram.size - 1
    }

    /** The first largest value. */
    private fun argmax(values: DoubleArray): Int {
        var best = 0
        for (i in values.indices) if (values[i] > values[best]) best = i
        return best
    }

    /** The mean over [radius] values on each side (fewer at the ends). */
    private fun smoothed(values: DoubleArray, radius: Int) = DoubleArray(values.size) { i ->
        val from = max(0, i - radius)
        val to = min(values.size - 1, i + radius)
        var sum = 0.0
        for (j in from..to) sum += values[j]
        sum / (to - from + 1)
    }

    /**
     * From [start] both ways while the values reach the threshold, over up to [gap]
     * values below it in a row: the first and last values that reach it ([start]
     * itself when none does).
     */
    private fun grow(values: DoubleArray, start: Int, gap: Int): Pair<Int, Int> {
        var low = start
        var high = start
        var misses = 0
        var i = start - 1
        while (i >= 0) {
            if (values[i] >= THRESHOLD) { low = i; misses = 0 } else if (++misses > gap) break
            i--
        }
        misses = 0
        i = start + 1
        while (i < values.size) {
            if (values[i] >= THRESHOLD) { high = i; misses = 0 } else if (++misses > gap) break
            i++
        }
        return Pair(low, high)
    }
}
