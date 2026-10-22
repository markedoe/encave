import Crypto
import Foundation

/// A full backup: a standard ZIP file, not encrypted, that anyone can open.
///
/// - `README.txt`: what the files are (English and French).
/// - `cellar.json`: the cellar as `CellarExport.json` writes it, the source of truth;
///   it can also be imported on its own.
/// - `files.json`: which file of the archive is which photo or document (`Manifest`).
/// - `photos/` and `documents/`: the files, under readable names ("Château X 2015.jpg",
///   "Invoice.pdf"). A restore hashes each file again to find its ID, so a renamed or
///   changed file is never taken for another.
///
/// The Android app writes and reads the same archive (`CellarArchive.kt`).
public enum CellarArchive {
    public static let format = "encave-archive"
    /// Version of the archive's layout and `files.json`. A newer one is refused.
    public static let version = 1
    /// Largest photo or document read from an archive: the limit for adding a document.
    public static let maxFileByteCount = UInt64(Attachment.maxByteCount)
    static let maxCellarByteCount: UInt64 = 256 * 1024 * 1024

    static let readmePath = "README.txt", cellarPath = "cellar.json", manifestPath = "files.json"
    static let photoFolder = "photos/", documentFolder = "documents/"

    /// "Encave Backup 2026-10-21.zip".
    public static func fileName(date: Date, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return "Encave Backup \(formatter.string(from: date)).zip"
    }

    /// Which file of the archive is which photo or document.
    public struct Manifest: Codable, Equatable, Sendable {
        public var format: String
        public var version: Int
        public var files: [Item]

        public init(files: [Item]) {
            format = CellarArchive.format
            version = CellarArchive.version
            self.files = files
        }
    }

    public struct Item: Codable, Hashable, Sendable {
        /// "photos/Château X 2015.jpg".
        public var path: String
        /// The file's ID, the hash of its content.
        public var id: String
        /// "photo" or "document".
        public var kind: String

        public init(path: String, id: String, kind: FileKind) {
            self.path = path
            self.id = id
            self.kind = kind.rawValue
        }

        public var ref: FileRef? { FileKind(rawValue: kind).map { FileRef(id: id, kind: $0) } }
    }

    // MARK: Writing

    /// Where each file the cellar refers to goes, in archive order: the photos of each
    /// wine, named after it, then the documents of the wines, then those of the bottles,
    /// under their own names. Names are made safe for any file system and unique
    /// (ignoring case) with " 2", " 3"… The Android app names them the same way.
    public static func plan(for cellar: Cellar) -> [Item] {
        var items: [Item] = []
        var seen = Set<FileRef>()
        var used = Set<String>()
        func add(_ ref: FileRef, stem: String, extension ext: String?, folder: String) {
            guard PhotoID.isValid(ref.id), seen.insert(ref).inserted else { return }
            let name = uniqueName(stem: stem, extension: ext, folder: folder, used: &used)
            items.append(Item(path: folder + name, id: ref.id, kind: ref.kind))
        }
        let wines = cellar.wines.values.sorted { $0.id.uuidString < $1.id.uuidString }
        for wine in wines {
            for id in wine.allPhotoIDs {
                add(FileRef(id: id, kind: .photo), stem: cleanStem(wine.displayName, fallback: "Photo"),
                    extension: "jpg", folder: photoFolder)
            }
        }
        func addDocuments(_ attachments: [Attachment]?) {
            for attachment in attachments ?? [] {
                let (stem, ext) = documentName(attachment)
                add(FileRef(id: attachment.id, kind: .document), stem: stem, extension: ext, folder: documentFolder)
            }
        }
        wines.forEach { addDocuments($0.attachments) }
        cellar.bottles.values.sorted { $0.id.uuidString < $1.id.uuidString }.forEach { addDocuments($0.attachments) }
        return items
    }

    /// What a backup holds of the photos and documents the cellar refers to.
    public struct WriteResult: Equatable, Sendable {
        /// Written in the archive.
        public var included: Int
        /// Not on this device (not synced yet): left out.
        public var missing: Int
        /// Left out because the archive would be over 4 GB or 65,535 files, the limits of a
        /// ZIP file every system opens.
        public var leftOut: Int

        public init(included: Int, missing: Int, leftOut: Int) {
            self.included = included
            self.missing = missing
            self.leftOut = leftOut
        }
    }

