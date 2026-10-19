import Foundation
import Testing
@testable import CaveKit

/// A store whose inboxes refuse every new file, as if each name were taken.
struct FullInboxStore: MailboxStore {
    let base = MemoryMailboxStore()
    func mailbox(id: String, token: String) -> Mailbox { base.mailbox(id: id, token: token) }
    func inbox(id: String, token: String) -> Mailbox { Taken() }

    struct Taken: Mailbox {
        func list(prefix: String) async throws -> [String] { [] }
        func get(_ path: String) async throws -> Data? { nil }
        func create(_ path: String, data: Data) async throws -> Bool { false }
        func delete(_ path: String) async throws {}
    }
}

/// Counts the photos read in full.
final class CountingPhotos: @unchecked Sendable {
    private let lock = NSLock()
    private var photos: [String: Data]
    private var reads = 0
    init(_ photos: [String: Data]) { self.photos = photos }
    var readCount: Int { lock.withLock { reads } }
    var access: FileAccess {
        FileAccess(data: { id, _ in self.lock.withLock { self.reads += 1; return self.photos[id] } },
                   save: { id, _, data in self.lock.withLock { self.photos[id] = data } },
                   exists: { id, _ in self.lock.withLock { self.photos[id] != nil } })
    }
}

@Suite struct GrantNameTests {
    @Test func grantNamesHaveARandomPartAndParse() throws {
        let sender = DeviceIdentity.generate().identity, recipient = DeviceIdentity.generate().identity
        let key = CellarKey.random(cellarID: UUID(), epoch: 3)
        let grant = KeyGrant(cellarKey: key, from: sender.id, storage: nil)
        let first = try grant.sealed(by: sender, for: recipient.publicKey).path
        let second = try grant.sealed(by: sender, for: recipient.publicKey).path
        #expect(first != second)
        let name = try #require(GrantPath(first))
        #expect(name.cellarID == key.cellarID && name.epoch == 3 && name.sender == sender.id)
        #expect(RelayPath.isValid(first))
        // The old form without a random part, and other near misses, are not grants.
        let cellar = key.cellarID.uuidString.lowercased(), from = sender.id.uuidString.lowercased()
        #expect(GrantPath("grant/\(cellar)-00000003-\(from)") == nil)
        #expect(GrantPath("grant/\(cellar)-0000003-\(from)-0a1b2c3d") == nil)
        #expect(GrantPath("grant/\(cellar)-00000003-\(from)-0A1B2C3D") == nil)
        #expect(GrantPath("grant/\(cellar.uppercased())-00000003-\(from)-0a1b2c3d") == nil)
        #expect(GrantPath("grant/\(cellar)-00000003-\(from)-0a1b2c3d")?.epoch == 3)
    }

    @Test func aSquatterCannotBlockAGrant() async throws {
        let store = MemoryMailboxStore()
        let devices = household(2)
        let key = try #require(devices[0].key)
        let inbox = store.inbox(id: devices[1].identity.inboxID, token: "")
        // Someone who knows the inbox, the cellar and the sender fills the old grant name with junk.
        let cellar = key.cellarID.uuidString.lowercased(), sender = devices[0].identity.id.uuidString.lowercased()
        try await inbox.create("grant/\(cellar)-00000001-\(sender)", data: Data(repeating: 7, count: 100))
        try await RemoteSync.grant(key, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        let received = try await RemoteSync.receiveGrant(store: store, identity: devices[1].identity,
                                                         membership: devices[1].snapshot.membership, current: nil)
        #expect(received?.cellarKey == key)
        // The junk is deleted; the grant stays until a newer key replaces it.
        #expect(try await inbox.list(prefix: "grant/").count == 1)
    }

    @Test func aGrantThatCannotBeCreatedIsAnError() async throws {
        let devices = household(2)
        await #expect(throws: RemoteSyncError.unavailable("grant not created")) {
            try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active,
                                       store: FullInboxStore(), identity: devices[0].identity, storage: nil)
        }
    }

    @Test func grantsOfUnknownDevicesAreKeptOnlyAFew() async throws {
        let store = MemoryMailboxStore()
        let devices = household(2)
        let recipient = devices[1]
        let inbox = store.inbox(id: recipient.identity.inboxID, token: "")
        let cellar = recipient.snapshot.membership.cellarID
        for _ in 0..<(RemoteSync.maxWaitingGrants + 5) {
            let path = GrantPath(cellarID: cellar, epoch: 2, sender: UUID()).string
            try await inbox.create(path, data: Data(repeating: 1, count: 200))
        }
        _ = try await RemoteSync.receiveGrant(store: store, identity: recipient.identity,
                                              membership: recipient.snapshot.membership, current: nil)
        #expect(try await inbox.list(prefix: "grant/").count == RemoteSync.maxWaitingGrants)
    }

    @Test func aGrantOverFourKilobytesIsDeleted() async throws {
        let store = MemoryMailboxStore()
        let devices = household(2)
        let inbox = store.inbox(id: devices[1].identity.inboxID, token: "")
        let path = GrantPath(cellarID: devices[1].snapshot.membership.cellarID, epoch: 1, sender: devices[0].identity.id).string
        try await inbox.create(path, data: Data(repeating: 1, count: KeyGrant.maxSize + 1))
        _ = try await RemoteSync.receiveGrant(store: store, identity: devices[1].identity,
                                              membership: devices[1].snapshot.membership, current: nil)
        #expect(try await inbox.list(prefix: "grant/").isEmpty)
    }
}

