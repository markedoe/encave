import Foundation
import Testing
@testable import CaveKit

@Suite struct SearchIndexSnapshotTests {
    let a = UUID(uuidString: "00000000-0000-0000-0000-00000000000A")!
    let b = UUID(uuidString: "00000000-0000-0000-0000-00000000000B")!
    let c = UUID(uuidString: "00000000-0000-0000-0000-00000000000C")!

    func entry(_ name: String, _ detail: String = "1 bottle") -> SearchIndexSnapshot.Entry {
        .init(name: name, detail: detail)
    }

    @Test func firstRunRebuildsEverything() {
        let changes = SearchIndexSnapshot().changes(to: [b: entry("Chablis"), a: entry("Morgon")])
        #expect(changes.isFullReindex)
        #expect(changes.toIndex == [a, b])
        #expect(changes.toRemove.isEmpty)
        #expect(changes.namesChanged)
    }

    @Test func emptyCellarAtFirstRunStillClearsTheIndex() {
        let changes = SearchIndexSnapshot().changes(to: [:])
        #expect(changes.isFullReindex)
        #expect(!changes.isEmpty)
    }

    @Test func nothingChanged() {
        let content = [a: entry("Morgon"), b: entry("Chablis")]
        var snapshot = SearchIndexSnapshot()
        snapshot.record(content)
        #expect(snapshot.changes(to: content).isEmpty)
    }

    @Test func onlyChangedAddedAndRemovedWines() {
        var snapshot = SearchIndexSnapshot()
        snapshot.record([a: entry("Morgon"), b: entry("Chablis")])
        let changes = snapshot.changes(to: [a: entry("Morgon", "2 bottles"), c: entry("Chablis")])
        #expect(!changes.isFullReindex)
        #expect(changes.toIndex == [a, c])
        #expect(changes.toRemove == [b])
        // Still a Morgon and a Chablis.
        #expect(!changes.namesChanged)
    }

    @Test func renamedWineChangesTheNames() {
        var snapshot = SearchIndexSnapshot()
        snapshot.record([a: entry("Morgon")])
        let changes = snapshot.changes(to: [a: entry("Fleurie")])
        #expect(changes.toIndex == [a])
        #expect(changes.namesChanged)
    }

    @Test func invalidatedSnapshotRebuilds() {
        var snapshot = SearchIndexSnapshot()
        snapshot.record([a: entry("Morgon")])
        snapshot.invalidate()
        #expect(snapshot.changes(to: [a: entry("Morgon")]).isFullReindex)
    }
}
