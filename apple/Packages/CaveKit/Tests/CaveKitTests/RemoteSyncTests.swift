import Foundation
import Testing
@testable import CaveKit

/// A device for remote sync tests: its cellar, identity and remote state.
struct RemoteDevice {
    var identity: DeviceIdentity
    var snapshot: SyncSnapshot
    var key: CellarKey?
    var progress = RemoteProgress()
    let photos = PhotoBox([:])

    mutating func edit(_ change: (inout Cellar) throws -> Void) rethrows {
        let before = snapshot.cellar
        try change(&snapshot.cellar)
        snapshot.state.record(from: before, to: snapshot.cellar)
    }

    mutating func sync(_ store: MailboxStore) async throws {
        if key == nil {
            key = try await RemoteSync.receiveGrant(store: store, identity: identity, membership: snapshot.membership,
                                                    current: nil)?.cellarKey
        }
        guard let key else { return }
        let result = try await RemoteSync.run(store: store, identity: identity, key: key, local: snapshot,
                                              progress: progress, files: photos.access)
        if let merged = result.snapshot {
            snapshot.cellar = merged.cellar
            snapshot.state = merged.state
            snapshot.membership = merged.membership
        }
        if let newKey = result.newKey { self.key = newKey }
        snapshot.state.clock = max(snapshot.state.clock, result.clock)
        progress = result.progress
    }
}

/// Devices A, B and C of one cellar, A holding the first key.
func household(_ count: Int = 3) -> [RemoteDevice] {
    let identities = (0..<count).map { _ in DeviceIdentity.generate().identity }
    var membership = Membership()
    var clock: UInt64 = 0
    for (index, identity) in identities.enumerated() {
        membership.add(Member(id: identity.id, name: "Device \(index)", publicKey: identity.publicKey, platform: "ios",
                              inbox: identity.inboxID),
                       clock: &clock, by: identities[0].id)
    }
    let key = CellarKey.random(cellarID: membership.cellarID)
    return identities.enumerated().map { index, identity in
        var state = SyncState(device: identity.id)
        state.clock = clock
        return RemoteDevice(identity: identity, snapshot: SyncSnapshot(cellar: Cellar(), state: state, membership: membership),
                            key: index == 0 ? key : nil)
    }
}

@Suite struct RemoteSyncTests {
    @Test func firstRoundComesOneIntervalAfterTheLastOne() {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        #expect(RemoteSync.firstWait(interval: 900, lastRound: nil, now: now) == 0)
        #expect(RemoteSync.firstWait(interval: 900, lastRound: now.addingTimeInterval(-3600), now: now) == 0)
        #expect(RemoteSync.firstWait(interval: 900, lastRound: now.addingTimeInterval(-600), now: now) == 300)
        // A last round in the future (the clock moved back) waits one interval at most.
        #expect(RemoteSync.firstWait(interval: 900, lastRound: now.addingTimeInterval(600), now: now) == 900)
    }