@Suite struct ProtocolBoundsTests {
    @Test func clocksOverTwoToThe52AreRefused() throws {
        try SyncState.check(SyncState(clock: 1 << 52))
        #expect(throws: SyncError.self) { try SyncState.check(SyncState(clock: (1 << 52) + 1)) }
        var members = Membership()
        var clock: UInt64 = 1 << 52
        members.add(Member(id: UUID(), name: "M", publicKey: Data(count: 32), platform: "ios"), clock: &clock, by: UUID())
        #expect(throws: SyncError.self) { try SyncState.check(SyncState(), membership: members) }
    }

    @Test func aSnapshotMustHaveTheClockOfItsName() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        let key = try #require(devices[0].key)
        // B's snapshot of clock 5, written under a name that says 900.
        var forged = devices[1].snapshot
        forged.state.clock = 5
        let path = SnapshotPath(device: devices[1].identity.id, clock: 900).string
        let mailbox = store.mailbox(id: key.mailboxID, token: key.token)
        try await mailbox.create(path, data: Envelope.seal(RemoteSync.encodeSnapshot(forged), kind: .snapshot, path: path,
                                                           key: key.dataKey))
        let result = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                              local: devices[0].snapshot, progress: devices[0].progress,
                                              files: devices[0].photos.access)
        #expect(result.unreadable == [devices[1].identity.id])
    }

    @Test func aSnapshotIsNeverWrittenTwiceWithOneClock() async throws {
        let store = MemoryMailboxStore()
        let devices = household(2)
        let key = try #require(devices[0].key)
        let first = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                             local: devices[0].snapshot, progress: RemoteProgress(), files: .none)
        // The progress of that round was lost (the app stopped before saving it).
        let second = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                              local: devices[0].snapshot, progress: RemoteProgress(), files: .none)
        #expect(second.clock == first.clock + 1)
        let mine = try await store.mailbox(id: key.mailboxID, token: key.token)
            .list(prefix: "snap/\(devices[0].identity.id.uuidString.lowercased())/").compactMap(SnapshotPath.init)
        #expect(Set(mine.map(\.clock)).count == mine.count)
    }

    @Test func aKeyGrantedWithAnotherStorageEndsTheRound() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        let old = try #require(devices[0].key)
        devices[1].key = old
        let new = CellarKey.random(cellarID: old.cellarID, epoch: 2)
        try await RemoteSync.grant(new, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: "https://new.example/")
        let result = try await RemoteSync.run(store: store, identity: devices[1].identity, key: old,
                                              local: devices[1].snapshot, progress: RemoteProgress(), files: .none,
                                              storage: "https://old.example/")
        #expect(result.newKey == new && result.storage == "https://new.example/")
        #expect(result.progress.uploadedClock == nil && result.progress.epoch == 2)
        #expect(store.allFiles[new.mailboxID] == nil, "nothing written under the new key on the old storage")
        // On the same storage, the round goes on with the new key.
        let same = try await RemoteSync.run(store: store, identity: devices[1].identity, key: old,
                                            local: devices[1].snapshot, progress: RemoteProgress(), files: .none,
                                            storage: "https://new.example/")
        #expect(same.newKey == new && same.progress.uploadedClock != nil)
    }

    @Test func aCellarKeyIsThirtyTwoBytes() throws {
        let cellar = UUID().uuidString
        let short = Data(#"{"cellarID":"\#(cellar)","epoch":1,"key":"\#(Data(count: 16).base64EncodedString())"}"#.utf8)
        #expect(throws: DecodingError.self) { _ = try JSONDecoder.sync.decode(CellarKey.self, from: short) }
        let right = Data(#"{"cellarID":"\#(cellar)","epoch":1,"key":"\#(Data(count: 32).base64EncodedString())"}"#.utf8)
        #expect(try JSONDecoder.sync.decode(CellarKey.self, from: right).key.count == 32)
    }

    @Test func pairingCodesAreASCIIDigits() {
        #expect(!PairingCode(digits: "١٢٣٤٥٦").isValid)
        #expect(PairingCode(digits: "12 34 56").digits == "123456")
        #expect(PairingCode(digits: "①23456").digits == "23456")
    }

    @Test func racksFromElsewhereAreCutToADrawableSize() throws {
        let json = #"{"id":"\#(UUID().uuidString)","name":"H","rows":2000000000,"columns":-5,"binCapacity":99999,"rowColumns":[7,100000]}"#
        let rack = try JSONDecoder().decode(Rack.self, from: Data(json.utf8))
        #expect(rack.rows == Rack.maxSize && rack.columns == 1 && rack.binCapacity == Rack.maxSize)
        #expect(rack.rowColumns == [7, Rack.maxSize])
        #expect(Rack(name: "Big", rows: 500, columns: 500).cellCount == Rack.maxSize * Rack.maxSize)
    }

    @Test func roundsCheckWhetherPhotosExistWithoutReadingThem() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        let label = Data("label".utf8)
        let id = PhotoID.make(for: label)
        let photos = CountingPhotos([id: label])
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Pictured", photoID: id), quantity: 1) }
        let key = try #require(devices[0].key)
        let first = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                             local: devices[0].snapshot, progress: RemoteProgress(), files: photos.access)
        #expect(photos.readCount == 1, "read once, to upload it")
        _ = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                     local: devices[0].snapshot, progress: first.progress, files: photos.access)
        #expect(photos.readCount == 1, "a round with nothing new reads no photo")
    }
}

