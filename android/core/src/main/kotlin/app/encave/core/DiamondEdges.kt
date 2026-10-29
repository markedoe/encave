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
 * How a diamond rack's triangles are laid out among its cells (docs/SYNC.md). The triangles are
 * ordinary cells added after those of the diamonds, so turning a side on or off never moves a
 * diamond, and a version that does not know them shows them as whole diamonds: the rows of
 * diamonds stay rows 0 to [rows] − 1 with [columns] diamonds each; top and bottom add a row of
 * [columns] triangles each after them, in the order the sides are listed ([appendedRows]), drawn
 * above the first row and below the last; left adds a last cell on each offset row of diamonds
 * (B, D…), drawn first; right adds a last cell on each other row. The small corners are not
 * places. Mirrors CaveKit.
 */
class DiamondLayout(rows: Int, columns: Int, sides: List<DiamondEdge>) {
    /** Rows of whole diamonds. */
    val rows: Int = maxOf(1, rows)

    /** Whole diamonds in each row. */
    val columns: Int = maxOf(1, columns)

    val edges: Set<DiamondEdge> = sides.toSet()

    /** The rows of triangles after the rows of diamonds: top and bottom, in their listed order. */
    val appendedRows: List<DiamondEdge> = sides.distinct().filter { it == DiamondEdge.Top || it == DiamondEdge.Bottom }

    private val leftCells: Int get() = if (DiamondEdge.Left in edges) 1 else 0
    private val rightCells: Int get() = if (DiamondEdge.Right in edges) 1 else 0

    /** Rows of the rack: the diamonds', then the rows of triangles. */
    val rackRows: Int get() = rows + appendedRows.size

    /** Cells in each row of the rack, by index. */
    val widths: List<Int>
        get() = (0 until rackRows).map { row -> if (row >= rows) columns else columns + if (row % 2 == 0) rightCells else leftCells }

    /** What a cell of the rack is. */
    fun place(row: Int, column: Int): DiamondPlace {
        if (row >= rows) return DiamondPlace.Triangle(appendedRows[minOf(row - rows, appendedRows.size - 1)], column)
        if (column == columns) {
            if (row % 2 == 0 && rightCells == 1) return DiamondPlace.Triangle(DiamondEdge.Right, row / 2)
            if (row % 2 == 1 && leftCells == 1) return DiamondPlace.Triangle(DiamondEdge.Left, row / 2)
        }
        return DiamondPlace.Diamond(row, column)
    }

    /** The cell of a place, as (row, column), or null when this layout does not have it. */
    fun cellOf(place: DiamondPlace): Pair<Int, Int>? = when (place) {
        is DiamondPlace.Diamond -> if (place.row in 0 until rows && place.column in 0 until columns) place.row to place.column else null
        is DiamondPlace.Triangle -> {
            val i = place.index
            if (place.edge !in edges || i < 0) {
                null
            } else {
                when (place.edge) {
                    DiamondEdge.Top, DiamondEdge.Bottom -> {
                        val at = appendedRows.indexOf(place.edge)
                        if (i < columns && at >= 0) rows + at to i else null
                    }
                    DiamondEdge.Right -> if (2 * i < rows) 2 * i to columns else null
                    DiamondEdge.Left -> if (2 * i + 1 < rows) 2 * i + 1 to columns else null
                }
            }
        }
    }

    /** Is this row drawn offset by half a place? Odd rows of diamonds; the top triangles, and the bottom ones below an even last row. */
    fun isOffset(row: Int): Boolean {
        if (row < rows) return row % 2 == 1
        return appendedRows[minOf(row - rows, appendedRows.size - 1)] == DiamondEdge.Top || rows % 2 == 1
    }

    /** The rows of the rack from the top of the drawing: the top triangles, the rows of diamonds, the bottom triangles. */
    val drawingRows: List<Int>
        get() {
            val top = appendedRows.indexOf(DiamondEdge.Top).takeIf { it >= 0 }?.let { listOf(rows + it) }.orEmpty()
            val bottom = appendedRows.indexOf(DiamondEdge.Bottom).takeIf { it >= 0 }?.let { listOf(rows + it) }.orEmpty()
            return top + (0 until rows) + bottom
        }

    /** The cells of a row from the left of the drawing: a left triangle first. */
    fun drawingColumns(row: Int): List<Int> =
        if (row < rows && row % 2 == 1 && leftCells == 1) listOf(columns) + (0 until columns) else (0 until (if (row < rows) widths[row] else columns)).toList()

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

