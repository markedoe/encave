import Crypto
import Foundation

// The device-to-device protocol: pairing, then sync sessions. Specified in
// docs/SYNC.md; the Android app implements the same messages.
//
// Every device has a long-term X25519 identity key. Pairing introduces two devices
// (and authenticates them with a short code the user compares). Afterwards each
// sync session authenticates both sides with their identity keys and derives a
// fresh session key, so a removed device cannot connect any more.

public enum SyncProtocol {
    public static let version = 1
    /// Bonjour service type.
    public static let serviceType = "_encave._tcp"
    /// Largest frame accepted (a label photo is well under this).
    public static let maxFrameSize = 16 * 1024 * 1024
}

public enum SyncError: Error, Equatable, Sendable {
    /// The other device sent something unexpected or malformed.
    case protocolViolation(String)
    /// The other device revealed a key that does not match its commitment, or proved
    /// nothing: someone may be in the middle.
    case authenticationFailed
    /// The other device does not know this one (yet): it may not have heard of a
    /// device added recently on another device.
    case unknownDevice
    /// This device was removed from the cellar.
    case removedDevice
    /// The other device belongs to another cellar.
    case otherCellar
    /// The user said the codes do not match, or cancelled.
    case rejected
    case unsupportedVersion(Int)
    case frameTooLarge
    case closed
}

// MARK: - Identity and members

/// This device's long-term key. The private key stays in the Keychain.
public struct DeviceIdentity: Sendable {
    public let id: UUID
    public let privateKey: Curve25519.KeyAgreement.PrivateKey
    public var publicKey: Data { privateKey.publicKey.rawRepresentation }

    public init(id: UUID, privateKeyData: Data) throws {
        self.id = id
        privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyData)
    }

    public static func generate(id: UUID = UUID()) -> (identity: DeviceIdentity, privateKeyData: Data) {
        let key = Curve25519.KeyAgreement.PrivateKey()
        // swiftlint:disable:next force_try
        return (try! DeviceIdentity(id: id, privateKeyData: key.rawRepresentation), key.rawRepresentation)
    }
}

/// A device of the cellar.
public struct Member: Codable, Hashable, Sendable, Identifiable {
    public var id: UUID
    public var name: String
    /// X25519 public key, 32 bytes.
    public var publicKey: Data
    /// "ios", "ipados", "macos" or "android".
    public var platform: String
    public var addedAt: Date

    public init(id: UUID, name: String, publicKey: Data, platform: String, addedAt: Date = .now) {
        self.id = id; self.name = name; self.publicKey = publicKey; self.platform = platform; self.addedAt = addedAt
    }
}

/// The devices of a cellar, synced like the cellar itself: each change is stamped,
/// and removing a device leaves a stamp so it is not added back by an older copy.
public struct Membership: Codable, Equatable, Sendable {
    /// Shared by all devices of the cellar; set by the first device.
    public var cellarID: UUID
    public var members: [UUID: Member]
    public var stamps: [UUID: Stamp]

    public init(cellarID: UUID = UUID(), members: [UUID: Member] = [:], stamps: [UUID: Stamp] = [:]) {
        self.cellarID = cellarID; self.members = members; self.stamps = stamps
    }

