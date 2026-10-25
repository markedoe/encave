import Foundation

/// The kind of storage a person chooses when creating one. Each maps onto `Rack`; bins also
/// save which type they were made as (`Rack.binStyle`): see `StorageSettings.rack(id:name:)`
/// and `Rack.storageType`.
public enum StorageType: String, CaseIterable, Sendable {
    /// A rack on a wall: a grid, a honeycomb or diamond bins (`RackLayout`).
    case rack
    /// Shelves of bottles in one row each, or in layers stacked in quincunx.
    case wineFridge
    /// Crates or boxes, each a bin holding several bottles.
    case crates
}

/// How a rack holds its bottles.
public enum RackLayout: String, CaseIterable, Sendable {
    case grid
    case honeycomb
    case diamond
}

/// One row of a storage (a shelf of a wine fridge) set on its own, when rows are not all
/// the same: one single, the next two deep head-to-tail, a shorter top row…
public struct StorageRowSettings: Equatable, Sendable {
    /// Positions in the row (bins in a row of diamond bins); on a fridge in layers, in the
    /// bottom layer: each layer above rests in its gaps, one fewer, then as many.
    public var positions: Int
    /// Bottles one behind the other (not for diamond bins).
    public var depth: Int
    /// Wine fridge, two deep, in layers: the back bottles lie head-to-tail.
    public var headToTail: Bool
    /// Wine fridge: layers stacked in quincunx on this shelf, 1 to 3.
    public var layers: Int

    public init(positions: Int, depth: Int = 1, headToTail: Bool = false, layers: Int = 1) {
        self.positions = positions; self.depth = depth; self.headToTail = headToTail; self.layers = layers
    }
}

/// What the storage wizard asks for, type by type. Only the settings of `type` are read.
public struct StorageSettings: Equatable, Sendable {
    public var type: StorageType
    // Rack.
    public var layout: RackLayout = .grid
    public var rows = 6
    public var columns = 8
    /// Bottles one behind the other (rack or wine fridge).
    public var depth = 1
    /// Diamond bins and crates.
    public var bottlesPerBin = 10
    // Wine fridge.
    public var shelves = 5
    public var bottlesPerShelf = 6
    /// 1: a single row on each shelf; 2 or 3: layers stacked in quincunx.
    public var layersPerShelf = 2
    /// Two deep, in layers: the back bottles lie head-to-tail.
    public var headToTail = true
    // Crates.
    public var bins = 6
    /// Rows (a fridge's shelves) set one by one when they are not all the same; nil while
    /// they are (the usual case). Not for crates. See `setRowsDiffer(_:)`.
    public var rowSettings: [StorageRowSettings]?

    /// Crates are drawn this many to a row.
    public static let binsPerRow = 4
    /// Largest value the wizard offers for a count, well below `Rack.maxSize`.
    public static let maxCount = 40
    /// Most shelves a fridge can have, so three layers each stay within `Rack.maxSize` rows.
    public static let maxShelves = 30

    public init(type: StorageType) {
        self.type = type
        switch type {
        case .rack: break
        case .wineFridge: depth = 2
        case .crates: bottlesPerBin = 12
        }
    }

    /// Settings that make this rack again, for editing and for describing it.
    public init(rack: Rack) {
        self.init(type: rack.storageType)
        // Only the settings of its type, so the same storage gives the same settings.
        switch rack.storageType {
        case .rack:
            layout = switch rack.kind {
            case .staggered: .honeycomb
            case .bins: .diamond
            default: .grid
            }
            if rack.kind == .bins { bottlesPerBin = rack.binCapacity }
            rows = rack.rows
            columns = rack.columns
            depth = rack.depth
        case .wineFridge:
            depth = rack.depth
            if let layers = rack.shelfLayers, rack.hasShelfLayers {
                shelves = layers.count
                layersPerShelf = layers.max() ?? 1
            } else {
                shelves = rack.rows
                layersPerShelf = 1
            }
            bottlesPerShelf = rack.columns
            headToTail = rack.isBackReversed
        case .crates:
            bins = rack.cellCount
            bottlesPerBin = rack.binCapacity
        }
        guard offersRowSettings, !Self.sameShape(self.rack(name: ""), rack) else { return }
        if rack.hasShelfLayers {
            rowSettings = rack.shelfRows.indices.map { shelf in
                let rows = rack.shelfRows[shelf]
                return StorageRowSettings(positions: rack.columns(inRow: rows.upperBound - 1), depth: rack.depth(ofShelf: shelf),
                                          headToTail: rack.isBackReversed(shelf: shelf), layers: rows.count)
            }
        } else {
            rowSettings = (0..<rack.rows).map { StorageRowSettings(positions: rack.columns(inRow: $0), depth: rack.depth(ofRow: $0)) }
        }
    }

