import Foundation
import Testing
@testable import CaveKit

struct LabelFramingTests {
    @Test func aTallLabelKeepsTheShoulderAboveAndAMarginBelow() throws {
        // A bottle 300 × 1000 with its label from 50% to 90% of its height.
        let frame = try #require(LabelFraming.frame(label: 0.5...0.9, width: 300, height: 1000))
        #expect(abs(frame.lowerBound - 0.44) < 0.001) // 15% of 0.4 above
        #expect(abs(frame.upperBound - 0.92) < 0.001) // 5% below
    }

    @Test func aShortLabelIsMadeTallerToFillThePictures() throws {
        let frame = try #require(LabelFraming.frame(label: 0.55...0.65, width: 300, height: 1000))
        let shape = 300 / (1000 * (frame.upperBound - frame.lowerBound))
        #expect(abs(shape - LabelFraming.aspect) < 0.001)
        // More is added above (the shoulder) than below.
        #expect(0.55 - frame.lowerBound > frame.upperBound - 0.65)
    }

    @Test func theFrameStaysOnTheBottle() throws {
        let frame = try #require(LabelFraming.frame(label: 0.85...0.98, width: 300, height: 1000))
        #expect(frame.upperBound == 1)
        #expect(frame.lowerBound >= 0)
    }

    @Test func nearlyTheWholeBottleIsNotFramed() {
        #expect(LabelFraming.frame(label: 0.05...0.95, width: 300, height: 1000) == nil)
    }

    @Test func onlyStandingBottlesAreFramed() {
        #expect(LabelFraming.isStandingBottle(width: 300, height: 1000))
        #expect(!LabelFraming.isStandingBottle(width: 800, height: 1000))
        #expect(!LabelFraming.isStandingBottle(width: 300, height: 0))
    }

    @Test func theLargestGroupOfLinesIsTheLabel() throws {
        let lines = [
            LabelFraming.Line(minY: 0.88, maxY: 0.9, width: 0.3), // a word on the capsule
            LabelFraming.Line(minY: 0.40, maxY: 0.44, width: 0.8),
            LabelFraming.Line(minY: 0.33, maxY: 0.38, width: 0.9),
            LabelFraming.Line(minY: 0.28, maxY: 0.31, width: 0.6),
            LabelFraming.Line(minY: 0.2, maxY: 0.205, width: 0.5), // too small to count
        ]
        let label = try #require(LabelFraming.label(lines: lines))
        // Text from 0.56 to 0.72 from the top, with paper around it.
        #expect(label.band.lowerBound < 0.56 && label.band.lowerBound > 0.45)
        #expect(label.band.upperBound > 0.72 && label.band.upperBound < 0.83)
        #expect(label.coverage < LabelFraming.closeUpTextCoverage)
    }

    @Test func noTextNoLabel() {
        #expect(LabelFraming.label(lines: []) == nil)
        #expect(LabelFraming.label(lines: [LabelFraming.Line(minY: 0.5, maxY: 0.505, width: 0.04)]) == nil)
    }
}
