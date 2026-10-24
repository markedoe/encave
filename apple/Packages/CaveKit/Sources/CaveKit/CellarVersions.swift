import Foundation

/// An earlier state of the cellar kept on the device.
public struct CellarVersion: Identifiable, Hashable, Sendable {
    /// The file name ("cellar-20261020-221430.json").
    public var id: String
    /// When this state of the cellar was saved.
    public var date: Date

    public init(id: String, date: Date) {
        self.id = id
        self.date = date
    }

    /// Saved on purpose before the cellar was replaced by another device's (Replace and
    /// Join), rather than automatically on a new day.
    public var isBeforeReplacing: Bool { CellarVersionFolder.isBeforeReplacing(id) }
}

/// Where earlier versions of the cellar are read from: the folder next to the cellar,
/// or sample versions in previews and the screen tour.
public protocol CellarVersionSource: Sendable {
    /// Newest first.
    func versions() -> [CellarVersion]
    func cellar(of version: CellarVersion) throws -> Cellar
}

/// Automatic copies of the cellar file, in a folder next to it: when the cellar is saved
/// on a new day, the file as it was (its last state of the day it was saved) is kept
/// first. One copy is kept for each of the last 7 days the cellar changed, then one for
/// each of the 4 weeks before; older ones are removed. Counted from the copies, not from
/// today, so the last ones stay after weeks without a change. The Android app keeps them
/// the same way.
///
/// Only the cellar file is copied: photos and documents are named after their content
/// and kept while a version refers to them (`fileRefs`).
///
/// A version is also saved before the cellar is replaced by another device's
/// (`keepBeforeReplacing`): those are kept apart from the daily and weekly ones, the
/// last 3 of them, so the next day's copy never takes their place.
public struct CellarVersionFolder: CellarVersionSource {
    public let folder: URL
    /// Days and ISO weeks of the device's time zone.
    public var calendar: Calendar

    public static let dailyCount = 7
    public static let weeklyCount = 4
    /// Versions saved before replacing the cellar that are kept.
    public static let beforeReplacingCount = 3
    static let prefix = "cellar-", suffix = ".json", temporarySuffix = ".tmp"
    /// "cellar-20261020-221430-before-replace.json"
    static let beforeReplacingMark = "-before-replace"

    public init(folder: URL, timeZone: TimeZone = .current) {
        self.folder = folder
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = timeZone
        self.calendar = calendar
    }

    /// Called before `cellarFile` is saved over: keeps a copy of it if it was last saved on
    /// an earlier day than `now` and is not kept yet, then checks it and removes the versions
    /// no longer needed (`tidy`). Errors are ignored: a copy never stops a save.
    public func keepCopy(of cellarFile: URL, now: Date = .now) {
        if let copy = copyIfNewDay(of: cellarFile, now: now) { tidy(checking: copy) }
    }

