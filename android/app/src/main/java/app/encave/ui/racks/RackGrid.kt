package app.encave.ui.racks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.core.WineColor
import java.util.UUID
import app.encave.ui.components.BottleEnd
import app.encave.ui.components.depthName
import app.encave.ui.components.displayLabel
import app.encave.ui.components.labelRes
import app.encave.ui.components.zoneName
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.encave.ui.theme.LocalWinePalette

/** From these cell sizes a bottle shows its vintage, then (grid and shelves) its name under it. */
object RackLabels {
    val vintageSize = 34.dp
    val nameSize = 56.dp

    /** Cells showing a name are this much taller than wide. */
    const val NAME_HEIGHT_RATIO = 1.3f

    fun showsVintage(rack: Rack, cell: Dp) = cell >= vintageSize && rack.kind != RackKind.Bins
    fun showsName(rack: Rack, cell: Dp) = cell >= nameSize && (rack.kind == RackKind.Grid || rack.kind == RackKind.Shelves)
}

/**
 * Width and height of [RackGrid] for a cell size, following its layout (cells, gaps,
 * offset rows, nesting, headers, shelf bars, padding, names under bottles). [labelHeight]
 * is the height of the column-number row, which follows the font size.
 */
fun rackGridSize(rack: Rack, cell: Dp, showHeaders: Boolean, labelHeight: Dp): Pair<Dp, Dp> {
    val size = if (rack.kind == RackKind.Bins) cell * 1.7f else cell
    val cellHeight = if (RackLabels.showsName(rack, cell)) size * RackLabels.NAME_HEIGHT_RATIO else size
    val spacing = when (rack.kind) {
        RackKind.Grid -> cell * 0.08f
        RackKind.Staggered -> cell * 0.04f
        RackKind.Shelves -> cell * 0.1f
        RackKind.Bins -> 0.dp
    }
    val pad = if (rack.isFramed) cell * 0.12f else 0.dp
    val header = if (showHeaders) maxOf(18.dp, cell * 0.42f) else 0.dp
    val width = (0 until rack.rows).maxOf { row ->
        val n = rack.columnsInRow(row)
        val offset = if (rack.isOffset(row)) (size + spacing) / 2 else 0.dp
        header + offset + size * n + spacing * (n - 1)
    } + pad * 2
    val overlap = when (rack.kind) {
        RackKind.Staggered -> (size + spacing) * 0.134f
        RackKind.Bins -> size / 2 - cell * 0.06f
        else -> 0.dp
    }
    val gap = when (rack.kind) {
        RackKind.Grid -> spacing
        RackKind.Shelves -> cell * 0.42f
        else -> 0.dp
    }
    if (rack.hasShelfLayers) {
        // Each shelf: its layers nesting into each other, the shelf under them, a gap to the next.
        val shelves = rack.shelfRows
        val height = shelves.fold(0.dp) { total, rows ->
            total + cellHeight * rows.count() - overlap * (rows.count() - 1) + cell * SHELF_BAR_GAP + shelfBar(cell)
        } + cell * SHELF_GAP * (shelves.size - 1) +
            // Each zone's name over its shelves.
            (if (rack.zoneCount > 1) (labelHeight + cell * SHELF_GAP) * rack.zoneCount else 0.dp)
        return width to height
    }
    val shelf = if (rack.kind == RackKind.Shelves) shelfBar(cell) else 0.dp
    val headerRow = if (showHeaders && (rack.kind == RackKind.Grid || rack.kind == RackKind.Shelves)) labelHeight + gap else 0.dp
    val height = headerRow + (cellHeight + shelf) * rack.rows - overlap * (rack.rows - 1) + gap * (rack.rows - 1) + pad * 2
    return width to height
}

/**
 * The largest cell size (16–80dp) at which the whole rack fits in [width] × [height].
 * Grid and shelf cells get taller once they show names, so sizes from
 * [RackLabels.nameSize] are only used when the rack still fits with the names.
 */
