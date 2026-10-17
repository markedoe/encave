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
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return Cellar() }
        let data = try Data(contentsOf: fileURL)
        return try Self.makeDecoder().decode(Cellar.self, from: data)
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