    /// Advertised on the local network so devices of the same cellar find each other
    /// without showing the cellar ID.
    public var networkTag: String {
        KeySchedule.hash("encave-cellar-tag-v1", [Data(cellarID.uuidString.lowercased().utf8)]).prefix(6)
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Devices that may sync.
    public var active: [Member] { members.values.sorted { $0.addedAt < $1.addedAt } }

    public mutating func add(_ member: Member, clock: inout UInt64, by device: UUID) {
        clock += 1
        members[member.id] = member
        stamps[member.id] = Stamp(counter: clock, device: device)
    }

    public mutating func remove(_ id: UUID, clock: inout UInt64, by device: UUID) {
        clock += 1
        members[id] = nil
        stamps[id] = Stamp(counter: clock, device: device, deleted: true)
    }

    /// Combines two lists of the same cellar; each device as of its latest change.
    public func merged(with other: Membership) -> Membership {
        var result = Membership(cellarID: cellarID)
        for id in Set(stamps.keys).union(other.stamps.keys).union(members.keys).union(other.members.keys) {
            let a = stamps[id] ?? (members[id] != nil ? .zero : nil)
            let b = other.stamps[id] ?? (other.members[id] != nil ? .zero : nil)
            let useOther: Bool
            switch (a, b) {
            case (nil, _): useOther = true
            case (_, nil): useOther = false
            case (let a?, let b?): useOther = b > a
            }
            let stamp = useOther ? b : a
            if let stamp { result.stamps[id] = stamp }
            if stamp?.deleted == true { continue }
            result.members[id] = useOther ? (other.members[id] ?? members[id]) : (members[id] ?? other.members[id])
        }
        return result
    }
}

// MARK: - Wire messages

/// One JSON object per frame, with a "type".
public struct WireMessage: Codable, Sendable, Equatable {
    public var type: String
    public var version: Int?
    public var device: UUID?
    public var name: String?
    public var platform: String?
    public var cellar: UUID?
    /// Base64: a commitment, ephemeral or identity public key, nonce or proof.
    public var commitment: Data?
    public var key: Data?
    public var identityKey: Data?
    public var nonce: Data?
    public var proof: Data?
    public var accepted: Bool?
    /// Sync payloads.
    public var cellarData: Data?
    public var syncData: Data?
    public var membersData: Data?
    public var photos: [String]?
    public var photoID: String?
    public var photoData: Data?
    public var reason: String?

    public init(type: String) { self.type = type }

    static func make(_ type: String, _ fill: (inout WireMessage) -> Void) -> WireMessage {
        var message = WireMessage(type: type)
        fill(&message)
        return message
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dataEncodingStrategy = .base64
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> WireMessage {
        let decoder = JSONDecoder()
        decoder.dataDecodingStrategy = .base64
        do { return try decoder.decode(WireMessage.self, from: data) } catch {
            throw SyncError.protocolViolation("malformed message")
        }
    }

    func require(_ type: String) throws -> WireMessage {
        if self.type == "error" {
            switch reason {
            case "unknown-device": throw SyncError.unknownDevice
            case "removed-device": throw SyncError.removedDevice
            case "other-cellar": throw SyncError.otherCellar
            case "rejected": throw SyncError.rejected
            default: throw SyncError.protocolViolation(reason ?? "the other device stopped")
            }
        }
        guard self.type == type else { throw SyncError.protocolViolation("expected \(type), got \(self.type)") }
        return self
    }
}

// MARK: - Transport and secure channel

/// A reliable, ordered connection carrying whole frames (length-prefixed on the wire).
public protocol FrameTransport: Sendable {
    func send(_ frame: Data) async throws
    func receive() async throws -> Data
    func close()
}

/// Encrypts frames with ChaCha20-Poly1305. Each direction has its own key and a
/// counter nonce, so frames cannot be replayed, reordered or reflected.
public actor SecureChannel {
    private let transport: FrameTransport
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private var sent: UInt64 = 0
    private var received: UInt64 = 0

    init(transport: FrameTransport, sendKey: SymmetricKey, receiveKey: SymmetricKey) {
        self.transport = transport; self.sendKey = sendKey; self.receiveKey = receiveKey
    }

    static func nonce(_ counter: UInt64) -> ChaChaPoly.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        for index in 0..<8 { bytes[4 + index] = UInt8(truncatingIfNeeded: counter >> (8 * (7 - index))) }
        // swiftlint:disable:next force_try
        return try! ChaChaPoly.Nonce(data: bytes)
    }

    public func send(_ message: WireMessage) async throws {
        let box = try ChaChaPoly.seal(try message.encoded(), using: sendKey, nonce: Self.nonce(sent))
        sent += 1
        // The nonce is implied by the counter: send only ciphertext and tag.
        try await transport.send(box.ciphertext + box.tag)
    }

    public func receive() async throws -> WireMessage {
        let frame = try await transport.receive()
        guard frame.count >= 16 else { throw SyncError.authenticationFailed }
        let box = try ChaChaPoly.SealedBox(nonce: Self.nonce(received), ciphertext: frame.dropLast(16), tag: frame.suffix(16))
        let plain: Data
        do { plain = try ChaChaPoly.open(box, using: receiveKey) } catch { throw SyncError.authenticationFailed }
        received += 1
        return try WireMessage.decode(plain)
    }

