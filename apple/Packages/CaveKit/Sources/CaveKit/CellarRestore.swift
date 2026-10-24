import Foundation

/// How a backup or an earlier version is put back.
public enum RestoreMode: String, Sendable, CaseIterable {
    /// Adds the wines, bottles, racks and Journal entries that are not in the cellar (by
    /// ID), and the saved searches whose name is not there (ignoring case); nothing already
    /// there changes, the currency included.
    case addMissing
    /// The cellar becomes the backup, its settings (currency, saved searches) included:
    /// what is not in it is deleted, what differs is put back as it was. Applied as a
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
        // A record that only differs by what a backup does not keep is the same.
        let compared = current.matching(backup)
        compare(compared.wines, current.wines)
        compare(compared.bottles, current.bottles)
        compare(CellarMerge.byID(compared.racks), CellarMerge.byID(current.racks))
        compare(compared.discoveries, current.discoveries)
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
            return matching(backup)
        }
    }

    /// `backup`, with each record that differs from this cellar's only by what a JSON backup
    /// does not keep (fractions of a second in dates) taken from this cellar instead. A
    /// backup made a minute ago then reads as the same cellar, and Replace leaves those
    /// records as they are: not stamped, not sent again. The Android app does the same.
    func matching(_ backup: Cellar) -> Cellar {
        // Only the records that differ are written out and read back, as a backup would be.
        func differing<T: Equatable>(_ own: [UUID: T], _ other: [UUID: T]) -> [UUID: T] {
            own.filter { id, value in other[id].map { $0 != value } ?? false }
        }
        let backupRacks = CellarMerge.byID(backup.racks)
        var written = Cellar(settings: settings)
        written.wines = differing(wines, backup.wines)
        written.bottles = differing(bottles, backup.bottles)
        written.racks = racks.filter { rack in backupRacks[rack.id].map { $0 != rack } ?? false }
        written.discoveries = differing(discoveries, backup.discoveries)
        guard let data = try? CellarExport.json(written),
              let read = try? CellarExport.backup(fromJSON: data).cellar else { return backup }
        func pick<T: Equatable>(_ other: [UUID: T], _ readBack: [UUID: T], _ own: [UUID: T]) -> [UUID: T] {
            var result = other
            for (id, value) in other where readBack[id] == value { result[id] = own[id] }
            return result
        }
        let readRacks = CellarMerge.byID(read.racks), ownRacks = CellarMerge.byID(racks)
        var result = backup
        result.wines = pick(backup.wines, read.wines, wines)
        result.bottles = pick(backup.bottles, read.bottles, bottles)
        result.racks = backup.racks.map { rack in readRacks[rack.id] == rack ? ownRacks[rack.id] ?? rack : rack }
        if read.settings == backup.settings { result.settings = settings }
        result.discoveries = pick(backup.discoveries, read.discoveries, discoveries)
        return result
    }
}
