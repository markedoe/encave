import Crypto
import Foundation

// Remote sync: devices that are apart exchange encrypted files through storage
// that only ever holds ciphertext (iCloud, a relay, a folder). Specified in
// docs/SYNC.md, section 8; the choice of storage is explained in docs/REMOTE_SYNC.md.
//
// Every file is written once by one device, so any storage that can list, read,
// create and delete files works, without locks or server logic.

// MARK: - Storage

/// A namespace of files on some storage. Paths use "/" and never start with it.
public protocol Mailbox: Sendable {
    /// Paths starting with `prefix`.
    func list(prefix: String) async throws -> [String]
    /// Nil if there is no such file.
    func get(_ path: String) async throws -> Data?
    /// Creates a file. Returns false (and changes nothing) if it already exists.
    @discardableResult
    func create(_ path: String, data: Data) async throws -> Bool
    func delete(_ path: String) async throws
}

/// Opens mailboxes on one storage (one relay, one iCloud account…).
public protocol MailboxStore: Sendable {
    /// `token` proves the right to read and write; the mailbox ID is derived from it.
    func mailbox(id: String, token: String) -> Mailbox
    /// A device's inbox: anyone may drop a key grant in it, only its owner reads it.
    func inbox(id: String, token: String) -> Mailbox
}

public enum RemoteSyncError: Error, Equatable, Sendable {
    case unauthorized
    /// The login is right but the server refused access to the folder (WebDAV 403).
    case forbidden
    case quotaExceeded
    /// One file is larger than the storage accepts (a very large cellar).
    case fileTooLarge
    case unavailable(String)
    case corrupted(String)
}

// MARK: - Keys

/// The key shared by the devices of a cellar for remote sync. Changes ("epoch")
/// when a device is removed, so the removed device cannot read anything new.
public struct CellarKey: Codable, Equatable, Sendable {
    public var cellarID: UUID
    public var epoch: UInt32
    public var key: Data

    public init(cellarID: UUID, epoch: UInt32, key: Data) {
        self.cellarID = cellarID; self.epoch = epoch; self.key = key
    }

    private enum CodingKeys: String, CodingKey { case cellarID, epoch, key }

    /// A key received from another device must be 32 bytes, like the ones devices make.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        cellarID = try c.decode(UUID.self, forKey: .cellarID)
        epoch = try c.decode(UInt32.self, forKey: .epoch)
        key = try c.decode(Data.self, forKey: .key)
        guard key.count == 32 else {
            throw DecodingError.dataCorruptedError(forKey: .key, in: c, debugDescription: "A cellar key is 32 bytes")
        }
    }

    public static func random(cellarID: UUID, epoch: UInt32 = 1) -> CellarKey {
        CellarKey(cellarID: cellarID, epoch: epoch, key: KeySchedule.randomNonce())
    }

    /// Whether this key replaces `other`: a later epoch, or on the same epoch (two
    /// devices turned remote sync on, or rotated, at the same time) the key whose
    /// SHA-256 is smaller, so every device settles on the same one.
    public func supersedes(_ other: CellarKey?) -> Bool {
        guard let other, other.cellarID == cellarID else { return other == nil }
        if epoch != other.epoch { return epoch > other.epoch }
        return Data(SHA256.hash(data: key)).lexicographicallyPrecedes(Data(SHA256.hash(data: other.key)))
    }

    func derive(_ info: String, count: Int = 32) -> Data {
        KeySchedule.bytes(KeySchedule.derive(key, salt: Data(), info: info, count: count))
    }

    /// Where this epoch's files are: "c" and the start of the token's SHA-256, so a
    /// relay can check the token without storing anything. Changes with the key.
    public var mailboxID: String { RelayAuth.mailboxID(kind: "c", token: token) }
    /// Proves to a relay the right to use the mailbox.
    public var token: String { derive("encave/v1/relay-auth").base64URLEncoded }
    var dataKey: SymmetricKey { SymmetricKey(data: derive("encave/v1/data")) }
}

