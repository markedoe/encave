import Foundation
import Testing
@testable import CaveKit

/// A folder of its own for one test, removed afterwards.
final class TemporaryFolder {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("cavekit-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit { try? FileManager.default.removeItem(at: url) }

    func file(_ name: String) -> URL { url.appendingPathComponent(name) }
}

/// Runs `unzip -t` on an archive, when the tool is there (Linux CI, macOS): nil otherwise.
func unzipTest(_ url: URL) throws -> (status: Int32, output: String)? {
    let tool = ["/usr/bin/unzip", "/bin/unzip"].first { FileManager.default.isExecutableFile(atPath: $0) }
    guard let tool else { return nil }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: tool)
    process.arguments = ["-t", url.path]
    let log = url.deletingLastPathComponent().appendingPathComponent("unzip-\(UUID().uuidString).txt")
    FileManager.default.createFile(atPath: log.path, contents: nil)
    let handle = try FileHandle(forWritingTo: log)
    process.standardOutput = handle
    process.standardError = handle
    try process.run()
    process.waitUntilExit()
    try handle.close()
    let output = (try? Data(contentsOf: log)) ?? Data()
    try? FileManager.default.removeItem(at: log)
    return (process.terminationStatus, String(decoding: output, as: UTF8.self))
}

@Suite struct ZipTests {
    @Test func crc32OfTheStandardCheckValue() {
        #expect(CRC32.checksum(Data("123456789".utf8)) == 0xCBF4_3926)
        #expect(CRC32.checksum(Data()) == 0)
        var crc = CRC32()
        crc.update(Data("1234".utf8))
        crc.update(Data("56789".utf8))
        #expect(crc.value == 0xCBF4_3926)
    }

    @Test func writesAndReadsEntriesFromDataAndFiles() throws {
        let folder = try TemporaryFolder()
        let big = Data((0..<700_000).map { UInt8(truncatingIfNeeded: $0 &* 31) })
        try big.write(to: folder.file("big.bin"))
        let zipURL = folder.file("test.zip")
        let writer = try ZipWriter(url: zipURL, modified: Date(timeIntervalSince1970: 1_790_000_000))
        try writer.add(name: "README.txt", data: Data("hello".utf8))
        try writer.add(name: "photos/Château été 2015.jpg", contentsOf: folder.file("big.bin"))
        try writer.add(name: "empty", data: Data())
        try writer.finish()

        let reader = try ZipReader(url: zipURL)
        #expect(reader.entries.map(\.name) == ["README.txt", "photos/Château été 2015.jpg", "empty"])
        #expect(try reader.data(of: reader.entries[0], maxSize: 100) == Data("hello".utf8))
        try reader.extract(reader.entries[1], to: folder.file("out.bin"), maxSize: 1_000_000)
        #expect(try Data(contentsOf: folder.file("out.bin")) == big)
        #expect(try reader.data(of: reader.entries[2], maxSize: 0).isEmpty)
        #expect(throws: ZipError.tooLarge) { try reader.data(of: reader.entries[1], maxSize: 1000) }

        if let result = try unzipTest(zipURL) {
            #expect(result.status == 0, "unzip -t: \(result.output)")
            #expect(result.output.contains("No errors detected"))
        }
    }

    @Test func aDamagedEntryFailsItsCheck() throws {
        let folder = try TemporaryFolder()
        let url = folder.file("test.zip")
        let writer = try ZipWriter(url: url, modified: .now)
        try writer.add(name: "a.txt", data: Data("some content".utf8))
        try writer.finish()
        var bytes = try Data(contentsOf: url)
        bytes[30 + 5 + 2] ^= 0xFF // a byte of the content, after the local header and the name
        try bytes.write(to: url)
        let reader = try ZipReader(url: url)
        #expect(throws: ZipError.corrupt) { try reader.data(of: reader.entries[0], maxSize: 100) }
    }

