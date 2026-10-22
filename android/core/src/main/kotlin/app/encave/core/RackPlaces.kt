package app.encave.core

/**
 * Shelves, and fridges with shelf layers, number their places along each shelf (the shelf
 * number is on the side); other racks name a place by row letter and column, "B3".
 */
val Rack.numbersPlacesOnShelves: Boolean get() = kind == RackKind.Shelves || hasShelfLayers

/** What an empty place shows in the drawing: its number on a shelf ("6"), else "B3". */
fun Rack.emptyCaption(cell: RackCell): String = if (numbersPlacesOnShelves) "${cell.column + 1}" else label(cell)
