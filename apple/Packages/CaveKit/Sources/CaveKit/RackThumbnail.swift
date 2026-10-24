public extension Rack {
    /// The shelves drawn in a storage card: from the top, as many as fit in `layers` rows,
    /// stopping at the first one that does not fit (a lower shelf is never shown without
    /// the ones above it); at least the top shelf.
    func thumbnailShelves(layers: Int = 8) -> [Range<Int>] {
        var shown: [Range<Int>] = []
        for rows in shelfRows {
            guard (shown.last?.upperBound ?? 0) + rows.count <= layers else { break }
            shown.append(rows)
        }
        return shown.isEmpty ? Array(shelfRows.prefix(1)) : shown
    }
}
