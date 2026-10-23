import Foundation

/// How a backup or an earlier version is put back.
public enum RestoreMode: String, Sendable, CaseIterable {
    /// Adds the wines, bottles, racks, Journal entries and saved searches that are not in
    /// the cellar (by ID); nothing already there changes.
    case addMissing
    /// The cellar becomes the backup: what is not in it is deleted, what differs is put
    /// back as it was. The settings (currency, saved searches) stay as they are: they are
    /// preferences, not the cellar, and an older JSON backup may hold none. Applied as a
    /// change made on this device, so sync takes it to the other devices (docs/SYNC.md).
    case replace
}

/// What a backup or an earlier version holds, and how it differs from the cellar, shown
/// before restoring it. The Android app counts the same way.
public struct RestorePreview: Equatable, Sendable {
    /// When the backup was made, or when the version was saved.
    public var date: Date
    /// Wines with bottles in the cellar (`CellarCounts.winesWithBottles`).
    public var wines: Int
    /// Bottles in the cellar (not opened, given away, or on order).
    public var bottles: Int
    public var racks: Int
    /// Bottles opened and wines tasted elsewhere.
    public var journalEntries: Int
    public var photos: Int
    public var documents: Int
    /// Wines, bottles, racks and wines tasted elsewhere in the backup and not in the cellar
    /// (what Add What Is Missing adds).
    public var added: Int
    /// In the cellar and not in the backup (what Replace deletes).
    public var removed: Int
    /// In both, but different (what Replace puts back as it was).
    public var changed: Int

    public init(of backup: Cellar, date: Date, comparedWith current: Cellar) {
        self.date = date
        wines = backup.winesWithBottlesCount
        bottles = backup.bottlesInCellarCount
        racks = backup.racks.count
        journalEntries = backup.bottles.values.filter { $0.status == .consumed }.count + backup.discoveries.count
        photos = backup.photoIDs.count
        documents = backup.attachmentIDs.count
        var added = 0, removed = 0, changed = 0
        func compare<T: Equatable>(_ a: [UUID: T], _ b: [UUID: T]) {
            for (id, value) in a {
                if let other = b[id] { if other != value { changed += 1 } } else { added += 1 }
            }
            removed += b.keys.filter { a[$0] == nil }.count
        }
        compare(backup.wines, current.wines)
        compare(backup.bottles, current.bottles)
        compare(CellarMerge.byID(backup.racks), CellarMerge.byID(current.racks))
        compare(backup.discoveries, current.discoveries)
        self.added = added
        self.removed = removed
        self.changed = changed
    }

    /// The backup holds the same cellar.
    public var isSame: Bool { added == 0 && removed == 0 && changed == 0 }
}

public extension Cellar {
    /// The cellar after restoring `backup`.
    func restoring(_ backup: Cellar, mode: RestoreMode) -> Cellar {
        switch mode {
        case .addMissing:
            var copy = self
            copy.merge(backup)
            return copy
        case .replace:
            var copy = backup
            copy.settings = settings
            return copy
        }
    }
}