    public func close() { transport.close() }
}

// MARK: - Key schedule

enum KeySchedule {
    static func hash(_ label: String, _ parts: [Data]) -> Data {
        var hasher = SHA256()
        hasher.update(data: Data(label.utf8))
        for part in parts {
            var length = UInt32(part.count).bigEndian
            hasher.update(data: Data(bytes: &length, count: 4))
            hasher.update(data: part)
        }
        return Data(hasher.finalize())
    }

    static func derive(_ secret: Data, salt: Data, info: String, count: Int = 32) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: secret), salt: salt, info: Data(info.utf8),
                               outputByteCount: count)
    }

    static func bytes(_ key: SymmetricKey) -> Data { key.withUnsafeBytes { Data($0) } }

    static func dh(_ privateKey: Curve25519.KeyAgreement.PrivateKey, _ publicKey: Data) throws -> Data {
        guard publicKey.count == 32 else { throw SyncError.protocolViolation("bad key") }
        let other = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: other)
        let data = shared.withUnsafeBytes { Data($0) }
        // An all-zero result means a low-order key: refuse it.
        guard data.contains(where: { $0 != 0 }) else { throw SyncError.authenticationFailed }
        return data
    }

    static func randomNonce() -> Data {
        Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })
    }

    /// The code both screens show: 6 digits, "123 456".
    static func confirmationCode(from key: SymmetricKey) -> String {
        let value = bytes(key).prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) } % 1_000_000
        let digits = String(format: "%06u", value)
        return digits.prefix(3) + " " + digits.suffix(3)
    }
}

// MARK: - Pairing

/// A one-time pairing code: six digits shown by the device that invites, typed or
/// scanned on the new one. It only finds the right device on the network; the
/// security comes from the confirmation code both users compare.
public struct PairingCode: Sendable, Equatable {
    public let digits: String

    public init(digits: String) { self.digits = digits.filter(\.isNumber) }

    public static func random() -> PairingCode {
        PairingCode(digits: String(format: "%06u", UInt32.random(in: 0..<1_000_000)))
    }

    public var isValid: Bool { digits.count == 6 }

    /// Advertised on the network instead of the code itself.
    public var rendezvous: String {
        KeySchedule.hash("encave-pair-rendezvous-v1", [Data(digits.utf8)]).prefix(8)
            .map { String(format: "%02x", $0) }.joined()
    }

    /// Contents of the QR code: the code and the inviting device's commitment.
    public func qrPayload(commitment: Data) -> String {
        "encave://pair?code=\(digits)&c=\(commitment.base64URLEncoded)"
    }

    /// Reads a QR code made by `qrPayload`.
    public static func parse(qr: String) -> (code: PairingCode, commitment: Data?)? {
        guard let components = URLComponents(string: qr), components.scheme == "encave", components.host == "pair",
              let digits = components.queryItems?.first(where: { $0.name == "code" })?.value else { return nil }
        let code = PairingCode(digits: digits)
        guard code.isValid else { return nil }
        let commitment = components.queryItems?.first(where: { $0.name == "c" })?.value.flatMap(Data.init(base64URLEncoded:))
        return (code, commitment)
    }
}

/// Who a device is, as told during pairing.
public struct PeerInfo: Sendable, Equatable {
    public var device: UUID
    public var name: String
    public var platform: String
}

/// The device that invites (shows the code). One instance per code.
///
/// Messages: host → commit; joiner → key; host → reveal. Both then show the
/// confirmation code; after both users accept, each sends "confirm" over the
/// encrypted channel, then the host sends "welcome" (cellar ID and members) and
/// the joiner answers with its member record.
public struct PairingHost: Sendable {
    let identity: DeviceIdentity
    let info: PeerInfo
    let ephemeral = Curve25519.KeyAgreement.PrivateKey()
    let nonce = KeySchedule.randomNonce()

