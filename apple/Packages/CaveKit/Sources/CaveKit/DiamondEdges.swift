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

/// How a diamond rack's triangles are laid out among its rows (docs/SYNC.md). Rows of diamonds
/// nest by half a diamond, every other one offset half a diamond right, as drawn. The triangles
/// are ordinary cells, so a version that does not know them shows them as whole diamonds:
/// - top: a first row of `columns` triangles, in the notches above the first row of diamonds;
/// - bottom: a last row of `columns` triangles, below the last row of diamonds;
/// - left: a first cell on each offset row of diamonds (B, D…), in the notch at its left;
/// - right: a last cell on each row not offset (A, C…), in the notch at its right.
/// The small corners (a quarter of a diamond) hold no bottle and are not places.
public struct DiamondLayout: Hashable, Sendable {
    /// Rows of whole diamonds.
    public var rows: Int
    /// Whole diamonds in each row.
    public var columns: Int
    public var edges: Set<DiamondEdge>

    public init(rows: Int, columns: Int, edges: Set<DiamondEdge>) {
        self.rows = max(1, rows); self.columns = max(1, columns); self.edges = edges
    }

    /// 1 when the top triangles are on: the first row of diamonds is then row 1 of the rack.
    public var topRows: Int { edges.contains(.top) ? 1 : 0 }
    var bottomRows: Int { edges.contains(.bottom) ? 1 : 0 }
    var leftCells: Int { edges.contains(.left) ? 1 : 0 }
    var rightCells: Int { edges.contains(.right) ? 1 : 0 }

    /// Rows of the rack: the diamonds' and the triangle rows.
    public var rackRows: Int { rows + topRows + bottomRows }

    /// Cells in each row of the rack, from the top.
    public var widths: [Int] {
        (0..<rackRows).map { row in
            if row < topRows || row >= topRows + rows { return columns }
            let diamondRow = row - topRows
            return columns + (diamondRow.isMultiple(of: 2) ? rightCells : leftCells)
        }
    }

    /// What a cell of the rack is.
    public func place(row: Int, column: Int) -> DiamondPlace {
        if row < topRows { return .triangle(.top, index: column) }
        if row >= topRows + rows { return .triangle(.bottom, index: column) }
        let diamondRow = row - topRows
        if diamondRow.isMultiple(of: 2) {
            if rightCells == 1, column == columns { return .triangle(.right, index: diamondRow / 2) }
            return .diamond(row: diamondRow, column: column)
        }
        if leftCells == 1 {
            return column == 0 ? .triangle(.left, index: diamondRow / 2) : .diamond(row: diamondRow, column: column - 1)
        }
        return .diamond(row: diamondRow, column: column)
    }

    /// The cell of a place (row, column), or nil when this layout does not have it.
    public func cell(of place: DiamondPlace) -> (row: Int, column: Int)? {
        switch place {
        case let .diamond(row, column):
            guard (0..<rows).contains(row), (0..<columns).contains(column) else { return nil }
            return (row + topRows, column + (row.isMultiple(of: 2) ? 0 : leftCells))
        case let .triangle(edge, index):
            guard edges.contains(edge), index >= 0 else { return nil }
            switch edge {
            case .top: return index < columns ? (0, index) : nil
            case .bottom: return index < columns ? (rackRows - 1, index) : nil
            case .right: return 2 * index < rows ? (2 * index + topRows, columns) : nil
            case .left: return 2 * index + 1 < rows ? (2 * index + 1 + topRows, 0) : nil
            }
        }
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
        let edges = Set(raw.compactMap(DiamondEdge.init(rawValue:)))
        guard !edges.isEmpty else { return nil }
        let top = edges.contains(.top) ? 1 : 0, bottom = edges.contains(.bottom) ? 1 : 0
        let diamondRows = rows - top - bottom
        guard diamondRows >= 1 else { return nil }
        let diamondColumns = columns(inRow: top) - (edges.contains(.right) ? 1 : 0)
        guard diamondColumns >= 1 else { return nil }
        let layout = DiamondLayout(rows: diamondRows, columns: diamondColumns, edges: edges)
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

    /// Diamond bins of `rows` rows of `columns` diamonds, with the triangles of `edges`
    /// holding `triangleCapacity` bottles each (half a bin when nil). Rows are written one by
    /// one (`rowColumns`) when they differ.
    public static func diamond(
        id: UUID = UUID(), name: String, rows: Int, columns: Int, binCapacity: Int,
        edges: Set<DiamondEdge> = [], triangleCapacity: Int? = nil
    ) -> Rack {
        let extraRows = (edges.contains(.top) ? 1 : 0) + (edges.contains(.bottom) ? 1 : 0)
        let extraColumns = edges.contains(.left) || edges.contains(.right) ? 1 : 0
        let layout = DiamondLayout(rows: min(maxSize - extraRows, bounded(rows)),
                                   columns: min(maxSize - extraColumns, bounded(columns)), edges: edges)
        let widths = layout.widths
        var rack = Rack(id: id, name: name, kind: .bins, rows: widths.count, columns: widths.max() ?? 1,
                        binCapacity: binCapacity, rowColumns: Set(widths).count > 1 ? widths : nil, binStyle: .diamond)
        if !edges.isEmpty {
            rack.edgeTrianglesValue = DiamondEdge.allCases.filter(edges.contains).map(\.rawValue)
            rack.triangleCapacityValue = min(rack.binCapacity,
                                             bounded(triangleCapacity ?? defaultTriangleCapacity(binCapacity: rack.binCapacity)))
        }
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