    /// Writes the archive to `url`. `fileURL` gives the local file of a photo or document,
    /// nil if it is not on this device (it is then left out). A file that would take the
    /// archive over the ZIP limits is left out too (`fitting`); the cellar itself is always
    /// written. `isCancelled` is checked between files (a background task running out of time).
    @discardableResult
    public static func write(_ cellar: Cellar, exportedAt: Date, to url: URL, fileURL: (FileRef) -> URL?,
                             isCancelled: () -> Bool = { false }) throws -> WriteResult {
        let writer = try ZipWriter(url: url, modified: exportedAt)
        let readmeData = Data(readme.utf8)
        let cellarData = try CellarExport.json(cellar, exportedAt: exportedAt)
        try writer.add(name: readmePath, data: readmeData)
        try writer.add(name: cellarPath, data: cellarData)
        var available: [(item: Item, url: URL, size: UInt64)] = []
        var missing = 0
        for item in plan(for: cellar) {
            guard let ref = item.ref, let source = fileURL(ref),
                  let size = (try? FileManager.default.attributesOfItem(atPath: source.path))?[.size] as? NSNumber
            else { missing += 1; continue }
            available.append((item, source, size.uint64Value))
        }
        let fits = fitting(available.map { (Data($0.item.path.utf8).count, $0.size) },
                           fixedBytes: UInt64(readmeData.count + cellarData.count))
        let present = zip(available, fits).filter(\.1).map(\.0)
        // The manifest lists only what is written, and comes before the files.
        try writer.add(name: manifestPath, data: try manifestData(Manifest(files: present.map(\.item))))
        for file in present {
            if isCancelled() { throw CancellationError() }
            try writer.add(name: file.item.path, contentsOf: file.url)
        }
        try writer.finish()
        return WriteResult(included: present.count, missing: missing, leftOut: available.count - present.count)
    }

    /// Which files fit in an archive, in order: at most 65,534 entries and 4 GB in all, with
    /// room for the headers, the manifest and the central directory. `fixedBytes`: the README
    /// and the cellar. The Android app counts the same way.
    static func fitting(_ files: [(nameBytes: Int, size: UInt64)], fixedBytes: UInt64,
                        limit: UInt64 = ZipWriter.limit, maxEntries: Int = ZipWriter.maxEntries) -> [Bool] {
        // README, cellar and manifest: two headers each (local and central) and a name.
        var total = fixedBytes + 3 * (30 + 46 + 16) + 22 + reserve
        var entries = 3
        return files.map { file in
            // Two headers, the name three times (twice in headers, once in the manifest), and
            // the manifest's line for it (ID, kind, JSON).
            let cost = file.size + UInt64(76 + 3 * file.nameBytes + 160)
            guard entries < maxEntries, total + cost < limit else { return false }
            total += cost
            entries += 1
            return true
        }
    }

    /// Room kept below the 4 GB limit for what the estimate leaves out.
    static let reserve: UInt64 = 1 << 20