    /// Same places, depths and head-to-tail, row by row.
    static func sameShape(_ a: Rack, _ b: Rack) -> Bool {
        a.kind == b.kind && a.rows == b.rows && a.shelfLayers == b.shelfLayers && a.binCapacity == b.binCapacity
            && (0..<a.rows).allSatisfy { row in
                a.columns(inRow: row) == b.columns(inRow: row) && a.positions(inRow: row) == b.positions(inRow: row)
                    && a.isBackReversed(row: row) == b.isBackReversed(row: row)
            }
    }

    /// Head-to-tail applies to a fridge with layers, two deep.
    public var offersHeadToTail: Bool { type == .wineFridge && layersPerShelf > 1 && depth == 2 }

    /// Rows can be set one by one: racks and wine fridges (crates have no rows).
    public var offersRowSettings: Bool { type != .crates }

    /// Each row has a depth: every storage but diamond bins and crates.
    public var offersRowDepth: Bool {
        switch type {
        case .rack: layout != .diamond
        case .wineFridge: true
        case .crates: false
        }
    }

    /// Deepest a row can be in the wizard.
    public static let maxRowDepth = 2

    /// A shelf set on its own lies head-to-tail only on a fridge in layers (a shelf at least
    /// has several), two deep.
    public func offersHeadToTail(_ row: StorageRowSettings) -> Bool {
        type == .wineFridge && row.depth == 2 && (rowSettings ?? [row]).contains { $0.layers > 1 }
    }

    /// The rows of a rack, or the shelves of a fridge.
    public var rowCount: Int {
        get { type == .wineFridge ? shelves : rows }
        set {
            if type == .wineFridge { shelves = newValue } else { rows = newValue }
            fitRowSettings()
        }
    }

    /// Rows set one by one start as the uniform settings make them; turned off, the
    /// uniform settings apply again.
    public mutating func setRowsDiffer(_ differ: Bool) {
        guard differ, offersRowSettings else {
            rowSettings = nil
            return
        }
        guard rowSettings == nil else { return fitRowSettings() }
        var uniform = self
        uniform.rowSettings = nil
        let rack = uniform.rack(name: "")
        switch type {
        case .wineFridge where rack.hasShelfLayers:
            rowSettings = rack.shelfRows.indices.map { shelf in
                let rows = rack.shelfRows[shelf]
                return StorageRowSettings(positions: rack.columns(inRow: rows.upperBound - 1), depth: rack.depth(ofShelf: shelf),
                                          headToTail: rack.isBackReversed(shelf: shelf), layers: rows.count)
            }
        default:
            rowSettings = (0..<rack.rows).map {
                StorageRowSettings(positions: rack.columns(inRow: $0), depth: rack.depth(ofRow: $0))
            }
        }
    }

    /// Rows set one by one follow the number of rows: new ones copy the last one.
    public mutating func fitRowSettings() {
        guard var rows = rowSettings, let last = rows.last else { return }
        let count = min(type == .wineFridge ? Self.maxShelves : Self.maxCount, max(1, rowCount))
        if rows.count < count { rows += Array(repeating: last, count: count - rows.count) }
        if rows.count > count { rows.removeLast(rows.count - count) }
        rowSettings = rows
    }

