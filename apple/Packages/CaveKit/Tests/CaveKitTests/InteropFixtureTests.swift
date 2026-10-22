import Foundation
import Testing
@testable import CaveKit

/// The files shared with the Kotlin core, in `fixtures/interop` at the top of the
/// repository. This suite checks that CaveKit still writes `swift-*.json` (the same
/// JSON, whatever the order of dictionary entries) and reads what Kotlin wrote in
/// `kotlin-*.json`; `InteropFixtureTest.kt` checks the reverse. Run with
/// `ENCAVE_WRITE_FIXTURES=1` to write the files again after a format change.
///
/// The cellar has every optional field (documents of a wine and of a bottle among them), deleted objects, saved searches, a removed
/// device, prices such as 12.30 and dates with fractions of a second and before 2001.
enum InteropFixture {
    static func id(_ suffix: String) -> UUID { UUID(uuidString: "00000000-0000-4000-8000-" + suffix)! }
    static func date(_ seconds: TimeInterval) -> Date { Date(timeIntervalSinceReferenceDate: seconds) }
    static func price(_ text: String) -> Decimal { Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))! }

    static let iphone = id("000000000001"), android = id("000000000002"), removed = id("000000000003")
    static let cellarID = id("0000000000aa")
    static let fullWine = id("000000000011"), plainWine = id("000000000012")
    static let grid = id("000000000031"), bins = id("000000000032"), fridge = id("000000000033")
    static let label = PhotoID.make(for: Data("label".utf8)), back = PhotoID.make(for: Data("back".utf8))
    static let cover = PhotoID.make(for: Data("cover".utf8))
    static let invoice = PhotoID.make(for: Data("invoice".utf8)), certificate = PhotoID.make(for: Data("certificate".utf8))

    static var cellar: Cellar {
        var wine = Wine(id: fullWine, name: "Château Test", producer: "Domaine d'Exemple", vintage: 2015, color: .red,
                        region: "Bourgogne", country: "France", appellation: "Gevrey-Chambertin",
                        grapes: ["Pinot Noir", "Gamay"], drinkFrom: 2022, drinkUntil: 2035,
                        notes: "1/2 bottle, \"quoted\"\nsecond line", photoID: label, barcodes: ["3760123456789"],
                        tags: ["Gift", "for summer"])
        wine.extraPhotoIDs = [back]
        wine.currentPrice = price("45.90")
        wine.currentPriceCurrency = "EUR"
        wine.currentPriceDate = date(790_000_000.5)
        wine.coverPhotoID = cover
        wine.aiCompletedAt = date(790_100_000.25)
        wine.attachments = [
            Attachment(id: invoice, name: "Facture n°12 – Cave du coin.pdf", contentType: "application/pdf",
                       byteCount: 245_760, addedAt: date(782_000_000.75)),
            Attachment(id: back, name: "Back label.jpg", contentType: "image/jpeg", byteCount: 4, addedAt: date(-12.5)),
        ]
        let plain = Wine(id: plainWine, name: "Vin de table")

        var placed = Bottle(id: id("000000000021"), wineID: fullWine, slot: Slot(rackID: grid, row: 1, column: 2, depth: 1),
                            addedAt: date(782_000_000.25), purchaseDate: date(-1000.75), purchasePrice: price("12.30"),
                            currency: "EUR", purchasePlace: "Cave du coin", receivedFrom: "Paul", sizeML: 1500)
        placed.reservedFor = "Paul's 40th"
        placed.attachments = [Attachment(id: certificate, name: "Certificate", contentType: "application/octet-stream",
                                         byteCount: 11, addedAt: date(782_000_001))]
        let drunk = Bottle(id: id("000000000022"), wineID: fullWine, status: .consumed, storageNote: "Kitchen",
                           addedAt: date(782_000_100), purchasePrice: price("1234.5"), currency: "CHF",
                           tasting: Tasting(date: date(785_000_000.125), rating: 4, note: "Lovely", occasion: "Dinner",
                                            appearance: "Ruby", nose: "Cherry", palate: "Silky", finish: "Long",
                                            foodPairing: "Duck"))
        let ordered = Bottle(id: id("000000000023"), wineID: fullWine, status: .ordered, addedAt: date(782_000_200),
                             expectedAt: date(783_000_000))
        let gone = Bottle(id: id("000000000024"), wineID: plainWine, status: .gone, addedAt: date(700_000_000))
        let inBin = Bottle(id: id("000000000025"), wineID: plainWine, slot: Slot(rackID: bins, row: 0, column: 1, depth: 3),
                           addedAt: date(781_000_000.5), sizeML: 375)

        let discovery = Discovery(id: id("000000000041"), wineID: plainWine, date: date(781_500_000.75), setting: .restaurant,
                                  place: "Lyon", rating: 5, note: "By the glass", tags: ["Trip", "Lyon"],
                                  price: price("8.50"), currency: "EUR", wantsToBuy: true, boughtAt: date(781_600_000))
        let racks = [
            Rack(id: grid, name: "Cave/left wall", kind: .grid, rows: 4, columns: 6, depth: 2, rowColumns: [6, 6, 5, 4]),
            Rack(id: bins, name: "Bins", kind: .bins, rows: 2, columns: 3, binCapacity: 10),
            Rack(id: fridge, name: "Fridge", kind: .staggered, rows: 3, columns: 6, depth: 2, shelfLayers: [2, 1], zoneBreaks: [1],
                 backReversed: true),
        ]
        let searches = [
            SavedSearch(id: id("000000000051"), name: "Burgundy, ready",
                        filter: CellarFilter(query: "bourgogne", colors: [.white, .red], maturities: [.ready, .drinkSoon],
                                             rackID: grid, tag: "Gift", reservation: "Paul's 40th", grape: "Pinot Noir")),
            SavedSearch(id: id("000000000052"), name: "All", filter: CellarFilter()),
        ]
        return Cellar(wines: [wine, plain], bottles: [placed, drunk, ordered, gone, inBin], racks: racks,
                      settings: CellarSettings(currency: "CHF", savedSearches: SavedSearch.ordered(searches)),
                      discoveries: [discovery])
    }

    static var state: SyncState {
        SyncState(device: iphone, clock: 42, stamps: [
            "wine:" + fullWine.uuidString.lowercased(): Stamp(counter: 3, device: iphone),
            "wine:" + plainWine.uuidString.lowercased(): Stamp(counter: 7, device: android),
            "bottle:" + id("000000000021").uuidString.lowercased(): Stamp(counter: 40, device: android),
            "bottle:" + id("000000000099").uuidString.lowercased(): Stamp(counter: 41, device: iphone, deleted: true),
            "discovery:" + id("000000000041").uuidString.lowercased(): Stamp(counter: 9, device: iphone),
            "rack:" + grid.uuidString.lowercased(): Stamp(counter: 2, device: iphone),
            "search:" + id("000000000051").uuidString.lowercased(): Stamp(counter: 11, device: android),
            "settings": Stamp(counter: 12, device: android),
            "rack-order": Stamp(counter: 13, device: iphone),
        ])
    }

    static var members: Membership {
        Membership(cellarID: cellarID, members: [
            iphone: Member(id: iphone, name: "Marie's iPhone", publicKey: Data(repeating: 1, count: 32), platform: "ios",
                           addedAt: date(780_000_000.5), inbox: "i2a17946aea22c15127c53bac03a59f8a"),
            android: Member(id: android, name: "Pixel", publicKey: Data(repeating: 2, count: 32), platform: "android",
                            addedAt: date(781_000_000)),
        ], stamps: [
            iphone: Stamp(counter: 1, device: iphone),
            android: Stamp(counter: 2, device: iphone),
            removed: Stamp(counter: 4, device: android, deleted: true),
        ])
    }

    static let exportedAt = date(790_000_000)

    /// The files, by name, as CaveKit writes them.
    static func files() throws -> [String: Data] {
        [
            "cellar.json": try JSONFileCellarStore.makeEncoder().encode(cellar),
            "sync-state.json": try JSONEncoder.sync.encode(state),
            "members.json": try JSONEncoder.sync.encode(members),
            "backup.json": try CellarExport.json(cellar, exportedAt: exportedAt),
        ]
    }

    /// The photos and documents of the cellar, as an archive holds them.
    static var archiveFiles: [FileRef: Data] {
        var files: [FileRef: Data] = [:]
        for name in ["label", "back", "cover"] { files[FileRef(id: PhotoID.make(for: Data(name.utf8)), kind: .photo)] = Data(name.utf8) }
        for name in ["invoice", "back", "certificate"] {
            files[FileRef(id: PhotoID.make(for: Data(name.utf8)), kind: .document)] = Data(name.utf8)
        }
        return files
    }

    /// The archive of the cellar and its files, as CaveKit writes it.
    static func writeArchive(to url: URL, folder: URL) throws {
        var urls: [FileRef: URL] = [:]
        for (index, (ref, data)) in archiveFiles.enumerated() {
            let file = folder.appendingPathComponent("file-\(index)")
            try data.write(to: file)
            urls[ref] = file
        }
        try CellarArchive.write(cellar, exportedAt: exportedAt, to: url, fileURL: { urls[$0] })
    }

    /// Each entry of an archive: JSON entries in canonical form, others as bytes.
    static func entries(of url: URL) throws -> [String: NSObject] {
        let reader = try ZipReader(url: url)
        var entries: [String: NSObject] = [:]
        for entry in reader.entries {
            let data = try reader.data(of: entry, maxSize: 10_000_000)
            entries[entry.name] = entry.name.hasSuffix(".json") ? try canonical(data) : data as NSData
        }
        return entries
    }

    static var folder: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/interop", isDirectory: true)
    }

    /// The same JSON, whatever the order of the entries of dictionaries keyed by UUID
    /// (Swift writes them in hash order, as arrays alternating key and value).
    static func canonical(_ data: Data) throws -> NSObject {
        func walk(_ value: Any) -> Any {
            if let object = value as? [String: Any] { return object.mapValues(walk) }
            if let array = value as? [Any] {
                let pairs = stride(from: 0, to: array.count - 1, by: 2).map { (array[$0], array[$0 + 1]) }
                if !array.isEmpty, array.count.isMultiple(of: 2),
                   pairs.allSatisfy({ ($0.0 as? String).flatMap(UUID.init(uuidString:)) != nil && $0.1 is [String: Any] }) {
                    return Dictionary(uniqueKeysWithValues: pairs.map { (($0.0 as! String).uppercased(), walk($0.1)) })
                }
                return array.map(walk)
            }
            return value
        }
        return walk(try JSONSerialization.jsonObject(with: data)) as! NSObject
    }
}

