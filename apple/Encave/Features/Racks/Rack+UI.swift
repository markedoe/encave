import CaveKit
import SwiftUI

extension RackKind {
    var title: LocalizedStringKey {
        switch self {
        case .grid: "Grid rack"
        case .staggered: "Honeycomb rack"
        case .shelves: "Wine fridge or cabinet"
        case .bins: "Bins or crates"
        }
    }

    var subtitle: LocalizedStringKey {
        switch self {
        case .grid: "One bottle per cell, single or double depth"
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

extension Rack {
    /// "B3", "B3, back", "Shelf B, 3, front", "Shelf 1, top layer, place 3, back" or "Bin B3",
    /// for people (not for files).
    func displayLabel(for slot: Slot) -> String {
        let cell = displayLabel(for: slot.cell)
        guard kind != .bins, positionsPerCell > 1 else { return cell }
        return "\(cell), \(depthName(slot.depth))"
    }

    func displayLabel(for cell: RackCell) -> String {
        if let place = shelfPlace(ofRow: cell.row) {
            // A fridge with bottles in layers: "Shelf 1, top layer, place 3".
            let shelf = place.shelf + 1, number = cell.column + 1
            if place.layers == 1 { return String(localized: "Shelf \(shelf), place \(number)") }
            if place.layer == 0 { return String(localized: "Shelf \(shelf), bottom layer, place \(number)") }
            if place.layer == place.layers - 1 { return String(localized: "Shelf \(shelf), top layer, place \(number)") }
            return String(localized: "Shelf \(shelf), middle layer, place \(number)")
        }
        switch kind {
        case .grid, .staggered:
            return label(for: cell)
        case .shelves:
            return String(localized: "Shelf \(Rack.letters(cell.row)), \(cell.column + 1)")
        case .bins:
            return String(localized: "Bin \(label(for: cell))")
        }
    }

    /// Front, middle or back of a cell.
    func depthName(_ depth: Int) -> String {
        if depth == 0 { return String(localized: "front") }
        if depth == positionsPerCell - 1 { return String(localized: "back") }
        return String(localized: "middle")
    }

    static func letters(_ row: Int) -> String { rowLetters(row) }

    /// "Upper zone" and "Lower zone" for a fridge with two zones, else "Zone 2".
    func zoneName(_ zone: Int) -> String {
        if zoneCount == 2 { return zone == 0 ? String(localized: "Upper zone") : String(localized: "Lower zone") }
        return String(localized: "Zone \(zone + 1)")
    }
}

extension CellarModel {
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
            Button("Delete Rack", role: .destructive) {
                if let rack { model.deleteRack(rack.id) }
                rackID = nil
                onDelete()
            }
            Button("Cancel", role: .cancel) { rackID = nil }
        } message: {
            if count == 0 {
                Text("It holds no bottles.")
            } else {
                Text("\(count) bottles stay in your cellar without a place.")
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
