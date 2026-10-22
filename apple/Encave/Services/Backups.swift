import CaveKit
import Foundation
import Observation
#if os(macOS)
import AppKit
#endif

/// Something that can be restored, read and checked: an earlier version of the cellar,
/// a backup archive or a JSON backup.
struct RestoreSource: Identifiable, Sendable {
    enum Kind: Sendable { case version, archive, json }

    let id = UUID()
    var kind: Kind
    /// When the backup was made, or when the version was saved.
    var date: Date
    var cellar: Cellar
    /// The archive's photos and documents, checked, in `workingFolder`.
    var files: [FileRef: URL] = [:]
    /// Removed once restored, and at the next launch otherwise.
    var workingFolder: URL?
}

/// An archive made by Back Up Now, waiting in a temporary folder to be shared or saved.
struct MadeArchive: Identifiable, Sendable {
    let url: URL
    let date: Date
    /// The photos and documents it holds, and those left out.
    let result: CellarArchive.WriteResult
    var id: URL { url }
}

/// Backups made on this device: the previous versions of the cellar (kept by the store),
/// archives made with Back Up Now, and the weekly archive kept in the app's Documents
/// folder (Files › On My iPhone › Encave). Nothing here uses the network.
@MainActor
@Observable
final class BackupCenter {
    let versions: any CellarVersionSource
    private let isDemo: Bool
    private let defaults = UserDefaults.standard

    /// The weekly archive on this device (on by default).
    var weeklyEnabled: Bool {
        didSet { defaults.set(weeklyEnabled, forKey: Self.weeklyKey) }
    }

    /// The last backup kept: an archive shared or saved with Back Up Now, or the weekly one.
    private(set) var lastBackup: Date?
    /// Making an archive.
    private(set) var isWorking = false
    /// Bumped when a version or archive is added, so lists reload.
    private(set) var changeCount = 0
    /// The last weekly archive that could not be made (no space): not tried again for a day.
    private var lastAutomaticFailure: Date?

    static let weeklyKey = "backup.weekly"
    static let lastKey = "backup.last"
    static let lastAutomaticKey = "backup.lastAutomatic"
    static let automaticFilesKey = "backup.automaticFiles"
    static let firstUseKey = "backup.firstUse"
    /// How often the weekly archive is made.
    static let automaticInterval: TimeInterval = 7 * 24 * 3600
    /// Settings suggest a backup when none was made for this long.
    static let reminderInterval: TimeInterval = 30 * 24 * 3600
    /// Weekly archives kept on the device (each holds every photo).
    static let automaticCount = 2

    init(versions: any CellarVersionSource, demo: Bool) {
        let stored = UserDefaults.standard
        self.versions = versions
        isDemo = demo
        weeklyEnabled = stored.object(forKey: Self.weeklyKey) as? Bool ?? true
        lastBackup = demo ? Date.now.addingTimeInterval(-3 * 24 * 3600) : stored.object(forKey: Self.lastKey) as? Date
        if !demo, stored.object(forKey: Self.firstUseKey) == nil { stored.set(Date.now, forKey: Self.firstUseKey) }
    }

    /// No backup for 30 days (or since Encave was first used), and a cellar to keep.
    func reminderDue(for cellar: Cellar, now: Date = .now) -> Bool {
        guard !cellar.isEmpty else { return false }
        let since = lastBackup ?? defaults.object(forKey: Self.firstUseKey) as? Date ?? now
        return now.timeIntervalSince(since) >= Self.reminderInterval
    }

    // MARK: Folders

    /// Where weekly archives are kept: the Documents folder, which the Files app shows
    /// on iPhone and iPad.
    static var automaticFolder: URL? {
        try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }

    /// Archives made for Back Up Now, and files read for a restore: removed at each launch.
    nonisolated static var temporaryFolder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Backups", isDirectory: true)
    }

    nonisolated static func removeTemporaryFiles() {
        try? FileManager.default.removeItem(at: temporaryFolder)
    }

    nonisolated static func newTemporaryFolder() throws -> URL {
        let folder = temporaryFolder.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// The weekly archives on the device, newest first.
    var automaticArchives: [URL] {
        _ = changeCount
        guard let folder = Self.automaticFolder else { return [] }
        return automaticNames.reversed().map { folder.appendingPathComponent($0) }
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    private var automaticNames: [String] {
        get { defaults.stringArray(forKey: Self.automaticFilesKey) ?? [] }
        set { defaults.set(newValue, forKey: Self.automaticFilesKey) }
    }

    // MARK: Making archives

    /// The local file of a photo or document.
    nonisolated static func fileURL(_ ref: FileRef) -> URL? {
        switch ref.kind {
        case .photo: PhotoStore.url(for: ref.id)
        case .document: DocumentStore.url(for: ref.id)
        }
    }

    /// Back Up Now: an archive of the cellar in a temporary folder, to share or save. It
    /// counts as the last backup once it is shared or saved (`noteKept`).
    func makeArchive(of cellar: Cellar) async throws -> MadeArchive {
        isWorking = true
        defer { isWorking = false }
        let now = Date.now
        return try await Task.detached(priority: .userInitiated) { () throws -> MadeArchive in
            let folder = try Self.newTemporaryFolder()
            let target = folder.appendingPathComponent(CellarArchive.fileName(date: now))
            do {
                let result = try CellarArchive.write(cellar, exportedAt: now, to: target, fileURL: Self.fileURL)
                return MadeArchive(url: target, date: now, result: result)
            } catch {
                try? FileManager.default.removeItem(at: folder)
                throw error
            }
        }.value
    }

    /// The archive was shared or saved.
    func noteKept(_ archive: MadeArchive) {
        noteBackup(at: archive.date)
    }

    /// Removes an archive's temporary folder once it is shared, saved or given up (it can
    /// hold gigabytes of photos; the Mac app is seldom relaunched to clear it).
    nonisolated static func discard(_ archive: MadeArchive) {
        try? FileManager.default.removeItem(at: archive.url.deletingLastPathComponent())
    }

    private func noteBackup(at date: Date) {
        lastBackup = date
        defaults.set(date, forKey: Self.lastKey)
        changeCount += 1
    }

    /// The weekly archive, when it is due: on, a cellar to keep, and none made for 7 days.
    /// Written next to the others, then the oldest beyond the last two are removed.
    func runAutomaticIfDue(cellar: Cellar, now: Date = .now) async {
        guard !isDemo, weeklyEnabled, !isWorking, !cellar.isEmpty, let folder = Self.automaticFolder else { return }
        if let last = defaults.object(forKey: Self.lastAutomaticKey) as? Date, now.timeIntervalSince(last) < Self.automaticInterval,
           last <= now { return }
        if let failed = lastAutomaticFailure, now.timeIntervalSince(failed) < 24 * 3600, failed <= now { return }
        isWorking = true
        defer { isWorking = false }
        let cancelled = CancelFlag()
        let name = Self.automaticName(date: now, in: folder, ours: Set(automaticNames))
        let result: URL? = await withTaskCancellationHandler {
            await Task.detached(priority: .utility) { () -> URL? in
                do {
                    let temporary = try Self.newTemporaryFolder().appendingPathComponent(name)
                    try CellarArchive.write(cellar, exportedAt: now, to: temporary, fileURL: Self.fileURL,
                                            isCancelled: { cancelled.isSet })
                    let target = folder.appendingPathComponent(name)
                    try? FileManager.default.removeItem(at: target)
                    try FileManager.default.moveItem(at: temporary, to: target)
                    // The device's own backup already holds the cellar and its photos: an
                    // archive of them would double its size.
                    var values = URLResourceValues()
                    values.isExcludedFromBackup = true
                    var excluded = target
                    try? excluded.setResourceValues(values)
                    return target
                } catch {
                    return nil
                }
            }.value
        } onCancel: {
            cancelled.set()
        }
        guard result != nil else {
            if !cancelled.isSet { lastAutomaticFailure = now }
            return
        }
        lastAutomaticFailure = nil
        defaults.set(now, forKey: Self.lastAutomaticKey)
        var names = automaticNames.filter { $0 != name } + [name]
        while names.count > Self.automaticCount {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(names.removeFirst()))
        }
        automaticNames = names
        noteBackup(at: now)
    }

    /// "Encave Backup 2026-10-21.zip", or "… 2.zip" when a file of that name that is not a
    /// weekly archive is there (one the user saved in the folder): it is never written over,
    /// nor removed later with the weekly ones.
    static func automaticName(date: Date, in folder: URL, ours: Set<String>) -> String {
        let name = CellarArchive.fileName(date: date)
        let stem = String(name.dropLast(4))
        var candidate = name
        var number = 2
        while !ours.contains(candidate), FileManager.default.fileExists(atPath: folder.appendingPathComponent(candidate).path) {
            candidate = "\(stem) \(number).zip"
            number += 1
        }
        return candidate
    }

    #if os(macOS)
    /// The Mac keeps the weekly archives in the app's container: shows them in the Finder.
    func showAutomaticArchivesInFinder() {
        if let latest = automaticArchives.first {
            NSWorkspace.shared.activateFileViewerSelecting([latest])
        } else if let folder = Self.automaticFolder {
            NSWorkspace.shared.open(folder)
        }
    }
    #endif

    // MARK: Reading

    /// An earlier version, ready to preview.
    func source(for version: CellarVersion) async throws -> RestoreSource {
        let versions = versions
        let cellar = try await Task.detached(priority: .userInitiated) { try versions.cellar(of: version) }.value
        return RestoreSource(kind: .version, date: version.date, cellar: cellar)
    }

    /// The newest earlier version, or nil if there is none.
    func latestVersionSource() async -> RestoreSource? {
        guard let latest = versions.versions().first else { return nil }
        return try? await source(for: latest)
    }

    /// Reads a backup the user picked: an archive (`.zip`) or a JSON backup. The file is
    /// copied first, while the caller holds access to it.
    nonisolated static func source(forFileAt url: URL) async throws -> RestoreSource {
        let folder = try newTemporaryFolder()
        do {
            return try await read(url, in: folder)
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
    }

    private nonisolated static func read(_ url: URL, in folder: URL) async throws -> RestoreSource {
        let copy = folder.appendingPathComponent("picked")
        try FileManager.default.copyItem(at: url, to: copy)
        let isArchive = (try? FileHandle(forReadingFrom: copy).read(upToCount: 4)) == Data([0x50, 0x4B, 0x03, 0x04])
        return try await Task.detached(priority: .userInitiated) { () throws -> RestoreSource in
            if isArchive {
                let files = folder.appendingPathComponent("files", isDirectory: true)
                try FileManager.default.createDirectory(at: files, withIntermediateDirectories: true)
                let contents = try CellarArchive.read(copy, workingFolder: files)
                try? FileManager.default.removeItem(at: copy)
                return RestoreSource(kind: .archive, date: contents.backup.exportedAt, cellar: contents.backup.cellar,
                                     files: contents.files, workingFolder: folder)
            }
            let backup = try CellarExport.backup(fromJSON: Data(contentsOf: copy))
            try? FileManager.default.removeItem(at: folder)
            return RestoreSource(kind: .json, date: backup.exportedAt, cellar: backup.cellar)
        }.value
    }

    /// Removes the files read from a backup that was not restored.
    nonisolated static func discard(_ source: RestoreSource) {
        if let folder = source.workingFolder { try? FileManager.default.removeItem(at: folder) }
    }

    /// Copies an archive's photos and documents into the stores, one at a time.
    nonisolated static func install(_ files: [FileRef: URL]) {
        for (ref, url) in files {
            guard let data = try? Data(contentsOf: url) else { continue }
            switch ref.kind {
            case .photo: PhotoStore.store(data, id: ref.id)
            case .document: DocumentStore.store(data, id: ref.id)
            }
        }
    }
}

/// Set when a background task runs out of time, read by the archive writer.
final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool { lock.withLock { value } }
    func set() { lock.withLock { value = true } }
}

/// Sample versions for previews and the screen tour: the sample cellar as it was a few
/// days and weeks ago.
enum DemoVersions {
    static func make(from cellar: Cellar, now: Date = .now) -> InMemoryCellarVersions {
        func without(_ count: Int) -> Cellar {
            var copy = cellar
            let ids = copy.bottles.values.filter { $0.status == .inCellar }.map(\.id).sorted { $0.uuidString < $1.uuidString }
            for id in ids.prefix(count) { copy.bottles[id] = nil }
            return copy
        }
        var older = cellar
        for id in older.discoveries.keys { older.discoveries[id] = nil }
        return InMemoryCellarVersions([
            (date: now.addingTimeInterval(-20 * 3600), cellar: without(2)),
            (date: now.addingTimeInterval(-3 * 24 * 3600 - 5 * 3600), cellar: without(5)),
            (date: now.addingTimeInterval(-12 * 24 * 3600), cellar: older),
        ])
    }
}