    @Test func anArchiveCutShortIsDamagedNotAnotherFile() throws {
        let folder = try TemporaryFolder()
        let url = folder.file("cut.zip")
        let writer = try ZipWriter(url: url, modified: .now)
        try writer.add(name: "cellar.json", data: Data("{}".utf8))
        try writer.add(name: "photos/a.jpg", data: Data(repeating: 7, count: 4000))
        try writer.finish()
        let bytes = try Data(contentsOf: url)
        for length in [bytes.count / 2, bytes.count - 10] {
            try bytes.prefix(length).write(to: url)
            #expect(throws: ZipError.corrupt) { try ZipReader(url: url) }
            #expect(throws: ImportError.damagedArchive) { try CellarArchive.read(url, workingFolder: folder.url) }
        }
    }

    @Test func refusesWhatIsNotAZipOrIsCompressed() throws {
        let folder = try TemporaryFolder()
        try Data("not a zip at all, just some text".utf8).write(to: folder.file("text.zip"))
        #expect(throws: ZipError.notAZip) { try ZipReader(url: folder.file("text.zip")) }
        try Data().write(to: folder.file("empty.zip"))
        #expect(throws: ZipError.notAZip) { try ZipReader(url: folder.file("empty.zip")) }

        let url = folder.file("deflated.zip")
        let writer = try ZipWriter(url: url, modified: .now)
        try writer.add(name: "a.txt", data: Data("x".utf8))
        try writer.finish()
        var bytes = try Data(contentsOf: url)
        // The central directory's method: 8 (deflate).
        let central = bytes.count - 22 - (46 + 5)
        bytes[central + 10] = 8
        try bytes.write(to: url)
        let reader = try ZipReader(url: url)
        #expect(throws: ZipError.unsupported) { try reader.data(of: reader.entries[0], maxSize: 100) }
    }

    @Test func datesAreStoredInUTC() {
        // 2026-10-21 14:35:08 UTC.
        let (time, date) = ZipWriter.dosDateTime(Date(timeIntervalSince1970: 1_792_593_308))
        #expect(time == UInt16(14 << 11 | 35 << 5 | 4))
        #expect(date == UInt16((2026 - 1980) << 9 | 10 << 5 | 21))
    }
}

@Suite struct CellarArchiveTests {
    @Test func namesAreSafeAndReadable() {
        #expect(CellarArchive.cleanStem("Château X 2015", fallback: "Photo") == "Château X 2015")
        #expect(CellarArchive.cleanStem("a/b\\c:d*e?f\"g<h>i|j", fallback: "Photo") == "a-b-c-d-e-f-g-h-i-j")
        #expect(CellarArchive.cleanStem("..hidden. ", fallback: "Photo") == "hidden")
        #expect(CellarArchive.cleanStem("tab\there\nnew", fallback: "Photo") == "tab here new")
        #expect(CellarArchive.cleanStem(" . ", fallback: "Photo") == "Photo")
        #expect(CellarArchive.cleanStem(String(repeating: "é", count: 100), fallback: "Photo").unicodeScalars.count == 80)

        func document(_ name: String, _ type: String) -> (String, String?) {
            let result = CellarArchive.documentName(Attachment(id: "sha256-0", name: name, contentType: type, byteCount: 1))
            return (result.stem, result.extension)
        }
        #expect(document("Invoice.pdf", "application/pdf") == ("Invoice", "pdf"))
        #expect(document("Invoice", "application/pdf") == ("Invoice", "pdf"))
        #expect(document("Scan 12.03.2024", "image/jpeg") == ("Scan 12.03", "2024"))
        #expect(document("Notes", "application/x-unknown") == ("Notes", nil))
        #expect(document(".pdf", "application/pdf") == ("pdf", "pdf"))
        #expect(document("Facture n°12 – Cave.PDF", "application/pdf") == ("Facture n°12 – Cave", "PDF"))
    }

