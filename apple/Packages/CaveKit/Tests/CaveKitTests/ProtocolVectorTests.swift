import Crypto
import Foundation
import Testing
@testable import CaveKit

/// Fixed inputs and their expected outputs, in `fixtures/sync/protocol-vectors.json` and
/// published in docs/SYNC.md §10. The Android app's ProtocolVectorTests.kt reads the same
/// file, so the two platforms compute the same values.
@Suite struct ProtocolVectorTests {
    struct Vectors: Decodable {
        struct Key: Decodable { var epoch: UInt32; var key: String }
        struct EnvelopeInput: Decodable { var plaintext: String; var kind: UInt8; var path: String; var nonce: String }
        struct GrantInput: Decodable {
            var path: String, ephemeral: String, recipient: String, sender: String, storage: String, nonce: String
        }
        struct Inputs: Decodable {
            var hostDevice: UUID, joinerDevice: UUID, cellar: UUID
            var hostEphemeral: String, joinerEphemeral: String, hostNonce: String, joinerNonce: String
            var initiatorIdentity: String, responderIdentity: String, rendezvousCode: String
            var cellarKey: Key, epoch2CellarKey: Key
            var envelope: EnvelopeInput
            var grant: GrantInput
        }
        struct Frame: Decodable {
            var name: String, direction: String, counter: UInt64, plaintext: String, expected: String
        }
        struct EnvelopeCase: Decodable {
            var name: String, file: String, kind: UInt8, path: String, outcome: String
        }
        var inputs: Inputs
        var pairing: [String: String]
        var frames: [Frame]
        var remote: [String: String]
        var envelopes: [EnvelopeCase]
    }

    static let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
        .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
        .deletingLastPathComponent().deletingLastPathComponent() // apple
        .deletingLastPathComponent()