    /// The rack these settings describe.
    public func rack(id: UUID = UUID(), name: String) -> Rack {
        if let rowSettings, !rowSettings.isEmpty, offersRowSettings {
            return rack(id: id, name: name, rows: Array(rowSettings.prefix(type == .wineFridge ? Self.maxShelves : Self.maxCount)))
        }
        let count = { (value: Int) in min(Self.maxCount, max(1, value)) }
        switch type {
        case .rack:
            switch layout {
            case .grid:
                return Rack(id: id, name: name, kind: .grid, rows: count(rows), columns: count(columns), depth: min(2, depth))
            case .honeycomb:
                return Rack(id: id, name: name, kind: .staggered, rows: count(rows), columns: count(columns), depth: depth)
            case .diamond:
                return Rack(id: id, name: name, kind: .bins, rows: count(rows), columns: count(columns),
                            binCapacity: count(bottlesPerBin), binStyle: .diamond)
            }
        case .wineFridge:
            let shelves = min(Self.maxShelves, max(1, shelves)), depth = min(2, max(1, depth))
            if layersPerShelf <= 1 {
                return Rack(id: id, name: name, kind: .shelves, rows: shelves, columns: count(bottlesPerShelf), depth: depth)
            }
            let layers = min(Rack.maxLayers, layersPerShelf)
            // Each layer above the bottom one rests in its gaps: one fewer, then as many.
            return Rack(id: id, name: name, kind: .staggered, rows: shelves * layers, columns: count(bottlesPerShelf),
                        depth: depth, shelfLayers: Array(repeating: layers, count: shelves),
                        backReversed: headToTail && depth == 2)
        case .crates:
            return Self.crates(id: id, name: name, bins: count(bins), bottlesPerBin: count(bottlesPerBin))
        }
    }

    /// A rack whose rows (a fridge's shelves) are set one by one. Places and depths are
    /// written per row only where they differ from the rack's (docs/SYNC.md).
    private func rack(id: UUID, name: String, rows: [StorageRowSettings]) -> Rack {
        let count = { (value: Int) in min(Self.maxCount, max(1, value)) }
        let depthOf = { (row: StorageRowSettings) in min(Self.maxRowDepth, max(1, row.depth)) }
        let positions = rows.map { count($0.positions) }, depths = rows.map(depthOf)
        let widest = positions.max() ?? 1, deepest = depths.max() ?? 1
        switch type {
        case .rack:
            let kind: RackKind = switch layout {
            case .grid: .grid
            case .honeycomb: .staggered
            case .diamond: .bins
            }
            var rack = Rack(id: id, name: name, kind: kind, rows: rows.count, columns: widest,
                            depth: kind == .bins ? 1 : deepest, binCapacity: count(bottlesPerBin),
                            binStyle: kind == .bins ? .diamond : nil,
                            rowDepths: kind == .bins ? nil : depths)
            // Left out when the rows are as wide as the rack makes them anyway.
            if (0..<rack.rows).map(rack.columns(inRow:)) != positions {
                rack = Rack(id: id, name: name, kind: kind, rows: rows.count, columns: widest,
                            depth: rack.depth, binCapacity: rack.binCapacity, rowColumns: positions,
                            binStyle: rack.binStyle, rowDepths: rack.rowDepths)
            }
            return rack
        case .crates:
            return Self.crates(id: id, name: name, bins: count(bins), bottlesPerBin: count(bottlesPerBin))
        case .wineFridge:
            let layers = rows.map { min(Rack.maxLayers, max(1, $0.layers)) }
            if layers.allSatisfy({ $0 == 1 }) {
                return Rack(id: id, name: name, kind: .shelves, rows: rows.count, columns: widest, depth: deepest,
                            rowColumns: positions.allSatisfy { $0 == widest } ? nil : positions, rowDepths: depths)
            }
            // Each shelf from its top layer: one fewer on the layer above the bottom one.
            var widths: [Int] = [], layerDepths: [Int] = [], reversed: [Bool] = []
            for (index, row) in rows.enumerated() {
                let bottom = positions[index]
                let fromBottom = [bottom, max(1, bottom - 1), bottom].prefix(layers[index])
                widths += fromBottom.reversed()
                layerDepths += Array(repeating: depths[index], count: layers[index])
                reversed += Array(repeating: row.headToTail && depths[index] == 2, count: layers[index])
            }
            return Rack(id: id, name: name, kind: .staggered, rows: widths.count, columns: widest, depth: deepest,
                        rowColumns: widths, shelfLayers: layers, backReversed: reversed.contains(true),
                        rowDepths: layerDepths, rowBackReversed: reversed)
        }
    }

