import Foundation

/// Finds the label in a label photo without any model: printed text makes many
/// sharp edges, so the label is the band of the photo where edges are densest,
/// looked for in the central column (where the bottle stands). The band gets 15%
/// of its height above and 10% below (the label about 80% of the result), and is
/// as wide as the photo allows while centred on the label.
///
/// A photo whose edges are spread evenly (a close-up of the label) gives nil; a
/// photo with almost no edges gets the centre band where labels usually sit.
/// The Kotlin twin (LabelCrop.kt) is what Android shows; on Apple it frames a
/// photo when Vision finds neither a cut-out nor text. Pixels are ARGB.
public enum LabelCrop {
    /// A part of the photo, in pixels from the top left; right and bottom excluded.
    public struct Box: Equatable, Sendable {
        public var left: Int, top: Int, right: Int, bottom: Int
        public var width: Int { right - left }
        public var height: Int { bottom - top }
    }

    static let fallbackTop: Float = 0.35
    static let fallbackBottom: Float = 0.85
    private static let grid = 160
    private static let edge = 28
    private static let contrast: Float = 1.5

    public static func find(pixels: [UInt32], width: Int, height: Int) -> Box? {
        guard width >= 8, height >= 8, pixels.count >= width * height else { return nil }
        let step = max(1, max(width, height) / grid)
        let w = width / step, h = height / step
        guard w >= 4, h >= 4 else { return nil }
        let light = grayGrid(pixels, width: width, step: step, w: w, h: h)
        var edges = [Bool](repeating: false, count: w * h)
        for y in 0..<(h - 1) {
            for x in 0..<(w - 1) {
                let here = light[y * w + x]
                edges[y * w + x] = abs(light[y * w + x + 1] - here) + abs(light[(y + 1) * w + x] - here) > edge
            }
        }
        let columnLeft = Int(Float(w) * 0.2)
        let columnRight = max(columnLeft + 1, Int(Float(w) * 0.8))
        let rows: [Float] = (0..<h).map { y in
            var count = 0
            for x in columnLeft..<columnRight where edges[y * w + x] { count += 1 }
            return Float(count) / Float(columnRight - columnLeft)
        }
        let mean = rows.reduce(0, +) / Float(h)
        if mean < 0.01 {
            return frame(top: Int(Float(h) * fallbackTop), bottom: Int(Float(h) * fallbackBottom), centre: Float(w) / 2,
                         w: w, h: h)?.scaled(step, width, height)
        }
        // The densest window of rows, about the height of a label on a whole bottle.
        let window = max(3, Int((Float(h) * 0.22).rounded()))
        var sum: Float = 0
        for y in 0..<window { sum += rows[y] }
        var best = sum, bestTop = 0
        for y in window..<h {
            sum += rows[y] - rows[y - window]
            if sum > best { best = sum; bestTop = y - window + 1 }
        }
        let density = best / Float(window)
        guard density >= mean * contrast else { return nil }
        // Rows next to it that are still busy belong to the label too.
        var top = bestTop, bottom = bestTop + window
        let maxRows = Int(Float(h) * 0.45)
        while bottom - top < maxRows, top > 0, rows[top - 1] >= density * 0.5 { top -= 1 }
        while bottom - top < maxRows, bottom < h, rows[bottom] >= density * 0.5 { bottom += 1 }
        // Across: the centre of the columns where the band is busy.
        let columns: [Float] = (0..<w).map { x in
            var count = 0
            for y in top..<bottom where edges[y * w + x] { count += 1 }
            return Float(count)
        }
        let threshold = (columns.max() ?? 0) * 0.35
        let left = columns.firstIndex { $0 >= threshold && $0 > 0 } ?? -1
        let right = (columns.lastIndex { $0 >= threshold && $0 > 0 } ?? -1) + 1
        let centre = right > left && left >= 0 ? Float(left + right) / 2 : Float(w) / 2
        return frame(top: top, bottom: bottom, centre: centre, w: w, h: h)?.scaled(step, width, height)
    }

    /// The label rows with 15% above and 10% below, as wide as the photo allows
    /// while centred on `centre`. Nil when that is nearly the whole height anyway.
    static func frame(top: Int, bottom: Int, centre: Float, w: Int, h: Int) -> Box? {
        let band = Float(bottom - top)
        let from = max(0, top - Int((band * Float(LabelFraming.above)).rounded()))
        let to = min(h, bottom + Int((band * Float(LabelFraming.below)).rounded()))
        guard Float(to - from) <= 0.9 * Float(h) else { return nil }
        let half = max(1, Int(min(centre, Float(w) - centre).rounded()))
        let middle = Int(centre.rounded())
        return Box(left: max(0, middle - half), top: from, right: min(w, middle + half), bottom: to)
    }

    private static func grayGrid(_ pixels: [UInt32], width: Int, step: Int, w: Int, h: Int) -> [Int] {
        var sums = [Int](repeating: 0, count: w * h)
        for y in 0..<(h * step) {
            let row = (y / step) * w, offset = y * width
            for x in 0..<(w * step) {
                let pixel = pixels[offset + x]
                let r = Int((pixel >> 16) & 0xFF), g = Int((pixel >> 8) & 0xFF), b = Int(pixel & 0xFF)
                sums[row + x / step] += (r * 77 + g * 150 + b * 29) >> 8
            }
        }
        let cell = step * step
        return sums.map { $0 / cell }
    }
}

private extension LabelCrop.Box {
    func scaled(_ step: Int, _ width: Int, _ height: Int) -> LabelCrop.Box {
        LabelCrop.Box(left: left * step, top: top * step, right: min(width, right * step), bottom: min(height, bottom * step))
    }
}
