import Foundation

/// What a sync brought into the cellar, to tell the user ("From Marc's iPhone:
/// 2 bottles opened, 3 bottles added."). Computed on the device from the cellar
/// before and after applying the sync; nothing leaves the device. The Android app
/// follows the same rules.
public struct CellarChangeSummary: Codable, Equatable, Sendable {
    /// Bottles that became opened, or arrived already opened.
    public var bottlesOpened = 0
    /// Bottles that appeared in the cellar or on order.
    public var bottlesAdded = 0
    /// Bottles that disappeared, or were given away, sold or broken.
    public var bottlesRemoved = 0
    public var winesAdded = 0
    /// Every other change: wines edited, racks, discoveries, saved searches, settings,
    /// and bottles changed otherwise (moved, received, edited). A deleted wine is not
    /// counted: its bottles are.
    public var otherChanges = 0
    /// The devices that made the changes, from the stamps of the changed objects.
    public var devices: Set<UUID> = []
    /// Some changes have no stamp (or one from before sync was set up): who made them
    /// is not known.
    public var hasUnknownDevice = false

    public init() {}

    public var isEmpty: Bool {
        bottlesOpened == 0 && bottlesAdded == 0 && bottlesRemoved == 0 && winesAdded == 0 && otherChanges == 0
    }

    /// Only bottles opened: the Journal is the place to look.
    public var isOnlyOpened: Bool {
        bottlesOpened > 0 && bottlesAdded == 0 && bottlesRemoved == 0 && winesAdded == 0 && otherChanges == 0
    }

    /// Compares the cellar before and after a sync. `stamps` are the stamps after
    /// the sync; `localDevice` is this device, whose own changes are left out.
    public static func between(_ before: Cellar, _ after: Cellar, stamps: [String: Stamp],
                               localDevice: UUID) -> CellarChangeSummary {
        var summary = CellarChangeSummary()
        /// Credits the change to its device; false for a change made on this device.
        func credit(_ key: String) -> Bool {
            guard let stamp = stamps[key], stamp.device != Stamp.zero.device else {
                summary.hasUnknownDevice = true
                return true
            }
            guard stamp.device != localDevice else { return false }
            summary.devices.insert(stamp.device)
            return true
        }

        for id in Set(before.bottles.keys).union(after.bottles.keys) where before.bottles[id] != after.bottles[id] {
            guard credit(SyncKey(.bottle, id).string) else { continue }
            switch (before.bottles[id], after.bottles[id]) {
            case (nil, let new?):
                switch new.status {
                case .inCellar, .ordered: summary.bottlesAdded += 1
                case .consumed: summary.bottlesOpened += 1
                case .gone: summary.otherChanges += 1
                }
            case (_?, nil):
                summary.bottlesRemoved += 1
            case (let old?, let new?):
                if new.status == .consumed, old.status != .consumed {
                    summary.bottlesOpened += 1
                } else if new.status == .gone, old.status != .gone {
                    summary.bottlesRemoved += 1
                } else {
                    summary.otherChanges += 1
                }
            case (nil, nil):
                break
            }
        }

        for (id, wine) in after.wines where before.wines[id] != wine {
            guard credit(SyncKey(.wine, id).string) else { continue }
            if before.wines[id] == nil { summary.winesAdded += 1 } else { summary.otherChanges += 1 }
        }

        func others<T: Equatable>(_ kind: SyncKey.Kind, _ a: [UUID: T], _ b: [UUID: T]) {
            for id in Set(a.keys).union(b.keys) where a[id] != b[id] {
                if credit(SyncKey(kind, id).string) { summary.otherChanges += 1 }
            }
        }
        others(.discovery, before.discoveries, after.discoveries)
        others(.rack, CellarMerge.byID(before.racks), CellarMerge.byID(after.racks))
        others(.search, CellarMerge.byID(before.settings.savedSearches), CellarMerge.byID(after.settings.savedSearches))
        if before.settings.withoutSearches != after.settings.withoutSearches, credit(SyncKey.settings) {
            summary.otherChanges += 1
        }
        // Racks only put in another order (added or removed racks are counted above).
        if before.racks.map(\.id) != after.racks.map(\.id), Set(before.racks.map(\.id)) == Set(after.racks.map(\.id)),
           credit(SyncKey.rackOrder) {
            summary.otherChanges += 1
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
        hasUnknownDevice = hasUnknownDevice || other.hasUnknownDevice
    }

    /// The one device the changes came from, if there is exactly one, no change of an
    /// unknown device, and the cellar knows it; nil means "your other devices".
    public func device(in membership: Membership?) -> Member? {
        guard !hasUnknownDevice, devices.count == 1, let id = devices.first else { return nil }
        return membership?.members[id]
    }
}
