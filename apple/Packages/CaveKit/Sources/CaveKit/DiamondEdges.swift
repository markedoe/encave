import Foundation

/// A side of a diamond rack. Along it the lattice leaves half diamonds, triangles that can hold
/// bottles; each side is turned on or off ("edgeTriangles", docs/SYNC.md).
public enum DiamondEdge: String, CaseIterable, Codable, Sendable {
    case top, left, bottom, right
}

/// A place of a diamond rack with edge triangles: a whole diamond, by its row and column among
/// the diamonds, or a triangle, by its side and its number along that side (from the left on the
/// top and bottom, from the top on the left and right), all counted from 0.
public enum DiamondPlace: Hashable, Sendable {
    case diamond(row: Int, column: Int)
    case triangle(DiamondEdge, index: Int)
}

/// How a diamond rack's triangles are laid out among its cells (docs/SYNC.md). Rows of diamonds
/// nest by half a diamond, every other one offset half a diamond right, as drawn. The triangles
/// are ordinary cells added after those of the diamonds, so turning a side on or off never moves
/// a diamond, and a version that does not know them shows them as whole diamonds:
/// - the rows of diamonds stay rows 0 to `rows` − 1, each with `columns` diamonds;
/// - top and bottom: a row of `columns` triangles each, after the rows of diamonds, in the order
///   the sides are listed (`appendedRows`); drawn above the first row and below the last one;
/// - left: a last cell on each offset row of diamonds (B, D…), drawn first, in the notch at its left;
/// - right: a last cell on each row not offset (A, C…), in the notch at its right.
/// The small corners (a quarter of a diamond) hold no bottle and are not places.
public struct DiamondLayout: Hashable, Sendable {
    /// Rows of whole diamonds.
    public var rows: Int
    /// Whole diamonds in each row.
    public var columns: Int
    public var edges: Set<DiamondEdge>
    /// The rows of triangles after the rows of diamonds: top and bottom, in their listed order.
    public var appendedRows: [DiamondEdge]

    /// `sides` in their listed order (each once): it decides which row of triangles comes first.
    public init(rows: Int, columns: Int, sides: [DiamondEdge]) {
        self.rows = max(1, rows); self.columns = max(1, columns)
        var seen = Set<DiamondEdge>(), ordered: [DiamondEdge] = []
        for side in sides where seen.insert(side).inserted { ordered.append(side) }
        edges = seen
        appendedRows = ordered.filter { $0 == .top || $0 == .bottom }
    }

    var leftCells: Int { edges.contains(.left) ? 1 : 0 }
    var rightCells: Int { edges.contains(.right) ? 1 : 0 }

    /// Rows of the rack: the diamonds', then the rows of triangles.
    public var rackRows: Int { rows + appendedRows.count }

    /// Cells in each row of the rack, by index.
    public var widths: [Int] {
        (0..<rackRows).map { row in
            guard row < rows else { return columns }
            return columns + (row.isMultiple(of: 2) ? rightCells : leftCells)
        }
    }

    /// What a cell of the rack is.
    public func place(row: Int, column: Int) -> DiamondPlace {
        if row >= rows { return .triangle(appendedRows[min(row - rows, appendedRows.count - 1)], index: column) }
        if column == columns {
            if row.isMultiple(of: 2), rightCells == 1 { return .triangle(.right, index: row / 2) }
            if !row.isMultiple(of: 2), leftCells == 1 { return .triangle(.left, index: row / 2) }
        }
        return .diamond(row: row, column: column)
    }

    /// The cell of a place (row, column), or nil when this layout does not have it.
    public func cell(of place: DiamondPlace) -> (row: Int, column: Int)? {
        switch place {
        case let .diamond(row, column):
            guard (0..<rows).contains(row), (0..<columns).contains(column) else { return nil }
            return (row, column)
        case let .triangle(edge, index):
            guard edges.contains(edge), index >= 0 else { return nil }
            switch edge {
            case .top, .bottom:
                guard index < columns, let at = appendedRows.firstIndex(of: edge) else { return nil }
                return (rows + at, index)
            case .right: return 2 * index < rows ? (2 * index, columns) : nil
            case .left: return 2 * index + 1 < rows ? (2 * index + 1, columns) : nil
            }
        }
    }

