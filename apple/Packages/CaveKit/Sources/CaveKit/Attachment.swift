import Foundation

/// A document kept with a wine (for all its bottles) or with one bottle: an invoice, a
/// certificate of authenticity, a technical sheet, an article, a picture of a receipt.
/// The file is stored by the app under `id`, a hash of its content like a photo ID, and
/// syncs like the photos (docs/SYNC.md). Backups hold this record, not the file.
public struct Attachment: Codable, Hashable, Sendable, Identifiable {
    /// `sha256-` and the hex SHA-256 of the file (`PhotoID.make`).
    public var id: String
    /// The file name shown, normalized by `normalizedName`.
    public var name: String {
        didSet { name = Attachment.normalizedName(name) }
    }
    /// MIME type ("application/pdf"), on every platform.
    public var contentType: String
    public var byteCount: Int
    public var addedAt: Date

    /// Largest document accepted when adding one: 8 MB (8,000,000 bytes). Sealed for remote
    /// sync it stays under a relay's 8 MiB file limit; on the local network its Base64 fits
    /// a 16 MiB frame.
    public static let maxByteCount = 8_000_000
    /// Longest name kept, in Unicode scalars (the same count on every platform).
    public static let maxNameLength = 200
    /// When a file has no known type.
    public static let genericContentType = "application/octet-stream"

    public init(id: String, name: String, contentType: String, byteCount: Int, addedAt: Date = .now) {
        self.id = id
        self.name = Attachment.normalizedName(name)
        self.contentType = contentType
        self.byteCount = byteCount
        self.addedAt = addedAt
    }

    public init(from decoder: Decoder) throws {
        // Every field is required: a reader never fills one in (docs/SYNC.md).
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = Attachment.normalizedName(try c.decode(String.self, forKey: .name))
        contentType = try c.decode(String.self, forKey: .contentType)
        byteCount = try c.decode(Int.self, forKey: .byteCount)
        addedAt = try c.decode(Date.self, forKey: .addedAt)
    }

    /// The name as every device keeps it: control characters become spaces, `/` and `\`
    /// become `-`, white space is trimmed at both ends, and at most 200 Unicode scalars are
    /// kept (then trimmed again). The Kotlin core applies the same rule.
    public static func normalizedName(_ name: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in name.unicodeScalars {
            if scalar.properties.generalCategory == .control {
                scalars.append(" ")
            } else if scalar == "/" || scalar == "\\" {
                scalars.append("-")
            } else {
                scalars.append(scalar)
            }
        }
        func trimmed(_ view: String.UnicodeScalarView) -> String.UnicodeScalarView {
            var list = Array(view)
            while let first = list.first, first.properties.isWhitespace { list.removeFirst() }
            while let last = list.last, last.properties.isWhitespace { list.removeLast() }
            return String.UnicodeScalarView(list)
        }
        let cut = String.UnicodeScalarView(trimmed(scalars).prefix(maxNameLength))
        return String(trimmed(cut))
    }

    /// Each document once (by ID, the first kept), in order; nil when none are left.
    public static func unique(_ attachments: [Attachment]?) -> [Attachment]? {
        var seen = Set<String>()
        let unique = (attachments ?? []).filter { seen.insert($0.id).inserted }
        return unique.isEmpty ? nil : unique
    }
}

/// What a document is attached to.
public enum AttachmentTarget: Hashable, Sendable {
    /// The wine, so every bottle of it.
    case wine(UUID)
    case bottle(UUID)
}

/// The two places the app keeps files: photos and documents. A file is named after its
/// content, so the same ID can be both (a picture of a receipt added both ways).
public enum FileKind: String, Hashable, Sendable, CaseIterable {
    case photo, document
}

/// A file the cellar refers to.
public struct FileRef: Hashable, Sendable {
    public var id: String
    public var kind: FileKind

    public init(id: String, kind: FileKind) { self.id = id; self.kind = kind }
}

public extension Cellar {
    /// Every document of the wines and bottles (to sync, and to keep when cleaning up).
    var attachmentIDs: Set<String> {
        Set(wines.values.flatMap { ($0.attachments ?? []).map(\.id) } + bottles.values.flatMap { ($0.attachments ?? []).map(\.id) })
    }

    /// Every file the cellar refers to, photos and documents: what a sync exchanges.
    var fileRefs: Set<FileRef> {
        Set(photoIDs.map { FileRef(id: $0, kind: .photo) } + attachmentIDs.map { FileRef(id: $0, kind: .document) })
    }

    /// The documents of a wine or a bottle, in the order they were added.
    func attachments(of target: AttachmentTarget) -> [Attachment] {
        switch target {
        case .wine(let id): wines[id]?.attachments ?? []
        case .bottle(let id): bottles[id]?.attachments ?? []
        }
    }

    /// Adds documents at the end (one already there, by ID, is kept as it is).
    mutating func attach(_ attachments: [Attachment], to target: AttachmentTarget, at index: Int? = nil) throws {
        try changeAttachments(of: target) { list in
            let new = attachments.filter { item in !list.contains { $0.id == item.id } }
            list.insert(contentsOf: new, at: min(index ?? list.count, list.count))
        }
    }

    /// Renames a document. A blank name keeps the old one.
    mutating func renameAttachment(_ id: String, of target: AttachmentTarget, to name: String) throws {
        guard !Attachment.normalizedName(name).isEmpty else { return }
        try changeAttachments(of: target) { list in
            guard let index = list.firstIndex(where: { $0.id == id }) else { return }
            list[index].name = name
        }
    }

    /// Removes a document; returns it and where it was, for Undo (the file stays for a while).
    @discardableResult
    mutating func removeAttachment(_ id: String, from target: AttachmentTarget) throws -> (attachment: Attachment, index: Int)? {
        var removed: (Attachment, Int)?
        try changeAttachments(of: target) { list in
            guard let index = list.firstIndex(where: { $0.id == id }) else { return }
            removed = (list.remove(at: index), index)
        }
        return removed
    }

    private mutating func changeAttachments(of target: AttachmentTarget, _ change: (inout [Attachment]) -> Void) throws {
        switch target {
        case .wine(let id):
            guard var wine = wines[id] else { throw CellarError.unknownWine }
            var list = wine.attachments ?? []
            change(&list)
            wine.attachments = list
            wines[id] = wine
        case .bottle(let id):
            guard var bottle = bottles[id] else { throw CellarError.unknownBottle }
            var list = bottle.attachments ?? []
            change(&list)
            bottle.attachments = list
            bottles[id] = bottle
        }
    }
}
