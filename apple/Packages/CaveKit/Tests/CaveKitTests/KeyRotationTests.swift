import Foundation
import Testing
@testable import CaveKit

extension RemoteDevice {
    /// Keeps a key as the apps do (`storeRemoteKey`): settled against the current one,
    /// starting afresh only when the key bytes change.
    mutating func keep(_ new: CellarKey) {
        let settled = RemoteSync.settled(new, previous: key, membership: snapshot.membership)
        if settled.key != key?.key { progress = RemoteProgress() }
        key = settled
    }

    /// A round as the apps run it (SyncService, SyncManager): grants still owed, then a
    /// key rotation if a removal calls for it, reading the inbox before rotating, then `sync`.
    @discardableResult
    mutating func round(_ store: MailboxStore) async throws -> RemoteSync.Result? {
        if var current = key {
            if current.removed == nil {
                keep(current)
                current = key!
            }
            if var owed = pending, owed.key.key == current.key {
                await RemoteSync.grantMissing(&owed, store: store, identity: identity, local: snapshot, storage: nil)
                pending = owed.failed.isEmpty ? nil : owed
            }
            var next = try RemoteSync.nextRotation(key: current, pending: pending, membership: snapshot.membership,
                                                   device: identity.id)
            if next != nil, let grant = try await RemoteSync.receiveGrant(store: store, identity: identity,
                                                                          membership: snapshot.membership,
                                                                          current: current) {
                keep(grant.cellarKey)
                current = key!
                next = try RemoteSync.nextRotation(key: current, pending: pending, membership: snapshot.membership,
                                                   device: identity.id)
            }
            if var next {
                pending = next
                do {
                    try await RemoteSync.rotate(store: store, identity: identity, from: current, to: &next,
                                                local: snapshot, storage: nil)
                } catch {
                    pending = next
                    throw error
                }
                keep(next.key)
                pending = next.failed.isEmpty ? nil : next
            } else if let kept = pending, kept.key.key != current.key {
                pending = nil
            }
        }
        let result = try await sync(store)
        if let key { keep(key) }
        return result
    }

    mutating func remove(_ other: RemoteDevice) {
        if let key, key.removed == nil { keep(key) }
        var clock = snapshot.state.clock
        snapshot.membership.remove(other.identity.id, clock: &clock, by: identity.id)
        snapshot.state.clock = clock
    }

    /// The membership learned from another device on the local network.
    mutating func meet(_ other: RemoteDevice) {
        snapshot.membership = snapshot.membership.merged(with: other.snapshot.membership)
        snapshot.state.clock = max(snapshot.state.clock, other.snapshot.state.clock)
    }

    /// Every key this device could open from its inbox.
    func grantedKeys(_ store: MemoryMailboxStore, membership: Membership) -> [CellarKey] {
        (store.allFiles[identity.inboxID] ?? [:]).compactMap { path, data in
            try? KeyGrant.open(data, path: path, recipient: identity, members: membership).cellarKey
        }
    }
}

/// Storage whose mailboxes are full (`full`); inboxes still take grants.
final class FullMailboxStore: MailboxStore, @unchecked Sendable {
    let base = MemoryMailboxStore()
    private let lock = NSLock()
    private var _full = true
    var full: Bool {
        get { lock.withLock { _full } }
        set { lock.withLock { _full = newValue } }
    }

    func mailbox(id: String, token: String) -> Mailbox { Box(store: self, base: base.mailbox(id: id, token: token)) }
    func inbox(id: String, token: String) -> Mailbox { base.inbox(id: id, token: token) }

    struct Box: Mailbox {
        let store: FullMailboxStore
        let base: Mailbox
        func list(prefix: String) async throws -> [String] { try await base.list(prefix: prefix) }
        func get(_ path: String) async throws -> Data? { try await base.get(path) }
        func create(_ path: String, data: Data) async throws -> Bool {
            if store.full { throw RemoteSyncError.quotaExceeded }
            return try await base.create(path, data: data)
        }
        func delete(_ path: String) async throws { try await base.delete(path) }
    }
}