public extension DeviceIdentity {
    /// The device's inbox, where other devices leave key grants for it. Published
    /// in its member record (`Member.inbox`).
    var inboxID: String { RelayAuth.mailboxID(kind: "i", token: inboxToken) }
    /// Proves to a relay the right to read this inbox.
    var inboxToken: String {
        KeySchedule.bytes(KeySchedule.derive(privateKey.rawRepresentation, salt: Data(), info: "encave/v1/inbox-auth"))
            .base64URLEncoded
    }
}

/// How mailbox IDs follow from their tokens (the relay applies the same rule).
public enum RelayAuth {
    /// `kind` ("c" or "i") followed by the first 32 hex characters of SHA-256(UTF-8 token).
    public static func mailboxID(kind: String, token: String) -> String {
        kind + Data(SHA256.hash(data: Data(token.utf8))).prefix(16).hexString
    }
}

// MARK: - File envelope

/// `"ENCV" ‖ version ‖ kind ‖ nonce (12) ‖ ciphertext ‖ tag`, ChaCha20-Poly1305 with
/// the path as additional data, so storage cannot move a file to another name.
enum Envelope {
    enum Kind: UInt8 { case snapshot = 1, photo = 2, grant = 3 }
    static let magic = Data("ENCV".utf8)

    static func header(_ kind: Kind) -> Data { magic + [1, kind.rawValue] }

    /// `nonce` is random unless given (test vectors only).
    static func seal(_ plaintext: Data, kind: Kind, path: String, key: SymmetricKey,
                     nonce: ChaChaPoly.Nonce = ChaChaPoly.Nonce()) throws -> Data {
        let header = header(kind)
        let box = try ChaChaPoly.seal(plaintext, using: key, nonce: nonce, authenticating: header + Data(path.utf8))
        return header + box.combined
    }

    static func open(_ file: Data, kind: Kind, path: String, key: SymmetricKey) throws -> Data {
        let header = header(kind)
        guard file.count > header.count + 28, file.prefix(header.count) == header else {
            throw RemoteSyncError.corrupted(path)
        }
        do {
            let box = try ChaChaPoly.SealedBox(combined: file.dropFirst(header.count))
            return try ChaChaPoly.open(box, using: key, authenticating: header + Data(path.utf8))
        } catch {
            throw RemoteSyncError.corrupted(path)
        }
    }
}

// MARK: - Key grants

/// A cellar key given by one device to another, left in the recipient's inbox.
/// Sealed with both an ephemeral key and the sender's identity key: only the
/// recipient can open it, and only a current member can have sent it.
public struct KeyGrant: Codable, Equatable, Sendable {
    public var cellarKey: CellarKey
    public var from: UUID
    /// Where the files are (for example a relay URL), so the recipient can follow.
    public var storage: String?

    public init(cellarKey: CellarKey, from: UUID, storage: String?) {
        self.cellarKey = cellarKey; self.from = from; self.storage = storage
    }

    /// Grants are at most this large; larger files in an inbox are deleted unopened.
    static let maxSize = 4096

    /// Seals for `recipient` (its identity public key). The path ends with a random
    /// part, so nobody who can write to the inbox can take the name first.
    public func sealed(by sender: DeviceIdentity, for recipient: Data) throws -> (path: String, data: Data) {
        let path = GrantPath(cellarID: cellarKey.cellarID, epoch: cellarKey.epoch, sender: from).string
        let ephemeral = Curve25519.KeyAgreement.PrivateKey()
        let key = try Self.key(path: path, ephemeralPublic: ephemeral.publicKey.rawRepresentation, recipient: recipient,
                               ee: KeySchedule.dh(ephemeral, recipient), se: KeySchedule.dh(sender.privateKey, recipient))
        let body = try Envelope.seal(JSONEncoder.sync.encode(self), kind: .grant, path: path, key: key)
        return (path, ephemeral.publicKey.rawRepresentation + body)
    }

