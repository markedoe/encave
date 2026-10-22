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

    /// A bottle 60 × 240 pixels (a body and a neck) leaning `degrees` to the right.
    func leaningBottle(degrees: Double) -> (shape: LabelFraming.Silhouette, boxFill: Double) {
        var shape = LabelFraming.Silhouette()
        let turn = degrees * .pi / 180
        var minX = Int.max, maxX = Int.min, minY = Int.max, maxY = Int.min
        for y in -200...200 {
            for x in -200...200 {
                // Back to the upright bottle (y going down, the top leaning right).
                let u = Double(x) * cos(turn) + Double(y) * sin(turn)
                let v = -Double(x) * sin(turn) + Double(y) * cos(turn)
                let inBody = abs(u) <= 30 && v >= -50 && v <= 120
                let inNeck = abs(u) <= 10 && v >= -120 && v < -50
                guard inBody || inNeck else { continue }
                shape.add(x: x, y: y)
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        let box = Double((maxX - minX + 1) * (maxY - minY + 1))
        return (shape, Double(shape.count) / box)
    }

    @Test func aBottleLeaning20DegreesIsStillAClearShapeAndIsStraightened() throws {
        let leaning = leaningBottle(degrees: 20)
        // Its upright frame is mostly empty, near the 0.4 limit (a real cut-out, with
        // its shoulders and soft edges, falls below it): the turned frame is used.
        #expect(leaning.boxFill < 0.45)
        #expect(leaning.shape.orientedFill >= 0.4)
        #expect(abs(leaning.shape.orientedFill - leaningBottle(degrees: 0).shape.orientedFill) < 0.02)
        #expect(abs(leaning.shape.lean - 20) < 1)
        #expect(leaning.shape.elongation >= 3)
        #expect(abs(leaningBottle(degrees: -12).shape.lean + 12) < 1)
        #expect(abs(leaningBottle(degrees: 0).shape.lean) < 0.5)
    }

    @Test func anEmptyOrTinyShapeGivesNoNonsense() {
        let empty = LabelFraming.Silhouette()
        #expect(empty.count == 0)
        #expect(empty.lean.isFinite && empty.elongation.isFinite && empty.orientedFill.isFinite)
        var dot = LabelFraming.Silhouette()
        dot.add(x: 5, y: 5)
        #expect(dot.orientedFill <= 1)
        #expect(dot.elongation.isFinite)
    }

    @Test func textLines20DegreesUpAreTurnedLevel() throws {
        // A line rising 20° on a photo 3 wide for 4 tall: its ends in Vision's coordinates.
        let aspect = 0.75, run = 0.4
        let rise = run * aspect * tan(20 * .pi / 180)
        let angle = LabelFraming.angle(fromX: 0.3, y1: 0.5, toX: 0.3 + run, y2: 0.5 + rise, aspect: aspect)
        #expect(abs(angle - 20) < 0.001)
        let turn = try #require(LabelFraming.straightening(lineAngles: [angle - 1, angle, angle + 0.5]))
        #expect(abs(turn + 20) < 0.001)
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