    static func manifestData(_ manifest: Manifest) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(manifest)
    }

    // MARK: Reading

    /// What an archive holds, read and checked.
    public struct Contents: Sendable {
        public var backup: CellarBackup
        /// Files of the archive the cellar refers to, copied to the working folder and
        /// checked: each one's content has the ID it is kept under.
        public var files: [FileRef: URL]
        /// Files left out: damaged, too large, or not referred to by the cellar.
        public var skipped: Int
    }

    /// Reads an archive, copying its photos and documents into `workingFolder` (which
    /// must exist; the caller removes it afterwards). An archive made by a newer version
    /// of Encave is refused, as is a file that is not an Encave archive.
    public static func read(_ url: URL, workingFolder: URL) throws -> Contents {
        let zip: ZipReader
        do { zip = try ZipReader(url: url) } catch let error as ZipError { throw ImportError(error) }
        guard let cellarEntry = zip.entry(named: cellarPath) else { throw ImportError.notAnEncaveBackup }
        let backup: CellarBackup
        do {
            backup = try CellarExport.backup(fromJSON: try zip.data(of: cellarEntry, maxSize: maxCellarByteCount))
        } catch let error as ZipError { throw ImportError(error) }

        var kinds: [String: Item] = [:]
        if let entry = zip.entry(named: manifestPath), let data = try? zip.data(of: entry, maxSize: maxCellarByteCount) {
            if let envelope = try? JSONDecoder().decode(CellarExport.Envelope.self, from: data),
               envelope.format == format, let version = envelope.version, version > Self.version {
                throw ImportError.newerVersion
            }
            if let manifest = try? JSONDecoder().decode(Manifest.self, from: data), manifest.format == format {
                for item in manifest.files { kinds[item.path] = item }
            }
        }

        let referenced = backup.cellar.fileRefs
        var files: [FileRef: URL] = [:]
        var skipped = 0
        for (index, entry) in zip.entries.enumerated() where !entry.isDirectory {
            let folderKind: FileKind? = entry.name.hasPrefix(photoFolder) ? .photo
                : entry.name.hasPrefix(documentFolder) ? .document : nil
            guard let kind = kinds[entry.name]?.ref?.kind ?? folderKind else { continue }
            guard entry.size <= maxFileByteCount else { skipped += 1; continue }
            // Named by the manifest as a file the cellar does not refer to, or one already read:
            // not copied at all.
            if let named = kinds[entry.name]?.ref, !referenced.contains(named) || files[named] != nil {
                skipped += 1
                continue
            }
            let target = workingFolder.appendingPathComponent("file-\(index)")
            var hash = SHA256()
            do {
                try zip.extract(entry, to: target, maxSize: maxFileByteCount) { hash.update(data: $0) }
            } catch {
                try? FileManager.default.removeItem(at: target)
                skipped += 1
                continue
            }
            let id = "sha256-" + hash.finalize().map { String(format: "%02x", $0) }.joined()
            let ref = FileRef(id: id, kind: kind)
            // A file whose content is not the one the manifest names, that the cellar does not
            // refer to, or that is there twice is not kept.
            let expected = kinds[entry.name]?.id ?? id
            guard expected == id, referenced.contains(ref), files[ref] == nil else {
                try? FileManager.default.removeItem(at: target)
                skipped += 1
                continue
            }
            files[ref] = target
        }
        return Contents(backup: backup, files: files, skipped: skipped)
    }

    // MARK: Names

    /// Characters some file systems refuse, replaced by "-".
    static let forbidden: Set<Unicode.Scalar> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
    /// Trimmed at both ends: spaces, and dots (a hidden file, or a name Windows refuses).
    static let trimmed: Set<Unicode.Scalar> = [" ", "\u{00A0}", "\u{3000}", "."]
    static let maxStemLength = 80

    /// A name safe on any file system: control characters become spaces, the characters
    /// some systems refuse become "-", spaces and dots are trimmed at both ends, and at
    /// most 80 Unicode scalars are kept.
    static func cleanStem(_ text: String, fallback: String) -> String {
        var scalars: [Unicode.Scalar] = text.unicodeScalars.map { scalar in
            if scalar.properties.generalCategory == .control { return " " }
            return forbidden.contains(scalar) ? "-" : scalar
        }
        func trim() {
            while let first = scalars.first, trimmed.contains(first) { scalars.removeFirst() }
            while let last = scalars.last, trimmed.contains(last) { scalars.removeLast() }
        }
        trim()
        if scalars.count > maxStemLength { scalars = Array(scalars.prefix(maxStemLength)) }
        trim()
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return scalars.isEmpty ? fallback : String(view)
    }

    /// A document's name and extension: its own extension if it has one (1 to 8 letters
    /// and digits), otherwise the one of its type when known.
    static func documentName(_ attachment: Attachment) -> (stem: String, extension: String?) {
        let name = attachment.name
        if let dot = name.lastIndex(of: "."), dot != name.startIndex {
            let ext = name[name.index(after: dot)...]
            if (1...8).contains(ext.unicodeScalars.count), ext.unicodeScalars.allSatisfy({ $0.isASCII && ($0.properties.isAlphabetic || ("0"..."9").contains($0)) }) {
                return (cleanStem(String(name[..<dot]), fallback: "Document"), String(ext))
            }
        }
        return (cleanStem(name, fallback: "Document"), extensions[attachment.contentType.lowercased()])
    }

    static func uniqueName(stem: String, extension ext: String?, folder: String, used: inout Set<String>) -> String {
        var number = 1
        while true {
            let base = number == 1 ? stem : "\(stem) \(number)"
            let name = ext.map { "\(base).\($0)" } ?? base
            if used.insert((folder + name).lowercased()).inserted { return name }
            number += 1
        }
    }

    /// File name extensions of the usual document types (the same table as on Android).
    static let extensions: [String: String] = [
        "application/pdf": "pdf",
        "image/jpeg": "jpg", "image/png": "png", "image/heic": "heic", "image/heif": "heif",
        "image/gif": "gif", "image/webp": "webp", "image/tiff": "tiff",
        "text/plain": "txt", "text/csv": "csv", "text/html": "html", "application/rtf": "rtf",
        "application/msword": "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
        "application/vnd.ms-excel": "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
        "application/vnd.ms-powerpoint": "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation": "pptx",
        "application/vnd.oasis.opendocument.text": "odt",
        "application/vnd.oasis.opendocument.spreadsheet": "ods",
        "application/vnd.oasis.opendocument.presentation": "odp",
        "application/vnd.apple.pages": "pages", "application/vnd.apple.numbers": "numbers",
        "application/vnd.apple.keynote": "key",
        "application/zip": "zip", "message/rfc822": "eml",
    ]

    /// The archive's README.txt, the same on every platform.
    public static let readme = """
    Encave backup

    This archive holds your whole cellar, as it was when the backup was made:
    - cellar.json: wines, bottles, racks, the Journal and settings. It is the backup
      itself: in Encave, choose Settings > Your data > Import… and pick this archive
      (or cellar.json alone, without the photos and documents).
    - photos/ and documents/: the label photos and documents, under readable names.
    - files.json: which file is which photo or document.
    The archive is not encrypted: keep it somewhere safe.

    Sauvegarde Encave

    Cette archive contient toute votre cave, telle qu'elle était au moment de la sauvegarde :
    - cellar.json : vins, bouteilles, casiers, le Journal et les réglages. C'est la
      sauvegarde elle-même : dans Encave, choisissez Réglages > Vos données > Importer…
      et prenez cette archive (ou cellar.json seul, sans les photos ni les documents).
    - photos/ et documents/ : les photos d'étiquettes et les documents, sous des noms lisibles.
    - files.json : quel fichier est quelle photo ou quel document.
    L'archive n'est pas chiffrée : gardez-la en lieu sûr.

    """
}

extension ImportError {
    init(_ error: ZipError) {
        switch error {
        case .notAZip: self = .notAnEncaveBackup
        case .unsupported: self = .unsupportedArchive
        case .corrupt, .tooLarge: self = .damagedArchive
        }
    }
}
