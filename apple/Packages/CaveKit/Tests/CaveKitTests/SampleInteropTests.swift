import Foundation
import Testing
@testable import CaveKit

/// The sample cellar as CaveKit exports it, in `fixtures/interop`: the JSON backup
/// (`swift-sample-backup.json`) and the spreadsheet (`swift-sample-export.csv`), which the
/// Kotlin core's InteropTest reads and compares with its own sample. The sample has every
/// kind of storage, a head-to-tail fridge with a bottle at row 1, column 3, depth 1, bottles
/// on order, tags, reservations and tastings. Dates are fixed, so the files only change with
/// the format or the sample: run with `ENCAVE_WRITE_FIXTURES=1` to write them again.
enum SampleInterop {
    static let now = Date(timeIntervalSince1970: 1_790_000_000)
    static var cellar: Cellar { SampleData.cellar(year: 2026, now: now) }

    static func backup() throws -> Data { try CellarExport.json(cellar, exportedAt: now) }

    static func csv() -> Data {
        Data(CellarExport.csv(cellar, timeZone: TimeZone(identifier: "UTC")!).utf8)
    }

    /// The header, then the rows in a fixed order: bottles of a wine added at the same
    /// instant have no defined order in the export.
    static func rows(_ data: Data) -> [String] {
        let lines = String(decoding: data, as: UTF8.self).components(separatedBy: "\n")
        return Array(lines.prefix(1)) + lines.dropFirst().sorted()
    }
}

@Suite struct SampleInteropTests {
    let write = ProcessInfo.processInfo.environment["ENCAVE_WRITE_FIXTURES"] != nil

    /// Writes the file again when asked and it changed; true when it is up to date.
    func check(_ name: String, fresh: Data, same: (Data) throws -> Bool) throws -> Bool {
        let url = InteropFixture.folder.appendingPathComponent(name)
        let existing = try? Data(contentsOf: url)
        let upToDate = try existing.map(same) ?? false
        if write, !upToDate { try fresh.write(to: url) }
        return upToDate
    }

    @Test func cavekitStillWritesTheSampleBackup() throws {
        let fresh = try SampleInterop.backup()
        let same = try check("swift-sample-backup.json", fresh: fresh) {
            try InteropFixture.canonical($0) == InteropFixture.canonical(fresh)
        }
        #expect(same || write, "swift-sample-backup.json changed: run the tests with ENCAVE_WRITE_FIXTURES=1, then the Kotlin tests")
    }

    @Test func cavekitStillWritesTheSampleSpreadsheet() throws {
        let fresh = SampleInterop.csv()
        let same = try check("swift-sample-export.csv", fresh: fresh) { SampleInterop.rows($0) == SampleInterop.rows(fresh) }
        #expect(same || write, "swift-sample-export.csv changed: run the tests with ENCAVE_WRITE_FIXTURES=1, then the Kotlin tests")
    }

    /// The sample covers what the files are there to check.
    @Test func theSampleHasWhatTheFilesCheck() throws {
        let cellar = SampleInterop.cellar
        let fridge = try #require(cellar.racks.first { $0.isBackReversed })
        #expect(cellar.bottle(at: Slot(rackID: fridge.id, row: 1, column: 3, depth: 1)) != nil)
        #expect(cellar.bottles.values.contains { $0.status == .ordered })
        #expect(cellar.bottles.values.contains { $0.reservedFor != nil })
        #expect(cellar.wines.values.contains { $0.tags?.isEmpty == false })
        // Built twice, the sample is the same: nothing depends on the clock or on hash order.
        #expect(SampleInterop.cellar == cellar)
    }
}