    public init(identity: DeviceIdentity, name: String, platform: String) {
        self.identity = identity
        info = PeerInfo(device: identity.id, name: name, platform: platform)
    }

    /// Hash of the key and nonce the host will reveal. Also in the QR code.
    public var commitment: Data {
        KeySchedule.hash("encave-pair-commit-v1", [ephemeral.publicKey.rawRepresentation, nonce])
    }
}

/// The new device (types or scans the code).
public struct PairingJoiner: Sendable {
    let identity: DeviceIdentity
    let info: PeerInfo
    let ephemeral = Curve25519.KeyAgreement.PrivateKey()
    let nonce = KeySchedule.randomNonce()
    /// From the QR code, if scanned: the host must reveal a key matching it.
    let expectedCommitment: Data?

    public init(identity: DeviceIdentity, name: String, platform: String, expectedCommitment: Data? = nil) {
        self.identity = identity
        info = PeerInfo(device: identity.id, name: name, platform: platform)
        self.expectedCommitment = expectedCommitment
    }
}

/// Result of the key exchange, before the users confirm.
public struct PairingKeys: Sendable {
    /// Shown on both devices; the users check they are the same.
    public let confirmationCode: String
    public let peer: PeerInfo
    let hostToJoiner: SymmetricKey
    let joinerToHost: SymmetricKey
}

enum PairingCrypto {
    static func keys(ephemeral: Curve25519.KeyAgreement.PrivateKey, otherKey: Data,
                     hostKey: Data, joinerKey: Data, hostNonce: Data, joinerNonce: Data,
                     host: PeerInfo, joiner: PeerInfo) throws -> (code: String, h2j: SymmetricKey, j2h: SymmetricKey) {
        let shared = try KeySchedule.dh(ephemeral, otherKey)
        let transcript = KeySchedule.hash("encave-pair-v1", [
            hostKey, joinerKey, hostNonce, joinerNonce,
            Data(host.device.uuidString.lowercased().utf8), Data(joiner.device.uuidString.lowercased().utf8),
        ])
        let code = KeySchedule.confirmationCode(from: KeySchedule.derive(shared, salt: transcript, info: "encave pair code", count: 4))
        return (code,
                KeySchedule.derive(shared, salt: transcript, info: "encave pair host to joiner"),
                KeySchedule.derive(shared, salt: transcript, info: "encave pair joiner to host"))
    }
}

public extension PairingHost {
    /// Runs the key exchange. Returns the keys and the confirmation code to show.
    func exchangeKeys(over transport: FrameTransport) async throws -> PairingKeys {
        try await transport.send(WireMessage.make("pair-commit") {
            $0.version = SyncProtocol.version; $0.commitment = commitment
        }.encoded())
        let key = try WireMessage.decode(try await transport.receive()).require("pair-key")
        guard key.version == SyncProtocol.version else { throw SyncError.unsupportedVersion(key.version ?? 0) }
        guard let joinerKey = key.key, let joinerNonce = key.nonce, joinerNonce.count == 32,
              let device = key.device else { throw SyncError.protocolViolation("incomplete pair-key") }
        let joiner = PeerInfo(device: device, name: key.name ?? "", platform: key.platform ?? "")
        try await transport.send(WireMessage.make("pair-reveal") {
            $0.key = ephemeral.publicKey.rawRepresentation; $0.nonce = nonce
            $0.device = info.device; $0.name = info.name; $0.platform = info.platform
        }.encoded())
        let result = try PairingCrypto.keys(
            ephemeral: ephemeral, otherKey: joinerKey, hostKey: ephemeral.publicKey.rawRepresentation, joinerKey: joinerKey,
            hostNonce: nonce, joinerNonce: joinerNonce, host: info, joiner: joiner)
        return PairingKeys(confirmationCode: result.code, peer: joiner, hostToJoiner: result.h2j, joinerToHost: result.j2h)
    }

