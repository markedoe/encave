import Foundation

/// A small ZIP writer and reader for Encave's backup archives: entries are stored
/// (photos and PDFs are compressed already), names are UTF-8, and each entry's sizes
/// and CRC-32 are in its local header, so a streaming reader (Android's `ZipInputStream`)
/// reads it too. Archives over 4 GB or 65,535 entries (ZIP64) are refused. Entries are
/// copied to and from files in chunks, so no photo is held in memory longer than needed.
public enum ZipError: Error, Equatable, Sendable {
    /// Not a ZIP file, or a damaged one.
    case notAZip
    /// Compressed, encrypted, split or ZIP64: not an archive Encave writes.
    case unsupported
    /// An entry's content does not match its CRC-32, or the archive is cut short.
    case corrupt
    /// Over 4 GB, 65,535 entries, or an entry over the size asked for.
    case tooLarge
}

/// The CRC-32 of ZIP files (IEEE 802.3, the one of `java.util.zip.CRC32`).
public struct CRC32: Sendable {
    public private(set) var value: UInt32 = 0

    public init() {}

    private static let table: [UInt32] = (0..<256).map { index in
        var c = UInt32(index)
        for _ in 0..<8 { c = c & 1 == 1 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    public mutating func update(_ data: Data) {
        var c = ~value
        data.withUnsafeBytes { buffer in
            for byte in buffer { c = Self.table[Int((c ^ UInt32(byte)) & 0xFF)] ^ (c >> 8) }
        }
        value = ~c
    }

    public static func checksum(_ data: Data) -> UInt32 {
        var crc = CRC32()
        crc.update(data)
        return crc.value
    }
}

/// Writes a ZIP file entry by entry. Call `finish()` once all entries are added.
public final class ZipWriter {
    private let handle: FileHandle
    private var offset: UInt64 = 0
    private var central = Data()
    private var count = 0
    private let time: UInt16
    private let date: UInt16
    private var finished = false

    static let chunkSize = 256 * 1024
    static let limit: UInt64 = 0xFFFF_FFFF
    /// 65,535 in the end record means ZIP64: one less is the most a plain ZIP holds.
    static let maxEntries = 0xFFFE

    /// Creates (or empties) the file at `url`. Every entry gets `modified` as its date,
    /// in UTC, so the same content gives the same archive.
    public init(url: URL, modified: Date) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        handle = try FileHandle(forWritingTo: url)
        (time, date) = Self.dosDateTime(modified)
    }

    deinit { try? handle.close() }

    public func add(name: String, data: Data) throws {
        try addEntry(name: name, size: UInt64(data.count), crc: CRC32.checksum(data)) {
            try handle.write(contentsOf: data)
        }
    }

    /// Adds a file's content: read once for its CRC-32, then copied.
    public func add(name: String, contentsOf url: URL) throws {
        let reader = try FileHandle(forReadingFrom: url)
        defer { try? reader.close() }
        var crc = CRC32()
        var size: UInt64 = 0
        while let chunk = try reader.read(upToCount: Self.chunkSize), !chunk.isEmpty {
            crc.update(chunk)
            size += UInt64(chunk.count)
            if size >= Self.limit { throw ZipError.tooLarge }
        }
        try reader.seek(toOffset: 0)
        try addEntry(name: name, size: size, crc: crc.value) {
            var copied: UInt64 = 0
            while copied < size, let chunk = try reader.read(upToCount: Int(min(UInt64(Self.chunkSize), size - copied))),
                  !chunk.isEmpty {
                try handle.write(contentsOf: chunk)
                copied += UInt64(chunk.count)
            }
            // The file changed while it was copied.
            guard copied == size else { throw ZipError.corrupt }
        }
    }

    private func addEntry(name: String, size: UInt64, crc: UInt32, body: () throws -> Void) throws {
        precondition(!finished, "entry added after finish()")
        let nameBytes = Data(name.utf8)
        guard nameBytes.count <= 0xFFFF, count < Self.maxEntries else { throw ZipError.tooLarge }
        let headerSize = UInt64(30 + nameBytes.count)
        guard offset + headerSize + size < Self.limit else { throw ZipError.tooLarge }
        let localOffset = offset
        var header = Data()
        header.appendLE(UInt32(0x0403_4B50))
        header.appendLE(UInt16(20)) // version needed: 2.0
        header.appendLE(UInt16(0x0800)) // names in UTF-8
        header.appendLE(UInt16(0)) // stored
        header.appendLE(time)
        header.appendLE(date)
        header.appendLE(crc)
        header.appendLE(UInt32(size))
        header.appendLE(UInt32(size))
        header.appendLE(UInt16(nameBytes.count))
        header.appendLE(UInt16(0))
        header.append(nameBytes)
        try handle.write(contentsOf: header)
        try body()
        offset += headerSize + size

        central.appendLE(UInt32(0x0201_4B50))
        central.appendLE(UInt16(0x0314)) // made by: Unix, 2.0 (for the file mode below)
        central.appendLE(UInt16(20))
        central.appendLE(UInt16(0x0800))
        central.appendLE(UInt16(0))
        central.appendLE(time)
        central.appendLE(date)
        central.appendLE(crc)
        central.appendLE(UInt32(size))
        central.appendLE(UInt32(size))
        central.appendLE(UInt16(nameBytes.count))
        central.appendLE(UInt16(0)) // extra
        central.appendLE(UInt16(0)) // comment
        central.appendLE(UInt16(0)) // disk
        central.appendLE(UInt16(0)) // internal attributes
        central.appendLE(UInt32(0o100644) << 16) // a regular file, rw-r--r--
        central.appendLE(UInt32(localOffset))
        central.append(nameBytes)
        count += 1
    }

    /// Writes the central directory and closes the file.
    public func finish() throws {
        guard !finished else { return }
        finished = true
        guard offset + UInt64(central.count) < Self.limit else { throw ZipError.tooLarge }
        var end = Data()
        end.appendLE(UInt32(0x0605_4B50))
        end.appendLE(UInt16(0))
        end.appendLE(UInt16(0))
        end.appendLE(UInt16(count))
        end.appendLE(UInt16(count))
        end.appendLE(UInt32(central.count))
        end.appendLE(UInt32(offset))
        end.appendLE(UInt16(0))
        try handle.write(contentsOf: central + end)
        try handle.synchronize()
        try handle.close()
    }

    /// MS-DOS time and date fields, in UTC (1980 to 2107).
    static func dosDateTime(_ date: Date) -> (time: UInt16, date: UInt16) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        let year: Int = min(max((c.year ?? 1980) - 1980, 0), 127)
        let hour: Int = c.hour ?? 0, minute: Int = c.minute ?? 0, second: Int = c.second ?? 0
        let month: Int = c.month ?? 1, dayOfMonth: Int = c.day ?? 1
        let time = UInt16((hour << 11) | (minute << 5) | (second / 2))
        let day = UInt16((year << 9) | (month << 5) | dayOfMonth)
        return (time, day)
    }
}

