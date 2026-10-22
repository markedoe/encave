import Foundation

public extension Rack {
    /// Shelves, and fridges with shelf layers, number their places along each shelf (the
    /// shelf number is on the side); other racks name a place by row letter and column, "B3".
    var numbersPlacesOnShelves: Bool { kind == .shelves || hasShelfLayers }

    /// What an empty place shows in the drawing: its number on a shelf ("6"), else "B3".
    func emptyCaption(for cell: RackCell) -> String {
        numbersPlacesOnShelves ? "\(cell.column + 1)" : label(for: cell)
    }

    /// A place for people, in English (reports for other apps): "B3", "B3, back",
    /// "Shelf 2, place 3, back", "Shelf 1, top layer, place 3", "Bin B3". A fridge drawn
    /// front and back names the side first, as drawn: "Shelf 3, back, bottom layer, place 2".
    /// The app shows the same words in the person's language.
    func placeName(for slot: Slot) -> String {
        let number = slot.column + 1
        let side = slot.depth == 0 ? "front" : slot.depth == positionsPerCell - 1 ? "back" : "middle"
        let base: String
        if let place = shelfPlace(ofRow: slot.row) {
            let layer: String? = place.layers == 1 ? nil
                : place.layer == 0 ? "bottom layer" : place.layer == place.layers - 1 ? "top layer" : "middle layer"
            let shelf = "Shelf \(place.shelf + 1)"
            if hasDepthBands {
                return [shelf, side, layer, "place \(number)"].compactMap { $0 }.joined(separator: ", ")
            }
            base = [shelf, layer, "place \(number)"].compactMap { $0 }.joined(separator: ", ")
        } else {
            switch kind {
            case .grid, .staggered: base = label(row: slot.row, column: slot.column)
            case .shelves: base = "Shelf \(slot.row + 1), place \(number)"
            case .bins: return "Bin \(label(row: slot.row, column: slot.column))"
            }
        }
        return positionsPerCell > 1 ? "\(base), \(side)" : base
    }
}