fun fittingCellSize(rack: Rack, width: Dp, height: Dp, showHeaders: Boolean = true, labelHeight: Dp = 20.dp): Dp {
    fun fits(cell: Float): Boolean {
        val (w, h) = rackGridSize(rack, cell.dp, showHeaders, labelHeight)
        return w <= width && h <= height
    }
    fun search(from: Float, to: Float): Float {
        var low = from
        var high = to
        repeat(24) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }
    val nameSize = RackLabels.nameSize.value
    val withNames = rack.kind == RackKind.Grid || rack.kind == RackKind.Shelves
    if (withNames && fits(nameSize)) return search(nameSize, 80f).dp
    // Below the name size, cell heights do not jump, so the search is monotonic.
    return search(16f, if (withNames) nameSize - 0.01f else 80f).dp
}

/** A continuous honeycomb or a grid, drawn inside a frame. */
private val Rack.isFramed: Boolean get() = kind == RackKind.Grid || (kind == RackKind.Staggered && !hasShelfLayers)

/** Fridge with bottles in layers: the space above each shelf bar and between two shelves, in cell sizes. */
private const val SHELF_BAR_GAP = 0.04f
private const val SHELF_GAP = 0.12f

private fun shelfBar(cell: Dp): Dp = maxOf(4.dp, cell * 0.09f)

/** Light wine colors need dark text written on them; red and fortified need light text. */
val WineColor?.prefersDarkLabel: Boolean
    get() = this != WineColor.Red && this != WineColor.Fortified

/** A rhombus, for diamond bins. */
val DiamondShape: Shape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/** Pulls a row up by [overlap] so honeycomb and diamond rows nest into each other. */
private fun Modifier.overlapUp(overlap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = overlap.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
}

/**
 * Draws a rack as it looks in real life:
 * - grid: square cells with row letters and column numbers,
 * - honeycomb: touching round holes, every other row offset by half; on shelves (a wine
 *   fridge), each shelf's layers nest into each other in quincunx above a shelf,
 * - shelves: a row of bottles on a shelf, with dots for the places behind,
 * - bins: diamond bins showing a small pile of bottles and a count.
 * From [RackLabels.vintageSize] bottles show their vintage, and from [RackLabels.nameSize]
 * grid and shelf cells also show the wine's name. Each cell is read by TalkBack
 * ("B3, Name 2015" or "Bin B3, 7 of 10 bottles").
 */