    static func load() throws -> Vectors {
        let file = repository.appendingPathComponent("fixtures/sync/protocol-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func unhex(_ text: String) -> Data {
        var data = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            data.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    static func privateKey(_ hex: String) throws -> Curve25519.KeyAgreement.PrivateKey {
        try .init(rawRepresentation: unhex(hex))
    }
    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static func hex(_ key: SymmetricKey) -> String { hex(KeySchedule.bytes(key)) }
    static func cellarKey(_ key: Vectors.Key, cellar: UUID) -> CellarKey {
        CellarKey(cellarID: cellar, epoch: key.epoch, key: unhex(key.key))
    }

    @Test func vectors() throws {
        let vectors = try Self.load(), inputs = vectors.inputs
        let hostEphemeral = try Self.privateKey(inputs.hostEphemeral)
        let joinerEphemeral = try Self.privateKey(inputs.joinerEphemeral)
        let hostNonce = Self.unhex(inputs.hostNonce), joinerNonce = Self.unhex(inputs.joinerNonce)
        let hostKey = hostEphemeral.publicKey.rawRepresentation, joinerKey = joinerEphemeral.publicKey.rawRepresentation
        let commitment = KeySchedule.hash("encave-pair-commit-v1", [hostKey, hostNonce])
        let pairing = try PairingCrypto.keys(
            ephemeral: hostEphemeral, otherKey: joinerKey, hostKey: hostKey, joinerKey: joinerKey,
            hostNonce: hostNonce, joinerNonce: joinerNonce,
            host: PeerInfo(device: inputs.hostDevice, name: "", platform: ""),
            joiner: PeerInfo(device: inputs.joinerDevice, name: "", platform: ""))
        let initiatorIdentity = try Self.privateKey(inputs.initiatorIdentity)
        let responderIdentity = try Self.privateKey(inputs.responderIdentity)
        let session = try SyncHandshake.sessionKeys(
            initiator: inputs.hostDevice, responder: inputs.joinerDevice, initiatorKey: hostKey, responderKey: joinerKey,
            initiatorNonce: hostNonce, responderNonce: joinerNonce, cellar: inputs.cellar,
            ee: KeySchedule.dh(hostEphemeral, joinerKey),
            es: KeySchedule.dh(hostEphemeral, responderIdentity.publicKey.rawRepresentation),
            se: KeySchedule.dh(initiatorIdentity, joinerKey))

        let values: [String: String] = [
            "host ephemeral public": Self.hex(hostKey),
            "joiner ephemeral public": Self.hex(joinerKey),
            "commitment": Self.hex(commitment),
            "confirmation code": pairing.code,
            "pair host to joiner": Self.hex(pairing.h2j),
            "pair joiner to host": Self.hex(pairing.j2h),
            "sync auth": Self.hex(session.auth),
            "sync initiator to responder": Self.hex(session.i2r),
            "sync responder to initiator": Self.hex(session.r2i),
            "rendezvous of 123456": PairingCode(digits: inputs.rendezvousCode).rendezvous,
            "network tag of the cellar": Membership(cellarID: inputs.cellar).networkTag,
        ]
        #expect(values == vectors.pairing)

        // Frames: the nonce is the counter, each direction has its key.
        for frame in vectors.frames {
            let key = frame.direction == "i2r" ? session.i2r : session.r2i
            let box = try ChaChaPoly.seal(Data(frame.plaintext.utf8), using: key, nonce: SecureChannel.nonce(frame.counter))
            #expect(Self.hex(box.ciphertext + box.tag) == frame.expected, "\(frame.name)")
        }
        #expect(try WireMessage(type: "photos-end").encoded() == Data(#"{"type":"photos-end"}"#.utf8))
    }

    /// Remote sync (SYNC.md §8): mailbox and inbox IDs, tokens, data keys, an envelope and a grant.
    @Test func remoteVectors() throws {
        let vectors = try Self.load(), inputs = vectors.inputs
        let cellarKey = Self.cellarKey(inputs.cellarKey, cellar: inputs.cellar)
        let epoch2 = Self.cellarKey(inputs.epoch2CellarKey, cellar: inputs.cellar)
        let sender = try DeviceIdentity(id: inputs.hostDevice, privateKeyData: Self.unhex(inputs.grant.sender))
        let envelopeInput = inputs.envelope
        let envelope = try Envelope.seal(Data(envelopeInput.plaintext.utf8), kind: try #require(Envelope.Kind(rawValue: envelopeInput.kind)),
                                         path: envelopeInput.path, key: cellarKey.dataKey,
                                         nonce: ChaChaPoly.Nonce(data: Self.unhex(envelopeInput.nonce)))

        let grantInput = inputs.grant
        let grantPath = grantInput.path
        #expect(grantPath == "grant/\(inputs.cellar.uuidString.lowercased())-00000002-\(inputs.hostDevice.uuidString.lowercased())-0a1b2c3d")
        let ephemeral = try Self.privateKey(grantInput.ephemeral)
        let recipient = try DeviceIdentity(id: inputs.joinerDevice, privateKeyData: Self.unhex(grantInput.recipient))
        let grantKey = try KeyGrant.key(
            path: grantPath, ephemeralPublic: ephemeral.publicKey.rawRepresentation, recipient: recipient.publicKey,
            ee: KeySchedule.dh(ephemeral, recipient.publicKey), se: KeySchedule.dh(sender.privateKey, recipient.publicKey))
        // Built as KeyGrant.sealed does, with a fixed ephemeral key and envelope nonce.
        let grant = KeyGrant(cellarKey: epoch2, from: inputs.hostDevice, storage: grantInput.storage)
        let plaintext = try JSONEncoder.sync.encode(grant)
        let body = try Envelope.seal(plaintext, kind: .grant, path: grantPath, key: grantKey,
                                     nonce: ChaChaPoly.Nonce(data: Self.unhex(grantInput.nonce)))
        let grantFile = ephemeral.publicKey.rawRepresentation + body

        let values: [String: String] = [
            "relay token": cellarKey.token,
            "mailbox ID": cellarKey.mailboxID,
            "data key": Self.hex(cellarKey.dataKey),
            "inbox token": sender.inboxToken,
            "inbox ID": sender.inboxID,
            "envelope (whole file)": Self.hex(envelope),
            "grant key": Self.hex(grantKey),
            "epoch 2 relay token": epoch2.token,
            "epoch 2 mailbox ID": epoch2.mailboxID,
            "epoch 2 data key": Self.hex(epoch2.dataKey),
            "grant plaintext": String(decoding: plaintext, as: UTF8.self),
            "grant (whole file)": Self.hex(grantFile),
        ]
        for (name, value) in values.sorted(by: { $0.key < $1.key }) {
            #expect(vectors.remote[name] == value, "\(name): \(value)")
        }
        #expect(Set(values.keys) == Set(vectors.remote.keys))

        // The published files open: the envelope to its plaintext, the grant for a
        // recipient that knows the sender.
        let published = Self.unhex(try #require(vectors.remote["envelope (whole file)"]))
        #expect(try Envelope.open(published, kind: .photo, path: envelopeInput.path, key: cellarKey.dataKey)
                == Data(envelopeInput.plaintext.utf8))
        let members = Membership(cellarID: inputs.cellar, members: [
            inputs.hostDevice: Member(id: inputs.hostDevice, name: "Host", publicKey: sender.publicKey, platform: "ios"),
        ])
        let publishedGrant = Self.unhex(try #require(vectors.remote["grant (whole file)"]))
        #expect(try KeyGrant.open(publishedGrant, path: grantPath, recipient: recipient, members: members) == grant)
    }

    /// Envelopes that open, and ones a device must refuse (another kind, another path, a damaged tag).
    @Test func envelopeVectors() throws {
        let vectors = try Self.load()
        let key = Self.cellarKey(vectors.inputs.cellarKey, cellar: vectors.inputs.cellar).dataKey
        #expect(vectors.envelopes.filter { $0.outcome == "refused" }.count >= 4)
        for vector in vectors.envelopes {
            let kind = try #require(Envelope.Kind(rawValue: vector.kind))
            let file = Self.unhex(vector.file)
            if vector.outcome == "refused" {
                #expect(throws: RemoteSyncError.corrupted(vector.path), "\(vector.name)") {
                    _ = try Envelope.open(file, kind: kind, path: vector.path, key: key)
                }
            } else {
                #expect(try Envelope.open(file, kind: kind, path: vector.path, key: key) == Data(vector.outcome.utf8),
                        "\(vector.name)")
            }
        }
    }

    /// The tables of docs/SYNC.md §10 give the values of the fixture, and all of them.
    @Test func theDocumentationMatchesTheFixture() throws {
        let vectors = try Self.load()
        let text = try String(contentsOf: Self.repository.appendingPathComponent("docs/SYNC.md"), encoding: .utf8)
        let start = try #require(text.range(of: "\n## 10. Test vectors\n"))
        var section = String(text[start.upperBound...])
        if let end = section.range(of: "\n## ") { section = String(section[..<end.lowerBound]) }
        let rows: [[String]] = section.components(separatedBy: "\n")
            .filter { $0.hasPrefix("| ") && !$0.hasPrefix("| Value |") && !$0.hasPrefix("| Case |") }
            .map { line in
                let body = line.trimmingCharacters(in: .whitespaces).dropFirst().dropLast()
                return body.components(separatedBy: " | ").map {
                    $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "`", with: "")
                }
            }

        var expected = vectors.pairing.merging(vectors.remote) { first, _ in first }
        for frame in vectors.frames { expected[frame.name] = frame.expected }
        let pairs = rows.filter { $0.count == 2 }
        #expect(Dictionary(pairs.map { ($0[0], $0[1]) }, uniquingKeysWith: { first, _ in first }) == expected)

        let envelopes = vectors.envelopes.map { [$0.name, String($0.kind), $0.path, $0.file, $0.outcome] }
        #expect(rows.filter { $0.count == 5 } == envelopes)
        #expect(rows.count == pairs.count + envelopes.count, "every row of §10 is a value or an envelope")
    }
}
