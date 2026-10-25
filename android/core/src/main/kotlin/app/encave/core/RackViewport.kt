package app.encave.core

/** A part of a drawing, as fractions of it (0..1). */
data class ViewportArea(val x: Double, val y: Double, val width: Double, val height: Double)

/** A point, in pixels or as fractions of a drawing. */
data class ViewportPoint(val x: Double, val y: Double)

/**
 * How a storage is laid out on screen, the same on both platforms (RackViewport.swift):
 * fitted when every place is at least a touch target, else at that size, scrolling, with
 * an overview of the whole storage showing the part in view. Pure numbers: the drawing
 * itself is in the app.
 */
object RackViewport {
    /** Smallest touch target: 48 dp on Android (44 pt on Apple platforms). */
    const val MINIMUM_TARGET = 48.0

    /**
     * The back places of a fridge drawn front and back, as a share of the front ones' height
     * (their width stays, so each back place sits right under its front one).
     */
    const val BACK_PLACE_HEIGHT = 0.78

    /** The cell size at which the smallest place of [rack] is [target] wide and tall: back places of a fridge drawn in bands are shorter. */
    fun minimumCellSize(rack: Rack, target: Double = MINIMUM_TARGET): Double =
        if (rack.hasDepthBands) target / BACK_PLACE_HEIGHT else target

    /**
     * A storage only slightly larger than the space is fitted rather than scrolled by a few dp,
     * as long as its places stay at least this large.
     */
    const val NEAR_FIT_TARGET = 40.0

    /** How much larger than the space a storage may be at the minimum and still be fitted. */
    const val NEAR_FIT_MARGIN = 1.1

    /** Where a storage that scrolls opens: its top-leading corner (fractions of the drawing). */
    val start = ViewportPoint(0.0, 0.0)

    /**
     * Whether the storage scrolls: its places would be smaller than [minimum] when fitted, and it is
     * more than 10 % larger than the space at the minimum, or its places would be smaller than
     * [nearFit] (the cell size for [NEAR_FIT_TARGET]).
     */
    fun scrolls(fitted: Double, minimum: Double, nearFit: Double): Boolean =
        fitted < minimum && !(fitted >= nearFit && minimum <= fitted * NEAR_FIT_MARGIN)

    /** The cell size to draw at: the fitted one, unless the storage scrolls at the minimum. */
    fun cellSize(fitted: Double, minimum: Double, nearFit: Double): Double =
        if (scrolls(fitted, minimum, nearFit)) minimum else fitted

    /** The part of the drawing in view, as fractions of it; [offset] is the point of the drawing at the view's top left corner. */
    fun visibleArea(contentWidth: Double, contentHeight: Double, viewWidth: Double, viewHeight: Double, offset: ViewportPoint): ViewportArea {
        fun span(start: Double, length: Double, total: Double): Pair<Double, Double> {
            if (total <= 0) return 0.0 to 1.0
            val low = (start / total).coerceIn(0.0, 1.0)
            val high = ((start + length) / total).coerceIn(0.0, 1.0)
            return low to maxOf(0.0, high - low)
        }
        val (x, width) = span(offset.x, viewWidth, contentWidth)
        val (y, height) = span(offset.y, viewHeight, contentHeight)
        return ViewportArea(x, y, width, height)
    }

    /** The offset that brings [point] (fractions of the drawing) to the middle of the view, as far as the drawing allows. */
    fun offsetCentering(point: ViewportPoint, contentWidth: Double, contentHeight: Double, viewWidth: Double, viewHeight: Double): ViewportPoint {
        fun axis(fraction: Double, total: Double, length: Double) = minOf(maxOf(0.0, total - length), maxOf(0.0, fraction * total - length / 2))
        return ViewportPoint(axis(point.x, contentWidth, viewWidth), axis(point.y, contentHeight, viewHeight))
    }

    /**
     * Where a place is in the drawing, as fractions of it (its middle, roughly: captions and shelf
     * bars aside). On a fridge drawn front and back, each shelf two deep shows its front layers,
     * then its back ones, half a place right when head-to-tail; a shelf one deep shows its
     * layers once.
     */
    fun center(rack: Rack, cell: RackCell, depth: Int? = null): ViewportPoint {
        val columns = maxOf(1, rack.maxColumns) + rack.maxBandShift.toDouble() + if (rack.kind == RackKind.Staggered) 0.5 else 0.0
        val shift = (if (rack.kind == RackKind.Staggered && rack.isOffset(cell.row)) 0.5 else 0.0) + rack.bandShift(cell.row, depth)
        val x = minOf(1.0, (cell.column + 0.5 + shift) / columns)
        val shelf = rack.shelfRows.indexOfFirst { cell.row in it }
        if (!rack.hasDepthBands || shelf < 0) return ViewportPoint(x, (cell.row + 0.5) / maxOf(1, rack.rows))
        // Each shelf two deep is drawn twice: its front layers, then its back ones.
        fun lines(index: Int) = rack.shelfRows[index].count() * if (rack.hasDepthBandsOnShelf(index)) 2 else 1
        val before = (0 until shelf).sumOf { lines(it) }
        val total = rack.shelfRows.indices.sumOf { lines(it) }
        val rows = rack.shelfRows[shelf]
        val back = depth == 1 && rack.hasDepthBandsOnShelf(shelf)
        val line = before + (if (back) rows.count() else 0) + (cell.row - rows.first)
        return ViewportPoint(x, (line + 0.5) / maxOf(1, total))
    }
}

/**
 * The symbol drawn in a bottle end and next to the style's name, so a style never relies on
 * color alone: each differs by its shape. The same choice on Apple platforms (RackViewport.swift).
 */
enum class WineStyleMark { Glass, Sun, Heart, Leaf, Bubbles, Drop, Shield }

/** Red a glass, white a sun, rosé a heart, orange a leaf, sparkling bubbles, dessert a drop, fortified a shield. */
val WineColor.styleMark: WineStyleMark
    get() = when (this) {
        WineColor.Red -> WineStyleMark.Glass
        WineColor.White -> WineStyleMark.Sun
        WineColor.Rose -> WineStyleMark.Heart
        WineColor.Orange -> WineStyleMark.Leaf
        WineColor.Sparkling -> WineStyleMark.Bubbles
        WineColor.Dessert -> WineStyleMark.Drop
        WineColor.Fortified -> WineStyleMark.Shield
    }
