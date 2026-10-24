import Foundation
import Testing
@testable import CaveKit

@Suite struct RemoteProgressTests {
    /// The app moves the progress on only once what was read is saved: a snapshot that
    /// could not be taken in is read again next round, and only then marked as seen.
    @Test func aSnapshotNotTakenInIsReadAgainWithTheOldProgress() async throws {
        let store = MemoryMailboxStore()
        var devices = household(2)
        try await RemoteSync.grant(devices[0].key!, to: devices[0].snapshot.membership.active, store: store,
                                   identity: devices[0].identity, storage: "test")
        try devices[0].edit { try $0.addBottles(of: Wine(name: "From A"), quantity: 1) }
        try await devices[0].sync(store)

        let b = devices[1]
        let key = try #require(try await RemoteSync.receiveGrant(store: store, identity: b.identity,
                                                                 membership: b.snapshot.membership, current: nil)?.cellarKey)
        func round(_ progress: RemoteProgress) async throws -> RemoteSync.Result {
            try await RemoteSync.run(store: store, identity: b.identity, key: key, local: b.snapshot,
                                     progress: progress, files: b.photos.access)
        }
        let first = try await round(b.progress)
        #expect(first.snapshot?.cellar.wines.values.map(\.name) == ["From A"])
        // Taking it in failed: the progress was not kept, so the snapshot comes again.
        let again = try await round(b.progress)
        #expect(again.snapshot?.cellar.wines.values.map(\.name) == ["From A"])
        // Once kept, it is not read again.
        let after = try await round(again.progress)
        #expect(after.snapshot == nil)
    }
}