    /// Opens a grant found in this device's inbox. `members` gives the senders' keys.
    public static func open(_ data: Data, path: String, recipient: DeviceIdentity,
                            members: Membership) throws -> KeyGrant {
        guard data.count > 32, data.count <= maxSize, let name = GrantPath(path) else {
            throw RemoteSyncError.corrupted(path)
        }
        let ephemeralPublic = data.prefix(32)
        // The sender's ID is in the path; it must be a current member.
        let senderID = name.sender
        guard let sender = members.members[senderID] else { throw SyncError.unknownDevice }
        let key = try Self.key(path: path, ephemeralPublic: ephemeralPublic, recipient: recipient.publicKey,
                               ee: KeySchedule.dh(recipient.privateKey, ephemeralPublic),
                               se: KeySchedule.dh(recipient.privateKey, sender.publicKey))
        let grant = try JSONDecoder.sync.decode(KeyGrant.self, from: Envelope.open(data.dropFirst(32), kind: .grant,
                                                                                    path: path, key: key))
        guard grant.from == senderID, grant.cellarKey.cellarID == members.cellarID,
              name.cellarID == grant.cellarKey.cellarID, name.epoch == grant.cellarKey.epoch else {
            throw RemoteSyncError.corrupted(path)
        }
        return grant
    }

    static func key(path: String, ephemeralPublic: Data, recipient: Data, ee: Data, se: Data) throws -> SymmetricKey {
        KeySchedule.derive(ee + se, salt: KeySchedule.hash("encave-grant-v1", [ephemeralPublic, recipient, Data(path.utf8)]),
                           info: "encave grant")
    }
}

// MARK: - Sync

/// What a device remembers between remote syncs.
public struct RemoteProgress: Codable, Equatable, Sendable {
    /// Newest snapshot read from each device (its clock).
    public var seen: [UUID: UInt64] = [:]
    /// Clock of the last snapshot this device wrote, in the current epoch.
    public var uploadedClock: UInt64?
    /// Digest of the stamps of the last snapshot written: a merge on the local
    /// network can change the cellar without moving the clock.
    public var uploadedDigest: String?
    /// Files (photos and documents) already uploaded in the current epoch.
    public var uploadedPhotos: Set<String> = []
    /// Files the storage refused (too large, or no room left): the cellar still syncs
    /// without them, and they are tried again with the next snapshot this device writes.
    public var refusedFiles: Set<String> = []
    /// The last refusal was for lack of room rather than size.
    public var storageFull = false
    /// Epoch the progress refers to; a new epoch starts afresh.
    public var epoch: UInt32 = 0

    public init() {}

    enum CodingKeys: String, CodingKey {
        case seen, uploadedClock, uploadedDigest, uploadedPhotos, refusedFiles, storageFull, epoch
    }

    // Progress saved before `refusedFiles` existed still reads.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        seen = try container.decode([UUID: UInt64].self, forKey: .seen)
        uploadedClock = try container.decodeIfPresent(UInt64.self, forKey: .uploadedClock)
        uploadedDigest = try container.decodeIfPresent(String.self, forKey: .uploadedDigest)
        uploadedPhotos = try container.decode(Set<String>.self, forKey: .uploadedPhotos)
        refusedFiles = try container.decodeIfPresent(Set<String>.self, forKey: .refusedFiles) ?? []
        storageFull = try container.decodeIfPresent(Bool.self, forKey: .storageFull) ?? false
        epoch = try container.decode(UInt32.self, forKey: .epoch)
    }
}

public enum RemoteSync {
    /// Snapshots kept per device: the newest two (a reader may be fetching the previous one).
    static let keptSnapshots = 2

    /// The wait before the first round of a loop that runs every `interval`, counted from
    /// the last round: none when that round is older than the interval, or there was none.
    public static func firstWait(interval: TimeInterval, lastRound: Date?, now: Date = .now) -> TimeInterval {
        let elapsed = now.timeIntervalSince(lastRound ?? .distantPast)
        return min(interval, max(0, interval - elapsed))
    }

    /// Whether a round may run: after a refused WebDAV login, only when the user asks
    /// (Sync Now) or signs in again, never automatically (a server may lock the account).
    public static func mayRun(userAsked: Bool, webDAVLoginRefused: Bool) -> Bool {
        userAsked || !webDAVLoginRefused
    }