    /// After the user accepted the code: waits for the other device's answer, then
    /// adds it to the cellar and sends the cellar ID and members. Returns the channel,
    /// ready for the first sync, and the new member.
    func finish(over transport: FrameTransport, keys: PairingKeys, accepted: Bool,
                membership: Membership) async throws -> (SecureChannel, Member) {
        let channel = SecureChannel(transport: transport, sendKey: keys.hostToJoiner, receiveKey: keys.joinerToHost)
        try await channel.send(WireMessage.make("pair-confirm") { $0.accepted = accepted })
        let answer = try await channel.receive().require("pair-confirm")
        guard accepted, answer.accepted == true else { throw SyncError.rejected }
        let me = Member(id: info.device, name: info.name, publicKey: identity.publicKey, platform: info.platform)
        var members = membership
        members.members[me.id] = members.members[me.id] ?? me
        try await channel.send(WireMessage.make("pair-welcome") {
            $0.cellar = membership.cellarID; $0.identityKey = identity.publicKey
            $0.membersData = try? JSONEncoder.sync.encode(members)
        })
        let reply = try await channel.receive().require("pair-member")
        guard let identityKey = reply.identityKey, identityKey.count == 32 else {
            throw SyncError.protocolViolation("missing identity key")
        }
        let member = Member(id: keys.peer.device, name: keys.peer.name, publicKey: identityKey, platform: keys.peer.platform)
        return (channel, member)
    }
}

public extension PairingJoiner {
    func exchangeKeys(over transport: FrameTransport) async throws -> PairingKeys {
        let commit = try WireMessage.decode(try await transport.receive()).require("pair-commit")
        guard commit.version == SyncProtocol.version else { throw SyncError.unsupportedVersion(commit.version ?? 0) }
        guard let commitment = commit.commitment else { throw SyncError.protocolViolation("missing commitment") }
        if let expectedCommitment, expectedCommitment != commitment { throw SyncError.authenticationFailed }
        try await transport.send(WireMessage.make("pair-key") {
            $0.version = SyncProtocol.version
            $0.key = ephemeral.publicKey.rawRepresentation; $0.nonce = nonce
            $0.device = info.device; $0.name = info.name; $0.platform = info.platform
        }.encoded())
        let reveal = try WireMessage.decode(try await transport.receive()).require("pair-reveal")
        guard let hostKey = reveal.key, let hostNonce = reveal.nonce, hostNonce.count == 32,
              let device = reveal.device else { throw SyncError.protocolViolation("incomplete pair-reveal") }
        // The host committed to this key before seeing ours: it cannot have chosen it
        // to make the confirmation codes match.
        guard KeySchedule.hash("encave-pair-commit-v1", [hostKey, hostNonce]) == commitment else {
            throw SyncError.authenticationFailed
        }
        let host = PeerInfo(device: device, name: reveal.name ?? "", platform: reveal.platform ?? "")
        let result = try PairingCrypto.keys(
            ephemeral: ephemeral, otherKey: hostKey, hostKey: hostKey, joinerKey: ephemeral.publicKey.rawRepresentation,
            hostNonce: hostNonce, joinerNonce: nonce, host: host, joiner: info)
        return PairingKeys(confirmationCode: result.code, peer: host, hostToJoiner: result.h2j, joinerToHost: result.j2h)
    }

    /// After the user accepted the code: receives the cellar and its members.
    func finish(over transport: FrameTransport, keys: PairingKeys,
                accepted: Bool) async throws -> (SecureChannel, Membership) {
        let channel = SecureChannel(transport: transport, sendKey: keys.joinerToHost, receiveKey: keys.hostToJoiner)
        try await channel.send(WireMessage.make("pair-confirm") { $0.accepted = accepted })
        let answer = try await channel.receive().require("pair-confirm")
        guard accepted, answer.accepted == true else { throw SyncError.rejected }
        let welcome = try await channel.receive().require("pair-welcome")
        guard let data = welcome.membersData, var membership = try? JSONDecoder.sync.decode(Membership.self, from: data),
              let hostKey = welcome.identityKey, hostKey.count == 32 else {
            throw SyncError.protocolViolation("incomplete welcome")
        }
        // The host's own record must carry the key it just proved it holds.
        if var host = membership.members[keys.peer.device] {
            host.publicKey = hostKey
            membership.members[host.id] = host
        }
        try await channel.send(WireMessage.make("pair-member") { $0.identityKey = identity.publicKey })
        return (channel, membership)
    }
}

