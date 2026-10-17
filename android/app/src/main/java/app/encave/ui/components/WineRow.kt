package app.encave.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Maturity
import app.encave.core.WineGroup

/** "Main rack · A1, A3" for bottles in racks (people's labels), storage notes as they are. */
@Composable
fun locationsText(cellar: Cellar, bottles: List<Bottle>): String {
    val resources = LocalResources.current
    val parts = mutableListOf<String>()
    bottles.filter { it.slot != null }.groupBy { it.slot!!.rackId }.forEach { (rackId, inRack) ->
        val rack = cellar.rack(rackId) ?: return@forEach
        parts += "${rack.name} · " + inRack.sortedWith(compareBy({ it.slot!!.row }, { it.slot!!.column }, { it.slot!!.depth }))
            .joinToString(", ") { rack.displayLabel(it.slot!!, resources) }
    }
    bottles.filter { it.slot == null }.mapNotNull { it.storageNote?.takeIf(String::isNotBlank) }.distinct().forEach { parts += it }
    if (bottles.any { it.slot == null && it.storageNote.isNullOrBlank() } || parts.isEmpty()) {
        parts += stringResource(R.string.location_none)
    }
    return parts.joinToString(" · ")
}

/** List row: "Name Vintage", style · producer · region, location, ×N and maturity. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WineGroupRow(
    group: WineGroup,
    cellar: Cellar,
    year: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    showMaturity: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    val wine = group.wine
    val maturity = Maturity.of(wine, year)
    val style = stringResource(wine.color.labelRes)
    val origin = wine.origin()
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = { WineColorDot(wine.color, size = 16.dp) },
        headlineContent = { Text(wine.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf(style, origin).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    locationsText(cellar, group.bottles), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                )
                extra?.invoke()
            }
        },
        trailingContent = {
            val countDescription = pluralStringResource(R.plurals.bottle_count, group.count, group.count)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.count_times, group.count),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = countDescription },
                )
                if (showMaturity && maturity != Maturity.Unknown) {
                    Icon(
                        maturity.icon, contentDescription = stringResource(maturity.labelRes),
                        tint = maturity.tint, modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}