@Composable
fun RackGrid(
    rack: Rack,
    cellar: Cellar,
    modifier: Modifier = Modifier,
    cellSize: Dp = 56.dp,
    selected: Set<Slot> = emptySet(),
    highlighted: Slot? = null,
    /** The bottle shown in the card under the rack. */
    selectedCell: RackCell? = null,
    /** A wine chosen in the key: its bottles stand out, the others fade. */
    focusWine: UUID? = null,
    showHeaders: Boolean = true,
    clickLabel: (List<Bottle>) -> String? = { null },
    onCellClick: ((RackCell, List<Bottle>) -> Unit)? = null,
    /** Offered on the places where [longClickLabel] names what it does. */
    onCellLongClick: ((RackCell, List<Bottle>) -> Unit)? = null,
    longClickLabel: (List<Bottle>) -> String? = { null },
    /** More actions offered to TalkBack on a place. */
    customActions: (RackCell, List<Bottle>) -> List<CustomAccessibilityAction> = { _, _ -> emptyList() },
    /** Checking the rack: [selected] are the bottles found, shown as "8/10" while some are missing. */
    countsFound: Boolean = false,
    onCellDoubleClick: ((RackCell) -> Unit)? = null,
    /**
     * Drag and drop (wide screens): touch and hold a bottle, then drag it to another place.
     * Called with the bottle and the place it was dropped on (its own place when not moved).
     */
    onDrop: ((Bottle, RackCell) -> Unit)? = null,
) {
    // Only worked out again when the cellar or the rack changes, not at each redraw.
    val byCell = remember(cellar, rack) { cellar.bottlesByCell(rack) }
    // Drag and drop: where each place is on screen, and the bottle being dragged.
    val cellBounds = remember(rack.id) { mutableMapOf<RackCell, Rect>() }
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragged by remember { mutableStateOf<Bottle?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    fun cellAt(local: Offset): RackCell? {
        val root = container?.takeIf { it.isAttached }?.localToRoot(local) ?: return null
        return cellBounds.entries.firstOrNull { it.value.contains(root) }?.key
    }
    val dropTarget = dragged?.let { cellAt(dragAt) }
    val currentByCell by rememberUpdatedState(byCell)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val selectedByCell = remember(selected, rack.id) { selected.filter { it.rackId == rack.id }.groupBy { it.cell } }
    val size = if (rack.kind == RackKind.Bins) cellSize * 1.7f else cellSize
    val cellHeight = if (RackLabels.showsName(rack, cellSize)) size * RackLabels.NAME_HEIGHT_RATIO else size
    val spacing = when (rack.kind) {
        RackKind.Grid -> cellSize * 0.08f
        RackKind.Staggered -> cellSize * 0.04f
        RackKind.Shelves -> cellSize * 0.1f
        RackKind.Bins -> 0.dp
    }
    val overlap = when (rack.kind) {
        RackKind.Staggered -> (size + spacing) * 0.134f
        RackKind.Bins -> size / 2 - cellSize * 0.06f
        else -> 0.dp
    }
    val rowGap = when (rack.kind) {
        RackKind.Grid -> spacing
        RackKind.Shelves -> cellSize * 0.42f
        else -> 0.dp
    }
    val headerWidth = maxOf(18.dp, cellSize * 0.42f)
    val scheme = MaterialTheme.colorScheme
    // No touch-target minimum here: the rack screen fits the rack to the screen and zooms from there.
    val cellWidth = size

    Box(
        modifier
            .onGloballyPositioned { container = it }
            .then(
                if (onDrop == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(rack.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragAt = offset
                                dragged = cellAt(offset)?.let { cell ->
                                    currentByCell[cell].orEmpty().let { list -> list.firstOrNull { it.slot?.depth == 0 } ?: list.firstOrNull() }
                                }
                            },
                            onDrag = { change, amount ->
                                if (dragged != null) {
                                    change.consume()
                                    dragAt += amount
                                }
                            },
                            onDragEnd = {
                                val bottle = dragged
                                val target = cellAt(dragAt)
                                dragged = null
                                if (bottle != null && target != null) currentOnDrop?.invoke(bottle, target)
                            },
                            onDragCancel = { dragged = null },
                        )
                    }
                },
            ),
    ) {
        Column(
            Modifier
                .then(
                    if (rack.isFramed) {
                        Modifier.background(scheme.surfaceContainer, RoundedCornerShape(cellSize * 0.22f)).padding(cellSize * 0.12f)
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            if (showHeaders && (rack.kind == RackKind.Grid || rack.kind == RackKind.Shelves)) {
                Row(Modifier.clearAndSetSemantics { }) {
                    Spacer(Modifier.width(headerWidth))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        for (column in 0 until rack.maxColumns) {
                            Box(Modifier.width(cellWidth), contentAlignment = Alignment.Center) {
                                Text("${column + 1}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            for (row in 0 until rack.rows) {
                // On a fridge with layers, a shelf's top layer starts below the previous shelf.
                val place = rack.shelfPlace(row)
                val startsShelf = place != null && place.layer == place.layers - 1
                if (startsShelf && row > 0) Spacer(Modifier.height(cellSize * SHELF_GAP))
                if (place != null && startsShelf && rack.zoneCount > 1 &&
                    (place.shelf == 0 || rack.zoneOfShelf(place.shelf) != rack.zoneOfShelf(place.shelf - 1))
                ) {
                    ZoneDivider(rack, rack.zoneOfShelf(place.shelf), cellSize, if (showHeaders) headerWidth else 0.dp)
                    Spacer(Modifier.height(cellSize * SHELF_GAP))
                }
                Column(if (row > 0 && overlap > 0.dp && !startsShelf) Modifier.overlapUp(overlap) else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showHeaders) {
                            Box(Modifier.width(headerWidth).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                                // Shelves are numbered (layered ones beside their bottom layer); other racks letter their rows.
                                val header = when {
                                    place == null && rack.kind == RackKind.Shelves -> "${row + 1}"
                                    place == null -> Rack.rowLetters(row)
                                    place.layer == 0 -> "${place.shelf + 1}"
                                    else -> ""
                                }
                                Text(header, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                            }
                        }
                        if (rack.isOffset(row)) Spacer(Modifier.width((cellWidth + spacing) / 2))
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            for (column in 0 until rack.columnsInRow(row)) {
                                val cell = RackCell(rack.id, row, column)
                                val bottles = byCell[cell].orEmpty()
                                RackCellView(
                                    rack = rack, cell = cell, bottles = bottles, cellar = cellar, size = size, height = cellHeight,
                                    selectedCount = selectedByCell[cell]?.size ?: 0,
                                    isHighlighted = highlighted?.cell == cell || selectedCell == cell || dropTarget == cell,
                                    focusWine = focusWine,
                                    clickLabel = clickLabel(bottles),
                                    onClick = onCellClick?.let { { it(cell, bottles) } },
                                    longClickLabel = longClickLabel(bottles),
                                    onLongClick = onCellLongClick?.takeIf { longClickLabel(bottles) != null }?.let { { it(cell, bottles) } },
                                    customActions = customActions(cell, bottles),
                                    countsFound = countsFound,
                                    onDoubleClick = onCellDoubleClick?.let { { it(cell) } },
                                    onPositioned = if (onDrop != null) ({ bounds -> cellBounds[cell] = bounds }) else null,
                                )
                            }
                        }
                    }
                    if (rack.kind == RackKind.Shelves) {
                        // The shelf under the row of bottles.
                        Row {
                            if (showHeaders) Spacer(Modifier.width(headerWidth))
                            Box(
                                Modifier
                                    .width(cellWidth * rack.columnsInRow(row) + spacing * (rack.columnsInRow(row) - 1))
                                    .height(shelfBar(cellSize))
                                    .background(scheme.outlineVariant, CircleShape),
                            )
                        }
                    }
                    if (place != null && place.layer == 0) {
                        // The shelf under its layers, as wide as the widest of them.
                        val shelfWidth = rack.shelfRows[place.shelf].maxOf { layer ->
                            val n = rack.columnsInRow(layer)
                            (if (rack.isOffset(layer)) (cellWidth + spacing) / 2 else 0.dp) + cellWidth * n + spacing * (n - 1)
                        }
                        Spacer(Modifier.height(cellSize * SHELF_BAR_GAP))
                        Row {
                            if (showHeaders) Spacer(Modifier.width(headerWidth))
                            Box(Modifier.width(shelfWidth).height(shelfBar(cellSize)).background(scheme.outlineVariant, CircleShape))
                        }
                    }
                }
            }
        }
        // The bottle being dragged follows the finger.
        dragged?.let { bottle ->
            val half = size / 2
            BottleEnd(
                cellar.wines[bottle.wineId]?.color,
                Modifier.offset { IntOffset((dragAt.x - half.toPx()).roundToInt(), (dragAt.y - half.toPx()).roundToInt()) }
                    .alpha(0.85f).testTag("rack.dragging"),
                size = size,
            )
        }
    }
}

/** The name of a temperature zone over a line; zones below the first start under a thicker divider. */
@Composable
private fun ZoneDivider(rack: Rack, zone: Int, cellSize: Dp, inset: Dp) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = inset).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(cellSize * 0.12f),
    ) {
        Text(
            rack.zoneName(zone, LocalResources.current), style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant, maxLines = 1,
        )
        Box(
            Modifier.width(cellSize * rack.maxColumns).height(if (zone == 0) 1.dp else maxOf(3.dp, cellSize * 0.07f))
                .background(if (zone == 0) scheme.outlineVariant else scheme.outline, CircleShape),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RackCellView(
    rack: Rack,
    cell: RackCell,
    bottles: List<Bottle>,
    cellar: Cellar,
    size: Dp,
    height: Dp,
    selectedCount: Int,
    isHighlighted: Boolean,
    focusWine: UUID?,
    clickLabel: String?,
    onClick: (() -> Unit)?,
    longClickLabel: String?,
    onLongClick: (() -> Unit)?,
    customActions: List<CustomAccessibilityAction>,
    countsFound: Boolean,
    onDoubleClick: (() -> Unit)?,
    onPositioned: ((Rect) -> Unit)? = null,
) {
    val resources = LocalResources.current
    val isFocused = focusWine != null && bottles.any { it.wineId == focusWine }
    val isDimmed = focusWine != null && !isFocused
    val description = cellDescription(
        rack, cell, bottles, cellar, resources,
        found = if (countsFound) selectedCount else null,
        isSelected = selectedCount > 0, isShown = isHighlighted || isFocused, isDimmed = isDimmed,
    )
    Box(
        Modifier
            .size(size, height)
            .testTag("cell.${cell.row}.${cell.column}")
            .then(if (onPositioned != null) Modifier.onGloballyPositioned { onPositioned(it.boundsInRoot()) } else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick, onLongClick = onLongClick, onDoubleClick = onDoubleClick, onClickLabel = clickLabel,
                        onLongClickLabel = longClickLabel,
                    )
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = description
                selected = selectedCount > 0
                if (onClick != null) {
                    role = Role.Button
                    onClick(label = clickLabel) { onClick(); true }
                }
                if (onLongClick != null) onLongClick(label = longClickLabel) { onLongClick(); true }
                if (customActions.isNotEmpty()) this.customActions = customActions
            }
            .alpha(if (isDimmed) 0.25f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        if (rack.kind == RackKind.Bins) {
            Bin(rack, bottles, cellar, size, selectedCount, isHighlighted || isFocused, countsFound)
        } else {
            Position(rack, cell, bottles, cellar, size, height, selectedCount, isHighlighted, isFocused, countsFound)
        }
    }
}

/** One place (grid, honeycomb, shelf): the front bottle, or the one behind, faded; then the name when there is room. */
@Composable
private fun Position(
    rack: Rack,
    cell: RackCell,
    bottles: List<Bottle>,
    cellar: Cellar,
    size: Dp,
    height: Dp,
    selectedCount: Int,
    isHighlighted: Boolean,
    isFocused: Boolean,
    countsFound: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val front = bottles.firstOrNull { it.slot?.depth == 0 }
    val behind = bottles.firstOrNull { (it.slot?.depth ?: 0) > 0 }
    val shownWine = (front ?: bottles.firstOrNull())?.let { cellar.wines[it.wineId] }
    val showsName = height > size
    val shape = RoundedCornerShape(size * 0.16f)
    Column(
        Modifier
            .size(size, height)
            .then(
                if (rack.kind == RackKind.Grid) {
                    Modifier.background(scheme.surfaceContainerHighest, shape)
                        .border(if (isFocused) 2.dp else 1.dp, if (isFocused) scheme.primary else scheme.outlineVariant, shape)
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.03f),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (rack.kind == RackKind.Staggered) Box(Modifier.size(size).background(scheme.surfaceContainerHighest, CircleShape))
            val bottle = size * 0.72f
            when {
                front != null -> {
                    val wine = cellar.wines[front.wineId]
                    BottleEnd(wine?.color, size = bottle)
                    if (RackLabels.showsVintage(rack, size)) VintageLabel(wine, bottle)
                }
                behind != null -> BottleEnd(cellar.wines[behind.wineId]?.color, Modifier.alpha(0.55f), size = size * 0.52f)
                else -> {
                    Box(Modifier.size(bottle).border(1.dp, scheme.outline.copy(alpha = 0.5f), CircleShape))
                    if (selectedCount == 0) {
                        Text(
                            rack.label(cell), color = scheme.onSurfaceVariant,
                            fontSize = with(density) { (size * 0.18f).toSp().value.coerceAtLeast(9f).sp },
                        )
                    }
                }
            }
            if (isHighlighted || isFocused) {
                Box(Modifier.size(size * 0.86f).border(if (isHighlighted) 3.dp else 2.dp, scheme.primary, CircleShape))
            }
            if (selectedCount > 0) SelectionBadge(rack, selectedCount, if (countsFound) bottles.size else null, size)
            if (rack.positionsPerCell > 1) {
                val y = if (rack.kind == RackKind.Shelves && !showsName) size * 0.12f else -size * 0.02f
                DepthDots(rack, bottles, cellar, size, Modifier.align(Alignment.BottomCenter).offset(y = y))
            }
        }
        if (showsName) {
            val font = with(density) { maxOf(9.dp, size * 0.15f).toSp() }
            Text(
                shownWine?.name.orEmpty(),
                modifier = Modifier.width(size * 0.96f),
                color = if (isFocused) scheme.primary else scheme.onSurface,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = font, fontWeight = FontWeight.Medium, lineHeight = font * 1.15f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = font * 0.7f, maxFontSize = font, stepSize = 0.5.sp),
            )
        }
    }
}

/**
 * What TalkBack reads for a place, in the same order as VoiceOver: the place, then each
 * bottle with its style in words (the drawing shows it only by color), front first, then its
 * state ("8 of 10 found", "selected", "shown", "other wine").
 */
internal fun cellDescription(
    rack: Rack,
    cell: RackCell,
    bottles: List<Bottle>,
    cellar: Cellar,
    resources: android.content.res.Resources,
    found: Int? = null,
    isSelected: Boolean = false,
    isShown: Boolean = false,
    isDimmed: Boolean = false,
): String {
    val place = rack.displayLabel(cell, resources)
    fun wine(bottle: Bottle): String? = cellar.wines[bottle.wineId]?.let { wine ->
        wine.color?.let { resources.getString(R.string.cell_wine_style, wine.displayName, resources.getString(it.labelRes)) } ?: wine.displayName
    }
    val base = when {
        rack.kind == RackKind.Bins -> resources.getString(R.string.cell_bin, place, bottles.size, rack.positionsPerCell)
        rack.positionsPerCell == 1 -> bottles.firstOrNull()?.let(::wine)?.let { resources.getString(R.string.cell_filled, place, it) }
            ?: resources.getString(R.string.cell_empty, place)
        bottles.isEmpty() -> resources.getString(R.string.cell_empty, place)
        else -> {
            val parts = (0 until rack.positionsPerCell).map { depth ->
                val name = rack.depthName(depth, resources)
                bottles.firstOrNull { it.slot?.depth == depth }?.let(::wine)
                    ?.let { resources.getString(R.string.cell_depth_filled, name, it) }
                    ?: resources.getString(R.string.cell_depth_empty, name)
            }
            resources.getString(R.string.cell_depths, place, parts.joinToString("; "))
        }
    }
    return when {
        found != null && found > 0 && found < bottles.size -> resources.getString(R.string.cell_found_partial, place, found, bottles.size)
        isSelected -> resources.getString(R.string.slot_selected, base)
        isShown -> resources.getString(R.string.cell_highlighted, base)
        isDimmed -> resources.getString(R.string.cell_dimmed, base)
        else -> base
    }
}

/** The vintage on the bottle end ("2017", or "NV"), in a color readable on that wine's tint. */
@Composable
private fun VintageLabel(wine: Wine?, diameter: Dp) {
    val font = with(LocalDensity.current) { maxOf(8.dp, diameter * 0.27f).toSp() }
    Text(
        wine?.vintage?.toString() ?: stringResource(R.string.rack_non_vintage),
        modifier = Modifier.width(diameter * 0.82f),
        color = if (wine?.color.prefersDarkLabel) Color.Black.copy(alpha = 0.78f) else Color.White,
        style = TextStyle(fontSize = font, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", lineHeight = font),
        textAlign = TextAlign.Center,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = font * 0.5f, maxFontSize = font, stepSize = 0.5.sp),
    )
}

/** One dot per place in the cell, front first: filled when a bottle is there. */
@Composable
private fun DepthDots(rack: Rack, bottles: List<Bottle>, cellar: Cellar, size: Dp, modifier: Modifier) {
    val palette = LocalWinePalette.current
    val outline = MaterialTheme.colorScheme.outline
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(maxOf(2.dp, size * 0.05f))) {
        for (depth in 0 until rack.positionsPerCell) {
            val bottle = bottles.firstOrNull { it.slot?.depth == depth }
            val dot = maxOf(5.dp, size * 0.1f)
            Box(
                Modifier.size(dot)
                    .then(if (bottle != null) Modifier.background(palette[cellar.wines[bottle.wineId]?.color], CircleShape) else Modifier)
                    .border(0.75.dp, outline, CircleShape),
            )
        }
    }
}

/** A check, "+2" for bottles chosen in a place, or "1/2" for the bottles found when only some are ([total]). */
@Composable
private fun SelectionBadge(rack: Rack, count: Int, total: Int?, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(size * 0.74f).background(scheme.primaryContainer, CircleShape).border(2.dp, scheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (total != null && count < total) {
            Text("$count/$total", color = scheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        } else if (total == null && rack.positionsPerCell > 1) {
            Text("+$count", color = scheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimaryContainer)
        }
    }
}

/** A diamond bin with a small pile of bottles (up to ten, as a pyramid) and "7/10". */
@Composable
private fun Bin(rack: Rack, bottles: List<Bottle>, cellar: Cellar, size: Dp, selectedCount: Int, isHighlighted: Boolean, countsFound: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val capacity = rack.positionsPerCell
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size * 0.98f).background(scheme.surfaceContainerHigh, DiamondShape)
                .border(if (isHighlighted) 3.dp else 1.5.dp, if (isHighlighted) scheme.primary else scheme.outlineVariant, DiamondShape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = size * 0.04f)) {
            Pile(bottles.map { cellar.wines[it.wineId]?.color }, size * 0.11f)
            Text(
                "${bottles.size}/$capacity", style = MaterialTheme.typography.labelMedium,
                color = if (bottles.size >= capacity) scheme.onSurface else scheme.onSurfaceVariant,
            )
            if (selectedCount > 0 && countsFound) {
                // Checking: how many of its bottles were found.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                    Text("$selectedCount/${bottles.size}", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                }
            } else if (selectedCount > 0) {
                Text("+$selectedCount", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
        }
    }
}

@Composable
private fun Pile(colors: List<app.encave.core.WineColor?>, bottle: Dp) {
    val shown = colors.take(10)
    val rows = mutableListOf<List<app.encave.core.WineColor?>>()
    var index = 0
    for (width in listOf(4, 3, 2, 1)) {
        if (index >= shown.size) break
        val end = minOf(shown.size, index + width)
        rows += shown.subList(index, end)
        index = end
    }
    Column(
        Modifier.height(bottle * 3.2f).clearAndSetSemantics { },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.reversed().forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { row.forEach { BottleEnd(it, size = bottle) } }
        }
    }
}

