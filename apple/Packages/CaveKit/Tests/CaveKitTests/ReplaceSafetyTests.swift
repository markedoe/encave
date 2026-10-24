import Foundation
import Testing
@testable import CaveKit

/// What protects the cellar when it is replaced or erased, and the QR code's commitment
/// while joining. Shared with the Kotlin ReplaceSafetyTest.
@Suite struct ReplaceSafetyTests {
    @Test func replacingKeepsTheCellarAsAVersionFirst() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"))
        var mine = Cellar()
        try mine.addBottles(of: Wine(name: "Mine"), quantity: 2)
        // Nothing to keep: replacing may go on.
        #expect(versions.keepBeforeReplacingIfNeeded(Cellar()))
        #expect(versions.versions().isEmpty)

        #expect(versions.keepBeforeReplacingIfNeeded(mine))
        let first = try #require(versions.versions().first)
        #expect(first.isBeforeReplacing)
        #expect(try versions.cellar(of: first) == mine)
    }

    @Test func aVersionThatCannotBeSavedStopsTheReplace() throws {
        let folder = try TemporaryFolder()
        // A file where the folder of versions should be: nothing can be saved there.
        try Data("x".utf8).write(to: folder.file("Versions"))
        let versions = CellarVersionFolder(folder: folder.file("Versions"))
        var mine = Cellar()
        try mine.addBottles(of: Wine(name: "Mine"))
        #expect(!versions.keepBeforeReplacingIfNeeded(mine))
    }

    @Test func filesMadeDueAreRemovedAtOnce() throws {
        let folder = try TemporaryFolder()
        for name in ["a.jpg", "b.jpg", "kept.jpg", "back.jpg"] { try Data(name.utf8).write(to: folder.file(name)) }
        let now = Date()
        // "b" was already unreferenced for a day.
        OrphanFiles.remove(in: folder.url, pathExtension: "jpg", keeping: ["a", "kept", "back"],
                           now: now.addingTimeInterval(-86_400), gracePeriod: 30 * 86_400)
        OrphanFiles.expire(in: folder.url, pathExtension: "jpg", keeping: ["kept"])
        // An Undo refers to "back" again before the files are removed.
        OrphanFiles.remove(in: folder.url, pathExtension: "jpg", keeping: ["kept", "back"], now: now, gracePeriod: 30 * 86_400)
        let left = try FileManager.default.contentsOfDirectory(atPath: folder.url.path).filter { $0.hasSuffix(".jpg") }
        #expect(Set(left) == ["kept.jpg", "back.jpg"])
        // "back" is no longer due: unreferenced again, it gets the whole grace period.
        OrphanFiles.remove(in: folder.url, pathExtension: "jpg", keeping: ["kept"], now: now, gracePeriod: 30 * 86_400)
        #expect(FileManager.default.fileExists(atPath: folder.file("back.jpg").path))
    }

    @Test func afterEraseAllWithBackupsTheFoldersAreEmpty() throws {
        let photos = try TemporaryFolder(), documents = try TemporaryFolder()
        try Data("p".utf8).write(to: photos.file("p1.jpg"))
        try Data("d".utf8).write(to: documents.file("d1.document"))
        OrphanFiles.expire(in: photos.url, pathExtension: "jpg", keeping: [])
        OrphanFiles.expire(in: documents.url, pathExtension: "document", keeping: [])
        OrphanFiles.remove(in: photos.url, pathExtension: "jpg", keeping: [], now: .now, gracePeriod: 30 * 86_400)
        OrphanFiles.remove(in: documents.url, pathExtension: "document", keeping: [], now: .now, gracePeriod: 30 * 86_400)
        #expect(try FileManager.default.contentsOfDirectory(atPath: photos.url.path).isEmpty)
        #expect(try FileManager.default.contentsOfDirectory(atPath: documents.url.path).isEmpty)
    }

    @Test func theScannedCommitmentStaysUntilOtherDigitsAreTyped() {
        let commitment = Data([1, 2, 3])
        var entry = PairingEntry(digits: "123456", commitment: commitment)
        // The field is set to the scanned digits, then a confirmation is asked.
        entry.type("123456")
        #expect(entry.commitment == commitment)
        entry.type("12345a6")
        #expect(entry.digits == "123456")
        #expect(entry.commitment == commitment)
        entry.type("123457")
        #expect(entry.commitment == nil)
        #expect(entry.code.digits == "123457")
    }
}
