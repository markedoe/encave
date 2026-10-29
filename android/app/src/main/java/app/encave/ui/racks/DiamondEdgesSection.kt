package app.encave.ui.racks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.DiamondEdge
import app.encave.ui.components.Hint
import app.encave.ui.theme.Dimens

/** "Top triangles", for the switches. */
val DiamondEdge.togglesRes: Int
    get() = when (this) {
        DiamondEdge.Top -> R.string.rack_edges_top
        DiamondEdge.Left -> R.string.rack_edges_left
        DiamondEdge.Bottom -> R.string.rack_edges_bottom
        DiamondEdge.Right -> R.string.rack_edges_right
    }

/** How far the up arrow turns to point to the side, so a switch does not rely on its words alone. */
private val DiamondEdge.arrowRotation: Float
    get() = when (this) {
        DiamondEdge.Top -> 0f
        DiamondEdge.Left -> -90f
        DiamondEdge.Bottom -> 180f
        DiamondEdge.Right -> 90f
    }

/**
 * The triangles along the sides of a diamond rack: a switch per side, then how many bottles a
 * triangle holds. The same in the wizard and the editor (Apple: DiamondEdgesSection.swift).
 */
@Composable
fun DiamondEdgesSection(
    edges: Set<DiamondEdge>,
    triangleCapacity: Int,
    binCapacity: Int,
    onEdges: (Set<DiamondEdge>) -> Unit,
    onTriangleCapacity: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Header(R.string.rack_edges_title)
        DiamondEdge.entries.forEach { edge ->
            val on = edge in edges
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(on, role = Role.Switch) { onEdges(if (it) edges + edge else edges - edge) }
                    .testTag("rack.edges.${edge.raw}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ArrowDropUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(edge.arrowRotation),
                )
                Text(
                    stringResource(edge.togglesRes), Modifier.weight(1f).padding(horizontal = Dimens.m),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = on, onCheckedChange = null)
            }
        }
        if (edges.isNotEmpty()) {
            Row(Modifier.testTag("rack.edges.capacity")) {
                SizeRow(
                    stringResource(R.string.rack_edges_capacity), triangleCapacity.coerceAtMost(binCapacity), onTriangleCapacity,
                    1..maxOf(1, binCapacity),
                )
            }
        }
        Hint(stringResource(R.string.rack_edges_body))
    }
}
