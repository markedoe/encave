import Foundation

/// Finds a label on a bottle from its paper, not its text, so a frame built on it
/// keeps script, logos, drawings and blurry lines that cannot be read. The paper's
/// colour is taken from the lightest pixels in the middle of the photo (where the
/// bottle stands, or around the text when its row is given); pixels of that
/// colour, lighter or in shadow, are paper; the label is the block of rows and then
/// columns where most pixels are paper. Nil when no clear, light block of paper is
/// found (no label, a dark label, a white wall).
///
/// The Kotlin twin (LabelPaper.kt) does the same; both are checked with
/// `fixtures/label-paper-vectors.json`. Pixels are ARGB.
public enum LabelPaper {
    /// The label, as fractions of the photo from its top left (right and bottom
    /// excluded), and the share of paper inside it.
    public struct Box: Equatable, Sendable {
        public var left: Double, top: Double, right: Double, bottom: Double
        public var confidence: Double
        public var width: Double { right - left }
        public var height: Double { bottom - top }
    }

    /// The photo is analysed at most this many cells on its longest side.
    static let grid = 320
    static let minimumConfidence = 0.6
    static let minimumHeight = 0.15
    static let minimumWidth = 0.25
    /// A box over more than this on both sides is a wall, not a label.
    static let wall = 0.9
    /// Paper darker than this is glass or a shadow, not a label.
    static let minimumPaperLight = 0.3
    /// Rows and columns with at least this share of paper belong to the label.
    static let threshold = 0.35

    /// The label in a `width` × `height` photo. `seedRow` (a fraction from the top)
    /// is the middle of the text read on it, when any.
    public static func find(pixels: [UInt32], width: Int, height: Int, seedRow: Double? = nil) -> Box? {
        guard width >= 8, height >= 8, pixels.count >= width * height else { return nil }
        let step = max(1, (max(width, height) + grid - 1) / grid)
        let w = width / step, h = height / step
        guard w >= 8, h >= 8 else { return nil }
        // The average colour of each cell, 0 to 255 per channel.
        let count = w * h, cell = UInt32(step * step)
        var red = [UInt32](repeating: 0, count: count), green = red, blue = red
        for y in 0..<(h * step) {
            let row = (y / step) * w, offset = y * width
            for x in 0..<(w * step) {
                let pixel = pixels[offset + x], i = row + x / step
                red[i] += (pixel >> 16) & 0xFF
                green[i] += (pixel >> 8) & 0xFF
                blue[i] += pixel & 0xFF
            }
        }
        for i in 0..<count {
            red[i] /= cell
            green[i] /= cell
            blue[i] /= cell
        }
        func light(_ i: Int) -> Int {
            Int(max(red[i], green[i], blue[i]) + min(red[i], green[i], blue[i]))
        }

        // The paper's colour: the median of the lightest 40% of the middle.
        let x0 = Int(Double(w) * 0.3), x1 = max(x0 + 1, Int(Double(w) * 0.7))
        let seed = seedRow.map { min(h - 1, max(0, Int($0 * Double(h)))) }
        let y0: Int, y1: Int
        if let seed {
            y0 = max(0, seed - h / 10)
            y1 = min(h, seed + h / 10 + 1)
        } else {
            y0 = Int(Double(h) * 0.3)
            y1 = max(y0 + 1, Int(Double(h) * 0.7))
        }
        var lights = [Int](repeating: 0, count: 511)
        for y in y0..<y1 { for x in x0..<x1 { lights[light(y * w + x)] += 1 } }
        let cut = rank(lights, (y1 - y0) * (x1 - x0) * 6 / 10)
        var reds = [Int](repeating: 0, count: 256), greens = reds, blues = reds
        var kept = 0
        for y in y0..<y1 {
            for x in x0..<x1 {
                let i = y * w + x
                guard light(i) >= cut else { continue }
                reds[Int(red[i])] += 1
                greens[Int(green[i])] += 1
                blues[Int(blue[i])] += 1
                kept += 1
            }
        }
        let pr = rank(reds, kept / 2), pg = rank(greens, kept / 2), pb = rank(blues, kept / 2)
        let pMax = max(pr, pg, pb), pMin = min(pr, pg, pb), pSum = pr + pg + pb
        let paperLight = Double(pMax + pMin) / 510
        guard pSum > 0, paperLight >= minimumPaperLight else { return nil }
        let paperSaturation = pMax > 0 ? Double(pMax - pMin) / Double(pMax) : 0
        let cr = Double(pr) / Double(pSum), cg = Double(pg) / Double(pSum), cb = Double(pb) / Double(pSum)

        // Paper: as light as half the paper or more, no more saturated, and of its
        // colour (a shadow keeps the colour of the paper).
        var paper = [Bool](repeating: false, count: count)
        for i in 0..<count {
            let r = Int(red[i]), g = Int(green[i]), b = Int(blue[i])
            let high = max(r, g, b), low = min(r, g, b), sum = r + g + b
            guard sum > 0 else { continue }
            let l = Double(high + low) / 510, s = Double(high - low) / Double(high)
            let dr = Double(r) / Double(sum) - cr, dg = Double(g) / Double(sum) - cg, db = Double(b) / Double(sum) - cb
            paper[i] = l > 0.5 * paperLight && s < paperSaturation + 0.15 && dr * dr + dg * dg + db * db < 0.05 * 0.05
        }

        // The rows, then the columns of those rows, where most of it is paper.
        var rows = [Double](repeating: 0, count: h)
        for y in 0..<h {
            var n = 0
            for x in x0..<x1 where paper[y * w + x] { n += 1 }
            rows[y] = Double(n) / Double(x1 - x0)
        }
        let (top, bottom) = grow(rows, from: seed ?? argmax(smoothed(rows, radius: 4)), gap: max(2, h / 12))
        var columns = [Double](repeating: 0, count: w)
        for x in 0..<w {
            var n = 0
            for y in top...bottom where paper[y * w + x] { n += 1 }
            columns[x] = Double(n) / Double(bottom - top + 1)
        }
        let middle = columns[w / 2] >= threshold ? w / 2 : argmax(smoothed(columns, radius: 2))
        let (left, right) = grow(columns, from: middle, gap: max(2, w / 25))
        var inside = 0
        for y in top...bottom { for x in left...right where paper[y * w + x] { inside += 1 } }
        let box = Box(left: Double(left) / Double(w), top: Double(top) / Double(h),
                      right: Double(right + 1) / Double(w), bottom: Double(bottom + 1) / Double(h),
                      confidence: Double(inside) / Double((bottom - top + 1) * (right - left + 1)))
        guard box.confidence >= minimumConfidence, box.height >= minimumHeight, box.width >= minimumWidth,
              !(box.width > wall && box.height > wall) else { return nil }
        return box
    }