// MARK: - Sync session handshake

/// Opens an authenticated session between two devices of the same cellar, in the
/// style of the Noise KK pattern: ephemeral keys for forward secrecy, identity keys
/// to prove each side is a current member.
public enum SyncHandshake {
    /// The device that connects.
    public static func initiate(over transport: FrameTransport, identity: DeviceIdentity,
                                membership: Membership) async throws -> (SecureChannel, Member) {
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let nonce = KeySchedule.randomNonce()
        try await transport.send(WireMessage.make("sync-hello") {
            $0.version = SyncProtocol.version; $0.cellar = membership.cellarID; $0.device = identity.id
            $0.key = ephemeral.publicKey.rawRepresentation; $0.nonce = nonce
        }.encoded())
        let accept = try WireMessage.decode(try await transport.receive()).require("sync-accept")
        guard let device = accept.device, let otherKey = accept.key, let otherNonce = accept.nonce, otherNonce.count == 32,
              let proof = accept.proof else { throw SyncError.protocolViolation("incomplete sync-accept") }
        guard let peer = membership.members[device] else { throw SyncError.unknownDevice }
        let keys = try sessionKeys(
            initiator: identity.id, responder: device, initiatorKey: ephemeral.publicKey.rawRepresentation,
            responderKey: otherKey, initiatorNonce: nonce, responderNonce: otherNonce, cellar: membership.cellarID,
            ee: KeySchedule.dh(ephemeral, otherKey),
            es: KeySchedule.dh(ephemeral, peer.publicKey),
            se: KeySchedule.dh(identity.privateKey, otherKey))
        guard HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: Data("responder".utf8), using: keys.auth) else {
            throw SyncError.authenticationFailed
        }
        try await transport.send(WireMessage.make("sync-confirm") {
            $0.proof = Data(HMAC<SHA256>.authenticationCode(for: Data("initiator".utf8), using: keys.auth))
        }.encoded())
        return (SecureChannel(transport: transport, sendKey: keys.i2r, receiveKey: keys.r2i), peer)
    }

    /// The device that accepts the connection.
    public static func respond(over transport: FrameTransport, identity: DeviceIdentity,
                               membership: Membership) async throws -> (SecureChannel, Member) {
        let hello = try WireMessage.decode(try await transport.receive()).require("sync-hello")
        // Refusals are told to the other device, so it can say why (for example "removed").
        func refuse(_ reason: String, _ error: SyncError) async -> SyncError {
            try? await transport.send(WireMessage.make("error") { $0.reason = reason }.encoded())
            return error
        }
        guard hello.version == SyncProtocol.version else {
            throw await refuse("unsupported-version", .unsupportedVersion(hello.version ?? 0))
        }
        guard hello.cellar == membership.cellarID else { throw await refuse("other-cellar", .otherCellar) }
        guard let device = hello.device, let otherKey = hello.key, let otherNonce = hello.nonce, otherNonce.count == 32
        else { throw SyncError.protocolViolation("incomplete sync-hello") }
        guard let peer = membership.members[device], device != identity.id else {
            if membership.stamps[device]?.deleted == true { throw await refuse("removed-device", .removedDevice) }
            throw await refuse("unknown-device", .unknownDevice)
        }
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let nonce = KeySchedule.randomNonce()
        let keys = try sessionKeys(
            initiator: device, responder: identity.id, initiatorKey: otherKey,
            responderKey: ephemeral.publicKey.rawRepresentation, initiatorNonce: otherNonce, responderNonce: nonce,
            cellar: membership.cellarID,
            ee: KeySchedule.dh(ephemeral, otherKey),
            es: KeySchedule.dh(identity.privateKey, otherKey),
            se: KeySchedule.dh(ephemeral, peer.publicKey))
        try await transport.send(WireMessage.make("sync-accept") {
            $0.device = identity.id; $0.key = ephemeral.publicKey.rawRepresentation; $0.nonce = nonce
            $0.proof = Data(HMAC<SHA256>.authenticationCode(for: Data("responder".utf8), using: keys.auth))
        }.encoded())
        let confirm = try WireMessage.decode(try await transport.receive()).require("sync-confirm")
        guard let proof = confirm.proof,
              HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating: Data("initiator".utf8), using: keys.auth) else {
            throw SyncError.authenticationFailed
        }
        return (SecureChannel(transport: transport, sendKey: keys.r2i, receiveKey: keys.i2r), peer)
    }

    struct Keys { let auth: SymmetricKey; let i2r: SymmetricKey; let r2i: SymmetricKey }

    /// `es` is the initiator's ephemeral with the responder's identity, `se` the
    /// initiator's identity with the responder's ephemeral.
    static func sessionKeys(initiator: UUID, responder: UUID, initiatorKey: Data, responderKey: Data,
                            initiatorNonce: Data, responderNonce: Data, cellar: UUID,
                            ee: Data, es: Data, se: Data) throws -> Keys {
        let transcript = KeySchedule.hash("encave-sync-v1", [
            Data(cellar.uuidString.lowercased().utf8),
            Data(initiator.uuidString.lowercased().utf8), Data(responder.uuidString.lowercased().utf8),
            initiatorKey, responderKey, initiatorNonce, responderNonce,
        ])
        let secret = ee + es + se
        return Keys(auth: KeySchedule.derive(secret, salt: transcript, info: "encave sync auth"),
                    i2r: KeySchedule.derive(secret, salt: transcript, info: "encave sync initiator to responder"),
                    r2i: KeySchedule.derive(secret, salt: transcript, info: "encave sync responder to initiator"))
    }
}