/// One file of a ZIP archive, from its central directory.
public struct ZipEntry: Hashable, Sendable {
    public var name: String
    public var size: UInt64
    public var crc: UInt32
    var method: UInt16
    var flags: UInt16
    var compressedSize: UInt64
    var localHeaderOffset: UInt64

    public var isDirectory: Bool { name.hasSuffix("/") }
}

/// Reads a ZIP file from its central directory, entry by entry.
public final class ZipReader {
    private let handle: FileHandle
    private let fileSize: UInt64
    public let entries: [ZipEntry]
    /// 65,534 entries with names of 1 KB.
    static let maxDirectorySize: UInt64 = 0xFFFE * (46 + 1024)

    public init(url: URL) throws {
        let handle: FileHandle
        do { handle = try FileHandle(forReadingFrom: url) } catch { throw ZipError.notAZip }
        self.handle = handle
        fileSize = (try? handle.seekToEnd()) ?? 0
        do {
            entries = try Self.readDirectory(handle, fileSize: fileSize)
        } catch {
            try? handle.close()
            throw error
        }
    }

    deinit { try? handle.close() }

    public func entry(named name: String) -> ZipEntry? {
        entries.first { $0.name == name }
    }

    /// The entry's content; refused over `maxSize` bytes.
    public func data(of entry: ZipEntry, maxSize: UInt64) throws -> Data {
        var result = Data()
        try read(entry, maxSize: maxSize) { result.append($0) }
        return result
    }

