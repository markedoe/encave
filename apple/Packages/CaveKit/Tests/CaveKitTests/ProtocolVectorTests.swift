import Crypto
import Foundation
import Testing
@testable import CaveKit

/// Fixed inputs and their expected outputs, published in docs/SYNC.md so other
/// implementations (the Android app) can check they compute the same values.
@Suite struct ProtocolVectorTests {
    static func bytes(_ start: UInt8) -> Data { Data((0..<32).map { start &+ UInt8($0) }) }
    static func key(_ start: UInt8) throws -> Curve25519.KeyAgreement.PrivateKey {
        try .init(rawRepresentation: bytes(start))
    }
    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static func hex(_ key: SymmetricKey) -> String { hex(KeySchedule.bytes(key)) }

    static let hostDevice = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
    static let joinerDevice = UUID(uuidString: "22222222-2222-4222-8222-222222222222")!
    static let cellar = UUID(uuidString: "33333333-3333-4333-8333-333333333333")!

    @Test func vectors() throws {
        let hostEphemeral = try Self.key(0x01), joinerEphemeral = try Self.key(0x41)
        let hostNonce = Data(repeating: 0xA0, count: 32), joinerNonce = Data(repeating: 0xB0, count: 32)
        let hostKey = hostEphemeral.publicKey.rawRepresentation, joinerKey = joinerEphemeral.publicKey.rawRepresentation
        let commitment = KeySchedule.hash("encave-pair-commit-v1", [hostKey, hostNonce])
        let pairing = try PairingCrypto.keys(
            ephemeral: hostEphemeral, otherKey: joinerKey, hostKey: hostKey, joinerKey: joinerKey,
            hostNonce: hostNonce, joinerNonce: joinerNonce,
            host: PeerInfo(device: Self.hostDevice, name: "", platform: ""),
            joiner: PeerInfo(device: Self.joinerDevice, name: "", platform: ""))
        let initiatorIdentity = try Self.key(0x81), responderIdentity = try Self.key(0xC1)
        let session = try SyncHandshake.sessionKeys(
            initiator: Self.hostDevice, responder: Self.joinerDevice, initiatorKey: hostKey, responderKey: joinerKey,
            initiatorNonce: hostNonce, responderNonce: joinerNonce, cellar: Self.cellar,
            ee: KeySchedule.dh(hostEphemeral, joinerKey),
            es: KeySchedule.dh(hostEphemeral, responderIdentity.publicKey.rawRepresentation),
            se: KeySchedule.dh(initiatorIdentity, joinerKey))
        let frame = try ChaChaPoly.seal(Data(#"{"type":"photos-end"}"#.utf8), using: session.i2r, nonce: SecureChannel.nonce(0))

        let values: [(String, String)] = [
            ("host ephemeral public", Self.hex(hostKey)),
            ("joiner ephemeral public", Self.hex(joinerKey)),
            ("commitment", Self.hex(commitment)),
            ("confirmation code", pairing.code),
            ("pair host to joiner", Self.hex(pairing.h2j)),
            ("pair joiner to host", Self.hex(pairing.j2h)),
            ("sync auth", Self.hex(session.auth)),
            ("sync initiator to responder", Self.hex(session.i2r)),
            ("sync responder to initiator", Self.hex(session.r2i)),
            ("first frame", Self.hex(frame.ciphertext + frame.tag)),
            ("rendezvous 123456", PairingCode(digits: "123456").rendezvous),
            ("network tag", Membership(cellarID: Self.cellar).networkTag),
        ]
        for (name, value) in values {
            #expect(Self.expected[name] == value, "\(name)")
        }
    }

    /// Remote sync (SYNC.md §8): mailbox and inbox IDs, tokens, the data key, an
    /// envelope with a fixed nonce, and a grant key with a fixed ephemeral.
    @Test func remoteVectors() throws {
        let cellarKey = CellarKey(cellarID: Self.cellar, epoch: 1, key: Self.bytes(0x10))
        let sender = try DeviceIdentity(id: Self.hostDevice, privateKeyData: Self.bytes(0x81))
        let recipient = try Self.key(0xC1)
        let envelope = try Envelope.seal(Data("hello".utf8), kind: .photo, path: "photo/x", key: cellarKey.dataKey,
                                         nonce: ChaChaPoly.Nonce(data: Data(repeating: 0x07, count: 12)))
        let ephemeral = try Self.key(0x01)
        let grantPath = "grant/\(Self.cellar.uuidString.lowercased())-00000002-\(Self.hostDevice.uuidString.lowercased())"
        let grantKey = try KeyGrant.key(
            path: grantPath, ephemeralPublic: ephemeral.publicKey.rawRepresentation,
            recipient: recipient.publicKey.rawRepresentation,
            ee: KeySchedule.dh(ephemeral, recipient.publicKey.rawRepresentation),
            se: KeySchedule.dh(sender.privateKey, recipient.publicKey.rawRepresentation))
        let values: [(String, String)] = [
            ("relay token", cellarKey.token),
            ("mailbox", cellarKey.mailboxID),
            ("data key", Self.hex(cellarKey.dataKey)),
            ("inbox token", sender.inboxToken),
            ("inbox", sender.inboxID),
            ("envelope", Self.hex(envelope)),
            ("grant key", Self.hex(grantKey)),
        ]
        for (name, value) in values {
            #expect(Self.remoteExpected[name] == value, "\(name): \(value)")
        }
    }

    static let remoteExpected: [String: String] = [
        "relay token": "zyJIksEyVCrPawgL8q0UGr5GMi1KewaCGSzwHGOLFno",
        "mailbox": "cace8487402ad2a081f417653a8443def",
        "data key": "d9bdb64332f3da0ef2e703d1cbdf3be079c75095ce76a6818f07fdfa2b802f54",
        "inbox token": "K9Ij7YuyprGlxKKF0rXF1bTbBNHtk2czQHE87LzJvx8",
        "inbox": "i2a17946aea22c15127c53bac03a59f8a",
        "envelope": "454e435601020707070707070707070707078f6f64053663e6ba095fca5586e637cebda8c6dfbf",
        "grant key": "a9547f8ceac9dc53b97196fc6bb6f23ad6659127b8cc83591b95236c8303567d",
    ]

    static let expected: [String: String] = [
        "host ephemeral public": "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c",
        "joiner ephemeral public": "64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d48466",
        "commitment": "0096fb81cae546de04c7851836d2c4f8e5e9b9c97a1eb42b2a4b01cb32a9b5e2",
        "confirmation code": "141 953",
        "pair host to joiner": "a79c1d1f93a7699a239e54c6a62a9321e53b3e81a8cb3710c505c3f2c61fb354",
        "pair joiner to host": "47c1553f86cbcc6389e5a2562434f866f113a6b8268138ca5a9321e0bcb21019",
        "sync auth": "b37137f432db51fbb80805e75d49ab80cd1e3bb990f3c559a8decf54c14c62de",
        "sync initiator to responder": "bfe9c2c7723bdc71a54ed30da89382476a1f8abd8b90e6447767f4428bd56dfc",
        "sync responder to initiator": "718fd5b20b9fed4805c0fa0f6efe235c7f476228f10741211a3a8acc0245ba5f",
        "first frame": "8f7553c327cfefd5e3c77017246680dc74a070adfcdf6b09a06a2a8bf7c21dd2bbe39db85c",
        "rendezvous 123456": "2bc4802d60f2f128",
        "network tag": "aedcc6a68baa",
    ]
}