    public struct Result: Sendable {
        /// The merged copy, or nil if nothing came in.
        public var snapshot: SyncSnapshot?
        public var progress: RemoteProgress
        /// A newer cellar key received from another device.
        public var newKey: CellarKey?
        public var storage: String?
        /// Devices whose newest snapshot could not be read (damaged, from a newer
        /// version of the app, out of bounds). Skipped; the others still sync.
        public var unreadable: [UUID] = []
        /// This device's clock after the round: at least the one passed in. Raised
        /// when the content changed without the clock moving (a merge on the local
        /// network), so readers see a newer snapshot. Keep it.
        public var clock: UInt64 = 0
        /// Why the storage refused some of this device's files (`fileTooLarge` or
        /// `quotaExceeded`), if it did: the cellar synced, those files stay here for now.
        public var refusedFiles: RemoteSyncError?
    }

    /// One round: picks up key grants, reads the other devices' newest snapshots,
    /// merges them, fetches missing photos, then writes this device's snapshot and
    /// photos if anything changed.
    ///
    /// `storage` is where `store` keeps the files, if the caller knows it: a new key
    /// granted with another storage then ends the round, so nothing is read from or
    /// written to the old storage under the new key (the result has the key, the
    /// storage and a fresh progress for the next round).
    public static func run(store: MailboxStore, identity: DeviceIdentity, key currentKey: CellarKey,
                           local: SyncSnapshot, progress currentProgress: RemoteProgress,
                           files: FileAccess, storage currentStorage: String? = nil) async throws -> Result {
        var key = currentKey
        var progress = currentProgress
        var storage: String?
        var newKey: CellarKey?

        var unreadable: [UUID] = []

        // 1. Key grants: a newer epoch (a device was removed).
        if let grant = try await receiveGrant(store: store, identity: identity, membership: local.membership,
                                              current: key) {
            key = grant.cellarKey
            newKey = key
            storage = grant.storage
        }
        if progress.epoch != key.epoch || newKey != nil {
            progress = RemoteProgress()
            progress.epoch = key.epoch
        }
        if newKey != nil, let storage, let currentStorage, storage != currentStorage {
            return Result(snapshot: nil, progress: progress, newKey: newKey, storage: storage, clock: local.state.clock)
        }

        // 2. Other devices' newest snapshots.
        let mailbox = store.mailbox(id: key.mailboxID, token: key.token)
        var merged = local
        var received = false
        let snapshots = try await mailbox.list(prefix: "snap/").compactMap(SnapshotPath.init)
        for (device, paths) in Dictionary(grouping: snapshots, by: \.device) where device != identity.id {
            // Only current members are read: files of removed or unknown devices are
            // ignored (a device added elsewhere is read once its record arrives).
            guard merged.membership.members[device] != nil,
                  let newest = paths.max(by: { $0.clock < $1.clock }),
                  newest.clock > progress.seen[device] ?? 0,
                  let file = try await mailbox.get(newest.string) else { continue }
            // One unreadable file must not stop the round for the other devices.
            let remote: SyncSnapshot
            do {
                remote = try decodeSnapshot(Envelope.open(file, kind: .snapshot, path: newest.string, key: key.dataKey))
                // The path's clock is the one readers compare: it must be the snapshot's.
                guard remote.state.device == device, remote.state.clock == newest.clock,
                      remote.membership.cellarID == merged.membership.cellarID else {
                    throw RemoteSyncError.corrupted(newest.string)
                }
                try SyncState.check(remote.state, membership: remote.membership)
            } catch {
                unreadable.append(device)
                continue
            }
            let merge = CellarMerge.merge(local: merged.cellar, merged.state, remote: remote.cellar, remote.state)
            merged.cellar = merge.cellar
            merged.state.stamps = merge.stamps
            merged.state.clock = max(merged.state.clock, merge.clock)
            merged.membership = merged.membership.merged(with: remote.membership)
            merged.state.clock = max(merged.state.clock, merged.membership.stamps.values.map(\.counter).max() ?? 0)
            progress.seen[device] = newest.clock
            received = true
        }

        // 3. Missing files: photos and documents, both under "photo/".
        let refs = merged.cellar.fileRefs
        for id in files.missing(refs) {
            let path = photoPath(id)
            guard let file = try? await mailbox.get(path),
                  let data = try? Envelope.open(file, kind: .photo, path: path, key: key.dataKey),
                  PhotoID.matches(id, data) else { continue }
            files.save(id, data, in: refs)
            progress.uploadedPhotos.insert(id)
        }

        // 4. This device's snapshot and files, if something changed since the last upload.
        let digest = stampDigest(merged)
        if progress.uploadedClock != merged.state.clock || progress.uploadedDigest != digest || newKey != nil {
            // Other devices read a snapshot only if its clock is newer than the last one,
            // including one written by a round that failed after writing it.
            let written = [progress.uploadedClock, snapshots.filter { $0.device == identity.id }.map(\.clock).max()]
                .compactMap { $0 }.max()
            if let written, merged.state.clock <= written {
                merged.state.clock = written + 1
            }
            var full = false
            for id in Set(refs.map(\.id)).sorted() where PhotoID.isValid(id) && !progress.uploadedPhotos.contains(id) {
                guard !full, let data = files.data(id, in: refs) else { continue }
                let path = photoPath(id)
                // A file the storage refuses (a large document) must not stop the cellar from syncing.
                do {
                    try await mailbox.create(path, data: Envelope.seal(data, kind: .photo, path: path, key: key.dataKey))
                } catch let error as RemoteSyncError where error == .fileTooLarge || error == .quotaExceeded {
                    progress.refusedFiles.insert(id)
                    progress.storageFull = error == .quotaExceeded
                    // No room: the other files would be refused too.
                    full = error == .quotaExceeded
                    continue
                }
                progress.uploadedPhotos.insert(id)
                progress.refusedFiles.remove(id)
            }
            try await write(merged, to: mailbox, key: key, device: identity.id)
            progress.uploadedClock = merged.state.clock
            progress.uploadedDigest = digest
        }
        progress.refusedFiles.formIntersection(refs.map(\.id))
        let refused: RemoteSyncError? = progress.refusedFiles.isEmpty ? nil
            : progress.storageFull ? .quotaExceeded : .fileTooLarge
        return Result(snapshot: received ? merged : nil, progress: progress, newKey: newKey, storage: storage,
                      unreadable: unreadable, clock: merged.state.clock, refusedFiles: refused)
    }

