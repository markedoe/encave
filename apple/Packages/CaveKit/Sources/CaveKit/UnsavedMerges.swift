import Foundation

/// The stamps that merges replaced, kept until the merged cellar is saved. If the write
/// fails, the cellar goes back to what was saved, and so do these stamps: the next sync
/// takes the other devices' changes in again instead of believing it has them.
public struct UnsavedMerges: Sendable {
    /// Oldest first: the generation of the merged cellar, and the stamps before the merge.
    private var merges: [(generation: Int, before: [String: Stamp?])] = []

    public init() {}

    public var isEmpty: Bool { merges.isEmpty }

    /// A merge changed the stamps from `before` to `after`; its cellar was sent to be saved
    /// as change number `generation`.
    public mutating func add(before: [String: Stamp], after: [String: Stamp], generation: Int) {
        var changed: [String: Stamp?] = [:]
        for key in Set(before.keys).union(after.keys) where before[key] != after[key] {
            changed[key] = .some(before[key])
        }
        guard !changed.isEmpty else { return }
        merges.append((generation, changed))
    }

    /// The cellar numbered `generation` (or a newer one built on it) is saved.
    public mutating func saved(through generation: Int) {
        merges.removeAll { $0.generation <= generation }
    }

    /// A write failed and the cellar went back to the last one saved: the stamps of what
    /// was merged since go back too (the oldest stamp seen for each object).
    public mutating func revert(_ state: inout SyncState) {
        for merge in merges.reversed() {
            for (key, stamp) in merge.before { state.stamps[key] = stamp }
        }
        merges = []
    }
}
