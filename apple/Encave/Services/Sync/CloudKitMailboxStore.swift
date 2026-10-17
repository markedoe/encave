import CaveKit
import CloudKit
import Foundation

/// Remote sync through the user's own iCloud: each mailbox is a record zone in the
/// app's private CloudKit database, each file a record holding the encrypted bytes.
/// No server of ours, nothing to set up for the user, and Apple only ever sees
/// ciphertext. Works between devices signed in to the same Apple Account.
///
/// Available when the build declares a container (Info.plist key
/// `EncaveCloudKitContainer`, set with the iCloud entitlement; see docs/development.md).
struct CloudKitMailboxStore: MailboxStore {
    let database: CKDatabase

    /// The configured container, if this build has one.
    static var containerIdentifier: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "EncaveCloudKitContainer") as? String,
              !id.isEmpty, !id.hasPrefix("$(") else { return nil }
        return id
    }

    static var isConfigured: Bool { containerIdentifier != nil }

    /// Nil if the build has no container.
    static func make() -> CloudKitMailboxStore? {
        containerIdentifier.map { CloudKitMailboxStore(database: CKContainer(identifier: $0).privateCloudDatabase) }
    }

    /// Whether the user is signed in to iCloud with iCloud Drive available to apps.
    static func accountAvailable() async -> Bool {
        guard let id = containerIdentifier else { return false }
        return (try? await CKContainer(identifier: id).accountStatus()) == .available
    }

    func mailbox(id: String, token: String) -> Mailbox { Zone(database: database, zone: CKRecordZone.ID(zoneName: id)) }
    func inbox(id: String, token: String) -> Mailbox { Zone(database: database, zone: CKRecordZone.ID(zoneName: id)) }

    struct Zone: Mailbox {
        let database: CKDatabase
        let zone: CKRecordZone.ID
        static let recordType = "EncaveFile"

        private func recordID(_ path: String) throws -> CKRecord.ID {
            guard RelayPath.isValid(path) else { throw RemoteSyncError.corrupted(path) }
            return CKRecord.ID(recordName: path, zoneID: zone)
        }

        func list(prefix: String) async throws -> [String] {
            var names: [String] = []
            var token: CKServerChangeToken?
            var more = true
            do {
                while more {
                    let changes = try await database.recordZoneChanges(inZoneWith: zone, since: token, desiredKeys: [])
                    for (id, result) in changes.modificationResultsByID where (try? result.get()) != nil {
                        if id.recordName.hasPrefix(prefix) { names.append(id.recordName) }
                    }
                    names.removeAll { name in changes.deletions.contains { $0.recordID.recordName == name } }
                    token = changes.changeToken
                    more = changes.moreComing
                }
            } catch let error as CKError where error.code == .zoneNotFound || error.code == .userDeletedZone {
                return []
            } catch {
                throw Self.map(error)
            }
            return names.sorted()
        }

        func get(_ path: String) async throws -> Data? {
            do {
                let record = try await database.record(for: recordID(path))
                guard let url = (record["data"] as? CKAsset)?.fileURL else { return nil }
                return try Data(contentsOf: url)
            } catch let error as CKError where error.code == .unknownItem || error.code == .zoneNotFound {
                return nil
            } catch {
                throw Self.map(error)
            }
        }

        func create(_ path: String, data: Data) async throws -> Bool {
            let record = CKRecord(recordType: Self.recordType, recordID: try recordID(path))
            let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try data.write(to: file)
            defer { try? FileManager.default.removeItem(at: file) }
            record["data"] = CKAsset(fileURL: file)
            for attempt in 0..<2 {
                do {
                    let (saved, _) = try await database.modifyRecords(saving: [record], deleting: [],
                                                                      savePolicy: .ifServerRecordUnchanged)
                    switch saved[record.recordID] {
                    case .success: return true
                    case .failure(let error as CKError) where error.code == .serverRecordChanged: return false
                    case .failure(let error as CKError) where error.code == .zoneNotFound && attempt == 0:
                        try await createZone()
                    case .failure(let error): throw Self.map(error)
                    case nil: throw RemoteSyncError.unavailable("no result")
                    }
                } catch let error as CKError where error.code == .zoneNotFound && attempt == 0 {
                    try await createZone()
                } catch let error as RemoteSyncError {
                    throw error
                } catch {
                    throw Self.map(error)
                }
            }
            return false
        }

        func delete(_ path: String) async throws {
            do {
                _ = try await database.modifyRecords(saving: [], deleting: [recordID(path)])
            } catch let error as CKError where error.code == .unknownItem || error.code == .zoneNotFound {
                return
            } catch {
                throw Self.map(error)
            }
        }

        private func createZone() async throws {
            do {
                _ = try await database.modifyRecordZones(saving: [CKRecordZone(zoneID: zone)], deleting: [])
            } catch {
                throw Self.map(error)
            }
        }

        static func map(_ error: Error) -> RemoteSyncError {
            guard let error = error as? CKError else { return .unavailable(error.localizedDescription) }
            switch error.code {
            case .notAuthenticated, .permissionFailure: return .unauthorized
            case .quotaExceeded: return .quotaExceeded
            default: return .unavailable(error.localizedDescription)
            }
        }
    }
}