/// Storage where the inboxes in `refused` are full; everything else works.
final class RefusingInboxStore: MailboxStore, @unchecked Sendable {
    let base = MemoryMailboxStore()
    private let lock = NSLock()
    private var _refused: Set<String> = []
    var refused: Set<String> {
        get { lock.withLock { _refused } }
        set { lock.withLock { _refused = newValue } }
    }

    func mailbox(id: String, token: String) -> Mailbox { base.mailbox(id: id, token: token) }
    func inbox(id: String, token: String) -> Mailbox { Box(store: self, id: id, base: base.inbox(id: id, token: token)) }

    struct Box: Mailbox {
        let store: RefusingInboxStore
        let id: String
        let base: Mailbox
        func list(prefix: String) async throws -> [String] { try await base.list(prefix: prefix) }
        func get(_ path: String) async throws -> Data? { try await base.get(path) }
        func create(_ path: String, data: Data) async throws -> Bool {
            if store.refused.contains(id) { throw RemoteSyncError.quotaExceeded }
            return try await base.create(path, data: data)
        }
        func delete(_ path: String) async throws { try await base.delete(path) }
    }
}

/// Rotations reported while a rotation runs.
actor RotationLog {
    var all: [PendingRotation] = []
    func add(_ pending: PendingRotation) { all.append(pending) }
}

/// Key rotation after a removal (docs/SYNC.md §8, Removing a device).
@Suite struct KeyRotationTests {
    /// All devices on the first key, through `store`.
    static func start(_ count: Int, store: MailboxStore) async throws -> [RemoteDevice] {
        var devices = household(count)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for index in devices.indices { try await devices[index].round(store) }
        return devices
    }

    @Test func anyKeyHolderRotatesAfterARemoval() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(3, store: store)
        let oldKey = try #require(devices[2].key)
        // A removes D while it has no storage (it still holds the key from the local network).
        devices[0].remove(devices[2])
        // B gets the membership from A over the local network, then runs a round.
        devices[1].meet(devices[0])
        try devices[1].edit { try $0.addBottles(of: Wine(name: "After removal"), quantity: 1) }
        try await devices[1].round(store)
        let newKey = try #require(devices[1].key)
        #expect(newKey.epoch == 2)
        #expect(newKey.removed == [devices[2].identity.id])

