package app.encave.ui.racks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
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
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackLayout
import app.encave.core.Slot
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.LocalWinePalette

/** A rhombus, for diamond (X-shaped) bins. */
val DiamondShape: Shape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/**
 * The rack as rows (letters) and columns (numbers). Grid racks are square
 * cells; diamond racks are rhombi with every other row offset by half a cell.
 * Each cell is at least 48dp and is read by TalkBack as "B3, Name 2015".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RackGrid(
    rack: Rack,
    cellar: Cellar,
    modifier: Modifier = Modifier,
    cellSize: Dp = Dimens.touchTarget + 8.dp,
    selected: Set<Slot> = emptySet(),
    highlighted: Slot? = null,
    clickLabel: (Bottle?) -> String? = { null },
    onCellClick: ((Slot, Bottle?) -> Unit)? = null,
    onCellLongClick: ((Slot, Bottle?) -> Unit)? = null,
    showLabels: Boolean = true,
) {
    val occupants = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }.associateBy { it.slot!! }
    val diamond = rack.layout == RackLayout.Diamond
    val gap = if (diamond) 2.dp else 4.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (diamond) 0.dp else gap)) {
        if (showLabels) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Spacer(Modifier.width(24.dp))
                for (column in 0 until rack.columns) {
                    Box(Modifier.width(cellSize), contentAlignment = Alignment.Center) {
                        Text("${column + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        for (row in 0 until rack.rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                if (showLabels) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Text(Rack.rowLetters(row), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (diamond && row % 2 == 1) Spacer(Modifier.width(cellSize / 2))
                for (column in 0 until rack.columns) {
                    val slot = Slot(rack.id, row, column)
                    val bottle = occupants[slot]
                    RackCell(
                        label = rack.label(row, column),
                        bottle = bottle,
                        cellar = cellar,
                        size = cellSize,
                        diamond = diamond,
                        isSelected = slot in selected,
                        isHighlighted = slot == highlighted,
                        clickLabel = clickLabel(bottle),
                        onClick = onCellClick?.let { { it(slot, bottle) } },
                        onLongClick = onCellLongClick?.let { { it(slot, bottle) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RackCell(
    label: String,
    bottle: Bottle?,
    cellar: Cellar,
    size: Dp,
    diamond: Boolean,
    isSelected: Boolean,
    isHighlighted: Boolean,
    clickLabel: String?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val wine = bottle?.let { cellar.wines[it.wineId] }
    val shape = if (diamond) DiamondShape else RoundedCornerShape(Dimens.radiusCell)
    val scheme = MaterialTheme.colorScheme
    val description = when {
        isSelected -> stringResource(R.string.slot_selected, label)
        wine != null && isHighlighted -> stringResource(R.string.cell_highlighted, stringResource(R.string.cell_filled, label, wine.displayName))
        wine != null -> stringResource(R.string.cell_filled, label, wine.displayName)
        else -> stringResource(R.string.cell_empty, label)
    }
    val border = when {
        isHighlighted -> BorderStroke(3.dp, scheme.primary)
        isSelected -> BorderStroke(2.dp, scheme.primary)
        else -> BorderStroke(1.dp, scheme.outlineVariant)
    }
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHigh, shape)
            .border(border, shape)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = clickLabel, role = Role.Button)
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
                if (onClick != null) {
                    role = Role.Button
                    onClick(label = clickLabel) { onClick(); true }
                }
                if (onLongClick != null) onLongClick { onLongClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isSelected -> Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimaryContainer)
            wine != null -> app.encave.ui.components.BottleEnd(wine.color, size = size * if (diamond) 0.46f else 0.66f)
            else -> Box(
                Modifier.size(size * if (diamond) 0.36f else 0.5f).border(1.dp, scheme.outlineVariant, CircleShape),
            )
        }
    }
}

/** Small decorative drawing of a rack's shape (list rows, editor preview). */
@Composable
fun RackPreview(rack: Rack, modifier: Modifier = Modifier, maxSize: Dp = 160.dp, filled: Set<Slot> = emptySet()) {
    val scheme = MaterialTheme.colorScheme
    val diamond = rack.layout == RackLayout.Diamond
    val widthUnits = rack.columns + if (diamond && rack.rows > 1) 0.5f else 0f
    val ratio = widthUnits / rack.rows.toFloat()
    val width = if (ratio >= 1f) maxSize else maxSize * ratio
    val height = if (ratio >= 1f) maxSize / ratio else maxSize
    androidx.compose.foundation.Canvas(modifier.size(width, height)) {
        val cell = size.width / widthUnits
        val pad = cell * 0.08f
        for (row in 0 until rack.rows) {
            for (column in 0 until rack.columns) {
                val left = column * cell + if (diamond && row % 2 == 1) cell / 2 else 0f
                val top = row * cell
                val color = if (Slot(rack.id, row, column) in filled) scheme.primary else scheme.outlineVariant
                if (diamond) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(left + cell / 2, top + pad)
                        lineTo(left + cell - pad, top + cell / 2)
                        lineTo(left + cell / 2, top + cell - pad)
                        lineTo(left + pad, top + cell / 2)
                        close()
                    }
                    drawPath(path, color)
                } else {
                    drawRoundRect(
                        color,
                        topLeft = androidx.compose.ui.geometry.Offset(left + pad, top + pad),
                        size = androidx.compose.ui.geometry.Size(cell - 2 * pad, cell - 2 * pad),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.18f),
                    )
                }
            }
        }
    }
}
