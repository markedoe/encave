import CaveKit
import SwiftUI

extension RackKind {
    var title: LocalizedStringKey {
        switch self {
        case .grid: "Grid rack"
        case .staggered: "Honeycomb rack"
        case .shelves: "Wine fridge"
        case .bins: "Bins or crates"
        }
    }

    var subtitle: LocalizedStringKey {
        switch self {
        case .grid: "One bottle per position, single or double depth"
        case .staggered: "Offset rows, like metal or terracotta racks, or bottles stacked on fridge shelves"
        case .shelves: "Shelves holding a row of bottles, one to three deep"
        case .bins: "Diamond bins or crates holding several bottles each"
        }
    }

    var systemImage: String {
        switch self {
        case .grid: "square.grid.3x3"
        case .staggered: "circle.hexagongrid"
        case .shelves: "refrigerator"
        case .bins: "diamond"
        }
    }

    /// What a row is called in this kind of storage.
    var rowTitle: LocalizedStringKey {
        switch self {
        case .grid, .staggered: "Rows"
        case .shelves: "Shelves"
        case .bins: "Rows of bins"
        }
    }

    var columnTitle: LocalizedStringKey {
        switch self {
        case .grid: "Bottles per row"
        case .staggered: "Bottles in the widest row"
        case .shelves: "Bottles per shelf"
        case .bins: "Bins per row"
        }
    }
}

extension StorageType {
    /// Also the default name of a new storage of this type.
    var name: String {
        switch self {
        case .rack: String(localized: "Rack")
        case .wineFridge: String(localized: "Wine fridge")
        case .crates: String(localized: "Crates")
        }
    }

    var subtitle: String {
        switch self {
        case .rack: String(localized: "A grid, a honeycomb or diamond bins")
        case .wineFridge: String(localized: "Shelves of bottles, in a single row or in layers")
        case .crates: String(localized: "Wooden crates or boxes, each holding several bottles")
        }
    }

    var systemImage: String {
        switch self {
        case .rack: RackKind.grid.systemImage
        case .wineFridge: "refrigerator"
        case .crates: "shippingbox"
        }
    }
}

extension Rack {
    /// "Wine fridge · 4 shelves", "Rack · 6 rows", "Rack · 6 bins" (diamond bins) or
    /// "Crates · 10 bins", as in the Storage list.
    var storageSummary: String {
        let count = storageCount
        let detail = switch storageType {
        case .rack: kind == .bins ? String(localized: "\(count) bins") : String(localized: "\(count) rows")
        case .wineFridge: String(localized: "\(count) shelves")
        case .crates: String(localized: "\(count) bins")
        }
        return "\(storageType.name) · \(detail)"
    }

    /// The symbol of its type of storage; a honeycomb rack keeps its own.
    var systemImage: String { storageType == .rack ? kind.systemImage : storageType.systemImage }

    /// "B3", "B3, back", "Shelf 2, back, position 3", "Shelf 1, top layer, position 3",
    /// "Shelf 3, back, bottom layer, position 2" or "Bin B3", for people (not for files), in
    /// the person's language. Always in the order shelf, side, layer, position, leaving out
    /// what does not apply, as `placeName(for:)` does in English.
    func displayLabel(for slot: Slot) -> String {
        Self.keepingNumbersAttached(slotLabel(for: slot))
    }

    /// A no-break space before each number ("position 5"), so a number never starts a line alone.
    static func keepingNumbersAttached(_ label: String) -> String {
        label.replacing(#/ (?=\d)/#, with: "\u{00A0}")
    }

    private func slotLabel(for slot: Slot) -> String {
        let shown = shownPositions(inRow: slot.row, deepest: slot.depth)
        let sideName: String? = kind == .bins || shown == 1 ? nil : depthName(slot.depth, row: slot.row)
        if let place = shelfPlace(ofRow: slot.row) {
            return shelfLabel(shelf: place.shelf + 1, sideName: sideName, layer: place.layer, layers: place.layers,
                              number: slot.column + 1)
        }
        switch kind {
        case .grid, .staggered:
            let code = label(for: slot.cell)
            return sideName.map { "\(code), \($0)" } ?? code
        case .shelves:
            return shelfLabel(shelf: slot.row + 1, sideName: sideName, layer: 0, layers: 1, number: slot.column + 1)
        case .bins:
            return displayLabel(for: slot.cell)
        }
    }

    /// The whole position, without its side: "B3", "Shelf 2, position 3",
    /// "Shelf 1, top layer, position 3" or "Bin B3".
    func displayLabel(for cell: RackCell) -> String {
        Self.keepingNumbersAttached(cellLabel(for: cell))
    }

    private func cellLabel(for cell: RackCell) -> String {
        if let place = shelfPlace(ofRow: cell.row) {
            return shelfLabel(shelf: place.shelf + 1, sideName: nil, layer: place.layer, layers: place.layers, number: cell.column + 1)
        }
        switch kind {
        case .grid, .staggered:
            return label(for: cell)
        case .shelves:
            // Shelves are numbered from the top, as in layered fridges.
            return shelfLabel(shelf: cell.row + 1, sideName: nil, layer: 0, layers: 1, number: cell.column + 1)
        case .bins:
            return String(localized: "Bin \(label(for: cell))")
        }
    }

    /// "Shelf 2, back, bottom layer, position 3": the shelf, the side (nil on a shelf one bottle
    /// deep), the layer (on a shelf with several) and the position along the shelf.
    private func shelfLabel(shelf: Int, sideName: String?, layer: Int, layers: Int, number: Int) -> String {
        let bottom = layers > 1 && layer == 0
        let top = layers > 1 && layer > 0 && layer == layers - 1
        let middle = layers > 1 && !bottom && !top
        if let sideName {
            if bottom { return String(localized: "Shelf \(shelf), \(sideName), bottom layer, position \(number)") }
            if top { return String(localized: "Shelf \(shelf), \(sideName), top layer, position \(number)") }
            if middle { return String(localized: "Shelf \(shelf), \(sideName), middle layer, position \(number)") }
            return String(localized: "Shelf \(shelf), \(sideName), position \(number)")
        }
        if bottom { return String(localized: "Shelf \(shelf), bottom layer, position \(number)") }
        if top { return String(localized: "Shelf \(shelf), top layer, position \(number)") }
        if middle { return String(localized: "Shelf \(shelf), middle layer, position \(number)") }
        return String(localized: "Shelf \(shelf), position \(number)")
    }

    /// Front, middle or back of a position in this row.
    func depthName(_ depth: Int, row: Int) -> String {
        if depth == 0 { return String(localized: "front") }
        if depth == shownPositions(inRow: row, deepest: depth) - 1 { return String(localized: "back") }
        return String(localized: "middle")
    }

    /// "Upper zone" and "Lower zone" for a fridge with two zones, else "Zone 2".
    func zoneName(_ zone: Int) -> String {
        if zoneCount == 2 { return zone == 0 ? String(localized: "Upper zone") : String(localized: "Lower zone") }
        return String(localized: "Zone \(zone + 1)")
    }
}

/// What a tap or a drop on a rack reaches: a whole cell, or, on a fridge drawn front and back
/// (`Rack.hasDepthBands`), one place of it, in front or behind.
struct RackPlace: Hashable {
    let cell: RackCell
    /// 0 in front, 1 behind, on a fridge drawn front and back; nil for the whole cell.
    var depth: Int?