// MARK: - Exchange

/// What a device brings to a sync: its copy of the cellar, and its photos.
public struct SyncSnapshot: Sendable {
    public var cellar: Cellar
    public var state: SyncState
    public var membership: Membership

    public init(cellar: Cellar, state: SyncState, membership: Membership) {
        self.cellar = cellar; self.state = state; self.membership = membership
    }
}

/// Access to label photos for a sync (content-addressed by their ID).
public struct PhotoAccess: Sendable {
    public var data: @Sendable (String) -> Data?
    public var save: @Sendable (String, Data) -> Void

    public init(data: @escaping @Sendable (String) -> Data?, save: @escaping @Sendable (String, Data) -> Void) {
        self.data = data; self.save = save
    }

    public static let none = PhotoAccess(data: { _ in nil }, save: { _, _ in })
}

/// Remote sync settings shared between devices that meet on the network: the
/// cellar key and where the files are. Sent only over the encrypted channel,
/// never stored in a snapshot.
public struct RemoteInfo: Codable, Equatable, Sendable {
    public var key: CellarKey
    /// For example the relay URL.
    public var storage: String?

    public init(key: CellarKey, storage: String?) { self.key = key; self.storage = storage }
}

public enum SyncExchange {
    /// Sends this device's copy, receives the other's, and returns the merge (the
    /// same on both sides), then swaps the photos each side is missing. The
    /// initiator sends first at every step, so neither side waits on a full buffer.
    public static func run(over channel: SecureChannel, isInitiator: Bool, local: SyncSnapshot,
                           photos: PhotoAccess) async throws -> SyncSnapshot {
        try await exchange(over: channel, isInitiator: isInitiator, local: local, photos: photos, remote: nil).snapshot
    }

    /// `run`, then a last step where each side sends its remote sync key and storage
    /// (or nothing), so a device that turned remote sync on passes it to the others.
    public static func exchange(over channel: SecureChannel, isInitiator: Bool, local: SyncSnapshot,
                                photos: PhotoAccess,
                                remote: RemoteInfo?) async throws -> (snapshot: SyncSnapshot, remote: RemoteInfo?) {
        let snapshot = try await mergeAndSwapPhotos(over: channel, isInitiator: isInitiator, local: local, photos: photos)
        let mine = WireMessage.make("sync-keys") { $0.syncData = remote.flatMap { try? JSONEncoder.sync.encode($0) } }
        let theirs: WireMessage
        if isInitiator {
            try await channel.send(mine)
            theirs = try await channel.receive().require("sync-keys")
        } else {
            theirs = try await channel.receive().require("sync-keys")
            try await channel.send(mine)
        }
        let received = theirs.syncData.flatMap { try? JSONDecoder.sync.decode(RemoteInfo.self, from: $0) }
        // A key for another cellar is not this cellar's.
        return (snapshot, received?.key.cellarID == snapshot.membership.cellarID ? received : nil)
    }