    /// Copies the entry's content to a new file at `url`, checking its CRC-32.
    public func extract(_ entry: ZipEntry, to url: URL, maxSize: UInt64, chunk: (Data) -> Void = { _ in }) throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        let output = try FileHandle(forWritingTo: url)
        defer { try? output.close() }
        try read(entry, maxSize: maxSize) { data in
            try output.write(contentsOf: data)
            chunk(data)
        }
    }

    private func read(_ entry: ZipEntry, maxSize: UInt64, into consume: (Data) throws -> Void) throws {
        guard entry.method == 0, entry.flags & 0x0001 == 0, entry.compressedSize == entry.size else {
            throw ZipError.unsupported
        }
        guard entry.size <= maxSize else { throw ZipError.tooLarge }
        // The data starts after the local header, whose name and extra field can differ
        // in length from the central directory's.
        try handle.seek(toOffset: entry.localHeaderOffset)
        guard let local = try handle.read(upToCount: 30), local.count == 30, local.uint32(at: 0) == 0x0403_4B50 else {
            throw ZipError.notAZip
        }
        let start = entry.localHeaderOffset + 30 + UInt64(local.uint16(at: 26)) + UInt64(local.uint16(at: 28))
        guard start + entry.size <= fileSize else { throw ZipError.notAZip }
        try handle.seek(toOffset: start)
        var crc = CRC32()
        var remaining = entry.size
        while remaining > 0 {
            guard let chunk = try handle.read(upToCount: Int(min(UInt64(ZipWriter.chunkSize), remaining))), !chunk.isEmpty
            else { throw ZipError.notAZip }
            crc.update(chunk)
            try consume(chunk)
            remaining -= UInt64(chunk.count)
        }
        guard crc.value == entry.crc else { throw ZipError.corrupt }
    }

    private static func readDirectory(_ handle: FileHandle, fileSize: UInt64) throws -> [ZipEntry] {
        // The end record is in the last 22 bytes, or before a comment of up to 65,535 bytes.
        let tailSize = min(fileSize, 22 + 0xFFFF)
        guard tailSize >= 22 else { throw ZipError.notAZip }
        try handle.seek(toOffset: fileSize - tailSize)
        guard let tail = try handle.read(upToCount: Int(tailSize)), tail.count == Int(tailSize) else { throw ZipError.notAZip }
        guard let endIndex = stride(from: tail.count - 22, through: 0, by: -1).first(where: { tail.uint32(at: $0) == 0x0605_4B50 })
        else {
            // A ZIP file (it starts with an entry) without its end record was cut short.
            try handle.seek(toOffset: 0)
            if let start = try handle.read(upToCount: 4), start.count == 4, start.uint32(at: 0) == 0x0403_4B50 {
                throw ZipError.corrupt
            }
            throw ZipError.notAZip
        }
        let disk = tail.uint16(at: endIndex + 4), directoryDisk = tail.uint16(at: endIndex + 6)
        let onDisk = tail.uint16(at: endIndex + 8), total = tail.uint16(at: endIndex + 10)
        let directorySize = UInt64(tail.uint32(at: endIndex + 12)), directoryOffset = UInt64(tail.uint32(at: endIndex + 16))
        guard disk == 0, directoryDisk == 0, onDisk == total else { throw ZipError.unsupported }
        if total == 0xFFFF || directorySize == 0xFFFF_FFFF || directoryOffset == 0xFFFF_FFFF { throw ZipError.unsupported }
        guard directoryOffset + directorySize <= fileSize else { throw ZipError.notAZip }
        // Read whole: refused when larger than any archive Encave writes could need.
        guard directorySize <= Self.maxDirectorySize else { throw ZipError.tooLarge }
        try handle.seek(toOffset: directoryOffset)
        guard let directory = try handle.read(upToCount: Int(directorySize)), directory.count == Int(directorySize)
        else { throw ZipError.notAZip }
        var entries: [ZipEntry] = []
        var index = 0
        for _ in 0..<Int(total) {
            guard index + 46 <= directory.count, directory.uint32(at: index) == 0x0201_4B50 else { throw ZipError.notAZip }
            let nameLength = Int(directory.uint16(at: index + 28)), extraLength = Int(directory.uint16(at: index + 30))
            let commentLength = Int(directory.uint16(at: index + 32))
            guard index + 46 + nameLength + extraLength + commentLength <= directory.count else { throw ZipError.notAZip }
            let nameData = directory.subdata(in: (index + 46)..<(index + 46 + nameLength))
            let entry = ZipEntry(
                name: String(decoding: nameData, as: UTF8.self),
                size: UInt64(directory.uint32(at: index + 24)),
                crc: directory.uint32(at: index + 16),
                method: directory.uint16(at: index + 10),
                flags: directory.uint16(at: index + 8),
                compressedSize: UInt64(directory.uint32(at: index + 20)),
                localHeaderOffset: UInt64(directory.uint32(at: index + 42))
            )
            if entry.size == 0xFFFF_FFFF || entry.compressedSize == 0xFFFF_FFFF || entry.localHeaderOffset == 0xFFFF_FFFF {
                throw ZipError.unsupported
            }
            entries.append(entry)
            index += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }
}

private extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    func uint16(at offset: Int) -> UInt16 {
        let i = startIndex + offset
        return UInt16(self[i]) | UInt16(self[i + 1]) << 8
    }

    func uint32(at offset: Int) -> UInt32 {
        let i = startIndex + offset
        return UInt32(self[i]) | UInt32(self[i + 1]) << 8 | UInt32(self[i + 2]) << 16 | UInt32(self[i + 3]) << 24
    }
}
