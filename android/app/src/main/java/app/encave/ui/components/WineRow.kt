package app.encave.ui.components

import androidx.compose.ui.unit.Dp
import app.encave.core.Wine
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.text.TextStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import app.encave.ui.theme.Dimens
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Maturity
import app.encave.core.WineGroup
import app.encave.core.FirstPlace
import app.encave.ui.racks.icon
import androidx.compose.ui.platform.testTag

/** "Main rack · A1, A3" for bottles in racks (people's labels), storage notes as they are. */
@Composable
fun locationsText(cellar: Cellar, bottles: List<Bottle>): String {
    val resources = LocalResources.current
    val parts = mutableListOf<String>()
    bottles.filter { it.slot != null }.groupBy { it.slot!!.rackId }.forEach { (rackId, inRack) ->
        val rack = cellar.rack(rackId) ?: return@forEach
        parts += "${rack.name}$SEPARATOR" + inRack.sortedWith(compareBy({ it.slot!!.row }, { it.slot!!.column }, { it.slot!!.depth }))
            .joinToString(", ") { rack.displayLabel(it.slot!!, resources) }
    }
    bottles.filter { it.slot == null }.mapNotNull { it.storageNote?.takeIf(String::isNotBlank) }.distinct().forEach { parts += it }
    if (bottles.any { it.slot == null && it.storageNote.isNullOrBlank() } || parts.isEmpty()) {
        parts += stringResource(R.string.location_none)
    }
    return parts.joinToString(SEPARATOR)
}

/**
 * The symbol next to a bottle's place: its storage's, a pin for a note of where it is, a
 * tray when it is not in storage. The same on every screen, as on Apple.
 */
fun Cellar.locationIcon(bottle: Bottle): ImageVector {
    bottle.slot?.let { rack(it.rackId) }?.let { return it.icon }
    if (!bottle.storageNote.isNullOrBlank()) return Icons.Outlined.Place
    return BottlePlaceIcons.NotInStorage
}

/** "Main rack · B3", the storage note, or "Not in storage": never blank. */
@Composable
fun Cellar.placeLabel(bottle: Bottle): String = displayLocation(bottle) ?: stringResource(R.string.location_none)

/** The symbols of a bottle's place and of putting it away, shared by every screen (as on Apple). */
object BottlePlaceIcons {
    /** "Not in storage": a tray. */
    val NotInStorage: ImageVector get() = Icons.Outlined.Inbox
    /** Put away: into a tray. */
    val PutAway: ImageVector get() = Icons.Outlined.MoveToInbox
    /** Show in storage. */
    val Show: ImageVector get() = Icons.Outlined.MyLocation
}

/**
 * Up to two lines: the symbol of the first bottle's place, then that place ("Wine fridge ·
 * Shelf 1, place 5"), or "… and 2 more" when the bottles are in several places, "Not in
 * storage" counted as a place of its own. Bottles in the order their places read.
 */
@Composable
fun LocationLine(cellar: Cellar, bottles: List<Bottle>) {
    val resources = LocalResources.current
    val none = stringResource(R.string.location_none)
    val ordered = cellar.inPlaceOrder(bottles)
    val labels = ordered.map { cellar.displayLocation(it, resources) ?: none }
    val place = FirstPlace.of(labels) ?: return
    val text = if (place.others == 0) place.first
    else stringResource(R.string.location_and_more, place.first, pluralStringResource(R.plurals.cell_bin_more, place.others, place.others))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            cellar.locationIcon(ordered.first()), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
        )
        // Up to two lines, so a long place never cuts off "… and 2 more".
        Text(
            text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("wine.row.location"),
        )
    }
}

/**
 * The wine's picture (its cover, else its label photo) or, without one, its bottle end:
 * the same in rows, cards and sheets, as on Apple. [width] is the row size, 34 dp wide.
 */
@Composable
fun WineThumbnail(wine: Wine?, modifier: Modifier = Modifier, width: Dp = 34.dp) {
    val photos = LocalPhotoStore.current
    if (wine != null && photos != null && photos.hasPicture(wine)) {
        WineImage(wine, modifier.size(width, width * 46 / 34), maxPixelSize = 300, inset = 2.dp, corner = 7.dp)
    } else {
        BottleEnd(wine?.color, modifier, size = width)
    }
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
    /** What a long press does, read by TalkBack ("double tap and hold to …"). */
    onLongClickLabel: String? = null,
    selected: Boolean = false,
    showMaturity: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
    /** Set while choosing several wines: a check box, and a tap ticks the wine. */
    checked: Boolean? = null,
) {
    val wine = group.wine
    val maturity = Maturity.of(wine, year)
    // Bottles on order: when they should arrive, instead of maturity and place.
    val isOrder = group.bottles.firstOrNull()?.status == Bottle.Status.Ordered
    val style = stringResource(wine.color.labelRes)
    val origin = wine.origin()
    ListItem(
        modifier = if (checked != null) {
            modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() })
        } else {
            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
        },
        colors = if (selected || checked == true) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = {
            if (checked != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    WineColorDot(wine.color, size = 16.dp)
                }
            } else {
                val photos = LocalPhotoStore.current
                if (photos != null && photos.hasPicture(wine)) {
                    WineImage(wine, Modifier.size(34.dp, 46.dp), maxPixelSize = 200, inset = 2.dp, corner = 7.dp)
                } else {
                    WineColorDot(wine.color, size = 16.dp)
                }
            }
        },
        headlineContent = { Text(wine.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf(style, origin).filter { it.isNotEmpty() }.joinToString(SEPARATOR), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showMaturity && !isOrder && maturity != Maturity.Unknown) {
                    // The symbol in the maturity color, the words in the text color.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(maturity.icon, contentDescription = null, tint = maturity.tint, modifier = Modifier.size(16.dp))
                        Text(stringResource(maturity.shortLabelRes), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isOrder) {
                    ArrivalLabel(group.bottles.first().expectedAt)
                } else {
                    LocationLine(cellar, group.bottles)
                }
                extra?.invoke()
            }
        },
        trailingContent = {
            val countDescription = pluralStringResource(R.plurals.bottle_count, group.count, group.count)
            Text(
                stringResource(R.string.count_times, group.count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = countDescription },
            )
        },
    )
}

/** When bottles on order should arrive; a late delivery has a warning icon, not only a color. */
@Composable
fun ArrivalLabel(date: Instant?, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val zone = ZoneId.systemDefault()
    val late = date != null && date.atZone(zone).toLocalDate().isBefore(LocalDate.now(zone))
    val text = when {
        date == null -> stringResource(R.string.arrival_unknown)
        late -> stringResource(R.string.arrival_late, Formats.recentDay(date))
        // With the year when it is not this one, as on Apple.
        else -> stringResource(R.string.arrival_expected, Formats.recentDay(date))
    }
    val color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (late) Icons.Outlined.WarningAmber else Icons.Outlined.LocalShipping,
            contentDescription = null, tint = color, modifier = Modifier.size(16.dp),
        )
        Text(text, color = color, style = style)
    }
}
