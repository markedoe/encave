package app.encave.core

/**
 * Evens out the light of a photo for the studio look: each color channel is
 * stretched between its 1st and 99th percentile, with the gain capped so a dim
 * photo is not blown out. Pixels are ARGB ints; alpha is kept.
 */
object AutoLevels {
    fun apply(pixels: IntArray, maxGain: Float = 1.6f): IntArray {
        if (pixels.isEmpty()) return pixels
        val histograms = Array(3) { IntArray(256) }
        for (pixel in pixels) {
            histograms[0][(pixel shr 16) and 0xFF]++
            histograms[1][(pixel shr 8) and 0xFF]++
            histograms[2][pixel and 0xFF]++
        }
        val cut = pixels.size / 100
        val tables = histograms.map { table(it, cut, maxGain) }
        return IntArray(pixels.size) { i ->
            val pixel = pixels[i]
            val r = tables[0][(pixel shr 16) and 0xFF]
            val g = tables[1][(pixel shr 8) and 0xFF]
            val b = tables[2][pixel and 0xFF]
            (pixel and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun table(histogram: IntArray, cut: Int, maxGain: Float): IntArray {
        var low = 0
        var count = 0
        while (low < 255 && count + histogram[low] <= cut) { count += histogram[low]; low++ }
        var high = 255
        count = 0
        while (high > low && count + histogram[high] <= cut) { count += histogram[high]; high-- }
        val range = (high - low).coerceAtLeast(1)
        val gain = (255f / range).coerceAtMost(maxGain)
        // With a capped gain, the stretched range stays centred where it was.
        val offset = if (gain * range >= 255f) 0f else (low + high) / 2f - range * gain / 2f
        // Rounded, not truncated: truncating darkens every channel by up to one level.
        return IntArray(256) { value -> Math.round((value - low) * gain + offset).coerceIn(0, 255) }
    }
}
