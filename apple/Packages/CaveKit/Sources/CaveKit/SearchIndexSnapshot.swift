import Foundation

/// What was last put in the system search index (Spotlight) for each wine, so only
/// the wines whose indexed content changed are indexed again.
public struct SearchIndexSnapshot: Sendable, Equatable {
    /// The indexed content of a wine.
    public struct Entry: Sendable, Equatable {
        public var name: String
        public var detail: String

        public init(name: String, detail: String) {
            self.name = name
            self.detail = detail
        }
    }

    /// What to send to the index to go from the snapshot to new content.
    public struct Changes: Sendable, Equatable {
        /// Nothing was indexed yet (for example at launch): the index is rebuilt from scratch.
        public var isFullReindex: Bool
        /// Wines to index (new or changed), sorted.
        public var toIndex: [UUID]
        /// Wines to delete from the index, sorted.
        public var toRemove: [UUID]
        /// The set of wine names changed (Siri phrases with a wine need an update).
        public var namesChanged: Bool

        public var isEmpty: Bool { !isFullReindex && toIndex.isEmpty && toRemove.isEmpty && !namesChanged }
    }

    /// nil until the index was rebuilt once.
    public private(set) var entries: [UUID: Entry]?

    public init(entries: [UUID: Entry]? = nil) {
        self.entries = entries
    }

    public func changes(to new: [UUID: Entry]) -> Changes {
        guard let old = entries else {
            return Changes(isFullReindex: true, toIndex: new.keys.sorted(), toRemove: [], namesChanged: true)
        }
        let toIndex = new.filter { old[$0.key] != $0.value }.keys.sorted()
        let toRemove = old.keys.filter { new[$0] == nil }.sorted()
        let namesChanged = Set(old.values.map(\.name)) != Set(new.values.map(\.name))
        return Changes(isFullReindex: false, toIndex: toIndex, toRemove: toRemove, namesChanged: namesChanged)
    }

    /// The index now holds `new`.
    public mutating func record(_ new: [UUID: Entry]) {
        entries = new
    }

    /// The index may not hold what the snapshot says (an indexing failed): rebuild it next time.
    public mutating func invalidate() {
        entries = nil
    }
}