    /// The key left in this device's inbox by a current member that supersedes
    /// `current` (nil: any), if any. Grants that are older or no longer useful are deleted.
    public static func receiveGrant(store: MailboxStore, identity: DeviceIdentity, membership: Membership,
                                    current: CellarKey?) async throws -> KeyGrant? {
        let epoch = current?.epoch ?? 0
        let inbox = store.inbox(id: identity.inboxID, token: identity.inboxToken)
        var best: KeyGrant?
        var waiting = 0
        for path in try await inbox.list(prefix: "grant/") {
            guard let data = try await inbox.get(path) else { continue }
            let grant: KeyGrant
            do {
                grant = try KeyGrant.open(data, path: path, recipient: identity, members: membership)
            } catch SyncError.unknownDevice where GrantPath(path)?.cellarID == membership.cellarID
                        && !isFromRemovedDevice(path, membership) && waiting < maxWaitingGrants {
                // From a device of this cellar this one has not heard of yet: keep it for
                // later. Only a few, since anyone can write to an inbox.
                waiting += 1
                continue
            } catch {
                // Forged, damaged or for another cellar: never usable, and it takes room.
                try? await inbox.delete(path)
                continue
            }
            if grant.cellarKey.supersedes(best?.cellarKey ?? current) { best = grant }
        }
        // Grants up to the one kept are done with.
        if let kept = best?.cellarKey.epoch ?? (epoch > 0 ? epoch : nil) {
            for path in try await inbox.list(prefix: "grant/\(membership.cellarID.lowercased)-") {
                if let name = GrantPath(path), name.cellarID == membership.cellarID, name.epoch < kept {
                    try? await inbox.delete(path)
                }
            }
        }
        return best
    }

