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
/// first. One copy is kept for each of the last 7 days the cellar changed, and one for
/// each week of the last 4 weeks; older ones are removed. The Android app keeps them
/// the same way.
///
/// Only the cellar file is copied: photos and documents are named after their content
/// and kept while a version refers to them (`fileRefs`).
public struct CellarVersionFolder: CellarVersionSource {
    public let folder: URL
    /// Days and ISO weeks of the device's time zone.
    public var calendar: Calendar

    public static let dailyCount = 7
    public static let weeklyDays = 28
    static let prefix = "cellar-", suffix = ".json"

    public init(folder: URL, timeZone: TimeZone = .current) {
        self.folder = folder
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = timeZone
        self.calendar = calendar
    }

    /// Called before `cellarFile` is saved over: keeps a copy of it if it was last saved on
    /// an earlier day than `now`, holds a cellar that is not empty, and is not kept yet.
    /// Then removes the versions no longer needed. Errors are ignored: a copy never stops
    /// a save.
    public func keepCopy(of cellarFile: URL, now: Date = .now) {
        guard let modified = (try? cellarFile.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate,
              calendar.startOfDay(for: modified) < calendar.startOfDay(for: now) else { return }
        let target = folder.appendingPathComponent(Self.name(for: modified))
        guard !FileManager.default.fileExists(atPath: target.path),
              let data = try? Data(contentsOf: cellarFile),
              let cellar = try? JSONFileCellarStore.makeDecoder().decode(Cellar.self, from: data), !cellar.isEmpty
        else { return }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            #if canImport(Darwin) && !os(macOS)
            try data.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: target, options: [.atomic])
            #endif
        } catch {
            return
        }
        prune(now: now)
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
    public func prune(now: Date = .now) {
        let all = versions()
        let kept = Self.kept(all.map(\.date), now: now, calendar: calendar)
        for version in all where !kept.contains(version.date) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(version.id))
        }
    }

    /// The dates kept: the newest of each of the last 7 days, and the newest of each ISO
    /// week of the last 28 days. Versions dated later than `now` (a clock set back) count
    /// as today's.
    public static func kept(_ dates: [Date], now: Date, calendar: Calendar) -> Set<Date> {
        let today = calendar.startOfDay(for: now)
        var days: [Date: Date] = [:]
        var weeks: [String: Date] = [:]
        for date in dates {
            let day = calendar.startOfDay(for: date)
            let age = max(0, calendar.dateComponents([.day], from: day, to: today).day ?? 0)
            if age < dailyCount { days[day] = max(days[day] ?? date, date) }
            if age < weeklyDays {
                let week = calendar.dateComponents([.yearForWeekOfYear, .weekOfYear], from: date)
                let key = "\(week.yearForWeekOfYear ?? 0)-\(week.weekOfYear ?? 0)"
                weeks[key] = max(weeks[key] ?? date, date)
            }
        }
        return Set(days.values).union(weeks.values)
    }

    /// "cellar-20261020-221430.json": the date in UTC, to the second.
    static func name(for date: Date) -> String {
        prefix + formatter.string(from: date) + suffix
    }

    static func date(fromName name: String) -> Date? {
        guard name.hasPrefix(prefix), name.hasSuffix(suffix) else { return nil }
        return formatter.date(from: String(name.dropFirst(prefix.count).dropLast(suffix.count)))
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
