import Testing
@testable import CaveKit

/// The same cases as LabelCropTest.kt.
struct LabelCropTests {
    private func gray(_ value: UInt32) -> UInt32 { 0xFF00_0000 | value << 16 | value << 8 | value }

    /// A plain wall, a dark bottle in the middle and a pale label with "text" (marks) on it.
    private func bottlePhoto() -> [UInt32] {
        (0..<(600 * 800)).map { i in
            let x = i % 600, y = i / 600
            let inBottle = (240..<360).contains(x) && (150..<760).contains(y)
            let inLabel = (240..<360).contains(x) && (470..<640).contains(y)
            if inLabel { return y % 12 < 5 && (x * 7 + y * 13) % 3 == 0 ? gray(30) : gray(230) }
            return inBottle ? gray(40) : gray(170)
        }
    }

    @Test func aWholeBottleIsFramedOnItsLabel() throws {
        let box = try #require(LabelCrop.find(pixels: bottlePhoto(), width: 600, height: 800))
        #expect((380...480).contains(box.top))
        #expect((630...720).contains(box.bottom))
        #expect(box.left <= 240 && box.right >= 360)
        #expect((0.7...0.9).contains(170 / Double(box.height)))
        #expect(abs((box.left + box.right) / 2 - 300) <= 10)
    }

    @Test func aCloseUpOfTheLabelIsLeftWhole() {
        let pixels: [UInt32] = (0..<(600 * 800)).map { i in
            let x = i % 600, y = i / 600
            return y % 12 < 5 && (x * 7 + y * 13) % 3 == 0 ? gray(30) : gray(230)
        }
        #expect(LabelCrop.find(pixels: pixels, width: 600, height: 800) == nil)
    }

    @Test func aPhotoWithoutEdgesGetsTheCentreBand() throws {
        let box = try #require(LabelCrop.find(pixels: [UInt32](repeating: gray(128), count: 600 * 800), width: 600, height: 800))
        #expect(box.top >= 160 && box.bottom <= 800)
        #expect(abs((box.left + box.right) / 2 - 300) <= 10)
    }

    @Test func tinyOrMissingPixelsAreIgnored() {
        #expect(LabelCrop.find(pixels: [UInt32](repeating: 0, count: 4), width: 2, height: 2) == nil)
        #expect(LabelCrop.find(pixels: [UInt32](repeating: 0, count: 10), width: 100, height: 100) == nil)
    }
}