    /// Grants from devices not known yet that an inbox keeps; the others are deleted.
    static let maxWaitingGrants = 8

    /// A grant path names the sender; true if that device was removed (or the path is not a grant's).
    static func isFromRemovedDevice(_ path: String, _ membership: Membership) -> Bool {
        guard let sender = GrantPath(path)?.sender else { return true }
        return membership.stamps[sender]?.deleted == true
    }

    /// After removing devices: a new key for the remaining ones. The new key is left in
    /// each remaining device's inbox, the updated list of devices is written to the
    /// old mailbox too (for devices that have not switched yet), and the cellar to
    /// the new mailbox. The removed devices cannot find or read the new mailbox.
    ///
    /// `newKey` is made once by the caller and kept until the rotation succeeds, so a
    /// retry after a failure gives the same key: grants already left stay valid.
    /// The old mailbox gets only the list of devices and the clock, not the cellar:
    /// the removed device can still read that mailbox.
    public static func rotate(store: MailboxStore, identity: DeviceIdentity, from oldKey: CellarKey, to newKey: CellarKey,
                              local: SyncSnapshot, storage: String?) async throws {
        try await grant(newKey, to: local.membership.active, store: store, identity: identity, storage: storage)
        var notice = local
        notice.cellar = Cellar()
        notice.state.stamps = [:]
        try await write(notice, to: store.mailbox(id: oldKey.mailboxID, token: oldKey.token), key: oldKey, device: identity.id)
        try await write(local, to: store.mailbox(id: newKey.mailboxID, token: newKey.token), key: newKey, device: identity.id)
    }

    /// Gives the current key to devices that may not have it (a device that joined
    /// while remote sync was already on, met only on the network). Devices whose
    /// record has no inbox yet are skipped: they get the key on the local network.
    public static func grant(_ key: CellarKey, to members: [Member], store: MailboxStore,
                             identity: DeviceIdentity, storage: String?) async throws {
        for member in members where member.id != identity.id {
            guard let inbox = member.inbox, RelayPath.isValidMailbox(inbox), inbox.hasPrefix("i") else { continue }
            let sealed = try KeyGrant(cellarKey: key, from: identity.id, storage: storage)
                .sealed(by: identity, for: member.publicKey)
            // The name has a random part: a file already there is not this grant.
            guard try await store.inbox(id: inbox, token: "").create(sealed.path, data: sealed.data) else {
                throw RemoteSyncError.unavailable("grant not created")
            }
        }
    }

    /// Changes whenever any object or device stamp changes.
    static func stampDigest(_ snapshot: SyncSnapshot) -> String {
        var hash = SHA256()
        for (key, stamp) in snapshot.state.stamps.sorted(by: { $0.key < $1.key }) {
            hash.update(data: Data("\(key) \(stamp.counter) \(stamp.device.lowercased) \(stamp.deleted)\n".utf8))
        }
        for (id, stamp) in snapshot.membership.stamps.sorted(by: { $0.key.lowercased < $1.key.lowercased }) {
            hash.update(data: Data("member \(id.lowercased) \(stamp.counter) \(stamp.device.lowercased) \(stamp.deleted)\n".utf8))
        }
        return Data(hash.finalize()).hexString
    }

    /// A photo or a document. Photo IDs from before sync are UUIDs in uppercase; paths are lowercase.
    static func photoPath(_ id: String) -> String { "photo/\(id.lowercased())" }

    static func write(_ snapshot: SyncSnapshot, to mailbox: Mailbox, key: CellarKey, device: UUID) async throws {
        let path = SnapshotPath(device: device, clock: snapshot.state.clock).string
        let data = try Envelope.seal(encodeSnapshot(snapshot), kind: .snapshot, path: path, key: key.dataKey)
        try await mailbox.create(path, data: data)
        // Older snapshots of this device: keep the newest few. The snapshot is written,
        // so a failure here is left for the next round.
        let mine = ((try? await mailbox.list(prefix: "snap/\(device.lowercased)/")) ?? []).compactMap(SnapshotPath.init)
            .sorted { $0.clock > $1.clock }
        for old in mine.dropFirst(keptSnapshots) { try? await mailbox.delete(old.string) }
    }