@Suite struct StrictDecodingTests {
    let wine = UUID().uuidString, id = UUID().uuidString

    @Test func anUnknownDiscoverySettingFails() throws {
        let known = #"{"id":"\#(id)","wineID":"\#(wine)","date":1,"setting":"shop"}"#
        #expect(try JSONDecoder.sync.decode(Discovery.self, from: Data(known.utf8)).setting == .shop)
        let unknown = known.replacingOccurrences(of: "shop", with: "boat")
        #expect(throws: DecodingError.self) { _ = try JSONDecoder.sync.decode(Discovery.self, from: Data(unknown.utf8)) }
    }

    @Test func winesAreNormalizedLikeInKotlin() throws {
        var wine = Wine(name: "W")
        wine.extraPhotoIDs = ["sha256-a", "sha256-b", "sha256-a"]
        #expect(wine.extraPhotoIDs == ["sha256-a", "sha256-b"])
        wine.extraPhotoIDs = []
        #expect(wine.extraPhotoIDs == nil)
        let json = #"{"id":"\#(id)","name":"W","grapes":[],"barcodes":[],"tags":[" Gift ","gift",""],"extraPhotoIDs":["x","y","x"]}"#
        let read = try JSONDecoder.sync.decode(Wine.self, from: Data(json.utf8))
        #expect(read.extraPhotoIDs == ["x", "y"])
        #expect(read.tags == ["Gift"])
        #expect(read.barcodes == nil)
        let empty = try JSONDecoder.sync.decode(Wine.self, from: Data(#"{"id":"\#(id)","name":"W","extraPhotoIDs":[]}"#.utf8))
        #expect(empty.extraPhotoIDs == nil)
    }

    @Test func missingDatesAreNotToday() {
        let discovery = #"{"id":"\#(id)","wineID":"\#(wine)"}"#
        #expect(throws: DecodingError.self) { _ = try JSONDecoder.sync.decode(Discovery.self, from: Data(discovery.utf8)) }
        let bottle = #"{"id":"\#(id)","wineID":"\#(wine)","status":"inCellar","sizeML":750}"#
        #expect(throws: DecodingError.self) { _ = try JSONDecoder.sync.decode(Bottle.self, from: Data(bottle.utf8)) }
    }
}