    static func mergeAndSwapPhotos(over channel: SecureChannel, isInitiator: Bool, local: SyncSnapshot,
                                   photos: PhotoAccess) async throws -> SyncSnapshot {
        let mine = WireMessage.make("sync-state") {
            $0.cellarData = try? JSONFileCellarStore.makeEncoder().encode(local.cellar)
            $0.syncData = try? JSONEncoder.sync.encode(local.state)
            $0.membersData = try? JSONEncoder.sync.encode(local.membership)
        }
        let theirs: WireMessage
        if isInitiator {
            try await channel.send(mine)
            theirs = try await channel.receive().require("sync-state")
        } else {
            theirs = try await channel.receive().require("sync-state")
            try await channel.send(mine)
        }
        guard let cellarData = theirs.cellarData, let syncData = theirs.syncData, let membersData = theirs.membersData,
              let remoteCellar = try? JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: cellarData),
              let remoteState = try? JSONDecoder.sync.decode(SyncState.self, from: syncData),
              let remoteMembers = try? JSONDecoder.sync.decode(Membership.self, from: membersData)
        else { throw SyncError.protocolViolation("unreadable sync-state") }
        guard remoteMembers.cellarID == local.membership.cellarID else { throw SyncError.otherCellar }

        let merge = CellarMerge.merge(local: local.cellar, local.state, remote: remoteCellar, remoteState)
        var state = local.state
        state.stamps = merge.stamps
        state.clock = merge.clock
        let membership = local.membership.merged(with: remoteMembers)
        let result = SyncSnapshot(cellar: merge.cellar, state: state, membership: membership)

        // Photos: each side asks for the ones it is missing.
        let wanted = Set(merge.cellar.wines.values.compactMap(\.photoID)).filter { photos.data($0) == nil }.sorted()
        let ask = WireMessage.make("photos-want") { $0.photos = wanted }
        let request: WireMessage
        if isInitiator {
            try await channel.send(ask)
            request = try await channel.receive().require("photos-want")
        } else {
            request = try await channel.receive().require("photos-want")
            try await channel.send(ask)
        }
        // Only photos of the merged cellar are sent, whatever is asked.
        let known = Set(merge.cellar.wines.values.compactMap(\.photoID))
        let toSend = (request.photos ?? []).filter { known.contains($0) }

        func sendPhotos() async throws {
            for id in toSend {
                guard let data = photos.data(id) else { continue }
                try await channel.send(WireMessage.make("photo") { $0.photoID = id; $0.photoData = data })
            }
            try await channel.send(WireMessage(type: "photos-end"))
        }
        func receivePhotos() async throws {
            let expected = Set(wanted)
            while true {
                let message = try await channel.receive()
                if message.type == "photos-end" { break }
                let photo = try message.require("photo")
                guard let id = photo.photoID, expected.contains(id), let data = photo.photoData,
                      PhotoID.matches(id, data) else { continue }
                photos.save(id, data)
            }
        }
        if isInitiator {
            try await sendPhotos()
            try await receivePhotos()
        } else {
            try await receivePhotos()
            try await sendPhotos()
        }
        return result
    }
}

/// Label photos are named after their content when synced, so a device can check
/// that a photo it receives is the one it asked for.
public enum PhotoID {
    /// IDs of photos taken before sync existed are not content hashes: accept them as is.
    public static func matches(_ id: String, _ data: Data) -> Bool {
        guard id.hasPrefix("sha256-") else { return true }
        return id == make(for: data)
    }

    public static func make(for data: Data) -> String {
        "sha256-" + SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

extension JSONEncoder {
    static var sync: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .deferredToDate
        return encoder
    }
}

extension JSONDecoder {
    static var sync: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .deferredToDate
        return decoder
    }
}

extension Data {
    var base64URLEncoded: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URLEncoded string: String) {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        self.init(base64Encoded: base64)
    }
}