    /// Is this row drawn offset by half a place? Odd rows of diamonds, as without triangles; the
    /// top triangles (in the notches above row A), and the bottom ones below an even last row.
    public func isOffset(row: Int) -> Bool {
        guard row >= rows else { return !row.isMultiple(of: 2) }
        return appendedRows[min(row - rows, appendedRows.count - 1)] == .top || !rows.isMultiple(of: 2)
    }

    /// The rows of the rack from the top of the drawing: the top triangles, the rows of
    /// diamonds, the bottom triangles.
    public var drawingRows: [Int] {
        let top = appendedRows.firstIndex(of: .top).map { [rows + $0] } ?? []
        let bottom = appendedRows.firstIndex(of: .bottom).map { [rows + $0] } ?? []
        return top + Array(0..<rows) + bottom
    }

    /// The cells of a row from the left of the drawing: a left triangle first.
    public func drawingColumns(row: Int) -> [Int] {
        if row < rows, !row.isMultiple(of: 2), leftCells == 1 { return [columns] + Array(0..<columns) }
        return Array(0..<(row < rows ? widths[row] : columns))
    }

    /// Triangles along a side.
    public func triangles(on edge: DiamondEdge) -> Int {
        guard edges.contains(edge) else { return 0 }
        switch edge {
        case .top, .bottom: return columns
        case .right: return (rows + 1) / 2
        case .left: return rows / 2
        }
    }
}

extension Rack {
    /// A diamond rack whose edge triangles are on, as this version reads it: diamond bins (not
    /// crates) with known sides in `edgeTriangles` and rows exactly as those sides shape them.
    /// Nil otherwise, also when a version that does not know the triangles reshaped the rack:
    /// its cells are then all whole diamonds, as that version shows them. Places themselves
    /// never depend on it (`contains`), so every version keeps the same bottles in place.
    public var diamondLayout: DiamondLayout? {
        guard kind == .bins, let raw = edgeTrianglesValue, storageType == .rack else { return nil }
        let sides = raw.compactMap(DiamondEdge.init(rawValue:))
        guard !sides.isEmpty else { return nil }
        let set = Set(sides)
        let diamondRows = rows - (set.contains(.top) ? 1 : 0) - (set.contains(.bottom) ? 1 : 0)
        guard diamondRows >= 1 else { return nil }
        let diamondColumns = columns(inRow: 0) - (set.contains(.right) ? 1 : 0)
        guard diamondColumns >= 1 else { return nil }
        let layout = DiamondLayout(rows: diamondRows, columns: diamondColumns, sides: sides)
        let widths = layout.widths
        guard widths == (0..<rows).map(columns(inRow:)), columns == widths.max() else { return nil }
        return layout
    }

    /// Bottles an edge triangle holds: as set, else half a bin, at least 1 and at most a bin.
    public var triangleCapacity: Int {
        min(binCapacity, max(1, triangleCapacityValue ?? binCapacity / 2))
    }

    /// Half a bin, at least 1: what a triangle holds unless set otherwise.
    public static func defaultTriangleCapacity(binCapacity: Int) -> Int { max(1, binCapacity / 2) }

    /// What a cell of a diamond rack with triangles is; nil elsewhere.
    public func diamondPlace(of cell: RackCell) -> DiamondPlace? {
        guard let layout = diamondLayout, contains(cell) else { return nil }
        return layout.place(row: cell.row, column: cell.column)
    }

    /// The side of the edge triangle this cell is, nil for a whole diamond or another rack.
    public func triangleEdge(of cell: RackCell) -> DiamondEdge? {
        if case let .triangle(edge, _) = diamondPlace(of: cell) { return edge }
        return nil
    }

