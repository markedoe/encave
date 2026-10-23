import Foundation
import Testing
@testable import CaveKit

/// Labels found from their paper on synthetic photos from shared vectors. The Kotlin
/// core (LabelPaperTest.kt) draws and checks the same file,
/// `fixtures/label-paper-vectors.json`, so the two platforms cannot drift.
@Suite struct LabelPaperTests {
    struct Vectors: Decodable {
        var tolerance: Double
        var cases: [Case]
    }

    struct Shape: Decodable {
        var rect: [Double]
        var color: [Int]
    }

    struct Shadow: Decodable {
        var from: Double
        var darkness: Double
    }

    struct Label: Decodable {
        var rect: [Double]
        var color: [Int]
        var shadow: Shadow?
        var softEdge: Int?
    }

    struct Hand: Decodable {
        var centre: [Double]
        var radius: [Double]
        var color: [Int]
    }

    struct Case: Decodable {
        var name: String
        var width: Int
        var height: Int
        var seed: Double?
        var background: [Int]
        var bottle: Shape?
        var label: Label?
        var picture: Shape?
        var text: [[Double]]?
        var textColor: [Int]?
        var hand: Hand?
        var expected: [Double]?
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/label-paper-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    /// The case's photo, drawn as the vectors file describes.
    static func draw(_ item: Case) -> [UInt32] {
        let w = item.width, h = item.height
        var image = [[Int]](repeating: item.background, count: w * h)
        func pixels(_ rect: [Double]) -> (Int, Int, Int, Int) {
            (Int(rect[0] * Double(w)), Int(rect[1] * Double(h)), Int(rect[2] * Double(w)), Int(rect[3] * Double(h)))
        }
        func fill(_ rect: [Double], _ color: [Int]) {
            let (l, t, r, b) = pixels(rect)
            for y in t..<b { for x in l..<r { image[y * w + x] = color } }
        }
        if let bottle = item.bottle { fill(bottle.rect, bottle.color) }
        if let label = item.label {
            let (l, t, r, b) = pixels(label.rect)
            let soft = label.softEdge ?? 0
            for y in t..<b {
                var factor = 1.0
                if let shadow = label.shadow {
                    let from = t + Int(shadow.from * Double(b - t))
                    if y >= from { factor = 1 - shadow.darkness * Double(y - from) / Double(max(1, b - 1 - from)) }
                }
                for x in l..<r {
                    var color = label.color.map { Int(Double($0) * factor) }
                    let d = min(x - l, r - 1 - x, y - t, b - 1 - y)
                    if soft > 0, d < soft {
                        let a = Double(d + 1) / Double(soft + 1), under = image[y * w + x]
                        color = (0..<3).map { Int(Double(under[$0]) + Double(color[$0] - under[$0]) * a) }
                    }
                    image[y * w + x] = color
                }
            }
        }
        if let picture = item.picture { fill(picture.rect, picture.color) }
        for bar in item.text ?? [] { fill(bar, item.textColor ?? [0, 0, 0]) }
        if let hand = item.hand {
            let cx = hand.centre[0] * Double(w), cy = hand.centre[1] * Double(h)
            let rx = hand.radius[0] * Double(w), ry = hand.radius[1] * Double(h)
            for y in 0..<h {
                for x in 0..<w {
                    let dx = (Double(x) + 0.5 - cx) / rx, dy = (Double(y) + 0.5 - cy) / ry
                    if dx * dx + dy * dy <= 1 { image[y * w + x] = hand.color }
                }
            }
        }
        return image.map { 0xFF00_0000 | UInt32($0[0]) << 16 | UInt32($0[1]) << 8 | UInt32($0[2]) }
    }

    @Test func vectorsFindTheWholeLabel() throws {
        let vectors = try Self.vectors()
        #expect(vectors.cases.count >= 8)
        for item in vectors.cases {
            let box = LabelPaper.find(pixels: Self.draw(item), width: item.width, height: item.height, seedRow: item.seed)
            guard let expected = item.expected else {
                #expect(box == nil, "\(item.name)")
                continue
            }
            let found = try #require(box, "\(item.name)")
            let sides = [found.left, found.top, found.right, found.bottom]
            for (side, want) in zip(sides, expected) {
                #expect(abs(side - want) <= vectors.tolerance, "\(item.name): \(sides) instead of \(expected)")
            }
            #expect(found.confidence >= 0.6, "\(item.name)")
        }
    }

    @Test func tinyOrMissingPixelsAreIgnored() {
        #expect(LabelPaper.find(pixels: [UInt32](repeating: 0, count: 4), width: 2, height: 2) == nil)
        #expect(LabelPaper.find(pixels: [UInt32](repeating: 0, count: 10), width: 100, height: 100) == nil)
    }
}
