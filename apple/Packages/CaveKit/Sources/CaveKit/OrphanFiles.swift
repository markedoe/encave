import Foundation

/// Photo and document files, each named after its ID, that the cellar no longer refers to.
/// They stay a while (an Undo, or a device that has not synced yet, may refer to them
/// again); when each was first seen unreferenced is noted in a small list in the folder.
/// The Android app keeps them the same way (OrphanFiles.kt).
public enum OrphanFiles {
    static let listName = "unreferenced.json"

    /// Removes the files of `folder` ending in `pathExtension` that are not in `ids` and were
    /// first seen unreferenced `gracePeriod` ago or more (or were made due by `expire`).
    public static func remove(in folder: URL, pathExtension: String, keeping ids: Set<String>,
                              now: Date, gracePeriod: TimeInterval) {
        guard let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        else { return }
        let seen = list(in: folder)
        var unreferenced: [String: Date] = [:]
        for file in files where file.pathExtension == pathExtension {
            let id = file.deletingPathExtension().lastPathComponent
            guard !ids.contains(id) else { continue }
            let since = seen[id] ?? now
            if now.timeIntervalSince(since) >= gracePeriod {
                try? FileManager.default.removeItem(at: file)
            } else {
                unreferenced[id] = since
            }
        }
        if unreferenced != seen { write(unreferenced, in: folder) }
    }

    /// Erase All Data with the previous versions and backups: every file not in `ids` is due
    /// at once, removed by the next `remove` (once the Undo is gone, or at the next launch).
    public static func expire(in folder: URL, pathExtension: String, keeping ids: Set<String>) {
        guard let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        else { return }
        var due = list(in: folder)
        for file in files where file.pathExtension == pathExtension {
            let id = file.deletingPathExtension().lastPathComponent
            if !ids.contains(id) { due[id] = .distantPast }
        }
        write(due, in: folder)
    }

    private static func list(in folder: URL) -> [String: Date] {
        let url = folder.appendingPathComponent(listName)
        return (try? JSONDecoder().decode([String: Date].self, from: Data(contentsOf: url))) ?? [:]
    }

    private static func write(_ list: [String: Date], in folder: URL) {
        let url = folder.appendingPathComponent(listName)
        if list.isEmpty {
            try? FileManager.default.removeItem(at: url)
        } else if let data = try? JSONEncoder().encode(list) {
            try? data.write(to: url, options: [.atomic])
        }
    }
}