/** Small decorative drawing of a rack's shape and fill (list rows). */
@Composable
fun RackThumbnail(rack: Rack, cellar: Cellar, modifier: Modifier = Modifier) {
    val byCell = cellar.bottlesByCell(rack)
    if (rack.hasShelfLayers) {
        // Shelves of layers nesting into each other, each above a shelf: up to eight layers.
        val shown = mutableListOf<IntRange>()
        for (rows in rack.shelfRows) {
            if (shown.sumOf { it.count() } + rows.count() > 8) break
            shown += rows
        }
        val bar = MaterialTheme.colorScheme.outlineVariant
        Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (rows in shown.ifEmpty { rack.shelfRows.take(1) }) {
                Column {
                    for (row in rows) {
                        ThumbnailRow(rack, row, byCell, cellar, if (row > rows.first) Modifier.overlapUp(1.5.dp) else Modifier)
                    }
                    Box(Modifier.padding(top = 1.dp).width(12.dp * rack.maxColumns).height(2.dp).background(bar, CircleShape))
                }
            }
        }
    } else {
        Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (row in 0 until minOf(rack.rows, 6)) ThumbnailRow(rack, row, byCell, cellar)
        }
    }
}

@Composable
private fun ThumbnailRow(rack: Rack, row: Int, byCell: Map<RackCell, List<Bottle>>, cellar: Cellar, modifier: Modifier = Modifier) {
    val palette = LocalWinePalette.current
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (rack.kind == RackKind.Staggered && rack.isOffset(row)) Spacer(Modifier.width(6.dp))
        for (column in 0 until minOf(rack.columnsInRow(row), 10)) {
            val bottles = byCell[RackCell(rack.id, row, column)].orEmpty()
            val color = if (bottles.isEmpty()) empty else palette[bottles.first().let { cellar.wines[it.wineId]?.color }]
            Box(Modifier.size(9.dp).background(color, if (rack.kind == RackKind.Bins) DiamondShape else CircleShape))
        }
    }
}
