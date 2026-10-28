import Foundation
import Testing
@testable import CaveKit

/// Swift Testing has its own `Attachment`.
typealias Attachment = CaveKit.Attachment

/// The same table is in AttachmentTest.kt: both platforms keep the same name.
enum AttachmentNames {
    static let table: [(String, String)] = [
        ("  Invoice.pdf  ", "Invoice.pdf"),
        ("a/b\\c.pdf", "a-b-c.pdf"),
        ("line\nbreak\t.pdf", "line break .pdf"),
        ("\u{00A0}\u{3000}Name\u{2028}", "Name"),
        ("", ""),
        ("   ", ""),
        ("..", ".."),
        ("\u{7F}x\u{85}", "x"),
        (String(repeating: "é", count: 250), String(repeating: "é", count: 200)),
        (String(repeating: "a", count: 199) + " b", String(repeating: "a", count: 199)),
        (String(repeating: "e\u{301}", count: 150), String(repeating: "e\u{301}", count: 100)),
        ("Facture 🍷.pdf", "Facture 🍷.pdf"),
    ]
}

@Suite struct AttachmentTests {
    static let date = Date(timeIntervalSinceReferenceDate: 782_000_000.5)

    func doc(_ content: String, _ name: String = "Invoice.pdf") -> Attachment {
        Attachment(id: PhotoID.make(for: Data(content.utf8)), name: name, contentType: "application/pdf",
                   byteCount: content.utf8.count, addedAt: Self.date)
    }

    @Test func namesAreNormalizedTheSameWayEverywhere() {
        for (input, expected) in AttachmentNames.table {
            #expect(Attachment.normalizedName(input) == expected, "\(input.debugDescription)")
            #expect(Attachment(id: "sha256-0", name: input, contentType: "text/plain", byteCount: 0).name == expected)
            #expect(Attachment.normalizedName(expected) == expected)
        }
        var attachment = doc("x")
        attachment.name = " new/name "
        #expect(attachment.name == "new-name")
    }

    @Test func longFileNamesAreShortenedBeforeTheirExtension() {
        for letter in ["é", "葡", "🍷", "e\u{301}"] {
            let name = Attachment.fileName(base: String(repeating: letter, count: 200), pathExtension: "pdf")
            #expect(name.utf8.count <= Attachment.maxFileNameBytes)
            #expect(name.hasSuffix(".pdf"))
            // Cut between characters: only whole letters are left.
            #expect(name.dropLast(4).allSatisfy { String($0) == letter })
        }
        #expect(Attachment.fileName(base: "Invoice", pathExtension: "pdf") == "Invoice.pdf")
        #expect(Attachment.fileName(base: "Notes", pathExtension: nil) == "Notes")
    }

