import Foundation
import Testing
@testable import CaveKit

/// Keys written by a newer version are kept and written back unchanged (docs/SYNC.md §1),
/// for every kind of record. `fixtures/interop/unknown-keys-*.json` are shared with the
/// Kotlin core (`UnknownKeysTest.kt`): each record has keys this version does not know,
/// with a scalar, an object and an array value.
@Suite struct UnknownFieldsTests {
    static func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: InteropFixture.folder.appendingPathComponent("unknown-keys-\(name).json"))
    }

    static func cellar() throws -> Cellar {
        try JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: fixture("cellar"))
    }

    static let wineID = InteropFixture.id("000000000011"), bottleID = InteropFixture.id("000000000021")
    static let rackID = InteropFixture.id("000000000031"), discoveryID = InteropFixture.id("000000000041")
    static let futureKeys: Set<String> = ["futureScalar", "futureObject", "futureArray"]

    /// The unknown fields of every record of the cellar, by a name for each record.
    static func unknowns(_ cellar: Cellar) -> [String: UnknownFields] {
        let wine = cellar.wines[wineID], bottle = cellar.bottles[bottleID]
        var all: [String: UnknownFields?] = [
            "wine": wine?.unknownFields, "wine attachment": wine?.attachments?.first?.unknownFields,
            "bottle": bottle?.unknownFields, "slot": bottle?.slot?.unknownFields,
            "tasting": bottle?.tasting?.unknownFields, "bottle attachment": bottle?.attachments?.first?.unknownFields,
            "rack": cellar.racks.first?.unknownFields, "discovery": cellar.discoveries[discoveryID]?.unknownFields,
            "settings": cellar.settings.unknownFields, "search": cellar.settings.savedSearches?.first?.unknownFields,
        ]
        all = all.filter { $0.value != nil }
        return all.mapValues { $0! }
    }

    @Test func everyRecordKeepsItsUnknownKeys() throws {
        let found = Self.unknowns(try Self.cellar())
        #expect(found.count == 10)
        for (record, fields) in found { #expect(Set(fields.values.keys) == Self.futureKeys, "\(record)") }
    }

    @Test func decodeThenEncodeKeepsThemExactly() throws {
        let expected = try InteropFixture.canonical(Self.fixture("cellar"))
        let cellar = try Self.cellar()
        // The on-device file and the sync payload.
        #expect(try InteropFixture.canonical(JSONFileCellarStore.makeEncoder().encode(cellar)) == expected)
        #expect(try InteropFixture.canonical(JSONEncoder.sync.encode(cellar)) == expected)
        let members = try JSONDecoder.sync.decode(Membership.self, from: Self.fixture("members"))
        #expect(Set(members.members.values.first?.unknownFields.values.keys ?? [:].keys) == Self.futureKeys)
        #expect(try InteropFixture.canonical(JSONEncoder.sync.encode(members))
            == InteropFixture.canonical(Self.fixture("members")))
    }

    @Test func editsKeepThem() throws {
        var cellar = try Self.cellar()
        let before = Self.unknowns(cellar)
        cellar.wines[Self.wineID]?.name = "Renamed"
        cellar.wines[Self.wineID]?.attachments?[0].name = "Renamed.pdf"
        cellar.bottles[Self.bottleID]?.status = .consumed
        cellar.bottles[Self.bottleID]?.slot?.column = 2
        cellar.bottles[Self.bottleID]?.tasting?.note = "Even better"
        cellar.racks[0].name = "Renamed"
        cellar.discoveries[Self.discoveryID]?.note = "Again"
        cellar.settings.currency = "CHF"
        cellar.settings.savedSearches?[0].name = "Renamed"
        let written = try JSONEncoder.sync.encode(cellar)
        let read = try JSONDecoder.sync.decode(Cellar.self, from: written)
        #expect(Self.unknowns(read) == before)
        #expect(read.wines[Self.wineID]?.name == "Renamed")
    }

    @Test func editsFromAFormKeepThem() throws {
        var cellar = try Self.cellar()
        cellar.bottles[Self.bottleID]?.status = .consumed
        let before = Self.unknowns(cellar)
        // Forms build the record again from the fields they show.
        try cellar.updateTasting(Self.bottleID, tasting: Tasting(date: .now, rating: 5, note: "New"))
        try cellar.updateWine(Wine(id: Self.wineID, name: "Rebuilt"))
        try cellar.updateRack(Rack(id: Self.rackID, name: "Rebuilt", rows: 2, columns: 3))
        try cellar.updateDiscovery(Discovery(id: Self.discoveryID, wineID: Self.wineID))
        var bottle = try #require(cellar.bottles[Self.bottleID])
        bottle.unknownFields = UnknownFields()
        bottle.tasting?.unknownFields = UnknownFields()
        try cellar.updateBottle(bottle)
        let after = Self.unknowns(cellar)
        for record in ["wine", "bottle", "tasting", "rack", "discovery"] { #expect(after[record] == before[record], "\(record)") }
        #expect(cellar.wines[Self.wineID]?.name == "Rebuilt")
    }

    @Test func nestedRecordsKeepThemWhenTheirParentHasNone() throws {
        var bottle = try #require(try Self.cellar().bottles[Self.bottleID])
        bottle.unknownFields = UnknownFields()
        let read = try JSONDecoder.sync.decode(Bottle.self, from: JSONEncoder.sync.encode(bottle))
        #expect(read == bottle)
        #expect(Set(read.tasting?.unknownFields.values.keys ?? [:].keys) == Self.futureKeys)
        #expect(Set(read.slot?.unknownFields.values.keys ?? [:].keys) == Self.futureKeys)
        #expect(Set(read.attachments?.first?.unknownFields.values.keys ?? [:].keys) == Self.futureKeys)
    }

    @Test func knownKeysWin() throws {
        var wine = try #require(try Self.cellar().wines[Self.wineID])
        wine.unknownFields.values["name"] = .string("Not the name")
        wine.unknownFields.values["vintage"] = .integer(1900)
        let object = try JSONSerialization.jsonObject(with: JSONEncoder.sync.encode(wine)) as? [String: Any]
        #expect(object?["name"] as? String == "Château Futur")
        #expect(object?["vintage"] as? Int == 2030)
        // And on reading, a known key is never kept.
        let read = try JSONDecoder.sync.decode(Wine.self, from: JSONEncoder.sync.encode(wine))
        #expect(Set(read.unknownFields.values.keys) == Self.futureKeys)
    }

    @Test func equalityCountsThemExceptForPlaces() throws {
        let cellar = try Self.cellar()
        var wine = try #require(cellar.wines[Self.wineID])
        let original = wine
        wine.unknownFields = UnknownFields()
        // A merge that only brings in a newer version's keys still changes the cellar (and is saved).
        #expect(wine != original)
        var slot = try #require(cellar.bottles[Self.bottleID]?.slot)
        let place = slot
        slot.unknownFields = UnknownFields()
        // A place is a place: two bottles in it are found whatever keys they carry.
        #expect(slot == place)
        #expect(Set([slot, place]).count == 1)
    }

    @Test func aMergeKeepsTheKeysOfTheNewerVersion() throws {
        let remote = try Self.cellar()
        var local = remote
        // The same records as an older build holds them: without the newer keys.
        local.wines[Self.wineID]?.unknownFields = UnknownFields()
        local.bottles[Self.bottleID]?.unknownFields = UnknownFields()
        let device = InteropFixture.android
        let key = "wine:" + Self.wineID.uuidString.lowercased()
        let bottleKey = "bottle:" + Self.bottleID.uuidString.lowercased()
        let localState = SyncState(device: InteropFixture.iphone, clock: 5,
                                   stamps: [key: Stamp(counter: 1, device: device), bottleKey: Stamp(counter: 1, device: device)])
        let remoteState = SyncState(device: device, clock: 6,
                                    stamps: [key: Stamp(counter: 6, device: device), bottleKey: Stamp(counter: 6, device: device)])
        let merged = CellarMerge.merge(local: local, localState, remote: remote, remoteState).cellar
        #expect(merged.wines[Self.wineID] == remote.wines[Self.wineID])
        #expect(Self.unknowns(merged) == Self.unknowns(remote))
        #expect(merged != local)
    }

    /// SD-16: a backup (JSON or readable ZIP archive) goes through the same encoding, so a
    /// restore keeps the keys of a newer version.
    @Test func backupsAndArchivesKeepThem() throws {
        let cellar = try Self.cellar()
        let exportedAt = InteropFixture.exportedAt
        let backup = try CellarExport.backup(fromJSON: CellarExport.json(cellar, exportedAt: exportedAt))
        #expect(Self.unknowns(backup.cellar) == Self.unknowns(cellar))

        let temporary = try TemporaryFolder()
        let url = temporary.file("archive.zip")
        try CellarArchive.write(cellar, exportedAt: exportedAt, to: url, fileURL: { _ in nil })
        let contents = try CellarArchive.read(url, workingFolder: temporary.url)
        #expect(Self.unknowns(contents.backup.cellar) == Self.unknowns(cellar))
        #expect(Self.unknowns(contents.backup.cellar).count == 10)
    }
}