    @Test func planNamesEachFileOnceAndUniquely() {
        let photo = PhotoID.make(for: Data("p".utf8)), other = PhotoID.make(for: Data("o".utf8))
        let doc = PhotoID.make(for: Data("d".utf8))
        var a = Wine(id: UUID(uuidString: "00000000-0000-4000-8000-000000000001")!, name: "Clos", vintage: 2015, photoID: photo)
        a.extraPhotoIDs = [other]
        a.attachments = [Attachment(id: doc, name: "Invoice.pdf", contentType: "application/pdf", byteCount: 1)]
        // Another wine of the same name, with the same photo and a document of the same name.
        var b = Wine(id: UUID(uuidString: "00000000-0000-4000-8000-000000000002")!, name: "clos", vintage: 2015, photoID: photo)
        b.attachments = [Attachment(id: other, name: "invoice.pdf", contentType: "application/pdf", byteCount: 1)]
        var bottle = Bottle(id: UUID(uuidString: "00000000-0000-4000-8000-000000000003")!, wineID: a.id)
        bottle.attachments = [Attachment(id: doc, name: "Same file", contentType: "application/pdf", byteCount: 1)]
        let plan = CellarArchive.plan(for: Cellar(wines: [a, b], bottles: [bottle]))
        #expect(plan == [
            CellarArchive.Item(path: "photos/Clos 2015.jpg", id: photo, kind: .photo),
            CellarArchive.Item(path: "photos/Clos 2015 2.jpg", id: other, kind: .photo),
            CellarArchive.Item(path: "documents/Invoice.pdf", id: doc, kind: .document),
            CellarArchive.Item(path: "documents/invoice 2.pdf", id: other, kind: .document),
        ])
    }

    /// A cellar with two photos and a document, their files in a folder.
    private func sample(in folder: TemporaryFolder) throws -> (Cellar, [FileRef: URL]) {
        let files: [(FileRef, Data)] = [
            (FileRef(id: PhotoID.make(for: Data("label".utf8)), kind: .photo), Data("label".utf8)),
            (FileRef(id: PhotoID.make(for: Data("back".utf8)), kind: .photo), Data("back".utf8)),
            (FileRef(id: PhotoID.make(for: Data("invoice".utf8)), kind: .document), Data("invoice".utf8)),
        ]
        var urls: [FileRef: URL] = [:]
        for (index, (ref, data)) in files.enumerated() {
            let url = folder.file("store-\(index)")
            try data.write(to: url)
            urls[ref] = url
        }
        var cellar = SampleData.cellar(year: 2026)
        var wine = cellar.wines.values.sorted { $0.id.uuidString < $1.id.uuidString }[0]
        wine.photoID = files[0].0.id
        wine.extraPhotoIDs = [files[1].0.id]
        wine.attachments = [Attachment(id: files[2].0.id, name: "Invoice", contentType: "application/pdf", byteCount: 7)]
        cellar.wines[wine.id] = wine
        return (cellar, urls)
    }

    @Test func roundTripKeepsTheCellarAndItsFiles() throws {
        let folder = try TemporaryFolder()
        let (cellar, urls) = try sample(in: folder)
        let zipURL = folder.file("backup.zip")
        let exportedAt = Date(timeIntervalSince1970: 1_790_000_000)
        let result = try CellarArchive.write(cellar, exportedAt: exportedAt, to: zipURL, fileURL: { urls[$0] })
        #expect(result == CellarArchive.WriteResult(included: 3, missing: 0, leftOut: 0))

        let reader = try ZipReader(url: zipURL)
        let names = reader.entries.map(\.name)
        #expect(Array(names.prefix(3)) == ["README.txt", "cellar.json", "files.json"])
        #expect(names.filter { $0.hasPrefix("photos/") }.count == 2)
        #expect(names.contains("documents/Invoice.pdf"))
        let manifest = try JSONDecoder().decode(CellarArchive.Manifest.self, from: reader.data(of: reader.entry(named: "files.json")!, maxSize: 10_000))
        #expect(manifest.files.count == 3)
        #expect(String(decoding: try reader.data(of: reader.entries[0], maxSize: 10_000), as: UTF8.self) == CellarArchive.readme)
        // cellar.json is the JSON backup itself.
        #expect(try reader.data(of: reader.entry(named: "cellar.json")!, maxSize: 10_000_000)
                == CellarExport.json(cellar, exportedAt: exportedAt))
        if let result = try unzipTest(zipURL) { #expect(result.status == 0, "unzip -t: \(result.output)") }

        let work = folder.file("work")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        let contents = try CellarArchive.read(zipURL, workingFolder: work)
        // Backups keep whole seconds.
        #expect(contents.backup.cellar == (try CellarExport.backup(fromJSON: CellarExport.json(cellar))).cellar)
        #expect(contents.backup.exportedAt == exportedAt)
        #expect(Set(contents.files.keys) == Set(urls.keys))
        for (ref, url) in contents.files { #expect(try Data(contentsOf: url) == Data(contentsOf: urls[ref]!)) }
        #expect(contents.skipped == 0)
    }

    @Test func roundTripKeepsAPhotoFromBeforeSync() throws {
        let folder = try TemporaryFolder()
        // A photo taken before sync existed: its ID is a UUID, not its hash.
        let photo = FileRef(id: UUID().uuidString, kind: .photo)
        let source = folder.file("old-photo")
        try Data("old label".utf8).write(to: source)
        var cellar = SampleData.cellar(year: 2026)
        var wine = cellar.wines.values.sorted { $0.id.uuidString < $1.id.uuidString }[0]
        wine.photoID = photo.id
        cellar.wines[wine.id] = wine
        let zipURL = folder.file("backup.zip")
        let result = try CellarArchive.write(cellar, exportedAt: .now, to: zipURL, fileURL: { $0 == photo ? source : nil })
        #expect(result.included == 1)
        let work = folder.file("work")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        let contents = try CellarArchive.read(zipURL, workingFolder: work)
        #expect(Set(contents.files.keys) == [photo])
        #expect(try contents.files[photo].map { try Data(contentsOf: $0) } == Data("old label".utf8))
        #expect(contents.skipped == 0)
    }

    @Test func missingFilesAreLeftOut() throws {
        let folder = try TemporaryFolder()
        let (cellar, urls) = try sample(in: folder)
        let zipURL = folder.file("backup.zip")
        let photo = urls.keys.first { $0.kind == .photo }!
        let result = try CellarArchive.write(cellar, exportedAt: .now, to: zipURL, fileURL: { $0 == photo ? nil : urls[$0] })
        #expect(result == CellarArchive.WriteResult(included: 2, missing: 1, leftOut: 0))
        let reader = try ZipReader(url: zipURL)
        let manifest = try JSONDecoder().decode(CellarArchive.Manifest.self, from: reader.data(of: reader.entry(named: "files.json")!, maxSize: 10_000))
        #expect(manifest.files.count == 2)
        #expect(reader.entries.count == 5)
    }

    @Test func filesBeyondTheZipLimitsAreLeftOut() {
        // 100 bytes of room: the first file fits, the second is too large, the third fits.
        let fixed: UInt64 = 1000
        let base = fixed + 3 * (30 + 46 + 16) + 22 + CellarArchive.reserve
        let cost = { (size: UInt64) in size + 76 + 3 * 10 + 160 }
        let limit = base + cost(40) + cost(10) + 1
        let fits = CellarArchive.fitting([(10, 40), (10, 200), (10, 10)], fixedBytes: fixed, limit: limit)
        #expect(fits == [true, false, true])
        // At most 65,534 entries: README, cellar and manifest, then the files.
        let many = CellarArchive.fitting(Array(repeating: (10, 1), count: 5), fixedBytes: 0, maxEntries: 6)
        #expect(many == [true, true, true, false, false])
    }

    @Test func aZipHoldsAtMost65534Entries() throws {
        let folder = try TemporaryFolder()
        let writer = try ZipWriter(url: folder.file("many.zip"), modified: .now)
        for index in 0..<ZipWriter.maxEntries { try writer.add(name: "\(index)", data: Data()) }
        #expect(throws: ZipError.tooLarge) { try writer.add(name: "one more", data: Data()) }
        try writer.finish()
        // Its own reader takes it back (65,535 would mean ZIP64).
        #expect(try ZipReader(url: folder.file("many.zip")).entries.count == ZipWriter.maxEntries)
    }

    @Test func filesThatAreNotWhatTheySayAreIgnored() throws {
        let folder = try TemporaryFolder()
        let (cellar, urls) = try sample(in: folder)
        let photo = FileRef(id: PhotoID.make(for: Data("label".utf8)), kind: .photo)
        // An archive written by hand: a photo whose content changed, a file the cellar does
        // not refer to, a file outside the folders, and one without a manifest entry.
        let zipURL = folder.file("edited.zip")
        let writer = try ZipWriter(url: zipURL, modified: .now)
        try writer.add(name: "cellar.json", data: try CellarExport.json(cellar))
        let manifest = CellarArchive.Manifest(files: [
            CellarArchive.Item(path: "photos/Label.jpg", id: photo.id, kind: .photo),
        ])
        try writer.add(name: "files.json", data: try CellarArchive.manifestData(manifest))
        try writer.add(name: "photos/Label.jpg", data: Data("edited label".utf8))
        try writer.add(name: "photos/Other.jpg", data: Data("not in the cellar".utf8))
        try writer.add(name: "elsewhere/back.jpg", data: Data("back".utf8))
        try writer.add(name: "photos/Renamed back.jpg", data: Data("back".utf8))
        try writer.add(name: "documents/", data: Data())
        try writer.finish()
        let work = folder.file("work")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        let contents = try CellarArchive.read(zipURL, workingFolder: work)
        #expect(Set(contents.files.keys) == [FileRef(id: PhotoID.make(for: Data("back".utf8)), kind: .photo)])
        #expect(contents.skipped == 2)
        _ = urls
    }

    @Test func refusesNewerFormatsAndOtherFiles() throws {
        let folder = try TemporaryFolder()
        let work = folder.file("work")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)

        // A backup of a newer version of Encave, whose fields this one may not know.
        let newer = Data(#"{"format":"encave","version":99,"exportedAt":"2026-10-21T10:00:00Z","cellar":{"something":"else"}}"#.utf8)
        #expect(throws: ImportError.newerVersion) { try CellarExport.backup(fromJSON: newer) }
        #expect(throws: ImportError.notAnEncaveBackup) { try CellarExport.backup(fromJSON: Data(#"{"format":"other","version":1}"#.utf8)) }

        let newerZip = folder.file("newer.zip")
        var writer = try ZipWriter(url: newerZip, modified: .now)
        try writer.add(name: "cellar.json", data: newer)
        try writer.finish()
        #expect(throws: ImportError.newerVersion) { try CellarArchive.read(newerZip, workingFolder: work) }

        let newerManifest = folder.file("manifest.zip")
        writer = try ZipWriter(url: newerManifest, modified: .now)
        try writer.add(name: "cellar.json", data: try CellarExport.json(Cellar()))
        try writer.add(name: "files.json", data: Data(#"{"format":"encave-archive","version":2,"files":[]}"#.utf8))
        try writer.finish()
        #expect(throws: ImportError.newerVersion) { try CellarArchive.read(newerManifest, workingFolder: work) }

        let other = folder.file("other.zip")
        writer = try ZipWriter(url: other, modified: .now)
        try writer.add(name: "photo.jpg", data: Data("x".utf8))
        try writer.finish()
        #expect(throws: ImportError.notAnEncaveBackup) { try CellarArchive.read(other, workingFolder: work) }

        try Data("plain text".utf8).write(to: folder.file("text.zip"))
        #expect(throws: ImportError.notAnEncaveBackup) { try CellarArchive.read(folder.file("text.zip"), workingFolder: work) }
    }

    @Test func fileNameHasTheDay() {
        let date = Date(timeIntervalSince1970: 1_792_593_308) // 2026-10-21 14:35 UTC
        #expect(CellarArchive.fileName(date: date, timeZone: TimeZone(identifier: "UTC")!) == "Encave Backup 2026-10-21.zip")
        #expect(CellarArchive.fileName(date: date, timeZone: TimeZone(identifier: "Pacific/Kiritimati")!) == "Encave Backup 2026-10-22.zip")
    }
}

@Suite struct CellarVersionTests {
    let utc = TimeZone(identifier: "UTC")!

    /// 2026-10-21 (a Wednesday) at `hour` UTC, `days` days later.
    func day(_ days: Int, hour: Int = 12) -> Date {
        Date(timeIntervalSince1970: 1_792_540_800 + TimeInterval(days * 86_400 + hour * 3600))
    }

    @Test func keepsTheNewestOfEachOfTheLastSevenDaysAndFourWeeks() {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = utc
        // One version a day for 40 days, two on some days.
        let dates = (0..<40).map { day(-$0) } + [day(-1, hour: 8), day(-10, hour: 8)]
        let kept = CellarVersionFolder.kept(dates, calendar: calendar)
        // The last 7 days (today included).
        let daily = (0..<7).map { day(-$0) }
        #expect(daily.allSatisfy(kept.contains))
        #expect(!kept.contains(day(-1, hour: 8)))
        // Then the newest of each of the 4 ISO weeks before: Wednesday 2026-10-14 (the rest of
        // the week of the oldest daily one), Sundays 10-11, 10-04 and 09-27.
        let weekly = kept.subtracting(daily).sorted(by: >)
        #expect(weekly == [day(-7), day(-10), day(-17), day(-24)])
        #expect(kept.count == 11)
        // A day later, with one more version: still 7 + 4, nothing kept comes back.
        let later = CellarVersionFolder.kept(Array(kept) + [day(1)], calendar: calendar)
        #expect(later.count == 11)
        #expect(later.isSubset(of: kept.union([day(1)])))
    }

    @Test func aLongPauseKeepsTheLastVersions() {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = utc
        // Versions from two months ago, then nothing: they are counted from the newest one.
        let dates = (60..<100).map { day(-$0) }
        let kept = CellarVersionFolder.kept(dates, calendar: calendar)
        #expect(kept.count == 11)
        #expect(kept.contains(day(-60)))
        #expect(CellarVersionFolder.kept([], calendar: calendar).isEmpty)
    }

    @Test func aCopyIsKeptWhenTheFileWasLastSavedOnAnEarlierDay() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"), timeZone: utc)
        let store = JSONFileCellarStore(fileURL: folder.file("cellar.json"))
        var cellar = SampleData.cellar(year: 2026)
        try store.save(cellar)
        func setModified(_ date: Date) throws {
            try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: store.fileURL.path)
        }

        // Saved today: no copy.
        try setModified(day(0, hour: 8))
        versions.keepCopy(of: store.fileURL, now: day(0, hour: 9))
        #expect(versions.versions().isEmpty)

        // Last saved yesterday evening: kept, dated then.
        try setModified(day(-1, hour: 22))
        versions.keepCopy(of: store.fileURL, now: day(0, hour: 9))
        #expect(versions.versions().map(\.date) == [day(-1, hour: 22)])
        let kept = try #require(versions.versions().first)
        #expect(try versions.cellar(of: kept) == cellar)
        // Not twice.
        versions.keepCopy(of: store.fileURL, now: day(0, hour: 10))
        #expect(versions.versions().count == 1)

        // An empty cellar is not kept.
        cellar = Cellar()
        try store.save(cellar)
        try setModified(day(-3))
        versions.keepCopy(of: store.fileURL, now: day(0))
        #expect(versions.versions().count == 1)

        // After weeks without a change, the copy is kept with the one before.
        try store.save(SampleData.cellar(year: 2026))
        try setModified(day(40))
        versions.keepCopy(of: store.fileURL, now: day(41))
        #expect(versions.versions().map(\.date) == [day(40), day(-1, hour: 22)])

        // Beyond 7 days and 4 weeks, the oldest go.
        for offset in 41..<70 {
            try setModified(day(offset))
            versions.keepCopy(of: store.fileURL, now: day(offset + 1))
        }
        #expect(versions.versions().count == 11)
        #expect(versions.versions().first?.date == day(69))
        #expect(!versions.versions().contains { $0.date == day(40) })
    }

    @Test func aVersionIsSavedBeforeReplacingAndOutlivesTheNextCopies() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"), timeZone: utc)
        let store = JSONFileCellarStore(fileURL: folder.file("cellar.json"))
        var mine = Cellar()
        try mine.addBottles(of: Wine(name: "Mine"), quantity: 3)
        try store.save(mine)
        // Nothing to keep: an empty cellar, or only the sample.
        #expect(versions.keepBeforeReplacing(Cellar(), now: day(0, hour: 9)) == nil)
        #expect(versions.keepBeforeReplacing(SampleData.cellar(year: 2026), now: day(0, hour: 9)) == nil)
        #expect(versions.versions().isEmpty)

        // Replace and Join: the cellar as it was is a version.
        let kept = try #require(versions.keepBeforeReplacing(mine, now: day(0, hour: 9)))
        #expect(kept.isBeforeReplacing)
        #expect(versions.versions() == [kept])
        #expect(try versions.cellar(of: kept) == mine)

        // The other cellar arrives and is saved the same day; the next day's copy of it does
        // not take the place of the one saved before replacing.
        try store.save(Cellar())
        try FileManager.default.setAttributes([.modificationDate: day(0, hour: 20)], ofItemAtPath: store.fileURL.path)
        try store.save(SampleData.cellar(year: 2026))
        try FileManager.default.setAttributes([.modificationDate: day(0, hour: 21)], ofItemAtPath: store.fileURL.path)
        versions.keepCopy(of: store.fileURL, now: day(1))
        #expect(versions.versions().count == 2)
        #expect(versions.versions().contains(kept))

        // Only the last 3 saved before replacing are kept.
        for hour in 10..<14 { versions.keepBeforeReplacing(mine, now: day(2, hour: hour)) }
        #expect(versions.versions().filter(\.isBeforeReplacing).map(\.date) == [day(2, hour: 13), day(2, hour: 12), day(2, hour: 11)])
        #expect(versions.versions().contains { !$0.isBeforeReplacing })

        // Erase All Data with previous versions.
        versions.removeAll()
        #expect(versions.versions().isEmpty)
    }

    @Test func aCopyThatCannotBeReadIsNotKept() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"), timeZone: utc)
        let file = folder.file("cellar.json")
        try Data("not json".utf8).write(to: file)
        try FileManager.default.setAttributes([.modificationDate: day(-1)], ofItemAtPath: file.path)
        let copy = try #require(versions.copyIfNewDay(of: file, now: day(0)))
        #expect(versions.versions().count == 1)
        versions.tidy(checking: copy)
        #expect(versions.versions().isEmpty)
        // Copies left half made are removed too.
        try Data("x".utf8).write(to: folder.file("Versions/cellar-20261020-000000.json.1.tmp"))
        versions.tidy()
        #expect((try FileManager.default.contentsOfDirectory(atPath: folder.file("Versions").path)).isEmpty)
    }

    @Test func theStoreKeepsTheOldFileBeforeSavingOnANewDay() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"))
        let store = JSONFileCellarStore(fileURL: folder.file("cellar.json"), versions: versions)
        let before = SampleData.cellar(year: 2026)
        try store.save(before)
        try FileManager.default.setAttributes([.modificationDate: Date.now.addingTimeInterval(-3 * 86_400)],
                                              ofItemAtPath: store.fileURL.path)
        try store.save(Cellar())
        #expect(versions.versions().count == 1)
        let kept = try #require(versions.versions().first)
        #expect(try versions.cellar(of: kept) == before)
        #expect(try store.load() == Cellar())
        // The versions are not taken for cellar files that could not be read.
        #expect(store.unreadableFiles().isEmpty)
    }

    @Test func versionsKeepTheirFilesReferenced() throws {
        let folder = try TemporaryFolder()
        let versions = CellarVersionFolder(folder: folder.file("Versions"), timeZone: utc)
        let store = JSONFileCellarStore(fileURL: folder.file("cellar.json"))
        var cellar = SampleData.cellar(year: 2026)
        var wine = cellar.wines.values.first!
        wine.photoID = PhotoID.make(for: Data("photo".utf8))
        cellar.wines[wine.id] = wine
        try store.save(cellar)
        try FileManager.default.setAttributes([.modificationDate: day(-1)], ofItemAtPath: store.fileURL.path)
        versions.keepCopy(of: store.fileURL, now: day(0))
        #expect(versions.fileRefs().contains(FileRef(id: wine.photoID!, kind: .photo)))
        // Files that are not versions are ignored.
        try Data("x".utf8).write(to: folder.file("Versions/notes.txt"))
        #expect(versions.versions().count == 1)
    }
}

