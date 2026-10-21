import Crypto
import Foundation
import Testing
@testable import CaveKit

/// An in-memory connection: frames written on one end are read on the other.
final class Pipe: FrameTransport, @unchecked Sendable {
    private let inbox: AsyncStream<Data>
    private let continuation: AsyncStream<Data>.Continuation
    private var iterator: AsyncStream<Data>.Iterator
    weak var other: Pipe?
    /// Lets a test change frames on their way (an attacker on the network).
    var tamper: (@Sendable (Data) -> Data)?
    private let lock = NSLock()

    init() {
        (inbox, continuation) = AsyncStream.makeStream(of: Data.self)
        iterator = inbox.makeAsyncIterator()
    }

    static func pair() -> (Pipe, Pipe) {
        let a = Pipe(), b = Pipe()
        a.other = b; b.other = a
        return (a, b)
    }

    func send(_ frame: Data) async throws {
        guard let other else { throw SyncError.closed }
        other.continuation.yield(tamper?(frame) ?? frame)
    }

    func receive() async throws -> Data {
        var it = lock.withLock { iterator }
        guard let frame = await it.next() else { throw SyncError.closed }
        lock.withLock { iterator = it }
        return frame
    }

    func close() {
        continuation.finish()
        other?.continuation.finish()
    }
}

/// A man in the middle: relays between two pipes, running its own pairing with each side.
private func identity() -> DeviceIdentity { DeviceIdentity.generate().identity }

@Suite struct SyncProtocolTests {
    @Test func pairingGivesBothDevicesTheSameCodeAndTheCellar() async throws {
        let (hostEnd, joinerEnd) = Pipe.pair()
        let hostID = identity(), joinerID = identity()
        let host = PairingHost(identity: hostID, name: "iPhone", platform: "ios")
        let joiner = PairingJoiner(identity: joinerID, name: "Mac", platform: "macos", expectedCommitment: host.commitment)
        let membership = Membership()

        async let hostKeys = host.exchangeKeys(over: hostEnd)
        async let joinerKeys = joiner.exchangeKeys(over: joinerEnd)
        let (hk, jk) = try await (hostKeys, joinerKeys)
        #expect(hk.confirmationCode == jk.confirmationCode)
        #expect(hk.confirmationCode.count == 7)
        #expect(hk.peer.name == "Mac" && jk.peer.name == "iPhone")

        async let hostDone = host.finish(over: hostEnd, keys: hk, accepted: true, membership: membership)
        async let joinerDone = joiner.finish(over: joinerEnd, keys: jk, accepted: true)
        let ((_, newMember), (_, received)) = try await (hostDone, joinerDone)
        #expect(newMember.publicKey == joinerID.publicKey)
        #expect(newMember.inbox == joinerID.inboxID)
        #expect(received.members[hostID.id]?.inbox == hostID.inboxID)
        #expect(received.cellarID == membership.cellarID)
        #expect(received.members[hostID.id]?.publicKey == hostID.publicKey)
    }

    @Test func aRejectedCodeStopsThePairing() async throws {
        let (hostEnd, joinerEnd) = Pipe.pair()
        let host = PairingHost(identity: identity(), name: "A", platform: "ios")
        let joiner = PairingJoiner(identity: identity(), name: "B", platform: "ios")
        async let hostKeys = host.exchangeKeys(over: hostEnd)
        async let joinerKeys = joiner.exchangeKeys(over: joinerEnd)
        let (hk, jk) = try await (hostKeys, joinerKeys)
        let hostDone = Task { _ = try await host.finish(over: hostEnd, keys: hk, accepted: true, membership: Membership()) }
        let joinerDone = Task { _ = try await joiner.finish(over: joinerEnd, keys: jk, accepted: false) }
        await #expect(throws: SyncError.rejected) { try await hostDone.value }
        await #expect(throws: SyncError.rejected) { try await joinerDone.value }
    }