    static func encodeSnapshot(_ snapshot: SyncSnapshot) throws -> Data {
        var message = WireMessage(type: "snapshot")
        message.cellarData = try JSONFileCellarStore.makeEncoder().encode(snapshot.cellar)
        message.syncData = try JSONEncoder.sync.encode(snapshot.state)
        message.membersData = try JSONEncoder.sync.encode(snapshot.membership)
        return try message.encoded()
    }

    static func decodeSnapshot(_ data: Data) throws -> SyncSnapshot {
        let message = try WireMessage.decode(data).require("snapshot")
        guard let cellarData = message.cellarData, let syncData = message.syncData, let membersData = message.membersData
        else { throw RemoteSyncError.corrupted("snapshot") }
        return SyncSnapshot(cellar: try JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: cellarData),
                            state: try JSONDecoder.sync.decode(SyncState.self, from: syncData),
                            membership: try JSONDecoder.sync.decode(Membership.self, from: membersData))
    }
}

/// `grant/<cellar>-<epoch, 8 digits>-<sender>-<8 random hex>` (lowercase UUIDs). The
/// random part keeps anyone who can write to an inbox from creating a grant's file
/// before its sender does.
struct GrantPath: Equatable {
    let cellarID: UUID
    let epoch: UInt32
    let sender: UUID
    let suffix: String

    init(cellarID: UUID, epoch: UInt32, sender: UUID, suffix: String = RandomHex.make(bytes: 4)) {
        self.cellarID = cellarID; self.epoch = epoch; self.sender = sender; self.suffix = suffix
    }

    init?(_ string: String) {
        guard string.hasPrefix("grant/") else { return nil }
        let parts = string.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false).map(String.init)
        // Cellar (a UUID: 5 parts), epoch, sender (5 parts), random part.
        guard parts.count == 12 else { return nil }
        let cellar = parts[0..<5].joined(separator: "-"), sender = parts[6..<11].joined(separator: "-")
        let epochText = parts[5], suffix = parts[11]
        guard let cellarID = UUID(uuidString: cellar), cellar == cellarID.lowercased,
              let senderID = UUID(uuidString: sender), sender == senderID.lowercased,
              (8...10).contains(epochText.count), epochText.allSatisfy(\.isASCIIDigit), let epoch = UInt32(epochText),
              suffix.count == 8, suffix.allSatisfy(\.isLowercaseHexDigit) else { return nil }
        self.init(cellarID: cellarID, epoch: epoch, sender: senderID, suffix: suffix)
    }

    var string: String {
        "grant/\(cellarID.lowercased)-\(String(format: "%08u", epoch))-\(sender.lowercased)-\(suffix)"
    }
}

enum RandomHex {
    static func make(bytes: Int) -> String { Data((0..<bytes).map { _ in UInt8.random(in: .min ... .max) }).hexString }
}

extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
    var isLowercaseHexDigit: Bool { isASCIIDigit || ("a"..."f").contains(self) }
}

/// `snap/<device>/<clock, 16 digits>-<8 random hex>`: the random part keeps two
/// snapshots with the same clock (after a new epoch, say) from colliding.
struct SnapshotPath {
    let device: UUID
    let clock: UInt64
    let suffix: String

    init(device: UUID, clock: UInt64) {
        self.device = device; self.clock = clock
        suffix = RandomHex.make(bytes: 4)
    }

    init?(_ string: String) {
        let parts = string.split(separator: "/")
        guard parts.count == 3, parts[0] == "snap", let device = UUID(uuidString: String(parts[1])) else { return nil }
        let name = parts[2].split(separator: "-")
        guard name.count == 2, name[0].count == 16, name[0].allSatisfy(\.isASCIIDigit), let clock = UInt64(name[0])
        else { return nil }
        self.device = device; self.clock = clock; suffix = String(name[1])
    }

    var string: String { "snap/\(device.lowercased)/\(String(format: "%016llu", clock))-\(suffix)" }
}

// MARK: - Simple storages

