import Foundation
import Testing
@testable import CaveKit

struct LabelFramingTests {
    @Test func theLabelIsAboutFourFifthsOfThePicture() throws {
        // A bottle 300 × 1000 with its label from 50% to 80% of its height.
        let frame = try #require(LabelFraming.frame(label: 0.5...0.8, width: 300, height: 1000))
        #expect(abs(frame.lowerBound - 0.455) < 0.001) // 15% of 0.3 above
        #expect(abs(frame.upperBound - 0.83) < 0.001) // 10% below
        #expect(abs(0.3 / (frame.upperBound - frame.lowerBound) - 0.8) < 0.001)
    }

    @Test func theFrameStaysOnTheBottle() throws {
        let frame = try #require(LabelFraming.frame(label: 0.75...0.99, width: 300, height: 1000))
        #expect(frame.upperBound == 1)
        #expect(frame.lowerBound >= 0)
    }

    @Test func nearlyTheWholeBottleIsNotFramed() {
        #expect(LabelFraming.frame(label: 0.02...0.97, width: 300, height: 1000) == nil)
    }

    @Test func tiltedTextIsStraightenedWithinLimits() {
        #expect(LabelFraming.straightening(lineAngles: [9, 10, 11, 40]) == -11)
        #expect(LabelFraming.straightening(lineAngles: [9, 10, 11]) == -10)
        #expect(LabelFraming.straightening(lineAngles: [0.5, -0.4, 1]) == nil) // level enough
        #expect(LabelFraming.straightening(lineAngles: [88, 90, 91]) == nil) // sideways
        #expect(LabelFraming.straightening(lineAngles: []) == nil)
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
