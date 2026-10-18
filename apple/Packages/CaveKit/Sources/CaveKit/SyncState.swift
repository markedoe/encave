import Foundation

/// When an object last changed, as a Lamport time: a counter that every device
/// advances past anything it has seen, plus the device that made the change.
/// Comparing stamps orders changes the same way on every device.
public struct Stamp: Codable, Hashable, Sendable, Comparable {
    public var counter: UInt64
    public var device: UUID
    /// The object was deleted at this time.
    public var deleted: Bool

    public init(counter: UInt64, device: UUID, deleted: Bool = false) {
        self.counter = counter; self.device = device; self.deleted = deleted
    }

    /// For objects that existed before sync was set up.
    public static let zero = Stamp(counter: 0, device: UUID(uuid: (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

    public static func < (a: Stamp, b: Stamp) -> Bool {
        if a.counter != b.counter { return a.counter < b.counter }
        if a.device != b.device { return a.device.uuidString < b.device.uuidString }
        // Same change seen twice: a deletion wins over the version it deleted.
        return !a.deleted && b.deleted
    }
}

/// What this device knows about changes, kept next to the cellar (not inside it,
/// so the cellar file stays the same for backups and the Android app).
public struct SyncState: Codable, Equatable, Sendable {
    public static let formatVersion = 1

    public var version: Int
    /// This device.
    public var device: UUID
    /// The highest counter seen; the next local change uses `clock + 1`.
    public var clock: UInt64
    /// Last change of each object, by key ("wine:…", "bottle:…", "rack:…",
    /// "discovery:…", "settings", "rack-order"). Deleted objects keep a stamp.
    public var stamps: [String: Stamp]

    public init(device: UUID = UUID(), clock: UInt64 = 0, stamps: [String: Stamp] = [:]) {
        version = Self.formatVersion
        self.device = device; self.clock = clock; self.stamps = stamps
    }

    /// Sync turned on for a cellar that already has data: every object gets a first stamp.
    public static func starting(with cellar: Cellar, device: UUID = UUID()) -> SyncState {
        var state = SyncState(device: device)
        state.record(from: Cellar(), to: cellar)
        return state
    }

    /// Stamps every object that differs between two versions of the cellar.
    /// Called after each local change.
    public mutating func record(from old: Cellar, to new: Cellar) {
        func compare<T: Equatable>(_ kind: SyncKey.Kind, _ before: [UUID: T], _ after: [UUID: T]) {
            for (id, value) in after where before[id] != value {
                stamp(SyncKey(kind, id).string, deleted: false)
            }
            for id in before.keys where after[id] == nil {
                stamp(SyncKey(kind, id).string, deleted: true)
            }
        }
        compare(.wine, old.wines, new.wines)
        compare(.bottle, old.bottles, new.bottles)
        compare(.discovery, old.discoveries, new.discoveries)
        compare(.rack, CellarMerge.byID(old.racks), CellarMerge.byID(new.racks))
        compare(.search, CellarMerge.byID(old.settings.savedSearches), CellarMerge.byID(new.settings.savedSearches))
        if old.settings.withoutSearches != new.settings.withoutSearches { stamp(SyncKey.settings, deleted: false) }
        if old.racks.map(\.id) != new.racks.map(\.id) { stamp(SyncKey.rackOrder, deleted: false) }
    }

    private mutating func stamp(_ key: String, deleted: Bool) {
        // Clocks are kept far below the limit by `SyncState.check`, so this cannot overflow.
        clock += 1
        stamps[key] = Stamp(counter: clock, device: device, deleted: deleted)
    }

    /// The largest counter a sender may use. Far below `UInt64.max`, so a device
    /// can never be pushed to the point where its next change overflows, and exact
    /// in JSON readers that use doubles.
    public static let maxCounter: UInt64 = 1 << 53

    /// Refuses a state from another device whose counters are out of bounds: over
    /// `maxCounter`, or over the sender's own clock (it would make a later local
    /// change look older than a remote one).
    /// Membership counters are only bounded: they may come from devices with a
    /// higher clock, and merging raises the local clock past them.
    public static func check(_ state: SyncState, membership: Membership? = nil) throws {
        let memberCounters = membership?.stamps.values.map(\.counter) ?? []
        guard state.clock <= maxCounter, state.stamps.values.allSatisfy({ $0.counter <= state.clock }),
              memberCounters.allSatisfy({ $0 <= maxCounter }) else {
            throw SyncError.protocolViolation("clock out of bounds")
        }
    }

    /// The stamp of an object, or `.zero` if it exists but was never stamped.
    func stamp(for key: String, exists: Bool) -> Stamp? {
        stamps[key] ?? (exists ? .zero : nil)
    }
}

/// Identifies an object in the stamps.
struct SyncKey {
    enum Kind: String { case wine, bottle, rack, discovery, search }
    static let settings = "settings"
    static let rackOrder = "rack-order"

    let kind: Kind
    let id: UUID
    init(_ kind: Kind, _ id: UUID) { self.kind = kind; self.id = id }
    var string: String { "\(kind.rawValue):\(id.uuidString.lowercased())" }
}

/// Merging two copies of a cellar: each object is taken from the copy where it
/// changed last. The result is the same whichever device runs the merge.
public enum CellarMerge {
    public struct Result: Sendable {
        public var cellar: Cellar
        public var stamps: [String: Stamp]
        public var clock: UInt64
    }

    /// Merges a remote copy into a local one. `local.device` stays the device.
    public static func merge(local: Cellar, _ localState: SyncState,
                             remote: Cellar, _ remoteState: SyncState) -> Result {
        var stamps: [String: Stamp] = [:]
        var merged = Cellar(settings: local.settings)

        func pick<T: Codable>(_ kind: SyncKey.Kind, _ a: [UUID: T], _ b: [UUID: T]) -> [UUID: T] {
            var result: [UUID: T] = [:]
            var ids = Set(a.keys).union(b.keys)
            let prefix = kind.rawValue + ":"
            for key in localState.stamps.keys where key.hasPrefix(prefix) {
                if let id = UUID(uuidString: String(key.dropFirst(prefix.count))) { ids.insert(id) }
            }
            for key in remoteState.stamps.keys where key.hasPrefix(prefix) {
                if let id = UUID(uuidString: String(key.dropFirst(prefix.count))) { ids.insert(id) }
            }
            for id in ids {
                let key = SyncKey(kind, id).string
                let sa = localState.stamp(for: key, exists: a[id] != nil)
                let sb = remoteState.stamp(for: key, exists: b[id] != nil)
                let (winner, stamp) = choose(a[id], sa, b[id], sb)
                if let stamp { stamps[key] = stamp }
                if let winner { result[id] = winner }
            }
            return result
        }

        merged.wines = pick(.wine, local.wines, remote.wines)
        merged.bottles = pick(.bottle, local.bottles, remote.bottles)
        merged.discoveries = pick(.discovery, local.discoveries, remote.discoveries)
        let racks = pick(.rack, byID(local.racks), byID(remote.racks))
        revive(&merged, stamps: &stamps, local: local, remote: remote)

        // Settings: one object, except saved searches, merged one by one so searches
        // saved on two devices at the same time are all kept.
        let settings = choose(local.settings, localState.stamps[SyncKey.settings] ?? .zero,
                              remote.settings, remoteState.stamps[SyncKey.settings] ?? .zero)
        merged.settings = settings.0 ?? local.settings
        if let stamp = settings.1 { stamps[SyncKey.settings] = stamp }
        let searches = pick(.search, byID(local.settings.savedSearches), byID(remote.settings.savedSearches))
        merged.settings.savedSearches = searches.isEmpty ? nil : SavedSearch.ordered(Array(searches.values))

        // Rack order: the order of the copy that reordered last (on equal stamps, the
        // list that is smaller compared as lowercase IDs), then the other racks by ID.
        let orderA = localState.stamps[SyncKey.rackOrder] ?? .zero
        let orderB = remoteState.stamps[SyncKey.rackOrder] ?? .zero
        let idsA = local.racks.map { $0.id.uuidString.lowercased() }
        let idsB = remote.racks.map { $0.id.uuidString.lowercased() }
        let remoteFirst = orderA == orderB ? idsB.lexicographicallyPrecedes(idsA) : orderB > orderA
        let base = remoteFirst ? remote.racks : local.racks
        if orderA != .zero || orderB != .zero { stamps[SyncKey.rackOrder] = max(orderA, orderB) }
        var seen = Set<UUID>()
        var ordered: [Rack] = []
        for rack in base where !seen.contains(rack.id) {
            seen.insert(rack.id)
            if let kept = racks[rack.id] { ordered.append(kept) }
        }
        for rack in racks.values.sorted(by: { $0.id.uuidString.lowercased() < $1.id.uuidString.lowercased() })
        where !seen.contains(rack.id) {
            ordered.append(rack)
        }
        merged.racks = ordered

        repair(&merged, stamps: stamps)
        // At least every counter seen, so the next local change is newer than all of them.
        let clock = ([localState.clock, remoteState.clock] + stamps.values.map(\.counter)).max() ?? 0
        return Result(cellar: merged, stamps: stamps, clock: clock)
    }

    static func byID(_ racks: [Rack]) -> [UUID: Rack] {
        Dictionary(racks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    static func byID(_ searches: [SavedSearch]?) -> [UUID: SavedSearch] {
        Dictionary((searches ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// A bottle or discovery changed after its wine was deleted on another device
    /// (someone added a bottle while someone else deleted the wine): the wine comes
    /// back, with the newest stamp of what refers to it, so nothing added is lost.
    private static func revive(_ merged: inout Cellar, stamps: inout [String: Stamp], local: Cellar, remote: Cellar) {
        var newest: [UUID: Stamp] = [:]
        let references = merged.bottles.values.map { (SyncKey(.bottle, $0.id).string, $0.wineID) }
            + merged.discoveries.values.map { (SyncKey(.discovery, $0.id).string, $0.wineID) }
        for (key, wineID) in references where merged.wines[wineID] == nil {
            guard let stamp = stamps[key], let deletion = stamps[SyncKey(.wine, wineID).string],
                  deletion.deleted, stamp > deletion else { continue }
            newest[wineID] = max(newest[wineID] ?? stamp, stamp)
        }
        for (wineID, stamp) in newest {
            guard let wine = preferred(local.wines[wineID], remote.wines[wineID]) else { continue }
            merged.wines[wineID] = wine
            stamps[SyncKey(.wine, wineID).string] = Stamp(counter: stamp.counter, device: stamp.device)
        }
    }

    /// The version that changed last, and its stamp. On equal stamps, see `preferred`.
    private static func choose<T>(_ a: T?, _ sa: Stamp?, _ b: T?, _ sb: Stamp?) -> (T?, Stamp?) {
        switch (sa, sb) {
        case (nil, nil): return (nil, nil)
        case (let sa?, nil): return (sa.deleted ? nil : a ?? b, sa)
        case (nil, let sb?): return (sb.deleted ? nil : b ?? a, sb)
        case (let sa?, let sb?):
            if sa == sb {
                if sa.deleted { return (nil, sa) }
                return (preferred(a, b), sa)
            }
            let (winner, other, stamp) = sa > sb ? (a, b, sa) : (b, a, sb)
            return (stamp.deleted ? nil : winner ?? other, stamp)
        }
    }

    /// A choice between two versions with the same stamp that does not depend on
    /// which device merges. They come from the same change, so they differ only
    /// where repair changed one without a stamp: a bottle that lost its place. The
    /// version without a place wins; otherwise either is fine and the local one is kept.
    private static func preferred<T>(_ a: T?, _ b: T?) -> T? {
        guard let a, let b else { return a ?? b }
        if let ba = a as? Bottle, let bb = b as? Bottle, (ba.slot == nil) != (bb.slot == nil) {
            return ba.slot == nil ? a : b
        }
        return a
    }

    /// Fixes what a merge can leave inconsistent, the same way on every device:
    /// bottles of a deleted wine, places in a removed or smaller rack, and two
    /// bottles in one place (the one moved there last keeps it).
    static func repair(_ cellar: inout Cellar, stamps: [String: Stamp]) {
        for (id, bottle) in cellar.bottles where cellar.wines[bottle.wineID] == nil {
            cellar.bottles[id] = nil
        }
        for (id, discovery) in cellar.discoveries where cellar.wines[discovery.wineID] == nil {
            cellar.discoveries[id] = nil
        }
        let racks = byID(cellar.racks)
        for (id, bottle) in cellar.bottles {
            guard let slot = bottle.slot else { continue }
            if bottle.status != .inCellar || !(racks[slot.rackID]?.contains(slot) ?? false) {
                cellar.bottles[id]?.slot = nil
            }
        }
        let placed = cellar.bottles.values.filter { $0.slot != nil }
        for (_, together) in Dictionary(grouping: placed, by: { $0.slot! }) where together.count > 1 {
            let ranked = together.sorted { x, y in
                let sx = stamps[SyncKey(.bottle, x.id).string] ?? .zero
                let sy = stamps[SyncKey(.bottle, y.id).string] ?? .zero
                return sx != sy ? sx > sy : x.id.uuidString.lowercased() < y.id.uuidString.lowercased()
            }
            for loser in ranked.dropFirst() { cellar.bottles[loser.id]?.slot = nil }
        }
        cellar.removeOrphanWines()
    }
}

/// Saves the sync state next to the cellar file.
public struct SyncStateFile: Sendable {
    public let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    /// `<Application Support>/Encave/sync.json`
    public static func defaultFile() throws -> SyncStateFile {
        let store = try JSONFileCellarStore.defaultStore()
        return SyncStateFile(fileURL: store.fileURL.deletingLastPathComponent().appendingPathComponent("sync.json"))
    }

    public func load() throws -> SyncState? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try JSONDecoder().decode(SyncState.self, from: Data(contentsOf: fileURL))
    }

    public func save(_ state: SyncState) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(state).write(to: fileURL, options: [.atomic])
    }

    public func delete() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
