package app.encave.core

/**
 * Shelves, and fridges with shelf layers, number their positions along each shelf (the shelf
 * number is on the side); other storage names a position by row letter and column, "B3".
 */
val Rack.numbersPlacesOnShelves: Boolean get() = kind == RackKind.Shelves || hasShelfLayers

/** What an empty position shows in the drawing: its number on a shelf ("6"), else "B3". */
fun Rack.emptyCaption(cell: RackCell): String = if (numbersPlacesOnShelves) "${cell.column + 1}" else label(cell)

/**
 * A position for people, in English (files and reports for other apps), always in the order
 * shelf, side, layer, position, leaving out what does not apply: "B3", "B3, back",
 * "Shelf 2, back, position 3", "Shelf 1, top layer, position 3",
 * "Shelf 3, back, bottom layer, position 2", "Bin B3", "Top triangle 3" (a diamond rack with
 * edge triangles names its diamonds from the first row of diamonds, [diamondPlace]); no side on a row one bottle deep, even
 * when other rows are deeper. Grids and honeycombs keep the code of
 * the cell ("B3"). The app shows the same words in the person's language. Same as CaveKit's
 * `Rack.placeName(for:)`.
 */
fun Rack.placeName(slot: Slot): String {
    val positions = shownPositions(slot.row, slot.depth)
    val side = when {
        positions == 1 -> null
        slot.depth == 0 -> "front"
        slot.depth == positions - 1 -> "back"
        else -> "middle"
    }
    val position = "position ${slot.column + 1}"
    shelfPlace(slot.row)?.let { place ->
        val layer = when {
            place.layers == 1 -> null
            place.layer == 0 -> "bottom layer"
            place.layer == place.layers - 1 -> "top layer"
            else -> "middle layer"
        }
        return listOfNotNull("Shelf ${place.shelf + 1}", side, layer, position).joinToString(", ")
    }
    return when (kind) {
        RackKind.Grid, RackKind.Staggered -> listOfNotNull(label(slot.row, slot.column), side).joinToString(", ")
        RackKind.Shelves -> listOfNotNull("Shelf ${slot.row + 1}", side, position).joinToString(", ")
        RackKind.Bins -> diamondPlace(slot.cell)?.let { diamondPlaceName(it) } ?: "Bin ${label(slot.row, slot.column)}"
    }
}
