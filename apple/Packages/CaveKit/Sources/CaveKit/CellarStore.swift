import Foundation

/// Persistence for a cellar. Sync does not change it: its metadata is kept next
/// to the cellar (`SyncStateFile`).
public protocol CellarStore: Sendable {
    func load() throws -> Cellar
    func save(_ cellar: Cellar) throws
}

/// Stores the cellar as one JSON file, written atomically.
/// On Apple platforms the file is encrypted at rest until first unlock.
public struct JSONFileCellarStore: CellarStore {
    public let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    /// `<Application Support>/Encave/cellar.json`
    public static func defaultStore() throws -> JSONFileCellarStore {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        let folder = base.appendingPathComponent("Encave", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return JSONFileCellarStore(fileURL: folder.appendingPathComponent("cellar.json"))
    }

    public func load() throws -> Cellar {
        guard let data = try readData() else { return Cellar() }
        return try Self.makeDecoder().decode(Cellar.self, from: data)
    }

    /// The file's bytes, or nil if there is no file yet.
    private func readData() throws -> Data? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try Data(contentsOf: fileURL)
    }

    /// Loads the cellar. A file whose content cannot be read as a cellar is moved aside,
    /// as `cellar-unreadable-<date>.json` next to it, before anything can be saved over
    /// it: the result is then an empty cellar and the file's new place. Throws if the
    /// file could not be moved, or could not be opened at all (for example before the
    /// first unlock after a restart, when the system may launch the app in the
    /// background): that file is not damaged and stays where it is. Nothing must then
    /// be saved to `fileURL`.
    public func loadSettingAsideUnreadable(now: Date = .now) throws -> (cellar: Cellar, setAside: URL?) {
        guard let data = try readData() else { return (Cellar(), nil) }
        do {
            return (try Self.makeDecoder().decode(Cellar.self, from: data), nil)
        } catch {
            let folder = fileURL.deletingLastPathComponent()
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyyMMdd-HHmmss"
            let stem = Self.unreadablePrefix + formatter.string(from: now)
            var target = folder.appendingPathComponent(stem + ".json")
            var number = 2
            while FileManager.default.fileExists(atPath: target.path) {
                target = folder.appendingPathComponent("\(stem)-\(number).json")
                number += 1
            }
            try FileManager.default.moveItem(at: fileURL, to: target)
            return (Cellar(), target)
        }
    }

    static let unreadablePrefix = "cellar-unreadable-"

    /// Cellar files set aside because they could not be read, oldest first.
    public func unreadableFiles() -> [URL] {
        let folder = fileURL.deletingLastPathComponent()
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return names.filter { $0.hasPrefix(Self.unreadablePrefix) && $0.hasSuffix(".json") }.sorted()
            .map { folder.appendingPathComponent($0) }
    }

    public func save(_ cellar: Cellar) throws {
        let data = try Self.makeEncoder().encode(cellar)
        #if canImport(Darwin) && !os(macOS)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: fileURL, options: [.atomic])
        #endif
    }

    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        // Seconds since 2001-01-01 as a Double: the exact internal value, so dates round-trip.
        encoder.dateEncodingStrategy = .deferredToDate
        return encoder
    }

    static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .deferredToDate
        return decoder
    }
}

/// Keeps everything in memory. For previews and tests.
public final class InMemoryCellarStore: CellarStore, @unchecked Sendable {
    private let lock = NSLock()
    private var cellar: Cellar

    public init(_ cellar: Cellar = Cellar()) { self.cellar = cellar }

    public func load() throws -> Cellar { lock.withLock { cellar } }
    public func save(_ cellar: Cellar) throws { lock.withLock { self.cellar = cellar } }
}