@Suite struct InteropFixtureTests {
    let write = ProcessInfo.processInfo.environment["ENCAVE_WRITE_FIXTURES"] != nil

    @Test func cavekitStillWritesTheSharedFiles() throws {
        for (name, fresh) in try InteropFixture.files().sorted(by: { $0.key < $1.key }) {
            let url = InteropFixture.folder.appendingPathComponent("swift-" + name)
            let existing = try? Data(contentsOf: url)
            let same = try existing.map { try InteropFixture.canonical($0) == InteropFixture.canonical(fresh) } ?? false
            if write, !same {
                try FileManager.default.createDirectory(at: InteropFixture.folder, withIntermediateDirectories: true)
                try fresh.write(to: url)
            }
            #expect(same || write, "\(name) changed: run the tests with ENCAVE_WRITE_FIXTURES=1, then the Kotlin tests")
        }
    }

    @Test func cavekitStillWritesTheSharedArchive() throws {
        let temporary = try TemporaryFolder()
        let fresh = temporary.file("archive.zip")
        try InteropFixture.writeArchive(to: fresh, folder: temporary.url)
        let url = InteropFixture.folder.appendingPathComponent("swift-archive.zip")
        let same = (try? InteropFixture.entries(of: url)) == (try InteropFixture.entries(of: fresh))
        if write, !same {
            try? FileManager.default.removeItem(at: url)
            try FileManager.default.copyItem(at: fresh, to: url)
        }
        #expect(same || write, "archive changed: run the tests with ENCAVE_WRITE_FIXTURES=1, then the Kotlin tests")
    }

