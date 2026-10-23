import Foundation
import Testing
@testable import CaveKit

/// The label's place read from the AI's answer, and the frame kept with the wine
/// (the same cases as `WineLabelFrameTest.kt`).
struct WineLabelFrameTests {
    private func parse(_ json: String) -> AILabelPlacement? { AILabelPlacement.parse(Data(json.utf8)) }

    @Test func readsTheBoxAndTheAngle() {
        let placement = parse(#"{"name":"X","label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":-4.5}"#)
        #expect(placement == AILabelPlacement(x: 0.2, y: 0.3, w: 0.5, h: 0.4, angle: -4.5))
    }

    @Test func aMissingOrNullAngleIsLevel() {
        #expect(parse(#"{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4}}"#)?.angle == 0)
        #expect(parse(#"{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":null}"#)?.angle == 0)
        // Whole numbers too.
        #expect(parse(#"{"label_box":{"x":0,"y":0,"w":1,"h":1},"label_angle":3}"#)?.angle == 3)
    }

    @Test func noBoxGivesNothing() {
        #expect(parse(#"{"name":"X"}"#) == nil)
        #expect(parse(#"{"label_box":null,"label_angle":2}"#) == nil)
        #expect(parse("not json") == nil)
    }

    @Test func rejectsABoxThatIsNotFourNumbers() {
        #expect(parse(#"{"label_box":"top"}"#) == nil)
        #expect(parse(#"{"label_box":{"x":0.2,"y":0.3,"w":0.5}}"#) == nil)
        #expect(parse(#"{"label_box":{"x":"0.2","y":0.3,"w":0.5,"h":0.4}}"#) == nil)
        #expect(parse(#"{"label_box":{"x":true,"y":0.3,"w":0.5,"h":0.4}}"#) == nil)
        #expect(parse(#"{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":"tilted"}"#) == nil)
    }

    @Test func rejectsValuesOutsideZeroToOne() {
        #expect(AILabelPlacement.validated(x: -0.1, y: 0.2, w: 0.5, h: 0.5) == nil)
        #expect(AILabelPlacement.validated(x: 0.2, y: 1.2, w: 0.5, h: 0.5) == nil)
        #expect(AILabelPlacement.validated(x: 0, y: 0, w: 1.5, h: 0.5) == nil)
        #expect(AILabelPlacement.validated(x: 0, y: 0, w: 0.5, h: .nan) == nil)
        #expect(AILabelPlacement.validated(x: 0, y: 0, w: 0.5, h: 0.5, angle: .infinity) == nil)
    }

    @Test func rejectsABoxLeavingThePhoto() {
        #expect(AILabelPlacement.validated(x: 0.6, y: 0.1, w: 0.5, h: 0.5) == nil)
        #expect(AILabelPlacement.validated(x: 0.1, y: 0.7, w: 0.5, h: 0.4) == nil)
        // Up to the edge, with rounding.
        #expect(AILabelPlacement.validated(x: 0.3, y: 0.7, w: 0.7, h: 0.3) != nil)
    }

    @Test func rejectsATinyBox() {
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.09, h: 0.5) == nil)
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.5, h: 0.05) == nil)
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.1, h: 0.1) != nil)
    }

    @Test func rejectsASteepAngle() {
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.5, h: 0.5, angle: 45.5) == nil)
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.5, h: 0.5, angle: -60) == nil)
        #expect(AILabelPlacement.validated(x: 0.2, y: 0.2, w: 0.5, h: 0.5, angle: -45) != nil)
        #expect(parse(#"{"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":90}"#) == nil)
    }

    @Test func theFrameIsUsedOnlyForItsPhoto() {
        var wine = Wine(name: "Test", photoID: "sha256-a")
        #expect(wine.labelFrame(forPhoto: "sha256-a") == nil)
        let frame = WineLabelFrame(photoID: "sha256-a", x: 0.1, y: 0.2, w: 0.6, h: 0.5, angle: 2)
        wine.labelFrame = frame
        #expect(wine.labelFrame(forPhoto: "sha256-a") == frame)
        #expect(wine.labelFrame(forPhoto: "sha256-b") == nil)
        #expect(wine.labelFrame(forPhoto: nil) == nil)
        // A new label photo: the frame is stale.
        wine.photoID = "sha256-b"
        #expect(wine.labelFrame(forPhoto: wine.photoID) == nil)
    }

    @Test func encodedOnlyWhenSetAndKeptByOlderReaders() throws {
        var wine = Wine(name: "Framed", photoID: "sha256-a")
        #expect(!String(decoding: try JSONEncoder().encode(wine), as: UTF8.self).contains("labelFrame"))
        wine.labelFrame = WineLabelFrame(photoID: "sha256-a", x: 0.25, y: 0.125, w: 0.5, h: 0.625, angle: -3.5)
        let data = try JSONEncoder().encode(wine)
        let decoded = try JSONDecoder().decode(Wine.self, from: data)
        #expect(decoded == wine)
        #expect(decoded.labelFrame?.angle == -3.5)
        // A key added inside the frame by a newer version is written back.
        let newer = #"{"id":"\#(wine.id.uuidString)","name":"N","grapes":[],"#
            + #""labelFrame":{"photoID":"p","x":0.1,"y":0.1,"w":0.5,"h":0.5,"angle":0,"corners":[1,2]}}"#
        let read = try JSONDecoder().decode(Wine.self, from: Data(newer.utf8))
        #expect(String(decoding: try JSONEncoder().encode(read), as: UTF8.self).contains("corners"))
        // Part of equality, so a merge sees the change.
        var other = wine
        other.labelFrame?.x = 0.3
        #expect(other != wine)
    }

    @Test func cropIsTheLabelWithItsMargin() {
        let frame = WineLabelFrame(photoID: "p", x: 0.25, y: 0.5, w: 0.5, h: 0.25, angle: 5)
        let crop = frame.crop(width: 1000, height: 2000)
        #expect(crop.centerX == 500)
        #expect(crop.centerY == 1250)
        #expect(abs(crop.width - 560) < 1e-9)
        #expect(abs(crop.height - 560) < 1e-9)
        // Never more than the photo.
        let whole = WineLabelFrame(photoID: "p", x: 0, y: 0, w: 1, h: 1).crop(width: 800, height: 600)
        #expect(whole.width == 800 && whole.height == 600)
    }

    @Test func cacheTagChangesWithTheFrame() {
        let frame = WineLabelFrame(photoID: "p", x: 0.25, y: 0.5, w: 0.5, h: 0.25, angle: -2.5)
        #expect(frame.cacheTag == "f250-500-500-250-a-25")
        var moved = frame
        moved.y = 0.45
        #expect(moved.cacheTag != frame.cacheTag)
    }
}
