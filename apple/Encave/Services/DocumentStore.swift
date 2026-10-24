import CaveKit
import Foundation
import UniformTypeIdentifiers

/// Documents of wines and bottles (invoices, certificates, technical sheets), kept as
/// they were added, next to the cellar. Each file is named after its content (the
/// attachment ID), like the photos.
enum DocumentStore {
    /// Files on disk end with this, whatever their type: the type is in the attachment.
    static let fileExtension = "document"

    static var folder: URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true) else { return nil }
        let folder = base.appendingPathComponent("Encave/Documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func url(for id: String) -> URL? {
        guard PhotoStore.isSafe(id) else { return nil }
        return folder?.appendingPathComponent("\(id).\(fileExtension)")
    }

    /// False when the cellar refers to a document that is not on this device yet (it has
    /// not synced yet, or a backup was imported: backups hold no files).
    static func exists(_ id: String) -> Bool {
        guard let url = url(for: id) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    static func data(for id: String) -> Data? {
        guard let url = url(for: id) else { return nil }
        return try? Data(contentsOf: url)
    }

    /// Saves a document received from another device, as is.
    static func store(_ data: Data, id: String) {
        guard let url = url(for: id), !FileManager.default.fileExists(atPath: url.path) else { return }
        try? data.write(to: url, options: [.atomic])
    }

    /// Removes document files nothing has referred to for the photos' grace period, so an
    /// Undo or a device that has not synced yet can still use them.
    static func removeOrphans(keeping ids: Set<String>, now: Date = .now) {
        guard let folder else { return }
        PhotoStore.removeOrphans(in: folder, pathExtension: fileExtension, keeping: ids, now: now)
    }

    /// Erase All Data with the backups: the document files nothing refers to are due at once.
    static func expireOrphans(keeping ids: Set<String>) {
        guard let folder else { return }
        OrphanFiles.expire(in: folder, pathExtension: fileExtension, keeping: ids)
    }

    // MARK: Adding

    enum AddError: Error {
        case tooLarge, unreadable
    }

    /// Copies a file the user picked to a temporary place, checking its size, so it can
    /// be attached later (when the bottles are added) or right away.
    static func stage(_ source: URL) throws -> PendingDocument {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let values = try? source.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        if let size = values?.fileSize, size > Attachment.maxByteCount { throw AddError.tooLarge }
        guard let data = try? Data(contentsOf: source, options: .mappedIfSafe) else { throw AddError.unreadable }
        guard data.count <= Attachment.maxByteCount else { throw AddError.tooLarge }
        let type = values?.contentType ?? UTType(filenameExtension: source.pathExtension)
        return try stage(data, name: source.lastPathComponent, contentType: type?.preferredMIMEType)
    }

    /// Keeps new content (a scan) in a temporary place, like a picked file.
    static func stage(_ data: Data, name: String, contentType: String?) throws -> PendingDocument {
        guard data.count <= Attachment.maxByteCount else { throw AddError.tooLarge }
        let pending = PendingDocument(name: Attachment.normalizedName(name),
                                      contentType: contentType ?? Attachment.genericContentType, byteCount: data.count)
        guard let url = pending.url else { throw AddError.unreadable }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        do { try data.write(to: url, options: [.atomic]) } catch { throw AddError.unreadable }
        return pending
    }

    /// Stores a pending document for good and returns its attachment. The temporary copy
    /// stays until `discard`, so a save that fails can be tried again.
    static func commit(_ pending: PendingDocument, at date: Date = .now) -> Attachment? {
        guard let source = pending.url, let data = try? Data(contentsOf: source) else { return nil }
        let id = PhotoID.make(for: data)
        guard let url = url(for: id) else { return nil }
        if !FileManager.default.fileExists(atPath: url.path) {
            guard (try? data.write(to: url, options: [.atomic])) != nil else { return nil }
        }
        return Attachment(id: id, name: pending.name.isEmpty ? String(localized: "Document") : pending.name,
                          contentType: pending.contentType, byteCount: data.count, addedAt: date)
    }

    static func discard(_ pending: PendingDocument) {
        guard let url = pending.url else { return }
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }

    // MARK: Opening and sharing

    /// Temporary copies of documents, to preview and share, removed at each launch.
    static var temporaryFolder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Documents", isDirectory: true)
    }

    /// At launch: copies made to preview or share, and pending ones left by a cancelled or
    /// interrupted Add Bottles.
    static func removeTemporaryCopies() {
        try? FileManager.default.removeItem(at: temporaryFolder)
        try? FileManager.default.removeItem(at: PendingDocument.folder)
    }

    /// A copy of the document with its name and an extension that matches its type, in a
    /// folder of its own (two documents can have the same name). Nil if the file is not
    /// on this device.
    static func temporaryCopy(of attachment: Attachment) -> URL? {
        guard let source = url(for: attachment.id), FileManager.default.fileExists(atPath: source.path) else { return nil }
        let folder = temporaryFolder.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let target = folder.appendingPathComponent(fileName(for: attachment))
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: source, to: target)
            return target
        } catch {
            return nil
        }
    }

    /// The document's name as a file name: never a path, with the extension of its type.
    static func fileName(for attachment: Attachment) -> String {
        var name = attachment.name
        if name.isEmpty || name == "." || name == ".." { name = String(localized: "Document") }
        if name.hasPrefix(".") { name = "_" + name }
        let current = (name as NSString).pathExtension
        // The extension is kept whole; a long name is shortened before it.
        let kept = current.isEmpty || current.count > 16 ? nil : current
        func named(_ base: String, _ pathExtension: String?) -> String {
            Attachment.fileName(base: base, pathExtension: pathExtension)
        }
        guard let type = UTType(mimeType: attachment.contentType),
              let preferred = type.preferredFilenameExtension else {
            return kept.map { named((name as NSString).deletingPathExtension, $0) } ?? named(name, nil)
        }
        let known = type.tags[.filenameExtension] ?? [preferred]
        if let kept, known.contains(kept.lowercased()) { return named((name as NSString).deletingPathExtension, kept) }
        return named(name, preferred)
    }
}

/// A document picked or scanned, not attached yet: a copy in a temporary place.
struct PendingDocument: Identifiable, Hashable, Sendable {
    let id = UUID()
    var name: String
    var contentType: String
    var byteCount: Int

    static var folder: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("PendingDocuments", isDirectory: true)
    }

    var url: URL? {
        PendingDocument.folder.appendingPathComponent(id.uuidString, isDirectory: true).appendingPathComponent("file")
    }
}