    @Test func aScannedQRCodeDetectsAnotherDevice() async throws {
        let (hostEnd, joinerEnd) = Pipe.pair()
        let real = PairingHost(identity: identity(), name: "Real", platform: "ios")
        let impostor = PairingHost(identity: identity(), name: "Impostor", platform: "ios")
        let joiner = PairingJoiner(identity: identity(), name: "Mac", platform: "macos", expectedCommitment: real.commitment)
        async let hostKeys = impostor.exchangeKeys(over: hostEnd)
        await #expect(throws: SyncError.authenticationFailed) { try await joiner.exchangeKeys(over: joinerEnd) }
        hostEnd.close()
        _ = try? await hostKeys
    }

    @Test func someoneInTheMiddleSeesDifferentCodes() async throws {
        // Host ↔ attacker ↔ joiner: the attacker pairs with each side separately.
        let (hostEnd, attackerToHost) = Pipe.pair()
        let (attackerToJoiner, joinerEnd) = Pipe.pair()
        let host = PairingHost(identity: identity(), name: "A", platform: "ios")
        let joiner = PairingJoiner(identity: identity(), name: "B", platform: "ios")
        let fakeJoiner = PairingJoiner(identity: identity(), name: "B", platform: "ios")
        let fakeHost = PairingHost(identity: identity(), name: "A", platform: "ios")
        async let h = host.exchangeKeys(over: hostEnd)
        async let fj = fakeJoiner.exchangeKeys(over: attackerToHost)
        async let fh = fakeHost.exchangeKeys(over: attackerToJoiner)
        async let j = joiner.exchangeKeys(over: joinerEnd)
        let (hostKeys, _, _, joinerKeys) = try await (h, fj, fh, j)
        // Each side shows a code; they differ unless the attacker is very lucky (1 in a million).
        #expect(hostKeys.confirmationCode != joinerKeys.confirmationCode)
    }

    @Test func pairingCodeRoundTripsThroughTheQRCode() {
        let code = PairingCode.random()
        let commitment = Data((0..<32).map { UInt8($0) })
        let parsed = PairingCode.parse(qr: code.qrPayload(commitment: commitment))
        #expect(parsed?.code == code)
        #expect(parsed?.commitment == commitment)
        #expect(PairingCode.parse(qr: "https://example.com") == nil)
        #expect(code.rendezvous.count == 16)
        #expect(code.rendezvous != PairingCode(digits: "000000").rendezvous || code.digits == "000000")
    }

    // MARK: Sync sessions

    private func members() -> (DeviceIdentity, DeviceIdentity, Membership) {
        let a = identity(), b = identity()
        var membership = Membership()
        var clock: UInt64 = 0
        membership.add(Member(id: a.id, name: "A", publicKey: a.publicKey, platform: "ios"), clock: &clock, by: a.id)
        membership.add(Member(id: b.id, name: "B", publicKey: b.publicKey, platform: "macos"), clock: &clock, by: a.id)
        return (a, b, membership)
    }

    @Test func membersOpenASessionAndSyncTheirCellars() async throws {
        let (a, b, membership) = members()
        var cellarA = Cellar()
        try cellarA.addBottles(of: Wine(name: "From A", photoID: "photo-a"), quantity: 2)
        var cellarB = Cellar()
        try cellarB.addBottles(of: Wine(name: "From B"), quantity: 1)
        let snapshotA = SyncSnapshot(cellar: cellarA, state: .starting(with: cellarA, device: a.id), membership: membership)
        let snapshotB = SyncSnapshot(cellar: cellarB, state: .starting(with: cellarB, device: b.id), membership: membership)
        let photosA = PhotoBox(["photo-a": Data("jpeg".utf8)])
        let photosB = PhotoBox([:])

        let (endA, endB) = Pipe.pair()
        async let sideA: SyncSnapshot = {
            let (channel, peer) = try await SyncHandshake.initiate(over: endA, identity: a, membership: membership)
            #expect(peer.id == b.id)
            return try await SyncExchange.run(over: channel, isInitiator: true, local: snapshotA, files: photosA.access)
        }()
        async let sideB: SyncSnapshot = {
            let (channel, peer) = try await SyncHandshake.respond(over: endB, identity: b, membership: membership)
            #expect(peer.id == a.id)
            return try await SyncExchange.run(over: channel, isInitiator: false, local: snapshotB, files: photosB.access)
        }()
        let (resultA, resultB) = try await (sideA, sideB)
        #expect(resultA.cellar == resultB.cellar)
        #expect(resultA.cellar.bottles.count == 3)
        #expect(photosB.stored["photo-a"] == Data("jpeg".utf8))
        #expect(resultA.state.device == a.id && resultB.state.device == b.id)
    }

