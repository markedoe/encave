package app.encave.core

import java.util.UUID

/**
 * A side of a diamond rack. Along it the lattice leaves half diamonds, triangles that can hold
 * bottles; each side is turned on or off ("edgeTriangles", docs/SYNC.md). Mirrors CaveKit.
 */
enum class DiamondEdge(val raw: String) {
    Top("top"),
    Left("left"),
    Bottom("bottom"),
    Right("right"),
    ;

    companion object {
        fun of(raw: String): DiamondEdge? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A place of a diamond rack with edge triangles: a whole diamond, by its row and column among
 * the diamonds, or a triangle, by its side and its number along that side (from the left on the
 * top and bottom, from the top on the left and right), all counted from 0.
 */
sealed interface DiamondPlace {
    data class Diamond(val row: Int, val column: Int) : DiamondPlace

    data class Triangle(val edge: DiamondEdge, val index: Int) : DiamondPlace
}

/**
 * How a diamond rack's triangles are laid out among its rows (docs/SYNC.md). Rows of diamonds
 * nest by half a diamond, every other one offset half a diamond right, as drawn. The triangles
 * are ordinary cells, so a version that does not know them shows them as whole diamonds:
 * top: a first row of [columns] triangles; bottom: a last row of [columns] triangles; left: a
 * first cell on each offset row of diamonds (B, D…); right: a last cell on each row not offset
 * (A, C…). The small corners (a quarter of a diamond) are not places. Mirrors CaveKit.
 */
data class DiamondLayout(val rows: Int, val columns: Int, val edges: Set<DiamondEdge>) {
    /** 1 when the top triangles are on: the first row of diamonds is then row 1 of the rack. */
    val topRows: Int get() = if (DiamondEdge.Top in edges) 1 else 0
    private val bottomRows: Int get() = if (DiamondEdge.Bottom in edges) 1 else 0
    private val leftCells: Int get() = if (DiamondEdge.Left in edges) 1 else 0
    private val rightCells: Int get() = if (DiamondEdge.Right in edges) 1 else 0

    /** Rows of the rack: the diamonds' and the triangle rows. */
    val rackRows: Int get() = rows + topRows + bottomRows

    /** Cells in each row of the rack, from the top. */
    val widths: List<Int>
        get() = (0 until rackRows).map { row ->
            if (row < topRows || row >= topRows + rows) {
                columns
            } else {
                columns + if ((row - topRows) % 2 == 0) rightCells else leftCells
            }
        }

    /** What a cell of the rack is. */
    fun place(row: Int, column: Int): DiamondPlace {
        if (row < topRows) return DiamondPlace.Triangle(DiamondEdge.Top, column)
        if (row >= topRows + rows) return DiamondPlace.Triangle(DiamondEdge.Bottom, column)
        val diamondRow = row - topRows
        if (diamondRow % 2 == 0) {
            if (rightCells == 1 && column == columns) return DiamondPlace.Triangle(DiamondEdge.Right, diamondRow / 2)
            return DiamondPlace.Diamond(diamondRow, column)
        }
        if (leftCells == 1) {
            return if (column == 0) DiamondPlace.Triangle(DiamondEdge.Left, diamondRow / 2) else DiamondPlace.Diamond(diamondRow, column - 1)
        }
        return DiamondPlace.Diamond(diamondRow, column)
    }

    /** The cell of a place, as (row, column), or null when this layout does not have it. */
    fun cellOf(place: DiamondPlace): Pair<Int, Int>? = when (place) {
        is DiamondPlace.Diamond ->
            if (place.row in 0 until rows && place.column in 0 until columns) {
                place.row + topRows to place.column + if (place.row % 2 == 0) 0 else leftCells
            } else {
                null
            }
        is DiamondPlace.Triangle -> {
            val i = place.index
            if (place.edge !in edges || i < 0) {
                null
            } else {
                when (place.edge) {
                    DiamondEdge.Top -> if (i < columns) 0 to i else null
                    DiamondEdge.Bottom -> if (i < columns) rackRows - 1 to i else null
                    DiamondEdge.Right -> if (2 * i < rows) 2 * i + topRows to columns else null
                    DiamondEdge.Left -> if (2 * i + 1 < rows) 2 * i + 1 + topRows to 0 else null
                }
            }
        }
    }

    /** Triangles along a side. */
    fun triangles(edge: DiamondEdge): Int = if (edge !in edges) {
        0
    } else {
        when (edge) {
            DiamondEdge.Top, DiamondEdge.Bottom -> columns
            DiamondEdge.Right -> (rows + 1) / 2
            DiamondEdge.Left -> rows / 2
        }
    }

    companion object {
        fun of(rows: Int, columns: Int, edges: Set<DiamondEdge>) = DiamondLayout(maxOf(1, rows), maxOf(1, columns), edges)
    }
}

/**
 * A diamond rack whose edge triangles are on, as this version reads it: diamond bins (not
 * crates) with known sides in "edgeTriangles" and rows exactly as those sides shape them. Null
 * otherwise, also when a version that does not know the triangles reshaped the rack: its cells
 * are then all whole diamonds. Places themselves never depend on it ([Rack.contains]).
 */
val Rack.diamondLayout: DiamondLayout?
    get() {
        val raw = edgeTrianglesValue ?: return null
        if (kind != RackKind.Bins || storageType != StorageType.Rack) return null
        val edges = raw.mapNotNull { DiamondEdge.of(it) }.toSet()
        if (edges.isEmpty()) return null
        val top = if (DiamondEdge.Top in edges) 1 else 0
        val bottom = if (DiamondEdge.Bottom in edges) 1 else 0
        val diamondRows = rows - top - bottom
        if (diamondRows < 1) return null
        val diamondColumns = columnsInRow(top) - if (DiamondEdge.Right in edges) 1 else 0
        if (diamondColumns < 1) return null
        val layout = DiamondLayout(diamondRows, diamondColumns, edges)
        val widths = layout.widths
        if (widths != (0 until rows).map { columnsInRow(it) } || columns != widths.max()) return null
        return layout
    }

/** Bottles an edge triangle holds: as set, else half a bin, at least 1 and at most a bin. */
val Rack.triangleCapacity: Int get() = minOf(binCapacity, maxOf(1, triangleCapacityValue ?: (binCapacity / 2)))

/** Half a bin, at least 1: what a triangle holds unless set otherwise. */
fun defaultTriangleCapacity(binCapacity: Int): Int = maxOf(1, binCapacity / 2)

/** What a cell of a diamond rack with triangles is; null elsewhere. */
fun Rack.diamondPlace(cell: RackCell): DiamondPlace? {
    val layout = diamondLayout ?: return null
    if (!contains(cell)) return null
    return layout.place(cell.row, cell.column)
}

/** The side of the edge triangle this cell is, null for a whole diamond or another rack. */
fun Rack.triangleEdge(cell: RackCell): DiamondEdge? = (diamondPlace(cell) as? DiamondPlace.Triangle)?.edge

/**
 * Diamond bins of [rows] rows of [columns] diamonds, with the triangles of [edges] holding
 * [triangleCapacity] bottles each (half a bin when null). Mirrors CaveKit's `Rack.diamond`.
 */
fun diamondRack(
    id: UUID = UUID.randomUUID(),
    name: String,
    rows: Int,
    columns: Int,
    binCapacity: Int,
    edges: Set<DiamondEdge> = emptySet(),
    triangleCapacity: Int? = null,
): Rack {
    val extraRows = (if (DiamondEdge.Top in edges) 1 else 0) + (if (DiamondEdge.Bottom in edges) 1 else 0)
    val extraColumns = if (DiamondEdge.Left in edges || DiamondEdge.Right in edges) 1 else 0
    val layout = DiamondLayout.of(
        minOf(Rack.MAX_SIZE - extraRows, rows.coerceIn(1, Rack.MAX_SIZE)),
        minOf(Rack.MAX_SIZE - extraColumns, columns.coerceIn(1, Rack.MAX_SIZE)),
        edges,
    )
    val widths = layout.widths
    val capacity = binCapacity.coerceIn(1, Rack.MAX_SIZE)
    return Rack(
        id = id, name = name, kind = RackKind.Bins, rows = widths.size, columns = widths.max(), binCapacity = capacity,
        rowColumns = if (widths.toSet().size > 1) widths else null, binStyleValue = BinStyle.Diamond.raw,
        edgeTrianglesValue = if (edges.isEmpty()) null else DiamondEdge.entries.filter { it in edges }.map { it.raw },
        triangleCapacityValue = if (edges.isEmpty()) {
            null
        } else {
            minOf(capacity, (triangleCapacity ?: defaultTriangleCapacity(capacity)).coerceIn(1, Rack.MAX_SIZE))
        },
    ).normalized()
}

/**
 * Where the bottles of a diamond rack go when its triangles change, or when a rack with triangles
 * is resized: each diamond keeps its row and column among the diamonds, each triangle its side
 * and number; null when the new rack does not have it. A rack without triangles counts its rows
 * and cells as diamonds. Empty (no move) unless one of the two has triangles. Mirrors CaveKit.
 */
fun Rack.Companion.diamondCellMap(old: Rack, new: Rack): Map<RackCell, RackCell?> {
    val from = old.diamondLayout
    val to = new.diamondLayout
    if (old.id != new.id || old.kind != RackKind.Bins || new.kind != RackKind.Bins || (from == null && to == null) || from == to) {
        return emptyMap()
    }
    return old.cells.associateWith { cell ->
        val place = from?.place(cell.row, cell.column) ?: DiamondPlace.Diamond(cell.row, cell.column)
        when {
            to != null -> to.cellOf(place)?.let { (row, column) -> RackCell(new.id, row, column) }
            place is DiamondPlace.Diamond -> RackCell(new.id, place.row, place.column).takeIf { new.contains(it) }
            else -> null
        }
    }
}

/** A diamond or a triangle in English, for reports: "Bin B3", "Top triangle 3". */
internal fun diamondPlaceName(place: DiamondPlace): String = when (place) {
    is DiamondPlace.Diamond -> "Bin ${Rack.rowLetters(place.row)}${place.column + 1}"
    is DiamondPlace.Triangle -> {
        val side = when (place.edge) {
            DiamondEdge.Top -> "Top"
            DiamondEdge.Left -> "Left"
            DiamondEdge.Bottom -> "Bottom"
            DiamondEdge.Right -> "Right"
        }
        "$side triangle ${place.index + 1}"
    }
}