    @Test func readsTheArchiveKotlinWrites() throws {
        let url = InteropFixture.folder.appendingPathComponent("kotlin-archive.zip")
        let temporary = try TemporaryFolder()
        // The same entries, names and files as the archive CaveKit writes.
        let ours = temporary.file("ours.zip")
        try InteropFixture.writeArchive(to: ours, folder: temporary.url)
        #expect(try InteropFixture.entries(of: url) == InteropFixture.entries(of: ours))
        let contents = try CellarArchive.read(url, workingFolder: temporary.url)
        #expect(contents.backup.exportedAt == InteropFixture.exportedAt)
        let rewritten = try CellarExport.json(contents.backup.cellar, exportedAt: contents.backup.exportedAt)
        #expect(try InteropFixture.canonical(rewritten) == InteropFixture.canonical(InteropFixture.files()["backup.json"]!))
        #expect(Set(contents.files.keys) == Set(InteropFixture.archiveFiles.keys))
        for (ref, file) in contents.files { #expect(try Data(contentsOf: file) == InteropFixture.archiveFiles[ref]) }
        #expect(contents.skipped == 0)
        if let result = try unzipTest(url) { #expect(result.status == 0, "unzip -t: \(result.output)") }
    }

    @Test func readsWhatKotlinWrites() throws {
        let file = { (name: String) in try Data(contentsOf: InteropFixture.folder.appendingPathComponent("kotlin-" + name)) }
        let cellar = try JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: file("cellar.json"))
        #expect(cellar == InteropFixture.cellar)
        #expect(try JSONDecoder.sync.decode(SyncState.self, from: file("sync-state.json")) == InteropFixture.state)
        #expect(try JSONDecoder.sync.decode(Membership.self, from: file("members.json")) == InteropFixture.members)
        // Written back, Kotlin's files are the same JSON as CaveKit's own.
        let ours = try InteropFixture.files()
        for name in ["cellar.json", "sync-state.json", "members.json"] {
            #expect(try InteropFixture.canonical(file(name)) == InteropFixture.canonical(ours[name]!), "\(name)")
        }
        // Backups keep whole seconds: compare what each platform writes from it.
        let backup = try CellarExport.backup(fromJSON: file("backup.json"))
        #expect(backup.exportedAt == InteropFixture.exportedAt)
        let rewritten = try CellarExport.json(backup.cellar, exportedAt: backup.exportedAt)
        #expect(try InteropFixture.canonical(rewritten) == InteropFixture.canonical(ours["backup.json"]!))
    }
}