    /// Diamond bins of `rows` rows of `columns` diamonds, with the triangles of `edges` (in this
    /// order: it decides which row of triangles comes first) holding `triangleCapacity` bottles
    /// each (half a bin when nil). Rows are written one by one (`rowColumns`) when they differ.
    public static func diamond(
        id: UUID = UUID(), name: String, rows: Int, columns: Int, binCapacity: Int,
        edges: [DiamondEdge] = [], triangleCapacity: Int? = nil
    ) -> Rack {
        let extraRows = (edges.contains(.top) ? 1 : 0) + (edges.contains(.bottom) ? 1 : 0)
        let extraColumns = edges.contains(.left) || edges.contains(.right) ? 1 : 0
        let layout = DiamondLayout(rows: min(maxSize - extraRows, bounded(rows)),
                                   columns: min(maxSize - extraColumns, bounded(columns)), sides: edges)
        let widths = layout.widths
        var rack = Rack(id: id, name: name, kind: .bins, rows: widths.count, columns: widths.max() ?? 1,
                        binCapacity: binCapacity, rowColumns: Set(widths).count > 1 ? widths : nil, binStyle: .diamond)
        if !layout.edges.isEmpty {
            var seen = Set<DiamondEdge>()
            rack.edgeTrianglesValue = edges.filter { seen.insert($0).inserted }.map(\.rawValue)
            rack.triangleCapacityValue = min(rack.binCapacity,
                                             bounded(triangleCapacity ?? defaultTriangleCapacity(binCapacity: rack.binCapacity)))
        }
        return rack
    }

    /// The sides of a diamond rack as an editor saves them: those of `original` still `chosen`,
    /// in their order (so no row of triangles moves), then those turned on, top, left, bottom, right.
    public static func orderedEdges(_ chosen: Set<DiamondEdge>, keepingOrderOf original: Rack?) -> [DiamondEdge] {
        let kept = (original?.edgeTrianglesValue ?? []).compactMap(DiamondEdge.init(rawValue:)).filter(chosen.contains)
        var seen = Set<DiamondEdge>()
        let ordered = kept.filter { seen.insert($0).inserted }
        return ordered + DiamondEdge.allCases.filter { chosen.contains($0) && !seen.contains($0) }
    }

    /// This rack as edited from `original`, keeping what a newer version wrote that this one does
    /// not apply: when neither applies triangles and the cells are the same, the sides as read;
    /// otherwise the values of sides this version does not know, after its own.
    public func keepingEdgeValues(from original: Rack) -> Rack {
        guard let read = original.edgeTrianglesValue else { return self }
        var rack = self
        let sameCells = rows == original.rows && (0..<rows).allSatisfy { columns(inRow: $0) == original.columns(inRow: $0) }
        if diamondLayout == nil, original.diamondLayout == nil, sameCells {
            rack.edgeTrianglesValue = read
            rack.triangleCapacityValue = original.triangleCapacityValue
            return rack
        }
        let unknown = read.filter { DiamondEdge(rawValue: $0) == nil }
        guard !unknown.isEmpty else { return rack }
        rack.edgeTrianglesValue = (rack.edgeTrianglesValue ?? []) + unknown
        if rack.triangleCapacityValue == nil { rack.triangleCapacityValue = original.triangleCapacityValue }
        return rack
    }

    /// Where the bottles of a diamond rack go when its triangles change, or when a rack with
    /// triangles is resized: each diamond keeps its row and column among the diamonds, each
    /// triangle its side and number; nil when the new rack does not have it. A rack without
    /// triangles counts its rows and cells as diamonds. Empty (no move) unless one of the two
    /// has triangles (`diamondLayout`).
    public static func diamondCellMap(from old: Rack, to new: Rack) -> [RackCell: RackCell?] {
        let from = old.diamondLayout, to = new.diamondLayout
        guard old.id == new.id, old.kind == .bins, new.kind == .bins, from != nil || to != nil, from != to else { return [:] }
        var map: [RackCell: RackCell?] = [:]
        for cell in old.cells {
            let place = from?.place(row: cell.row, column: cell.column) ?? .diamond(row: cell.row, column: cell.column)
            var target: RackCell?
            if let to {
                target = to.cell(of: place).map { RackCell(rackID: new.id, row: $0.row, column: $0.column) }
            } else if case let .diamond(row, column) = place {
                let moved = RackCell(rackID: new.id, row: row, column: column)
                target = new.contains(moved) ? moved : nil
            }
            map[cell] = .some(target)
        }
        return map
    }

    /// A diamond or a triangle in English, for reports: "Bin B3", "Top triangle 3".
    func diamondPlaceName(_ place: DiamondPlace) -> String {
        switch place {
        case let .diamond(row, column): return "Bin \(Rack.rowLetters(row))\(column + 1)"
        case let .triangle(edge, index):
            let side = switch edge {
            case .top: "Top"
            case .left: "Left"
            case .bottom: "Bottom"
            case .right: "Right"
            }
            return "\(side) triangle \(index + 1)"
        }
    }
}