    /// The quick part of `keepCopy`, done before the save: copies the file (a clone on APFS)
    /// if it was last saved on an earlier day than `now`. Returns the copy made, which `tidy`
    /// then checks, away from the main thread.
    @discardableResult
    public func copyIfNewDay(of cellarFile: URL, now: Date = .now) -> CellarVersion? {
        // Read from the file system each time: a URL's resource values are cached on Apple
        // platforms, and the store saves to the same URL all day long.
        guard let modified = (try? FileManager.default.attributesOfItem(atPath: cellarFile.path))?[.modificationDate] as? Date,
              calendar.startOfDay(for: modified) < calendar.startOfDay(for: now) else { return nil }
        let target = folder.appendingPathComponent(Self.name(for: modified))
        guard !FileManager.default.fileExists(atPath: target.path) else { return nil }
        let temporary = folder.appendingPathComponent(target.lastPathComponent + "." + UUID().uuidString + Self.temporarySuffix)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: cellarFile, to: temporary)
            try FileManager.default.moveItem(at: temporary, to: target)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            return nil
        }
        return Self.date(fromName: target.lastPathComponent).map { CellarVersion(id: target.lastPathComponent, date: $0) }
    }

    /// Saves `cellar` as a version now, before it is replaced by another device's cellar
    /// (Replace and Join), so it can be restored from Previous Versions. Nothing is saved
    /// for an empty cellar or one that holds only the sample. Returns the version saved.
    @discardableResult
    public func keepBeforeReplacing(_ cellar: Cellar, now: Date = .now) -> CellarVersion? {
        guard !cellar.isEmpty, !cellar.isOnlySample else { return nil }
        let name = Self.prefix + Self.formatter.string(from: now) + Self.beforeReplacingMark + Self.suffix
        let target = folder.appendingPathComponent(name)
        let temporary = folder.appendingPathComponent(name + "." + UUID().uuidString + Self.temporarySuffix)
        do {
            let data = try JSONFileCellarStore.makeEncoder().encode(cellar)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try data.write(to: temporary)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temporary, to: target)
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            return nil
        }
        prune()
        return Self.date(fromName: name).map { CellarVersion(id: name, date: $0) }
    }

    /// Before Replace (restoring a backup, Replace and Join, Erase All Data that keeps the
    /// versions): saves `cellar` as a version. True when it was saved or there was nothing
    /// to keep (an empty cellar, or only the sample); false when it could not be saved, and
    /// then the cellar must not be replaced.
    public func keepBeforeReplacingIfNeeded(_ cellar: Cellar, now: Date = .now) -> Bool {
        cellar.isEmpty || cellar.isOnlySample || keepBeforeReplacing(cellar, now: now) != nil
    }

    /// Erase All Data: removes every version.
    public func removeAll() {
        try? FileManager.default.removeItem(at: folder)
    }

    /// Removes `copy` if it cannot be read or holds an empty cellar, copies left half made, and
    /// the versions no longer kept.
    public func tidy(checking copy: CellarVersion? = nil) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for name in names where name.hasSuffix(Self.temporarySuffix) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
        if let copy, (try? cellar(of: copy))?.isEmpty ?? true {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(copy.id))
        }
        prune()
    }

    /// Newest first.
    public func versions() -> [CellarVersion] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.compactMap { name in Self.date(fromName: name).map { CellarVersion(id: name, date: $0) } }
            .sorted { $0.date > $1.date }
    }

    public func cellar(of version: CellarVersion) throws -> Cellar {
        guard Self.date(fromName: version.id) != nil else { throw CocoaError(.fileNoSuchFile) }
        let data = try Data(contentsOf: folder.appendingPathComponent(version.id))
        return try JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: data)
    }

    /// Photos and documents the versions refer to, kept on the device while they are.
    public func fileRefs() -> Set<FileRef> {
        versions().reduce(into: Set<FileRef>()) { refs, version in
            if let cellar = try? cellar(of: version) { refs.formUnion(cellar.fileRefs) }
        }
    }

    /// Removes the versions no longer kept.
    public func prune() {
        let all = versions()
        let automatic = all.filter { !$0.isBeforeReplacing }
        let keptDates = Self.kept(automatic.map(\.date), calendar: calendar)
        let kept = Set(automatic.filter { keptDates.contains($0.date) }.map(\.id))
            .union(all.filter(\.isBeforeReplacing).prefix(Self.beforeReplacingCount).map(\.id))
        for version in all where !kept.contains(version.id) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(version.id))
        }
    }

    /// The dates kept: the newest of each of the 7 latest days that have a version, then the
    /// newest of each of the 4 latest ISO weeks before those days. Counted from the versions
    /// themselves, not from today, so a long pause never removes them all.
    public static func kept(_ dates: [Date], calendar: Calendar) -> Set<Date> {
        var days: [Date: Date] = [:]
        for date in dates {
            let day = calendar.startOfDay(for: date)
            days[day] = max(days[day] ?? date, date)
        }
        let daily = days.keys.sorted(by: >).prefix(dailyCount)
        guard let oldestDaily = daily.last else { return [] }
        var weeks: [String: Date] = [:]
        for date in dates where date < oldestDaily {
            let week = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
            let key = "\(week.yearForWeekOfYear ?? 0)-\(week.weekOfYear ?? 0)"
            weeks[key] = max(weeks[key] ?? date, date)
        }
        let weekly = weeks.values.sorted(by: >).prefix(weeklyCount)
        return Set(daily.compactMap { days[$0] }).union(weekly)
    }

    /// "cellar-20261020-221430.json": the date in UTC, to the second.
    static func name(for date: Date) -> String {
        prefix + formatter.string(from: date) + suffix
    }

    static func date(fromName name: String) -> Date? {
        guard name.hasPrefix(prefix), name.hasSuffix(suffix) else { return nil }
        var stem = String(name.dropFirst(prefix.count).dropLast(suffix.count))
        if stem.hasSuffix(beforeReplacingMark) { stem.removeLast(beforeReplacingMark.count) }
        return formatter.date(from: stem)
    }

    static func isBeforeReplacing(_ name: String) -> Bool {
        name.hasSuffix(beforeReplacingMark + suffix) && date(fromName: name) != nil
    }

    private static var formatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter
    }
}

/// Sample versions, for previews and the screen tour.
public struct InMemoryCellarVersions: CellarVersionSource {
    public var items: [(CellarVersion, Cellar)]

    public init(_ items: [(date: Date, cellar: Cellar)]) {
        self.items = items.map { (CellarVersion(id: CellarVersionFolder.name(for: $0.date), date: $0.date), $0.cellar) }
            .sorted { $0.0.date > $1.0.date }
    }

    public func versions() -> [CellarVersion] { items.map(\.0) }

    public func cellar(of version: CellarVersion) throws -> Cellar {
        guard let cellar = items.first(where: { $0.0 == version })?.1 else { throw CocoaError(.fileNoSuchFile) }
        return cellar
    }
}
