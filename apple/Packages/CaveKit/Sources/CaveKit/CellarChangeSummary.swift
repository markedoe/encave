import Foundation

/// What a sync brought into the cellar, to tell the user ("From Marc's iPhone:
/// 2 bottles opened, 3 bottles added"). Computed on the device from the cellar
/// before and after applying the sync; nothing leaves the device.
public struct CellarChangeSummary: Codable, Equatable, Sendable {
    /// Bottles that were in the cellar (or on order) and are now opened.
    public var bottlesOpened = 0
    /// Bottles that appeared, in the cellar or on order.
    public var bottlesAdded = 0
    /// Bottles that left the cellar without being opened (given away, broken), or were deleted.
    public var bottlesRemoved = 0
    public var winesAdded = 0
    /// Every other object that changed: wines edited or deleted, racks, discoveries,
    /// settings, and bottles changed otherwise (moved, received, edited).
    public var otherChanges = 0
    /// The devices that made the changes, from the stamps of the changed objects.
    /// This device and objects stamped before sync was set up are not counted.
    public var devices: Set<UUID> = []

    public init() {}

    public var isEmpty: Bool {
        bottlesOpened == 0 && bottlesAdded == 0 && bottlesRemoved == 0 && winesAdded == 0 && otherChanges == 0
    }

    /// Only bottles opened: the Journal is the place to look.
    public var isOnlyOpened: Bool {
        bottlesOpened > 0 && bottlesAdded == 0 && bottlesRemoved == 0 && winesAdded == 0 && otherChanges == 0
    }

    /// Compares the cellar before and after a sync. `stamps` are the stamps after
    /// the sync; `localDevice` is this device.
    public static func between(_ before: Cellar, _ after: Cellar, stamps: [String: Stamp],
                               localDevice: UUID) -> CellarChangeSummary {
        var summary = CellarChangeSummary()
        func credit(_ key: String) {
            guard let stamp = stamps[key], stamp.device != localDevice, stamp.device != Stamp.zero.device else { return }
            summary.devices.insert(stamp.device)
        }
        let present: Set<Bottle.Status> = [.inCellar, .ordered]

        for id in Set(before.bottles.keys).union(after.bottles.keys) {
            let old = before.bottles[id], new = after.bottles[id]
            guard old != new else { continue }
            switch (old, new) {
            case (nil, let new?):
                switch new.status {
                case .inCellar, .ordered: summary.bottlesAdded += 1
                case .consumed: summary.bottlesOpened += 1
                case .gone: summary.otherChanges += 1
                }
            case (let old?, nil):
                if present.contains(old.status) { summary.bottlesRemoved += 1 } else { summary.otherChanges += 1 }
            case (let old?, let new?):
                if present.contains(old.status), new.status == .consumed {
                    summary.bottlesOpened += 1
                } else if present.contains(old.status), new.status == .gone {
                    summary.bottlesRemoved += 1
                } else {
                    summary.otherChanges += 1
                }
            case (nil, nil):
                continue
            }
            credit(SyncKey(.bottle, id).string)
        }

        for id in Set(before.wines.keys).union(after.wines.keys) where before.wines[id] != after.wines[id] {
            if before.wines[id] == nil { summary.winesAdded += 1 } else { summary.otherChanges += 1 }
            credit(SyncKey(.wine, id).string)
        }

        func others<T: Equatable>(_ kind: SyncKey.Kind, _ a: [UUID: T], _ b: [UUID: T]) {
            for id in Set(a.keys).union(b.keys) where a[id] != b[id] {
                summary.otherChanges += 1
                credit(SyncKey(kind, id).string)
            }
        }
        others(.discovery, before.discoveries, after.discoveries)
        others(.rack, CellarMerge.byID(before.racks), CellarMerge.byID(after.racks))
        others(.search, CellarMerge.byID(before.settings.savedSearches), CellarMerge.byID(after.settings.savedSearches))
        if before.settings.withoutSearches != after.settings.withoutSearches {
            summary.otherChanges += 1
            credit(SyncKey.settings)
        }
        if before.racks.map(\.id) != after.racks.map(\.id), Set(before.racks.map(\.id)) == Set(after.racks.map(\.id)) {
            // Racks only put in another order (added or removed racks are counted above).
            summary.otherChanges += 1
            credit(SyncKey.rackOrder)
        }
        return summary
    }

    /// Adds the changes of a later sync, for one notification about several syncs.
    public mutating func add(_ other: CellarChangeSummary) {
        bottlesOpened += other.bottlesOpened
        bottlesAdded += other.bottlesAdded
        bottlesRemoved += other.bottlesRemoved
        winesAdded += other.winesAdded
        otherChanges += other.otherChanges
        devices.formUnion(other.devices)
    }

    /// The one device the changes came from, if there is exactly one and the cellar
    /// knows it; nil means "your other devices".
    public func device(in membership: Membership?) -> Member? {
        guard devices.count == 1, let id = devices.first else { return nil }
        return membership?.members[id]
    }
}
