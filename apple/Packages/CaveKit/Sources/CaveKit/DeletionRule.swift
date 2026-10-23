import Foundation

/// What is being deleted, for `DeletionRule`.
public enum DeletionKind: String, Sendable, CaseIterable {
    case bottle, wine, tasting, discovery, savedSearch, storage
}

/// How a deletion is guarded: one rule on every platform and screen.
public enum DeletionGuard: Sendable, Equatable {
    /// Done at once, with an Undo banner (and, on Apple, the window's Undo).
    case undo
    /// Asked first, in a destructive alert naming the thing and its count.
    case confirm
}

public enum DeletionRule {
    /// A single thing that can be undone goes at once, with Undo. Several things, a
    /// storage, and anything that cannot be undone are confirmed first.
    ///
    /// `count` is how many things go: for a wine, its bottles (in the cellar, on order or
    /// opened, as their tastings go with them).
    public static func guarding(_ kind: DeletionKind, count: Int, undoable: Bool) -> DeletionGuard {
        if !undoable || kind == .storage || count > 1 { return .confirm }
        return .undo
    }
}

public extension Cellar {
    /// Bottle records (in the cellar, on order, opened or gone) deleted with these wines.
    func bottleRecordCount(ofWines wineIDs: Set<UUID>) -> Int {
        bottles.values.reduce(0) { $0 + (wineIDs.contains($1.wineID) ? 1 : 0) }
    }

    /// Deleting these wines: one wine with at most one bottle goes with Undo; several
    /// wines, or a wine with several bottles, are confirmed first.
    func deletionGuard(ofWines wineIDs: Set<UUID>) -> DeletionGuard {
        let count = max(wineIDs.count, bottleRecordCount(ofWines: wineIDs))
        return DeletionRule.guarding(.wine, count: count, undoable: true)
    }

    /// Puts back a saved search deleted by mistake, where it was in the list (the Undo of
    /// `deleteSearch`). Nothing changes when it, or another with its name, is there again.
    mutating func restoreSearch(_ search: SavedSearch) {
        let list = settings.savedSearches ?? []
        guard !list.contains(where: { $0.id == search.id || $0.name.caseInsensitiveCompare(search.name) == .orderedSame })
        else { return }
        settings.savedSearches = SavedSearch.ordered(list + [search])
    }
}
