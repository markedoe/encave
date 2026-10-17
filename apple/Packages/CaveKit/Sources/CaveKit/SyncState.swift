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
        compare(.rack, Dictionary(uniqueKeysWithValues: old.racks.map { ($0.id, $0) }),
                Dictionary(uniqueKeysWithValues: new.racks.map { ($0.id, $0) }))
        if old.settings != new.settings { stamp(SyncKey.settings, deleted: false) }
        if old.racks.map(\.id) != new.racks.map(\.id) { stamp(SyncKey.rackOrder, deleted: false) }
    }

    private mutating func stamp(_ key: String, deleted: Bool) {
        clock += 1
        stamps[key] = Stamp(counter: clock, device: device, deleted: deleted)
    }

    /// The stamp of an object, or `.zero` if it exists but was never stamped.
    func stamp(for key: String, exists: Bool) -> Stamp? {
        stamps[key] ?? (exists ? .zero : nil)
    }
}

/// Identifies an object in the stamps.
struct SyncKey {
    enum Kind: String { case wine, bottle, rack, discovery }
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
        let racks = pick(.rack, Dictionary(uniqueKeysWithValues: local.racks.map { ($0.id, $0) }),
                         Dictionary(uniqueKeysWithValues: remote.racks.map { ($0.id, $0) }))

        // Settings: one object.
        let settings = choose(local.settings, localState.stamps[SyncKey.settings] ?? .zero,
                              remote.settings, remoteState.stamps[SyncKey.settings] ?? .zero)
        merged.settings = settings.0 ?? local.settings
        if let stamp = settings.1 { stamps[SyncKey.settings] = stamp }

        // Rack order: the order of the copy that reordered last, then any other racks.
        let orderA = localState.stamps[SyncKey.rackOrder] ?? .zero
        let orderB = remoteState.stamps[SyncKey.rackOrder] ?? .zero
        let order = orderB > orderA ? remote.racks.map(\.id) + local.racks.map(\.id)
                                    : local.racks.map(\.id) + remote.racks.map(\.id)
        if orderA != .zero || orderB != .zero { stamps[SyncKey.rackOrder] = max(orderA, orderB) }
        var seen = Set<UUID>()
        var ordered: [Rack] = []
        for id in order where !seen.contains(id) {
            seen.insert(id)
            if let rack = racks[id] { ordered.append(rack) }
        }
        // Racks only in the stamps of neither order (should not happen): by ID.
        for rack in racks.values.sorted(by: { $0.id.uuidString < $1.id.uuidString }) where !seen.contains(rack.id) {
            ordered.append(rack)
        }
        merged.racks = ordered

        repair(&merged, stamps: stamps)
        return Result(cellar: merged, stamps: stamps, clock: max(localState.clock, remoteState.clock))
    }

    /// The version that changed last, and its stamp. Equal stamps with different
    /// content (two devices that never synced, same object) fall back to the content.
    private static func choose<T: Codable>(_ a: T?, _ sa: Stamp?, _ b: T?, _ sb: Stamp?) -> (T?, Stamp?) {
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

    /// A choice between two versions that does not depend on which device merges.
    private static func preferred<T: Codable>(_ a: T?, _ b: T?) -> T? {
        guard let a, let b else { return a ?? b }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let ea = (try? encoder.encode(a)) ?? Data()
        let eb = (try? encoder.encode(b)) ?? Data()
        return ea.lexicographicallyPrecedes(eb) ? b : a
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
        let racks = Dictionary(uniqueKeysWithValues: cellar.racks.map { ($0.id, $0) })
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
                return sx != sy ? sx > sy : x.id.uuidString < y.id.uuidString
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