    @Test func documentsAreKeptOnceAndNilWhenNone() throws {
        var wine = Wine(name: "A")
        wine.attachments = [doc("a"), doc("b"), doc("a", "Again.pdf")]
        #expect(wine.attachments?.map(\.name) == ["Invoice.pdf", "Invoice.pdf"])
        #expect(wine.attachments?.first?.id == PhotoID.make(for: Data("a".utf8)))
        wine.attachments = []
        #expect(wine.attachments == nil)
        var bottle = Bottle(wineID: wine.id)
        bottle.attachments = [doc("a"), doc("a")]
        #expect(bottle.attachments?.count == 1)

        // Read from a file too, where a reader cannot rely on didSet.
        let json = Data("""
        {"id":"\(UUID().uuidString)","name":"A","grapes":[],"attachments":[
          {"id":"sha256-1","name":" One ","contentType":"application/pdf","byteCount":3,"addedAt":1},
          {"id":"sha256-1","name":"Two","contentType":"application/pdf","byteCount":3,"addedAt":2}]}
        """.utf8)
        let decoded = try JSONFileCellarStore.makeDecoder().decode(Wine.self, from: json)
        #expect(decoded.attachments?.count == 1)
        #expect(decoded.attachments?.first?.name == "One")
        let empty = try JSONFileCellarStore.makeDecoder().decode(
            Wine.self, from: Data(#"{"id":"\#(UUID().uuidString)","name":"A","grapes":[],"attachments":[]}"#.utf8))
        #expect(empty.attachments == nil)
    }

    @Test func noDocumentsLeavesTheFieldOut() throws {
        let cellar = Cellar(wines: [Wine(name: "A")])
        let text = String(decoding: try JSONFileCellarStore.makeEncoder().encode(cellar), as: UTF8.self)
        #expect(!text.contains("attachments"))
    }

    @Test func everyFieldIsRequired() throws {
        let full: [String: Any] = ["id": "sha256-1", "name": "A", "contentType": "application/pdf", "byteCount": 3, "addedAt": 1]
        let decoder = JSONFileCellarStore.makeDecoder()
        #expect(try decoder.decode(Attachment.self, from: JSONSerialization.data(withJSONObject: full)).byteCount == 3)
        for key in full.keys {
            var missing = full
            missing[key] = nil
            #expect(throws: DecodingError.self, "\(key)") {
                try decoder.decode(Attachment.self, from: JSONSerialization.data(withJSONObject: missing))
            }
        }
        var wrong = full
        wrong["byteCount"] = "3"
        #expect(throws: DecodingError.self) { try decoder.decode(Attachment.self, from: JSONSerialization.data(withJSONObject: wrong)) }
    }

    @Test func theSizeLimitFitsTheRelayAndTheNetwork() {
        // Sealed (34 bytes more), a document stays under the relay's 8 MiB per file.
        #expect(Attachment.maxByteCount + 34 <= 8 * 1024 * 1024)
        // In Base64, inside a JSON message, it fits a local network frame.
        #expect((Attachment.maxByteCount + 2) / 3 * 4 + 1024 < SyncProtocol.maxFrameSize)
    }

    @Test func cellarCommandsAttachRenameAndRemove() throws {
        var cellar = Cellar()
        let wine = Wine(name: "A")
        let bottle = try cellar.addBottles(of: wine, quantity: 2)[0]
        try cellar.attach([doc("a"), doc("b")], to: .wine(wine.id))
        try cellar.attach([doc("a", "Same.pdf"), doc("c")], to: .wine(wine.id))
        #expect(cellar.attachments(of: .wine(wine.id)).map(\.id) == ["a", "b", "c"].map { PhotoID.make(for: Data($0.utf8)) })
        try cellar.attach([doc("receipt")], to: .bottle(bottle.id))
        #expect(cellar.attachments(of: .bottle(bottle.id)).count == 1)

        let b = PhotoID.make(for: Data("b".utf8))
        try cellar.renameAttachment(b, of: .wine(wine.id), to: "  Fiche technique.pdf ")
        #expect(cellar.attachments(of: .wine(wine.id))[1].name == "Fiche technique.pdf")
        try cellar.renameAttachment(b, of: .wine(wine.id), to: "   ")
        #expect(cellar.attachments(of: .wine(wine.id))[1].name == "Fiche technique.pdf")

        let removed = try cellar.removeAttachment(b, from: .wine(wine.id))
        #expect(removed?.index == 1)
        #expect(cellar.attachments(of: .wine(wine.id)).count == 2)
        // Undo puts it back where it was.
        try cellar.attach([removed!.attachment], to: .wine(wine.id), at: removed!.index)
        #expect(cellar.attachments(of: .wine(wine.id))[1].id == b)

        #expect(throws: CellarError.unknownBottle) { try cellar.attach([doc("x")], to: .bottle(UUID())) }
        #expect(throws: CellarError.unknownWine) { try cellar.attach([doc("x")], to: .wine(UUID())) }
    }

    @Test func theCellarRefersToPhotosAndDocuments() throws {
        var cellar = Cellar()
        var wine = Wine(name: "A", photoID: "label")
        let receipt = doc("receipt")
        // The same picture as a photo and as a document: both places need it.
        wine.extraPhotoIDs = [receipt.id]
        wine.attachments = [doc("sheet")]
        let bottle = try cellar.addBottles(of: wine, quantity: 1)[0]
        try cellar.attach([receipt], to: .bottle(bottle.id))
        #expect(cellar.photoIDs == ["label", receipt.id])
        #expect(cellar.attachmentIDs == [receipt.id, doc("sheet").id])
        #expect(cellar.fileRefs == [
            FileRef(id: "label", kind: .photo), FileRef(id: receipt.id, kind: .photo),
            FileRef(id: receipt.id, kind: .document), FileRef(id: doc("sheet").id, kind: .document),
        ])
    }

    @Test func theLastBottleDrunkKeepsTheWinesPictures() throws {
        var cellar = Cellar()
        var wine = Wine(name: "A", photoID: "label")
        wine.coverPhotoID = "cover"
        wine.extraPhotoIDs = ["back"]
        wine.attachments = [doc("sheet")]
        let bottle = try cellar.addBottles(of: wine, quantity: 1)[0]
        try cellar.drink(bottle.id, tasting: Tasting(date: .now))
        // The Journal still shows the wine, its pictures and its page.
        #expect(cellar.wines[wine.id] != nil)
        #expect(cellar.photoIDs == ["label", "cover", "back"])
        #expect(cellar.fileRefs.contains(FileRef(id: doc("sheet").id, kind: .document)))
    }

    @Test func documentsTravelThroughStorageToTheirPlace() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        let pdf = Data("%PDF-1.7 invoice".utf8), receipt = Data("receipt jpeg".utf8)
        let sheet = Attachment(id: PhotoID.make(for: pdf), name: "Invoice.pdf", contentType: "application/pdf",
                               byteCount: pdf.count)
        let both = PhotoID.make(for: receipt)
        devices[0].photos.access.save(sheet.id, .document, pdf)
        devices[0].photos.access.save(both, .photo, receipt)
        devices[0].photos.access.save(both, .document, receipt)
        var wine = Wine(name: "With papers")
        wine.extraPhotoIDs = [both]
        wine.attachments = [sheet]
        try devices[0].edit { cellar in
            let bottle = try cellar.addBottles(of: wine, quantity: 1)[0]
            try cellar.attach([Attachment(id: both, name: "Receipt.jpg", contentType: "image/jpeg", byteCount: receipt.count)],
                              to: .bottle(bottle.id))
        }
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        #expect(devices[1].photos.storedDocuments[sheet.id] == pdf)
        #expect(devices[1].photos.stored[sheet.id] == nil)
        #expect(devices[1].photos.stored[both] == receipt)
        #expect(devices[1].photos.storedDocuments[both] == receipt)
        // Uploaded once, though it is in two places.
        #expect(store.allFiles.values.flatMap(\.keys).filter { $0.hasPrefix("photo/") }.count == 2)
    }

    @Test func documentsTravelOnTheLocalNetworkToo() async throws {
        let a = DeviceIdentity.generate().identity, b = DeviceIdentity.generate().identity
        var members = Membership()
        var clock: UInt64 = 0
        members.add(Member(id: a.id, name: "A", publicKey: a.publicKey, platform: "ios"), clock: &clock, by: a.id)
        members.add(Member(id: b.id, name: "B", publicKey: b.publicKey, platform: "android"), clock: &clock, by: a.id)
        let membership = members
        let pdf = Data("%PDF certificate".utf8)
        let id = PhotoID.make(for: pdf)
        var cellarA = Cellar()
        let bottle = try cellarA.addBottles(of: Wine(name: "Certified"), quantity: 1)[0]
        try cellarA.attach([Attachment(id: id, name: "Certificate.pdf", contentType: "application/pdf", byteCount: pdf.count)],
                           to: .bottle(bottle.id))
        // A wrong file under a document's ID is never kept.
        let filesA = PhotoBox([:], documents: [id: pdf]), filesB = PhotoBox([:])
        let snapshotA = SyncSnapshot(cellar: cellarA, state: .starting(with: cellarA, device: a.id), membership: membership)
        let snapshotB = SyncSnapshot(cellar: Cellar(), state: SyncState(device: b.id), membership: membership)
        let (endA, endB) = Pipe.pair()
        async let sideA: SyncSnapshot = {
            let (channel, _) = try await SyncHandshake.initiate(over: endA, identity: a, membership: membership)
            return try await SyncExchange.run(over: channel, isInitiator: true, local: snapshotA, files: filesA.access)
        }()
        async let sideB: SyncSnapshot = {
            let (channel, _) = try await SyncHandshake.respond(over: endB, identity: b, membership: membership)
            return try await SyncExchange.run(over: channel, isInitiator: false, local: snapshotB, files: filesB.access)
        }()
        let (resultA, resultB) = try await (sideA, sideB)
        #expect(resultA.cellar == resultB.cellar)
        #expect(resultB.cellar.attachments(of: .bottle(bottle.id)).first?.name == "Certificate.pdf")
        #expect(filesB.storedDocuments[id] == pdf)
        #expect(filesB.stored.isEmpty)
    }
}