    /// The place of a fridge drawn front and back; nil for a whole cell.
    var slot: Slot? { depth.map { Slot(rackID: cell.rackID, row: cell.row, column: cell.column, depth: $0) } }

    /// Holds one bottle at most: a place of a fridge drawn front and back, or a cell of a row
    /// one bottle deep (with `cellar`, unless an older version put a bottle behind it).
    func holdsOne(in rack: Rack, cellar: Cellar? = nil) -> Bool {
        let deepest = cellar?.bottles(in: cell).compactMap(\.slot?.depth).max()
        return depth != nil || rack.shownPositions(inRow: cell.row, deepest: deepest) == 1
    }

    /// The place's own slot, or the front of the cell.
    var firstSlot: Slot { slot ?? Slot(rackID: cell.rackID, row: cell.row, column: cell.column) }

    func contains(_ slot: Slot) -> Bool { slot.cell == cell && (depth == nil || slot.depth == depth) }

    /// What a tap on this slot reaches in this rack: the slot itself on a fridge drawn front and
    /// back, else its cell.
    init(_ slot: Slot, in rack: Rack) {
        cell = slot.cell
        depth = rack.hasDepthBands(shelf: rack.shelfPlace(ofRow: slot.row)?.shelf ?? -1) ? slot.depth : nil
    }

    init(cell: RackCell, depth: Int? = nil) {
        self.cell = cell
        self.depth = depth
    }
}

extension Cellar {
    /// The bottles of a place, front first.
    func bottles(at place: RackPlace) -> [Bottle] {
        guard let slot = place.slot else { return bottles(in: place.cell) }
        return bottle(at: slot).map { [$0] } ?? []
    }

    /// Where a bottle put on a place goes: that very place on a fridge drawn front and back,
    /// else the first free one of the cell; nil when it is full.
    func freeSlot(at place: RackPlace) -> Slot? {
        guard let slot = place.slot else { return freeSlot(in: place.cell) }
        return rack(slot.rackID)?.hasPosition(slot) == true && bottle(at: slot) == nil ? slot : nil
    }
}

extension CellarModel {
    /// The symbol of the bottle's rack ("square.grid.3x3" when it has none), next to its place.
    func locationIcon(for bottle: Bottle) -> String {
        bottle.slot.flatMap { cellar.rack($0.rackID) }?.systemImage ?? RackKind.grid.systemImage
    }

    /// "Main rack · B3", the storage note, or nil.
    func locationLabel(for bottle: Bottle) -> String? {
        if let slot = bottle.slot, let rack = cellar.rack(slot.rackID) {
            return "\(rack.name) · \(rack.displayLabel(for: slot))"
        }
        return cellar.locationLabel(for: bottle)
    }
}

/// Asks before deleting a rack, naming it and how many bottles lose their place; the
/// deletion then shows an Undo banner. The same everywhere a rack can be deleted.
struct RackDeleteConfirmation: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Binding var rackID: UUID?
    var onDelete: () -> Void = {}

    func body(content: Content) -> some View {
        let rack = rackID.flatMap { model.cellar.rack($0) }
        let rackName = rack?.name ?? ""
        let count = rack.map { model.cellar.occupancy(of: $0) } ?? 0
        content.confirmationDialog(Text("Delete “\(rackName)”?"), isPresented: Binding(
            get: { rack != nil }, set: { if !$0 { rackID = nil } }
        ), titleVisibility: .visible) {
            Button("Delete Storage", role: .destructive) {
                if let rack { model.deleteRack(rack.id) }
                rackID = nil
                onDelete()
            }
            Button("Cancel", role: .cancel) { rackID = nil }
        } message: {
            if count == 0 {
                Text("It holds no bottles.")
            } else {
                Text("\(count) bottles stay in your cellar without a position.")
            }
        }
    }
}

extension View {
    /// Asks before deleting the rack `rackID` names (nil: nothing asked).
    func confirmsRackDeletion(_ rackID: Binding<UUID?>, onDelete: @escaping () -> Void = {}) -> some View {
        modifier(RackDeleteConfirmation(rackID: rackID, onDelete: onDelete))
    }
}