@Suite struct RestoreTests {
    @Test func previewCountsAndCompares() throws {
        let backup = SampleData.cellar(year: 2026)
        var current = backup
        // Removed since the backup: one bottle; added since: a wine; changed: a rack.
        let bottle = current.bottles.values.first { $0.status == .inCellar }!
        current.bottles[bottle.id] = nil
        let extra = Wine(name: "New since")
        current.wines[extra.id] = extra
        current.racks[0].name = "Renamed"

        let preview = RestorePreview(of: backup, date: Date(timeIntervalSince1970: 0), comparedWith: current)
        // Wines with bottles in the cellar, not every wine record (those tasted elsewhere).
        #expect(preview.wines == Set(backup.bottlesInCellar.map(\.wineID)).count)
        #expect(preview.wines < backup.wines.count)
        #expect(preview.bottles == backup.bottles.values.filter { $0.status == .inCellar }.count)
        #expect(preview.racks == backup.racks.count)
        #expect(preview.journalEntries == backup.consumedBottles.count + backup.discoveries.count)
        #expect(preview.photos == backup.photoIDs.count)
        #expect(preview.documents == backup.attachmentIDs.count)
        #expect(preview.added == 1)
        #expect(preview.removed == 1)
        #expect(preview.changed == 1)
        #expect(!preview.isSame)
        #expect(RestorePreview(of: backup, date: .now, comparedWith: backup).isSame)
    }

