import Foundation

public extension Rack {
    /// Shelves, and fridges with shelf layers, number their positions along each shelf (the
    /// shelf number is on the side); other storage names a position by row letter and column, "B3".
    var numbersPlacesOnShelves: Bool { kind == .shelves || hasShelfLayers }

    /// What an empty position shows in the drawing: its number on a shelf ("6"), else "B3".
    func emptyCaption(for cell: RackCell) -> String {
        numbersPlacesOnShelves ? "\(cell.column + 1)" : label(for: cell)
    }

    /// A position for people, in English (reports for other apps), always in the order shelf,
    /// side, layer, position, leaving out what does not apply: "B3", "B3, back",
    /// "Shelf 2, back, position 3", "Shelf 1, top layer, position 3",
    /// "Shelf 3, back, bottom layer, position 2", "Bin B3", "Top triangle 3" (a diamond rack with
    /// edge triangles names its diamonds from the first row of diamonds, `diamondPlace(of:)`). The side is left out on a row one
    /// bottle deep, even when other rows are deeper. Grids and honeycombs keep the code
    /// of the cell ("B3"). The app shows the same words in the person's language.
    func placeName(for slot: Slot) -> String {
        let positions = shownPositions(inRow: slot.row, deepest: slot.depth)
        let side: String? = positions == 1 ? nil
            : slot.depth == 0 ? "front" : slot.depth == positions - 1 ? "back" : "middle"
        let position = "position \(slot.column + 1)"
        if let place = shelfPlace(ofRow: slot.row) {
            let layer: String? = place.layers == 1 ? nil
                : place.layer == 0 ? "bottom layer" : place.layer == place.layers - 1 ? "top layer" : "middle layer"
            return ["Shelf \(place.shelf + 1)", side, layer, position].compactMap { $0 }.joined(separator: ", ")
        }
        switch kind {
        case .grid, .staggered: return [label(row: slot.row, column: slot.column), side].compactMap { $0 }.joined(separator: ", ")
        case .shelves: return ["Shelf \(slot.row + 1)", side, position].compactMap { $0 }.joined(separator: ", ")
        case .bins:
            if let place = diamondPlace(of: slot.cell) { return diamondPlaceName(place) }
            return "Bin \(label(row: slot.row, column: slot.column))"
        }
    }
}