    @Test func theRemoteKeyIsPassedOnDuringASync() async throws {
        let (a, b, membership) = members()
        let snapshotA = SyncSnapshot(cellar: Cellar(), state: SyncState(device: a.id), membership: membership)
        let snapshotB = SyncSnapshot(cellar: Cellar(), state: SyncState(device: b.id), membership: membership)
        let info = RemoteInfo(key: .random(cellarID: membership.cellarID), storage: "https://relay.example")
        let (endA, endB) = Pipe.pair()
        let sideA = Task {
            let (channel, _) = try await SyncHandshake.initiate(over: endA, identity: a, membership: membership)
            return try await SyncExchange.exchange(over: channel, isInitiator: true, local: snapshotA, files: .none, remote: info)
        }
        let (channel, _) = try await SyncHandshake.respond(over: endB, identity: b, membership: membership)
        let resultB = try await SyncExchange.exchange(over: channel, isInitiator: false, local: snapshotB, files: .none, remote: nil)
        let resultA = try await sideA.value
        #expect(resultB.remote == info)
        #expect(resultA.remote == nil)
    }

    @Test func aRemovedDeviceCannotConnect() async throws {
        let (a, b, original) = members()
        var membership = original
        var clock: UInt64 = 10
        membership.remove(b.id, clock: &clock, by: a.id)
        let (endB, endA) = Pipe.pair()
        let attempt = Task { _ = try await SyncHandshake.initiate(over: endB, identity: b, membership: original) }
        await #expect(throws: SyncError.removedDevice) {
            _ = try await SyncHandshake.respond(over: endA, identity: a, membership: membership)
        }
        // The removed device is told why.
        await #expect(throws: SyncError.removedDevice) { try await attempt.value }
    }

    @Test func aDeviceNotKnownYetIsNotToldItWasRemoved() async throws {
        let (a, b, membership) = members()
        // C was added on A, but B has not heard of it yet.
        let c = identity()
        var newer = membership
        var clock: UInt64 = 10
        newer.add(Member(id: c.id, name: "C", publicKey: c.publicKey, platform: "ios"), clock: &clock, by: a.id)
        let (endC, endB) = Pipe.pair()
        let attempt = Task { _ = try await SyncHandshake.initiate(over: endC, identity: c, membership: newer) }
        await #expect(throws: SyncError.unknownDevice) {
            _ = try await SyncHandshake.respond(over: endB, identity: b, membership: membership)
        }
        await #expect(throws: SyncError.unknownDevice) { try await attempt.value }
    }

    @Test func aDeviceWithAnotherKeyIsRefused() async throws {
        let (a, b, membership) = members()
        // Someone who knows B's device ID but not its key.
        let fakeB = try DeviceIdentity(id: b.id, privateKeyData: Curve25519.KeyAgreement.PrivateKey().rawRepresentation)
        let (endB, endA) = Pipe.pair()
        let attacker = Task { _ = try await SyncHandshake.initiate(over: endB, identity: fakeB, membership: membership) }
        let responder = Task { _ = try await SyncHandshake.respond(over: endA, identity: a, membership: membership) }
        // The impostor cannot check A's proof either (it fails first), then the session is closed.
        let attackerResult = await attacker.result
        endA.close()
        let responderResult = await responder.result
        #expect(throws: SyncError.authenticationFailed) { try attackerResult.get() }
        #expect(throws: (any Error).self) { try responderResult.get() }
    }

    @Test func aChangedFrameIsDetected() async throws {
        let (a, b, membership) = members()
        let (endA, endB) = Pipe.pair()
        let sideA = Task { try await SyncHandshake.initiate(over: endA, identity: a, membership: membership) }
        let (channelB, _) = try await SyncHandshake.respond(over: endB, identity: b, membership: membership)
        let (channelA, _) = try await sideA.value
        endA.tamper = { frame in var copy = frame; copy[copy.startIndex] ^= 1; return copy }
        try await channelA.send(WireMessage(type: "photos-end"))
        await #expect(throws: SyncError.authenticationFailed) { _ = try await channelB.receive() }
    }

    @Test func afterPairingAndTheFirstSyncBothDevicesListTheSameDevices() async throws {
        let (hostEnd, joinerEnd) = Pipe.pair()
        let hostID = identity(), joinerID = identity()
        let host = PairingHost(identity: hostID, name: "iPhone", platform: "ios")
        let joiner = PairingJoiner(identity: joinerID, name: "Mac", platform: "macos")
        // The host's cellar already lists the host, as SyncService.setUpIfNeeded does.
        var hostMembers = Membership()
        var hostState = SyncState(device: hostID.id)
        hostMembers.add(Member(id: hostID.id, name: "iPhone", publicKey: hostID.publicKey, platform: "ios"),
                        clock: &hostState.clock, by: hostID.id)
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Shared"), quantity: 1)
        hostState.record(from: Cellar(), to: cellar)

        async let hk = host.exchangeKeys(over: hostEnd)
        async let jk = joiner.exchangeKeys(over: joinerEnd)
        let (hostKeys, joinerKeys) = try await (hk, jk)
        let snapshotHost = hostMembers
        let stateHost = hostState
        let hostSide = Task { () -> SyncSnapshot in
            let (channel, member) = try await host.finish(over: hostEnd, keys: hostKeys, accepted: true, membership: snapshotHost)
            var members = snapshotHost
            var state = stateHost
            members.add(member, clock: &state.clock, by: hostID.id)
            return try await SyncExchange.run(over: channel, isInitiator: true,
                                              local: SyncSnapshot(cellar: cellar, state: state, membership: members), files: .none)
        }
        let joinerSide = Task { () -> SyncSnapshot in
            let (channel, received) = try await joiner.finish(over: joinerEnd, keys: joinerKeys, accepted: true)
            var members = received
            var state = SyncState(device: joinerID.id)
            state.clock = received.stamps.values.map(\.counter).max() ?? 0
            members.add(Member(id: joinerID.id, name: "Mac", publicKey: joinerID.publicKey, platform: "macos"),
                        clock: &state.clock, by: joinerID.id)
            return try await SyncExchange.run(over: channel, isInitiator: false,
                                              local: SyncSnapshot(cellar: Cellar(), state: state, membership: members), files: .none)
        }
        let (resultHost, resultJoiner) = try await (hostSide.value, joinerSide.value)
        #expect(resultHost.membership == resultJoiner.membership)
        #expect(Set(resultHost.membership.members.keys) == [hostID.id, joinerID.id])
        #expect(resultHost.membership.members[joinerID.id]?.publicKey == joinerID.publicKey)
        #expect(resultHost.cellar == resultJoiner.cellar)
    }

    @Test func membershipMergeKeepsRemovals() {
        let (a, b, membership) = members()
        var removed = membership
        var clock: UInt64 = 5
        removed.remove(b.id, clock: &clock, by: a.id)
        #expect(membership.merged(with: removed).members[b.id] == nil)
        #expect(removed.merged(with: membership).members[b.id] == nil)
        #expect(membership.merged(with: removed).members[a.id] != nil)
    }

    @Test func photoIDsMatchTheirContent() {
        let data = Data("label".utf8)
        let id = PhotoID.make(for: data)
        #expect(PhotoID.matches(id, data))
        #expect(!PhotoID.matches(id, Data("other".utf8)))
        #expect(PhotoID.matches("legacy-uuid", data))
    }
}

/// Photos and documents of a test device, each in its own place.
final class PhotoBox: @unchecked Sendable {
    private let lock = NSLock()
    private var photos: [String: Data]
    private var documents: [String: Data]
    init(_ photos: [String: Data], documents: [String: Data] = [:]) { self.photos = photos; self.documents = documents }
    var stored: [String: Data] { lock.withLock { photos } }
    var storedDocuments: [String: Data] { lock.withLock { documents } }
    var access: FileAccess {
        FileAccess(data: { id, kind in self.lock.withLock { kind == .photo ? self.photos[id] : self.documents[id] } },
                   save: { id, kind, data in
                       self.lock.withLock { if kind == .photo { self.photos[id] = data } else { self.documents[id] = data } }
                   })
    }
}
