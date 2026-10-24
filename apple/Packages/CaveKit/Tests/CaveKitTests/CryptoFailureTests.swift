import Crypto
import Foundation
import Testing
@testable import CaveKit

/// Frames sent are kept; frames received come from a queue the test fills.
private final class FrameQueue: FrameTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var sentFrames: [Data] = []
    private var incoming: [Data]

    init(_ incoming: [Data] = []) { self.incoming = incoming }

    var sent: [Data] { lock.withLock { sentFrames } }
    func push(_ frame: Data) { lock.withLock { incoming.append(frame) } }

    func send(_ frame: Data) async throws { lock.withLock { sentFrames.append(frame) } }

    func receive() async throws -> Data {
        let frame: Data? = lock.withLock { incoming.isEmpty ? nil : incoming.removeFirst() }
        guard let frame else { throw SyncError.closed }
        return frame
    }

    func close() {}
}

/// What encryption must refuse (docs/SYNC.md §4 and §8): damaged or moved files, forged
/// grants, snapshots under another device's name, and replayed or reflected frames.
/// Mirrors CryptoFailureTests.kt.
@Suite struct CryptoFailureTests {
    @Test func envelopeFailures() throws {
        let key = CellarKey.random(cellarID: UUID())
        let other = CellarKey.random(cellarID: key.cellarID)
        let sealed = try Envelope.seal(Data("x".utf8), kind: .photo, path: "photo/a", key: key.dataKey)
        #expect(try Envelope.open(sealed, kind: .photo, path: "photo/a", key: key.dataKey) == Data("x".utf8))

        var version = sealed
        version[version.startIndex + 4] = 2
        let hello = try Envelope.seal(Data("hello".utf8), kind: .photo, path: "photo/a", key: key.dataKey)
        let cases: [(String, Data, Envelope.Kind, SymmetricKey)] = [
            ("another key", sealed, .photo, other.dataKey),
            ("as a snapshot", sealed, .snapshot, key.dataKey),
            ("last byte dropped", Data(sealed.dropLast()), .photo, key.dataKey),
            ("another version", version, .photo, key.dataKey),
            // Header (6) and nonce and tag (28), nothing else: refused before decrypting.
            ("34 bytes", Data(sealed.prefix(34)), .photo, key.dataKey),
            // A longer file with its last byte dropped passes the size check and fails the tag.
            ("longer file, last byte dropped", Data(hello.dropLast()), .photo, key.dataKey),
        ]
        for (name, file, kind, dataKey) in cases {
            #expect(throws: RemoteSyncError.corrupted("photo/a"), "\(name)") {
                _ = try Envelope.open(file, kind: kind, path: "photo/a", key: dataKey)
            }
        }
    }

    /// A grant file built by hand, as KeyGrant.sealed does, for any path and content.
    static func sealGrant(_ grant: KeyGrant, path: String, sender: DeviceIdentity, recipient: Data) throws -> Data {
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let key = try KeyGrant.key(path: path, ephemeralPublic: ephemeral.publicKey.rawRepresentation, recipient: recipient,
                                   ee: KeySchedule.dh(ephemeral, recipient), se: KeySchedule.dh(sender.privateKey, recipient))
        let body = try Envelope.seal(JSONEncoder.sync.encode(grant), kind: .grant, path: path, key: key)
        return ephemeral.publicKey.rawRepresentation + body
    }

    @Test func grantForgedByAnotherMember() throws {
        let devices = household()
        let m = devices[0].identity, s = devices[1].identity, recipient = devices[2].identity
        let membership = devices[2].snapshot.membership
        let cellarKey = CellarKey.random(cellarID: membership.cellarID, epoch: 2)
        func openGrant(_ path: String, _ data: Data) throws -> KeyGrant {
            try KeyGrant.open(data, path: path, recipient: recipient, members: membership)
        }
        func expectCorrupted(_ path: String, _ data: Data, _ comment: Comment) {
            #expect(throws: RemoteSyncError.corrupted(path), comment) { _ = try openGrant(path, data) }
        }

        // The hand-built grant opens, so the failures below come from what was changed.
        let path = GrantPath(cellarID: membership.cellarID, epoch: 2, sender: m.id).string
        let honest = KeyGrant(cellarKey: cellarKey, from: m.id, storage: nil)
        #expect(try openGrant(path, Self.sealGrant(honest, path: path, sender: m, recipient: recipient.publicKey)) == honest)

        // M seals a grant claiming to be from S: the path names S, whose key did not seal it.
        let forged = try KeyGrant(cellarKey: cellarKey, from: s.id, storage: nil).sealed(by: m, for: recipient.publicKey)
        #expect(forged.path.contains(s.id.uuidString.lowercased()))
        expectCorrupted(forged.path, forged.data, "path names another member")
        // Under M's own name, with S in the content.
        expectCorrupted(path, try Self.sealGrant(KeyGrant(cellarKey: cellarKey, from: s.id, storage: nil), path: path,
                                                 sender: m, recipient: recipient.publicKey), "content names another member")

        // Sealed for S, opened by the recipient.
        let forS = try honest.sealed(by: m, for: s.publicKey)
        expectCorrupted(forS.path, forS.data, "sealed for another device")

        // A grant moved to another name (another epoch) no longer opens.
        let sealed = try honest.sealed(by: m, for: recipient.publicKey)
        let otherEpoch = GrantPath(cellarID: membership.cellarID, epoch: 3, sender: m.id,
                                   suffix: String(sealed.path.suffix(8))).string
        expectCorrupted(otherEpoch, sealed.data, "moved to another epoch")
        // Names that disagree with the content, sealed under those names.
        let epochName = GrantPath(cellarID: membership.cellarID, epoch: 3, sender: m.id).string
        expectCorrupted(epochName, try Self.sealGrant(honest, path: epochName, sender: m, recipient: recipient.publicKey),
                        "epoch of the name differs")
        let otherCellar = UUID()
        let cellarName = GrantPath(cellarID: otherCellar, epoch: 2, sender: m.id).string
        expectCorrupted(cellarName, try Self.sealGrant(honest, path: cellarName, sender: m, recipient: recipient.publicKey),
                        "cellar of the name differs")
        // Name and content agree, but for another cellar than the recipient's.
        let foreign = KeyGrant(cellarKey: CellarKey.random(cellarID: otherCellar, epoch: 2), from: m.id, storage: nil)
        expectCorrupted(cellarName, try Self.sealGrant(foreign, path: cellarName, sender: m, recipient: recipient.publicKey),
                        "another cellar")
    }

    @Test func snapshotUnderAnotherDevicesName() async throws {
        let store = MemoryMailboxStore()
        var devices = household()
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: nil)
        for _ in 0..<2 { for index in devices.indices { try await devices[index].sync(store) } }
        // A has read everything there is.
        try await devices[0].sync(store)
        let key = try #require(devices[0].key)
        let mailbox = store.mailbox(id: key.mailboxID, token: key.token)
        let reader = devices[0], deviceB = devices[1].identity.id, deviceC = devices[2].identity.id

        // C writes, with the right key, a snapshot of its own under B's name.
        try devices[2].edit { try $0.addBottles(of: Wine(name: "Planted by C"), quantity: 1) }
        var planted = devices[2].snapshot
        planted.state.clock += 100
        try await RemoteSync.write(planted, to: mailbox, key: key, device: deviceB)
        try await Self.expectUnreadable(store: store, reader: reader, device: deviceB)

        // B's name and device, with the membership of another cellar.
        var otherCellar = planted
        otherCellar.state.device = deviceB
        otherCellar.state.clock += 1
        otherCellar.membership.cellarID = UUID()
        try await RemoteSync.write(otherCellar, to: mailbox, key: key, device: deviceB)
        try await Self.expectUnreadable(store: store, reader: reader, device: deviceB)

        // The same snapshot under C's own name is read.
        try await RemoteSync.write(planted, to: mailbox, key: key, device: deviceC)
        let result = try await RemoteSync.run(store: store, identity: reader.identity, key: key, local: reader.snapshot,
                                              progress: reader.progress, files: reader.photos.access)
        #expect(result.unreadable == [deviceB])
        #expect(result.snapshot?.cellar.wines.values.contains { $0.name == "Planted by C" } == true)
    }

    static func expectUnreadable(store: MailboxStore, reader: RemoteDevice, device: UUID) async throws {
        let key = try #require(reader.key)
        let result = try await RemoteSync.run(store: store, identity: reader.identity, key: key, local: reader.snapshot,
                                              progress: reader.progress, files: reader.photos.access)
        #expect(result.unreadable == [device])
        #expect(result.snapshot == nil)
        #expect(result.progress.seen[device] == reader.progress.seen[device])
    }

    @Test func aReplayedFrameIsRefused() async throws {
        let i2r = SymmetricKey(size: .bits256), r2i = SymmetricKey(size: .bits256)
        let wire = FrameQueue()
        let initiator = SecureChannel(transport: wire, sendKey: i2r, receiveKey: r2i)
        try await initiator.send(WireMessage(type: "photos-end"))
        try await initiator.send(WireMessage(type: "photos-want"))
        let frames = wire.sent
        try #require(frames.count == 2)
        let first = frames[0], second = frames[1]

        // In order, both arrive.
        let inOrder = SecureChannel(transport: FrameQueue([first, second]), sendKey: r2i, receiveKey: i2r)
        #expect(try await inOrder.receive().type == "photos-end")
        #expect(try await inOrder.receive().type == "photos-want")

        // Replayed: the first frame again.
        let replayed = SecureChannel(transport: FrameQueue([first, first]), sendKey: r2i, receiveKey: i2r)
        #expect(try await replayed.receive().type == "photos-end")
        await #expect(throws: SyncError.authenticationFailed) { _ = try await replayed.receive() }
        // Reordered: frame 1 before frame 0.
        let reordered = SecureChannel(transport: FrameQueue([second, first]), sendKey: r2i, receiveKey: i2r)
        await #expect(throws: SyncError.authenticationFailed) { _ = try await reordered.receive() }
        // Reflected: the initiator's own frame fed back to it as if the responder sent it.
        wire.push(first)
        await #expect(throws: SyncError.authenticationFailed) { _ = try await initiator.receive() }
    }
}