    /// The value at `rank` (from 0) of the values counted in `histogram`.
    private static func rank(_ histogram: [Int], _ rank: Int) -> Int {
        var total = 0
        for (value, n) in histogram.enumerated() {
            total += n
            if total > rank { return value }
        }
        return histogram.count - 1
    }

    /// The first largest value.
    private static func argmax(_ values: [Double]) -> Int {
        var best = 0
        for i in values.indices where values[i] > values[best] { best = i }
        return best
    }

    /// The mean over `radius` values on each side (fewer at the ends).
    private static func smoothed(_ values: [Double], radius: Int) -> [Double] {
        var result = [Double](repeating: 0, count: values.count)
        for i in values.indices {
            let from = max(0, i - radius), to = min(values.count - 1, i + radius)
            var sum = 0.0
            for j in from...to { sum += values[j] }
            result[i] = sum / Double(to - from + 1)
        }
        return result
    }

    /// From `start` both ways while the values reach the threshold, over up to `gap`
    /// values below it in a row: the first and last values that reach it (`start`
    /// itself when none does).
    private static func grow(_ values: [Double], from start: Int, gap: Int) -> (Int, Int) {
        var low = start, high = start
        var misses = 0, i = start - 1
        while i >= 0 {
            if values[i] >= threshold {
                low = i
                misses = 0
            } else {
                misses += 1
                if misses > gap { break }
            }
            i -= 1
        }
        misses = 0
        i = start + 1
        while i < values.count {
            if values[i] >= threshold {
                high = i
                misses = 0
            } else {
                misses += 1
                if misses > gap { break }
            }
            i += 1
        }
        return (low, high)
    }
}
