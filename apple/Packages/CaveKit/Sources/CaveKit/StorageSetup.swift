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
    }

    /// Head-to-tail applies to a fridge with layers, two deep.
    public var offersHeadToTail: Bool { type == .wineFridge && layersPerShelf > 1 && depth == 2 }

    /// The rack these settings describe.
    public func rack(id: UUID = UUID(), name: String) -> Rack {
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