    /// Crates laid out `binsPerRow` to a row, the last row possibly shorter.
    public static func crates(id: UUID = UUID(), name: String, bins: Int, bottlesPerBin: Int) -> Rack {
        let bins = max(1, bins), perRow = min(bins, binsPerRow)
        let rows = (bins + perRow - 1) / perRow, last = bins - (rows - 1) * perRow
        return Rack(id: id, name: name, kind: .bins, rows: rows, columns: perRow, binCapacity: bottlesPerBin,
                    rowColumns: last == perRow ? nil : Array(repeating: perRow, count: rows - 1) + [last],
                    binStyle: .crates)
    }
}

extension Rack {
    /// The kind of storage, as the wizard names it: a honeycomb on shelves or shelves are a
    /// wine fridge; bins are what they were made as (`binStyle`), or when that is not saved
    /// or unknown, crates if laid out as the wizard lays out crates, diamond bins in a rack
    /// otherwise (see `isCratesLayout`).
    public var storageType: StorageType {
        switch kind {
        case .grid: .rack
        case .staggered: hasShelfLayers ? .wineFridge : .rack
        case .shelves: .wineFridge
        case .bins:
            switch binStyle {
            case .crates: .crates
            case .diamond: .rack
            case nil: isCratesLayout ? .crates : .rack
            }
        }
    }

    /// Bins laid out as crates are: `StorageSettings.binsPerRow` to a row, the last row
    /// possibly shorter. Decides the type of bins saved without `binStyle` (older versions):
    /// for those, a diamond rack of one row, or of rows of exactly that many bins, reads as crates.
    public var isCratesLayout: Bool {
        let bins = cellCount
        guard kind == .bins, bins >= 1 else { return false }
        let perRow = min(bins, StorageSettings.binsPerRow)
        guard rows == (bins + perRow - 1) / perRow else { return false }
        return (0..<rows).allSatisfy { columns(inRow: $0) == ($0 < rows - 1 ? perRow : bins - (rows - 1) * perRow) }
    }

    /// The editor shows crates as one count of bins when they are saved as crates or
    /// `chosen` as crates; other bins (and crates saved without a type) keep rows and columns.
    public func editsAsCrates(chosen: BinStyle?) -> Bool { kind == .bins && (chosen ?? binStyle) == .crates }

    /// Most bins the crates editor offers: `StorageSettings.maxCount`, or the bins there
    /// already are, so editing never cuts them silently.
    public var maxEditableBins: Int { max(StorageSettings.maxCount, cellCount) }

    /// These bins as the editor saves them. `chosen` is the type picked in the editor (only
    /// offered while none is saved). Edited as crates (`editsAsCrates`), they hold `bins`
    /// bins: the same shape while that count is unchanged (a rename keeps it), laid out
    /// again otherwise. Else they take the rows and columns of `shape`. `binStyle` is the
    /// one chosen, or stays as read (left out, or a value of a newer version).
    public func savingBins(name: String, binCapacity: Int, chosen: BinStyle?, bins: Int, shape: Rack) -> Rack {
        var rack: Rack
        if editsAsCrates(chosen: chosen) {
            if bins == cellCount {
                rack = self
                rack.name = name
                rack.binCapacity = Rack.bounded(binCapacity)
            } else {
                rack = StorageSettings.crates(id: id, name: name, bins: min(bins, maxEditableBins), bottlesPerBin: binCapacity)
            }
            rack.binStyle = .crates
        } else {
            rack = shape
            rack.name = name
            rack.binStyleValue = chosen?.rawValue ?? binStyleValue
        }
        rack.unknownFields = unknownFields
        return rack
    }

    /// What the Storage list counts: rows of a rack, shelves of a fridge, bins of crates or
    /// diamond bins.
    public var storageCount: Int {
        switch storageType {
        case .rack: kind == .bins ? cellCount : rows
        case .wineFridge: hasShelfLayers ? shelfLayers?.count ?? rows : rows
        case .crates: cellCount
        }
    }
}

extension StorageSettings {
    /// `base`, or "base 2", "base 3"… when a storage already has that name.
    public static func uniqueName(_ base: String, among names: [String]) -> String {
        let taken = Set(names.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        guard taken.contains(base.lowercased()) else { return base }
        var number = 2
        while taken.contains("\(base) \(number)".lowercased()) { number += 1 }
        return "\(base) \(number)"
    }
}