    override fun equals(other: Any?): Boolean =
        other is DiamondLayout && rows == other.rows && columns == other.columns && edges == other.edges && appendedRows == other.appendedRows

    override fun hashCode(): Int = listOf(rows, columns, edges, appendedRows).hashCode()

    override fun toString(): String = "DiamondLayout(rows=$rows, columns=$columns, edges=$edges, appendedRows=$appendedRows)"
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
        val sides = raw.mapNotNull { DiamondEdge.of(it) }
        if (sides.isEmpty()) return null
        val diamondRows = rows - (if (DiamondEdge.Top in sides) 1 else 0) - (if (DiamondEdge.Bottom in sides) 1 else 0)
        if (diamondRows < 1) return null
        val diamondColumns = columnsInRow(0) - if (DiamondEdge.Right in sides) 1 else 0
        if (diamondColumns < 1) return null
        val layout = DiamondLayout(diamondRows, diamondColumns, sides)
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
 * Diamond bins of [rows] rows of [columns] diamonds, with the triangles of [edges] (in this order:
 * it decides which row of triangles comes first) holding [triangleCapacity] bottles each (half a
 * bin when null). Mirrors CaveKit's `Rack.diamond`.
 */
fun diamondRack(
    id: UUID = UUID.randomUUID(),
    name: String,
    rows: Int,
    columns: Int,
    binCapacity: Int,
    edges: List<DiamondEdge> = emptyList(),
    triangleCapacity: Int? = null,
): Rack {
    val sides = edges.distinct()
    val extraRows = (if (DiamondEdge.Top in sides) 1 else 0) + (if (DiamondEdge.Bottom in sides) 1 else 0)
    val extraColumns = if (DiamondEdge.Left in sides || DiamondEdge.Right in sides) 1 else 0
    val layout = DiamondLayout(
        minOf(Rack.MAX_SIZE - extraRows, rows.coerceIn(1, Rack.MAX_SIZE)),
        minOf(Rack.MAX_SIZE - extraColumns, columns.coerceIn(1, Rack.MAX_SIZE)),
        sides,
    )
    val widths = layout.widths
    val capacity = binCapacity.coerceIn(1, Rack.MAX_SIZE)
    return Rack(
        id = id, name = name, kind = RackKind.Bins, rows = widths.size, columns = widths.max(), binCapacity = capacity,
        rowColumns = if (widths.toSet().size > 1) widths else null, binStyleValue = BinStyle.Diamond.raw,
        edgeTrianglesValue = if (sides.isEmpty()) null else sides.map { it.raw },
        triangleCapacityValue = if (sides.isEmpty()) {
            null
        } else {
            minOf(capacity, (triangleCapacity ?: defaultTriangleCapacity(capacity)).coerceIn(1, Rack.MAX_SIZE))
        },
    ).normalized()
}

/**
 * The sides of a diamond rack as an editor saves them: those of [original] still [chosen], in
 * their order (so no row of triangles moves), then those turned on, top, left, bottom, right.
 */
fun orderedEdges(chosen: Set<DiamondEdge>, original: Rack?): List<DiamondEdge> {
    val kept = original?.edgeTrianglesValue.orEmpty().mapNotNull { DiamondEdge.of(it) }.filter { it in chosen }.distinct()
    return kept + DiamondEdge.entries.filter { it in chosen && it !in kept }
}

/**
 * This rack as edited from [original], keeping what a newer version wrote that this one does not
 * apply: when neither applies triangles and the cells are the same, the sides as read; otherwise
 * the values of sides this version does not know, after its own. Mirrors CaveKit.
 */
fun Rack.keepingEdgeValues(original: Rack): Rack {
    val read = original.edgeTrianglesValue ?: return this
    val sameCells = rows == original.rows && (0 until rows).all { columnsInRow(it) == original.columnsInRow(it) }
    if (diamondLayout == null && original.diamondLayout == null && sameCells) {
        // Kept only while it still does not apply: otherwise cells shown as whole bins would turn into triangles on saving.
        val kept = copy(edgeTrianglesValue = read, triangleCapacityValue = original.triangleCapacityValue)
        return if (kept.diamondLayout == null) kept else this
    }
    val unknown = read.filter { DiamondEdge.of(it) == null }
    if (unknown.isEmpty()) return this
    return copy(
        edgeTrianglesValue = edgeTrianglesValue.orEmpty() + unknown,
        triangleCapacityValue = triangleCapacityValue ?: original.triangleCapacityValue,
    )
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