        // D cannot open B's next snapshot, and was given no new key.
        let mailbox = store.mailbox(id: newKey.mailboxID, token: newKey.token)
        let snapshots = try await mailbox.list(prefix: "snap/\(devices[1].identity.id.lowercased)/")
        let newest = try #require(snapshots.max())
        let file = try #require(try await mailbox.get(newest))
        #expect(throws: RemoteSyncError.self) {
            try Envelope.open(file, kind: .snapshot, path: newest, key: oldKey.dataKey)
        }
        try await devices[2].round(store)
        #expect(devices[2].key == oldKey)
        #expect(!devices[2].snapshot.cellar.wines.values.contains { $0.name == "After removal" })

        // A follows B without rotating again: the new key leaves D out.
        try await devices[0].round(store)
        #expect(devices[0].key == newKey)
        #expect(devices[0].snapshot.cellar.wines.values.contains { $0.name == "After removal" })
        try await devices[1].round(store)
        #expect(devices[1].key == newKey)
    }

    @Test func aDeviceThatLeftCallsForNoRotation() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(3, store: store)
        let key = try #require(devices[0].key)
        // C leaves by itself: a deletion stamp of its own.
        var clock = devices[2].snapshot.state.clock
        devices[2].snapshot.membership.remove(devices[2].identity.id, clock: &clock, by: devices[2].identity.id)
        devices[2].snapshot.state.clock = clock
        devices[0].meet(devices[2])
        #expect(try RemoteSync.nextRotation(key: key, pending: nil, membership: devices[0].snapshot.membership,
                                            device: devices[0].identity.id) == nil)
    }

    @Test func aRetriedRotationLeavesOneGrant() async throws {
        let store = FullMailboxStore()
        store.full = false
        var devices = try await Self.start(3, store: store)
        devices[0].remove(devices[2])
        store.full = true
        // Each try leaves the grant, then fails to write the snapshots.
        for _ in 0..<5 {
            do {
                try await devices[0].round(store)
                Issue.record("The round should fail")
            } catch {
                #expect(error as? RemoteSyncError == .quotaExceeded)
            }
        }
        let inbox = devices[1].identity.inboxID
        #expect(store.base.allFiles[inbox]?.keys.filter { $0.contains("-00000002-") }.count == 1)
        store.full = false
        try await devices[0].round(store)
        try await devices[1].round(store)
        #expect(devices[1].key == devices[0].key)
        #expect(devices[1].key?.epoch == 2)
    }

    @Test func aRecipientKeepsAtMostOneGrantOfAnEpoch() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(2, store: store)
        let next = CellarKey.random(cellarID: devices[0].key!.cellarID, epoch: 2)
        // Rotations retried by a build that did not remember who has the key.
        for _ in 0..<5 {
            try await RemoteSync.grant(next, to: devices[0].snapshot.membership.active, store: store,
                                       identity: devices[0].identity, storage: nil)
        }
        let inbox = devices[1].identity.inboxID
        #expect(store.allFiles[inbox]?.keys.filter { $0.contains("-00000002-") }.count == 5)
        try await devices[1].sync(store)
        #expect(devices[1].key == next)
        #expect((store.allFiles[inbox]?.count ?? 0) <= 1)
        try await devices[1].sync(store)
        #expect((store.allFiles[inbox]?.count ?? 0) == 0)
    }

    @Test func concurrentRemovalsEndOnAKeyNeitherRemovedDeviceHas() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(4, store: store)
        let start = try #require(devices[0].key)
        let membership = devices[2].snapshot.membership
        // A removes D and B removes E at the same time; A's key wins the tie on epoch 2.
        devices[0].remove(devices[2])
        devices[1].remove(devices[3])
        var keys = [CellarKey.random(cellarID: start.cellarID, epoch: 2),
                    CellarKey.random(cellarID: start.cellarID, epoch: 2)]
        if !keys[0].supersedes(keys[1]) { keys.swapAt(0, 1) }
        for index in 0..<2 {
            var next = try #require(try RemoteSync.nextRotation(key: start, pending: PendingRotation(key: keys[index]),
                                                                membership: devices[index].snapshot.membership,
                                                                device: devices[index].identity.id))
            try await RemoteSync.rotate(store: store, identity: devices[index].identity, from: start, to: &next,
                                        local: devices[index].snapshot, storage: nil)
            devices[index].key = next.key
            devices[index].progress = RemoteProgress()
        }
        #expect(devices[0].key?.removed == [devices[2].identity.id])
        #expect(devices[1].key?.removed == [devices[3].identity.id])
        for _ in 0..<3 {
            for index in 0..<2 { try await devices[index].round(store) }
        }
        let final = try #require(devices[0].key)
        #expect(devices[1].key == final)
        #expect(final.epoch == 3)
        #expect(Set(final.removed ?? []) == [devices[2].identity.id, devices[3].identity.id])
        for removed in devices[2...] {
            #expect(!removed.grantedKeys(store, membership: membership).contains { $0.key == final.key })
        }
    }

    @Test func aStartedRotationLosingToTheAdoptedKeyIsDropped() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(3, store: store)
        let start = try #require(devices[1].key)
        devices[0].remove(devices[2])
        devices[1].meet(devices[0])
        // A rotates; B had started its own rotation, given to A only, and failed.
        try await devices[0].round(store)
        let winner = try #require(devices[0].key)
        var losing = CellarKey.random(cellarID: start.cellarID, epoch: 2)
        while !winner.supersedes(losing) { losing = CellarKey.random(cellarID: start.cellarID, epoch: 2) }
        devices[1].pending = PendingRotation(key: losing, granted: [devices[0].identity.id])
        // B adopts A's key, which leaves D out: no second rotation.
        try await devices[1].round(store)
        #expect(devices[1].key == winner)
        #expect(devices[1].pending == nil)
        try await devices[0].round(store)
        #expect(devices[0].key == winner)
    }

    @Test func aStartedRotationGivenToNobodyIsDroppedOnTheSameEpoch() throws {
        var devices = household(3)
        devices[0].remove(devices[2])
        let key = CellarKey(cellarID: devices[0].snapshot.membership.cellarID, epoch: 2,
                            key: Data(repeating: 1, count: 32), removed: [devices[2].identity.id])
        for _ in 0..<8 {
            let started = PendingRotation(key: CellarKey.random(cellarID: key.cellarID, epoch: 2))
            #expect(try RemoteSync.nextRotation(key: key, pending: started, membership: devices[0].snapshot.membership,
                                                device: devices[0].identity.id) == nil)
        }
    }

    @Test func keysFromAnEarlierBuildCallForNoRotation() async throws {
        let store = MemoryMailboxStore()
        var devices = household(4)
        // A removed D before the update (and rotated then); the key lists no removals.
        var clock = devices[0].snapshot.state.clock
        devices[0].snapshot.membership.remove(devices[3].identity.id, clock: &clock, by: devices[0].identity.id)
        devices[0].snapshot.state.clock = clock
        for index in 1..<3 { devices[index].meet(devices[0]) }
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        let first = try #require(devices[0].key)
        #expect(first.removed == nil)
        for _ in 0..<3 {
            for index in 0..<3 { try await devices[index].round(store) }
        }
        for index in 0..<3 {
            #expect(devices[index].key?.key == first.key)
            #expect(devices[index].key?.removed == [devices[3].identity.id])
        }
        // A build before the list sends the same key back without it: the list stays.
        var stripped = first
        stripped.removed = nil
        let kept = RemoteSync.settled(stripped, previous: devices[1].key, membership: devices[1].snapshot.membership)
        #expect(kept.removed == [devices[3].identity.id])
        // Two lists for the same key bytes join.
        var other = first
        other.removed = [devices[2].identity.id]
        let joined = RemoteSync.settled(other, previous: devices[1].key, membership: nil)
        #expect(Set(joined.removed ?? []) == [devices[2].identity.id, devices[3].identity.id])
    }

    @Test func anEarlierBuildRotatingDoesNotStartAStorm() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(4, store: store)
        let start = try #require(devices[0].key)
        // O (an earlier build) removes X and rotates the earlier way: a key without a list,
        // given to N, then it fails before M.
        var clock = devices[0].snapshot.state.clock
        devices[0].snapshot.membership.remove(devices[3].identity.id, clock: &clock, by: devices[0].identity.id)
        devices[0].snapshot.state.clock = clock
        let old = CellarKey.random(cellarID: start.cellarID, epoch: 2)
        let n = try #require(devices[0].snapshot.membership.members[devices[1].identity.id])
        try await RemoteSync.grant(old, to: [n], store: store, identity: devices[0].identity, storage: nil)
        devices[1].meet(devices[0])
        try await devices[1].round(store)
        #expect(devices[1].key?.key == old.key)
        #expect(devices[1].key?.removed == [devices[3].identity.id])
        // O retries and finishes the earlier way; N and M settle on its key.
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: start, to: old,
                                    local: devices[0].snapshot, storage: nil)
        devices[0].key = old
        for _ in 0..<3 {
            for index in 1..<3 { try await devices[index].round(store) }
        }
        #expect(devices[1].key?.key == old.key)
        #expect(devices[2].key?.key == old.key)
        #expect(devices[2].key?.epoch == 2)
    }

    @Test func aDeviceWhoseGrantFailsDoesNotStopTheRotation() async throws {
        let store = RefusingInboxStore()
        var devices = try await Self.start(4, store: store)
        devices[0].remove(devices[3])
        store.refused = [devices[2].identity.inboxID]
        try devices[0].edit { try $0.addBottles(of: Wine(name: "After removal"), quantity: 1) }
        try await devices[0].round(store)
        let newKey = try #require(devices[0].key)
        #expect(newKey.epoch == 2)
        #expect(devices[0].pending?.failed == [devices[2].identity.id])
        // B moves on and syncs; C is tried again once its inbox has room.
        try await devices[1].round(store)
        #expect(devices[1].key == newKey)
        #expect(devices[1].snapshot.cellar.wines.values.contains { $0.name == "After removal" })
        store.refused = []
        try await devices[0].round(store)
        #expect(devices[0].pending == nil)
        try await devices[2].round(store)
        #expect(devices[2].key == newKey)
    }

    @Test func eachGrantIsReportedAsTheRotationGoes() async throws {
        let store = MemoryMailboxStore()
        var devices = try await Self.start(3, store: store)
        devices[0].remove(devices[2])
        let key = try #require(devices[0].key)
        var next = try #require(try RemoteSync.nextRotation(key: key, pending: nil,
                                                            membership: devices[0].snapshot.membership,
                                                            device: devices[0].identity.id))
        let log = RotationLog()
        try await RemoteSync.rotate(store: store, identity: devices[0].identity, from: key, to: &next,
                                    local: devices[0].snapshot, storage: nil) { await log.add($0) }
        let all = await log.all
        #expect(all.map(\.granted) == [[devices[1].identity.id]])
        #expect(next.granted == [devices[1].identity.id])
    }

    @Test func aStorageChangeForgetsWhoWasGiven() throws {
        var pending = PendingRotation(key: CellarKey.random(cellarID: UUID(), epoch: 2), granted: [UUID()],
                                      failed: [UUID()])
        pending.storageChanged()
        #expect(pending.granted.isEmpty && pending.failed.isEmpty)
        // A rotation kept before `failed` existed still reads.
        var object = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(pending)) as? [String: Any])
        object["failed"] = nil
        let older = try JSONSerialization.data(withJSONObject: object)
        #expect(try JSONDecoder().decode(PendingRotation.self, from: older).key == pending.key)
    }

    @Test func theRemovedListHasAtMost64Devices() {
        var membership = Membership()
        var clock: UInt64 = 0
        let me = UUID()
        for _ in 0..<70 { membership.remove(UUID(), clock: &clock, by: me) }
        #expect(RemoteSync.shutOut(membership).count == RemoteSync.maxRemoved)
        let list = RemoteSync.removedList(RemoteSync.shutOut(membership), (0..<20).map { _ in UUID() })
        #expect(list.count == RemoteSync.maxRemoved)
        #expect(Set(list).isSuperset(of: RemoteSync.shutOut(membership)))
    }

    @Test func aRemovedDeviceNeverRotates() throws {
        var devices = household(3)
        devices[0].remove(devices[2])
        devices[2].meet(devices[0])
        let key = CellarKey(cellarID: devices[0].snapshot.membership.cellarID, epoch: 1,
                            key: Data(repeating: 1, count: 32), removed: [])
        #expect(try RemoteSync.nextRotation(key: key, pending: nil, membership: devices[2].snapshot.membership,
                                            device: devices[2].identity.id) == nil)
        #expect(try RemoteSync.nextRotation(key: key, pending: nil, membership: devices[0].snapshot.membership,
                                            device: devices[0].identity.id) != nil)
    }

    @Test func noRotationPastTheLastEpoch() throws {
        var devices = household(3)
        devices[0].remove(devices[2])
        let last = CellarKey(cellarID: devices[0].snapshot.membership.cellarID, epoch: .max,
                             key: Data(repeating: 1, count: 32), removed: [])
        #expect(throws: RemoteSyncError.corrupted("epoch")) {
            try RemoteSync.nextRotation(key: last, pending: nil, membership: devices[0].snapshot.membership,
                                        device: devices[0].identity.id)
        }
    }

    @Test func theRemovedListTravelsWithTheKey() throws {
        let removed = UUID(uuidString: "44444444-4444-4444-8444-444444444444")!
        let key = CellarKey(cellarID: UUID(uuidString: "33333333-3333-4333-8333-333333333333")!, epoch: 2,
                            key: Data(repeating: 1, count: 32), removed: [removed])
        let json = String(decoding: try JSONEncoder.sync.encode(key), as: UTF8.self)
        #expect(json == #"{"cellarID":"33333333-3333-4333-8333-333333333333","epoch":2,"#
            + #""key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","removed":["44444444-4444-4444-8444-444444444444"]}"#)
        #expect(try JSONDecoder.sync.decode(CellarKey.self, from: Data(json.utf8)) == key)
        // A key from a build before the list existed still reads.
        var old = key
        old.removed = nil
        let oldJSON = #"{"cellarID":"33333333-3333-4333-8333-333333333333","epoch":2,"key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}"#
        #expect(try JSONDecoder.sync.decode(CellarKey.self, from: Data(oldJSON.utf8)) == old)
    }
}