    @Test func aChangeReachesTheOtherDevicesThroughStorage() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        // A gives the key to the others (as after pairing with remote sync on).
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: "test")
        let label = Data("label".utf8)
        let hashed = PhotoID.make(for: label), legacy = UUID().uuidString
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Château Test", photoID: hashed), quantity: 2) }
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Older photo", photoID: legacy), quantity: 1) }
        devices[0].photos.access.save(hashed, .photo, label)
        devices[0].photos.access.save(legacy, .photo, Data("old".utf8))
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        try await devices[2].sync(store)
        #expect(devices[1].snapshot.cellar == devices[0].snapshot.cellar)
        #expect(devices[2].snapshot.cellar.bottles.count == 3)
        #expect(devices[1].photos.stored[hashed] == label)
        // A photo from before sync (uppercase UUID) round-trips too.
        #expect(devices[1].photos.stored[legacy] == Data("old".utf8))
    }

    @Test func editsMadeApartMerge() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "From A"), quantity: 1) }
        try devices[1].edit { try $0.addBottles(of: Wine(name: "From B"), quantity: 1) }
        for _ in 0..<2 {
            try await devices[0].sync(store)
            try await devices[1].sync(store)
        }
        #expect(devices[0].snapshot.cellar == devices[1].snapshot.cellar)
        #expect(devices[0].snapshot.cellar.wines.count == 2)
    }

    @Test func storageHoldsOnlyCiphertext() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Very Secret Cuvée", producer: "Domaine Privé"), quantity: 1) }
        try await devices[0].sync(store)
        for (_, files) in store.allFiles {
            for (path, data) in files {
                #expect(!path.contains("secret"))
                #expect(data.range(of: Data("Secret".utf8)) == nil)
                #expect(data.range(of: Data("Domaine".utf8)) == nil)
            }
        }
    }

    @Test func aRemovedDeviceIsLeftBehind() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        let oldKey = try #require(devices[2].key)

        // A removes C and rotates the key.
        var clock = devices[0].snapshot.state.clock
        devices[0].snapshot.membership.remove(devices[2].identity.id, clock: &clock, by: devices[0].identity.id)
        devices[0].snapshot.state.clock = clock
        let newKey = CellarKey.random(cellarID: oldKey.cellarID, epoch: oldKey.epoch + 1)
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: oldKey, to: newKey,
                                    local: devices[0].snapshot, storage: nil)
        devices[0].key = newKey
        try devices[0].edit { try $0.addBottles(of: Wine(name: "After removal"), quantity: 1) }
        try await devices[0].sync(store)

        // B finds the new key and the new bottle.
        try await devices[1].sync(store)
        #expect(devices[1].key?.epoch == 2)
        #expect(devices[1].snapshot.cellar.wines.values.contains { $0.name == "After removal" })

        // C only has the old key: it learns it was removed, and sees nothing new.
        try await devices[2].sync(store)
        #expect(devices[2].key == oldKey)
        #expect(devices[2].snapshot.membership.members[devices[2].identity.id] == nil)
        #expect(!devices[2].snapshot.cellar.wines.values.contains { $0.name == "After removal" })
    }

    @Test func theRemovedDeviceDoesNotGetWhatChangedBeforeTheRotation() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        let oldKey = try #require(devices[0].key)
        // A adds a bottle while C is away, then removes C without syncing first.
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Before removal"), quantity: 1) }
        var clock = devices[0].snapshot.state.clock
        devices[0].snapshot.membership.remove(devices[2].identity.id, clock: &clock, by: devices[0].identity.id)
        devices[0].snapshot.state.clock = clock
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: oldKey,
                                    to: CellarKey.random(cellarID: oldKey.cellarID, epoch: 2),
                                    local: devices[0].snapshot, storage: nil)
        try await devices[2].sync(store)
        #expect(devices[2].snapshot.membership.members[devices[2].identity.id] == nil)
        #expect(!devices[2].snapshot.cellar.wines.values.contains { $0.name == "Before removal" })
    }

    @Test func aRotationRetriedAfterAFailureKeepsOneKey() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        let oldKey = try #require(devices[0].key)
        let pending = CellarKey.random(cellarID: oldKey.cellarID, epoch: 2)
        // The same pending key is used for the first try and the retry.
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: oldKey, to: pending,
                                    local: devices[0].snapshot, storage: nil)
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: oldKey, to: pending,
                                    local: devices[0].snapshot, storage: nil)
        devices[0].key = pending
        try await devices[1].sync(store)
        try await devices[2].sync(store)
        #expect(devices[1].key == pending)
        #expect(devices[2].key == pending)
    }

    @Test func twoKeysOfTheSameEpochSettleOnOne() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        let cellarID = devices[0].snapshot.membership.cellarID
        // Both devices turn remote sync on at once.
        let keyA = CellarKey.random(cellarID: cellarID), keyB = CellarKey.random(cellarID: cellarID)
        devices[0].key = keyA
        devices[1].key = keyB
        try await RemoteSync.grant(keyA, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        try await RemoteSync.grant(keyB, to: devices[1].snapshot.membership.active, store: store,
                                   identity: devices[1].identity, storage: nil)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "From A"), quantity: 1) }
        try devices[1].edit { try $0.addBottles(of: Wine(name: "From B"), quantity: 1) }
        for _ in 0..<2 { for index in devices.indices { try await devices[index].sync(store) } }
        #expect(devices[0].key == devices[1].key)
        #expect(devices[0].snapshot.cellar == devices[1].snapshot.cellar)
        #expect(devices[0].snapshot.cellar.wines.count == 2)
    }

    @Test func anUnreadableSnapshotDoesNotStopTheRound() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        let key = try #require(devices[0].key)
        // C's newest snapshot is garbage; B's is fine.
        let mailbox = store.mailbox(id: key.mailboxID, token: key.token)
        try await mailbox.create(SnapshotPath(device: devices[2].identity.id, clock: 999).string, data: Data("junk".utf8))
        try devices[1].edit { try $0.addBottles(of: Wine(name: "From B"), quantity: 1) }
        try await devices[1].sync(store)
        let result = try await RemoteSync.run(store: store, identity: devices[0].identity, key: key,
                                              local: devices[0].snapshot, progress: devices[0].progress,
                                              files: devices[0].photos.access)
        #expect(result.unreadable == [devices[2].identity.id])
        #expect(result.snapshot?.cellar.wines.values.contains { $0.name == "From B" } == true)
    }

    @Test func aChangeFromTheLocalNetworkIsUploadedEvenIfTheClockDidNotMove() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].sync(store) }
        // A merge brings in a stamp with a lower counter: the clock stays, the content changes.
        let before = devices[0].snapshot.state.clock
        let wine = Wine(name: "From the network")
        let bottle = Bottle(wineID: wine.id)
        devices[0].snapshot.cellar.wines[wine.id] = wine
        devices[0].snapshot.cellar.bottles[bottle.id] = bottle
        let elsewhere = UUID()
        devices[0].snapshot.state.stamps["wine:\(wine.id.uuidString.lowercased())"] = Stamp(counter: 1, device: elsewhere)
        devices[0].snapshot.state.stamps["bottle:\(bottle.id.uuidString.lowercased())"] = Stamp(counter: 1, device: elsewhere)
        #expect(devices[0].snapshot.state.clock == before)
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        #expect(devices[1].snapshot.cellar.wines[wine.id] != nil)
    }

    @Test func extraPhotosTravelToo() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        let back = Data("back label".utf8)
        let id = PhotoID.make(for: back)
        devices[0].photos.access.save(id, .photo, back)
        var wine = Wine(name: "With a back label")
        wine.extraPhotoIDs = [id]
        try devices[0].edit { try $0.addBottles(of: wine, quantity: 1) }
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        #expect(devices[1].photos.stored[id] == back)
    }

    @Test func grantsFromStrangersAreIgnored() async throws {
        let store = MemoryMailboxStore()
        let devices = household(2)
        let stranger = DeviceIdentity.generate().identity
        let fake = CellarKey.random(cellarID: devices[1].snapshot.membership.cellarID, epoch: 9)
        try await RemoteSync.grant(fake, to: [devices[1].snapshot.membership.members[devices[1].identity.id]!],
                                   store: store, identity: stranger, storage: "https://evil.example")
        let received = try await RemoteSync.receiveGrant(store: store, identity: devices[1].identity,
                                                         membership: devices[1].snapshot.membership, current: nil)
        #expect(received == nil)
    }

    @Test func aFileMovedToAnotherNameIsRejected() throws {
        let key = CellarKey.random(cellarID: UUID())
        let sealed = try Envelope.seal(Data("x".utf8), kind: .photo, path: "photo/a", key: key.dataKey)
        #expect(throws: RemoteSyncError.corrupted("photo/b")) {
            _ = try Envelope.open(sealed, kind: .photo, path: "photo/b", key: key.dataKey)
        }
        #expect(try Envelope.open(sealed, kind: .photo, path: "photo/a", key: key.dataKey) == Data("x".utf8))
    }

    @Test func aFolderWorksAsStorage() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("encave-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FolderMailboxStore(root: root)
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "In a folder"), quantity: 3) }
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        #expect(devices[1].snapshot.cellar.bottles.count == 3)
        let mailbox = store.mailbox(id: "x", token: "")
        #expect(try await mailbox.create("a/b", data: Data([1])))
        #expect(try await !mailbox.create("a/b", data: Data([2])), "files are never overwritten")
        await #expect(throws: RemoteSyncError.self) { _ = try await mailbox.create("../escape", data: Data()) }
    }

    @Test func pathsAreRestricted() {
        #expect(RelayPath.isValid("snap/0a-b/0000000000000001-ab12cd34"))
        #expect(!RelayPath.isValid("../x"))
        #expect(!RelayPath.isValid("a//b"))
        #expect(!RelayPath.isValid("/a"))
        #expect(!RelayPath.isValid("A"))
    }

    @Test func oldSnapshotsAreCleanedUp() async throws {
        let store = MemoryMailboxStore()
        var devices = household(1)
        for index in 0..<5 {
            try devices[0].edit { try $0.addBottles(of: Wine(name: "W\(index)"), quantity: 1) }
            try await devices[0].sync(store)
        }
        let key = try #require(devices[0].key)
        let snapshots = try await store.mailbox(id: key.mailboxID, token: key.token).list(prefix: "snap/")
        #expect(snapshots.count == RemoteSync.keptSnapshots)
    }

    /// Runs against a real relay when ENCAVE_RELAY_URL is set (the CI starts relay/server.mjs).
    @Test(.enabled(if: ProcessInfo.processInfo.environment["ENCAVE_RELAY_URL"] != nil))
    func syncThroughARelay() async throws {
        let url = try #require(ProcessInfo.processInfo.environment["ENCAVE_RELAY_URL"].flatMap(URL.init(string:)))
        let store = RelayMailboxStore(baseURL: url)
        try await store.check()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: url.absoluteString)
        let label = Data("relay label".utf8)
        let photo = PhotoID.make(for: label)
        devices[0].photos.access.save(photo, .photo, label)
        try devices[0].edit { try $0.addBottles(of: Wine(name: "Through the relay", photoID: photo), quantity: 2) }
        try await devices[0].sync(store)
        try await devices[1].sync(store)
        #expect(devices[1].snapshot.cellar == devices[0].snapshot.cellar)
        #expect(devices[1].photos.stored[photo] == label)
        // Someone without the key cannot read the mailbox.
        let key = try #require(devices[0].key)
        await #expect(throws: RemoteSyncError.unauthorized) {
            _ = try await store.mailbox(id: key.mailboxID, token: "wrong-token-0123456789").list(prefix: "")
        }
    }
}