/// Files in memory. For tests and previews.
public final class MemoryMailboxStore: MailboxStore, @unchecked Sendable {
    private let lock = NSLock()
    private var files: [String: [String: Data]] = [:]

    public init() {}

    public func mailbox(id: String, token: String) -> Mailbox { Box(store: self, id: id) }
    public func inbox(id: String, token: String) -> Mailbox { Box(store: self, id: id) }

    /// Every file, for tests that look at what the storage holds.
    public var allFiles: [String: [String: Data]] { lock.withLock { files } }

    struct Box: Mailbox {
        let store: MemoryMailboxStore
        let id: String

        func list(prefix: String) async throws -> [String] {
            store.lock.withLock { (store.files[id] ?? [:]).keys.filter { $0.hasPrefix(prefix) }.sorted() }
        }
        func get(_ path: String) async throws -> Data? { store.lock.withLock { store.files[id]?[path] } }
        func create(_ path: String, data: Data) async throws -> Bool {
            store.lock.withLock {
                guard store.files[id]?[path] == nil else { return false }
                store.files[id, default: [:]][path] = data
                return true
            }
        }
        func delete(_ path: String) async throws { _ = store.lock.withLock { store.files[id]?.removeValue(forKey: path) } }
    }
}

/// Files in a folder: one subfolder per mailbox. For tests and for any synced folder.
public struct FolderMailboxStore: MailboxStore {
    public let root: URL

    public init(root: URL) { self.root = root }

    public func mailbox(id: String, token: String) -> Mailbox { Folder(url: root.appendingPathComponent(id, isDirectory: true)) }
    public func inbox(id: String, token: String) -> Mailbox { Folder(url: root.appendingPathComponent(id, isDirectory: true)) }

    struct Folder: Mailbox {
        let url: URL

        private func file(_ path: String) throws -> URL {
            guard RelayPath.isValid(path) else { throw RemoteSyncError.corrupted(path) }
            return url.appendingPathComponent(path)
        }

        func list(prefix: String) async throws -> [String] {
            guard let enumerator = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil) else { return [] }
            let base = url.standardizedFileURL.path + "/"
            return enumerator.compactMap { ($0 as? URL)?.standardizedFileURL.path }
                .filter { $0.hasPrefix(base) }
                .map { String($0.dropFirst(base.count)) }
                .filter { $0.hasPrefix(prefix) && !$0.hasSuffix("/") && RelayPath.isValid($0) }
                .filter { path in (try? file(path)).map { !FileManager.default.directoryExists(at: $0) } ?? false }
                .sorted()
        }

        func get(_ path: String) async throws -> Data? { try? Data(contentsOf: file(path)) }

        func create(_ path: String, data: Data) async throws -> Bool {
            let target = try file(path)
            guard !FileManager.default.fileExists(atPath: target.path) else { return false }
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: target, options: [.withoutOverwriting])
            return true
        }

        func delete(_ path: String) async throws { try? FileManager.default.removeItem(at: file(path)) }
    }
}

/// Rules for paths, shared with the relay: lowercase letters, digits, "-", "_"
/// and "/" between parts, no empty or dot parts, at most 200 characters.
public enum RelayPath {
    /// "c" or "i" followed by 32 lowercase hex characters.
    public static func isValidMailbox(_ id: String) -> Bool {
        guard id.count == 33, let kind = id.first, kind == "c" || kind == "i" else { return false }
        return id.dropFirst().allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }

    public static func isValid(_ path: String) -> Bool {
        guard !path.isEmpty, path.count <= 200, !path.hasPrefix("/"), !path.hasSuffix("/") else { return false }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        return parts.allSatisfy { part in
            !part.isEmpty && part != "." && part != ".."
                && part.allSatisfy { $0.isASCII && ($0.isLowercase || $0.isNumber || $0 == "-" || $0 == "_") }
        }
    }
}

extension FileManager {
    func directoryExists(at url: URL) -> Bool {
        var isDirectory: ObjCBool = false
        return fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue
    }
}

extension UUID {
    var lowercased: String { uuidString.lowercased() }
}

extension String {
    var uuidValue: UUID? { UUID(uuidString: self) }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