    @Test func addMissingKeepsTheCellarAndReplaceTakesTheBackup() throws {
        let backup = SampleData.cellar(year: 2026)
        var current = backup
        let bottle = current.bottles.values.first { $0.status == .inCellar }!
        current.bottles[bottle.id] = nil
        let extra = Wine(name: "New since")
        current.wines[extra.id] = extra
        current.settings.currency = "CHF"

        let added = current.restoring(backup, mode: .addMissing)
        #expect(added.bottles[bottle.id] != nil)
        #expect(added.wines[extra.id] != nil)
        #expect(added.settings.currency == "CHF")

        // Replace takes the backup, its settings included.
        let replaced = current.restoring(backup, mode: .replace)
        #expect(replaced == backup)
        #expect(replaced.settings.currency == backup.settings.currency)
    }

    @Test func replaceRestoresTheSettingsAndAddMissingOnlyAddsSearches() throws {
        var backup = SampleData.cellar(year: 2026)
        backup.settings.currency = "CHF"
        backup.saveSearch("Ready", filter: CellarFilter(query: "bordeaux"))
        backup.saveSearch("Old", filter: CellarFilter(query: "1990"))
        var current = backup
        current.settings.currency = "USD"
        current.settings.savedSearches = nil
        current.saveSearch("ready", filter: CellarFilter(query: "mine"))
        current.saveSearch("Mine", filter: CellarFilter(query: "loire"))

        let replaced = current.restoring(backup, mode: .replace)
        #expect(replaced.settings == backup.settings)
        #expect(replaced.settings.currency == "CHF")
        #expect(replaced.settings.savedSearches?.map(\.name).sorted() == ["Old", "Ready"])

        // Only the searches whose name is not there yet (ignoring case); the currency stays.
        let added = current.restoring(backup, mode: .addMissing)
        #expect(added.settings.currency == "USD")
        #expect(added.settings.savedSearches?.map(\.name).sorted() == ["Mine", "Old", "ready"])
        #expect(added.settings.savedSearches?.first { $0.name == "ready" }?.query == "mine")
    }

