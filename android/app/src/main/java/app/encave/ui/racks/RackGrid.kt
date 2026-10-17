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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import app.encave.ui.components.BottleEnd
import app.encave.ui.components.displayLabel
import app.encave.ui.theme.LocalWinePalette

/**
 * Width and height of [RackGrid] for a cell size, following its layout (cells, gaps,
 * offset rows, nesting, headers, shelf bars, padding). [labelHeight] is the height of
 * the column-number row, which follows the font size.
 */
fun rackGridSize(rack: Rack, cell: Dp, showHeaders: Boolean, labelHeight: Dp): Pair<Dp, Dp> {
    val size = if (rack.kind == RackKind.Bins) cell * 1.7f else cell
    val spacing = when (rack.kind) {
        RackKind.Grid -> cell * 0.08f
        RackKind.Staggered -> cell * 0.04f
        RackKind.Shelves -> cell * 0.1f
        RackKind.Bins -> 0.dp
    }
    val pad = if (rack.kind == RackKind.Grid || rack.kind == RackKind.Staggered) cell * 0.12f else 0.dp
    val header = if (showHeaders) maxOf(18.dp, cell * 0.42f) else 0.dp
    val offsets = rack.kind == RackKind.Staggered || rack.kind == RackKind.Bins
    val width = (0 until rack.rows).maxOf { row ->
        val n = rack.columnsInRow(row)
        val offset = if (offsets && row % 2 == 1) (size + spacing) / 2 else 0.dp
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
    val shelf = if (rack.kind == RackKind.Shelves) maxOf(4.dp, cell * 0.09f) else 0.dp
    val headerRow = if (showHeaders && (rack.kind == RackKind.Grid || rack.kind == RackKind.Shelves)) labelHeight + gap else 0.dp
    val height = headerRow + (size + shelf) * rack.rows - overlap * (rack.rows - 1) + gap * (rack.rows - 1) + pad * 2
    return width to height
}

/** The largest cell size (16–80dp) at which the whole rack fits in [width] × [height]. */
fun fittingCellSize(rack: Rack, width: Dp, height: Dp, showHeaders: Boolean = true, labelHeight: Dp = 20.dp): Dp {
    var low = 16f
    var high = 80f
    repeat(24) {
        val mid = (low + high) / 2
        val (w, h) = rackGridSize(rack, mid.dp, showHeaders, labelHeight)
        if (w <= width && h <= height) low = mid else high = mid
    }
    return low.dp
}

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
 * - honeycomb: touching round holes, every other row offset by half,
 * - shelves: a row of bottles on a shelf, with dots for the places behind,
 * - bins: diamond bins showing a small pile of bottles and a count.
 * Each cell is at least 48dp and is read by TalkBack ("B3, Name 2015" or "Bin B3, 7 of 10 bottles").
 */
@Composable
fun RackGrid(
    rack: Rack,
    cellar: Cellar,
    modifier: Modifier = Modifier,
    cellSize: Dp = 56.dp,
    selected: Set<Slot> = emptySet(),
    highlighted: Slot? = null,
    showHeaders: Boolean = true,
    clickLabel: (List<Bottle>) -> String? = { null },
    onCellClick: ((RackCell, List<Bottle>) -> Unit)? = null,
    onCellLongClick: ((RackCell, List<Bottle>) -> Unit)? = null,
) {
    val byCell = cellar.bottlesByCell(rack)
    val selectedByCell = selected.filter { it.rackId == rack.id }.groupBy { it.cell }
    val size = if (rack.kind == RackKind.Bins) cellSize * 1.7f else cellSize
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
    val offsetsOddRows = rack.kind == RackKind.Staggered || rack.kind == RackKind.Bins
    val headerWidth = maxOf(18.dp, cellSize * 0.42f)
    val scheme = MaterialTheme.colorScheme
    // No touch-target minimum here: the rack screen fits the rack to the screen and zooms from there.
    val cellWidth = size

    Column(
        modifier
            .then(
                if (rack.kind == RackKind.Grid || rack.kind == RackKind.Staggered) {
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
            Column(if (row > 0 && overlap > 0.dp) Modifier.overlapUp(overlap) else Modifier) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showHeaders) {
                        Box(Modifier.width(headerWidth).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            Text(Rack.rowLetters(row), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (offsetsOddRows && row % 2 == 1) Spacer(Modifier.width((cellWidth + spacing) / 2))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        for (column in 0 until rack.columnsInRow(row)) {
                            val cell = RackCell(rack.id, row, column)
                            val bottles = byCell[cell].orEmpty()
                            RackCellView(
                                rack = rack, cell = cell, bottles = bottles, cellar = cellar, size = size,
                                selectedCount = selectedByCell[cell]?.size ?: 0,
                                isHighlighted = highlighted?.cell == cell,
                                clickLabel = clickLabel(bottles),
                                onClick = onCellClick?.let { { it(cell, bottles) } },
                                onLongClick = onCellLongClick?.let { { it(cell, bottles) } },
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
                                .height(maxOf(4.dp, cellSize * 0.09f))
                                .background(scheme.outlineVariant, CircleShape),
                        )
                    }
                }
            }
        }
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
    selectedCount: Int,
    isHighlighted: Boolean,
    clickLabel: String?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val resources = LocalResources.current
    val place = rack.displayLabel(cell, resources)
    val names = bottles.mapNotNull { cellar.wines[it.wineId]?.displayName }
    val base = when {
        rack.kind == RackKind.Bins -> stringResource(R.string.cell_bin, place, bottles.size, rack.positionsPerCell)
        names.isEmpty() -> stringResource(R.string.cell_empty, place)
        else -> stringResource(R.string.cell_filled, place, names.joinToString(", "))
    }
    val description = when {
        selectedCount > 0 -> stringResource(R.string.slot_selected, base)
        isHighlighted -> stringResource(R.string.cell_highlighted, base)
        else -> base
    }
    Box(
        Modifier
            .size(size)
            .testTag("cell.${cell.row}.${cell.column}")
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = clickLabel) else Modifier,
            )
            .clearAndSetSemantics {
                contentDescription = description
                selected = selectedCount > 0
                if (onClick != null) {
                    role = Role.Button
                    onClick(label = clickLabel) { onClick(); true }
                }
                if (onLongClick != null) onLongClick { onLongClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rack.kind == RackKind.Bins) {
            Bin(rack, bottles, cellar, size, selectedCount, isHighlighted)
        } else {
            Position(rack, cell, bottles, cellar, size, selectedCount, isHighlighted)
        }
    }
}

/** One place (grid, honeycomb, shelf): the front bottle, or the one behind, faded. */
@Composable
private fun Position(
    rack: Rack,
    cell: RackCell,
    bottles: List<Bottle>,
    cellar: Cellar,
    size: Dp,
    selectedCount: Int,
    isHighlighted: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val front = bottles.firstOrNull { it.slot?.depth == 0 }
    val behind = bottles.firstOrNull { (it.slot?.depth ?: 0) > 0 }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (rack.kind) {
            RackKind.Grid -> Box(
                Modifier.size(size).background(scheme.surfaceContainerHighest, RoundedCornerShape(size * 0.16f))
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(size * 0.16f)),
            )
            RackKind.Staggered -> Box(Modifier.size(size).background(scheme.surfaceContainerHighest, CircleShape))
            else -> Unit
        }
        when {
            front != null -> BottleEnd(cellar.wines[front.wineId]?.color, size = size * 0.72f)
            behind != null -> BottleEnd(cellar.wines[behind.wineId]?.color, Modifier.alpha(0.55f), size = size * 0.52f)
            else -> {
                Box(Modifier.size(size * 0.72f).border(1.dp, scheme.outline.copy(alpha = 0.5f), CircleShape))
                if (selectedCount == 0) {
                    Text(
                        rack.label(cell), color = scheme.onSurfaceVariant,
                        fontSize = with(LocalDensity.current) { (size * 0.18f).toSp().value.coerceAtLeast(9f).sp },
                    )
                }
            }
        }
        if (isHighlighted) Box(Modifier.size(size * 0.86f).border(3.dp, scheme.primary, CircleShape))
        if (selectedCount > 0) SelectionBadge(rack, selectedCount, size)
        if (rack.positionsPerCell > 1) {
            DepthDots(rack, bottles, cellar, size, Modifier.align(Alignment.BottomCenter).offset(y = if (rack.kind == RackKind.Shelves) size * 0.12f else -size * 0.02f))
        }
    }
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

@Composable
private fun SelectionBadge(rack: Rack, count: Int, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(size * 0.74f).background(scheme.primaryContainer, CircleShape).border(2.dp, scheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (rack.positionsPerCell > 1) {
            Text("+$count", color = scheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimaryContainer)
        }
    }
}

/** A diamond bin with a small pile of bottles (up to ten, as a pyramid) and "7/10". */
@Composable
private fun Bin(rack: Rack, bottles: List<Bottle>, cellar: Cellar, size: Dp, selectedCount: Int, isHighlighted: Boolean) {
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
            if (selectedCount > 0) Text("+$selectedCount", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
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
    val palette = LocalWinePalette.current
    val empty = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (row in 0 until minOf(rack.rows, 6)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (rack.kind == RackKind.Staggered && row % 2 == 1) Spacer(Modifier.width(6.dp))
                for (column in 0 until minOf(rack.columnsInRow(row), 10)) {
                    val bottles = byCell[RackCell(rack.id, row, column)].orEmpty()
                    val color = if (bottles.isEmpty()) empty else palette[bottles.first().let { cellar.wines[it.wineId]?.color }]
                    Box(Modifier.size(9.dp).background(color, if (rack.kind == RackKind.Bins) DiamondShape else CircleShape))
                }
            }
        }
    }
}