    @Test func addMissingBringsBackSavedSearchesAndKeepsBottlesInTheirRack() throws {
        var backup = SampleData.cellar(year: 2026)
        backup.saveSearch("Ready", filter: CellarFilter(query: "bordeaux"))
        backup.saveSearch("Old", filter: CellarFilter(query: "1990"))
        var current = backup
        // A search deleted since, one renamed and saved again under the same name.
        current.settings.savedSearches = backup.settings.savedSearches?.filter { $0.name == "Old" }
            .map { var search = $0; search.id = UUID(); return search }
        // A bottle in the second row of a rack, deleted since; the rack now has one row.
        let bottle = try #require(backup.bottlesInCellar.first { ($0.slot?.row ?? 0) >= 1 })
        let slot = try #require(bottle.slot)
        current.bottles[bottle.id] = nil
        let index = try #require(current.racks.firstIndex { $0.id == slot.rackID })
        current.racks[index].rows = 1

        let added = current.restoring(backup, mode: .addMissing)
        #expect(added.bottles[bottle.id] != nil)
        #expect(added.bottles[bottle.id]?.slot == nil)
        #expect(added.settings.savedSearches?.map(\.name) == ["Old", "Ready"])
        #expect(added.settings.savedSearches?.first?.id == current.settings.savedSearches?.first?.id)
    }
}
