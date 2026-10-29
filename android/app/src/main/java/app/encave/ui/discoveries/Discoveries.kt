package app.encave.ui.discoveries

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RemoveShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.Discovery
import app.encave.core.discoveries
import app.encave.data.PhotoStore
import app.encave.ui.components.Formats
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.hasPicture
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.theme.Dimens
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Actions available on a discovery row. */
class DiscoveryActions(
    val open: (Discovery) -> Unit,
    val addToCellar: (Discovery) -> Unit,
    val toggleToBuy: (Discovery) -> Unit,
    val delete: (Discovery) -> Unit,
)

/** "Tasted elsewhere · Lisbon": where it comes from, in words. */
@Composable
fun elsewhereText(discovery: Discovery): String {
    val place = discovery.place ?: discovery.setting?.let { stringResource(it.labelRes) }
    return if (place != null) stringResource(R.string.journal_tasted_elsewhere_at, place) else stringResource(R.string.journal_tasted_elsewhere)
}

/**
 * A wine tasted elsewhere in the Journal, saying so. Swipe right: add to cellar.
 * Swipe left: to buy on or off. Long press: all actions.
 */
@Composable
fun ElsewhereRow(cellar: Cellar, photos: PhotoStore, discovery: Discovery, actions: DiscoveryActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val addLabel = stringResource(R.string.discovery_add_to_cellar)
    val buyLabel = stringResource(if (discovery.wantsToBuy) R.string.discovery_remove_from_list else R.string.discovery_to_buy)
    val editLabel = stringResource(R.string.action_edit)
    val deleteLabel = stringResource(R.string.action_delete)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> actions.addToCellar(discovery)
                SwipeToDismissBoxValue.EndToStart -> actions.toggleToBuy(discovery)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    Box(modifier) {
        SwipeToDismissBox(
            state = swipe,
            backgroundContent = {
                val toEnd = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                Row(
                    Modifier.fillMaxSize()
                        .background(if (toEnd) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
                ) {
                    Icon(
                        if (toEnd) Icons.Filled.Add else if (discovery.wantsToBuy) Icons.Outlined.RemoveShoppingCart else Icons.Outlined.AddShoppingCart,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(Dimens.s))
                    Text(if (toEnd) addLabel else buyLabel, style = MaterialTheme.typography.labelLarge)
                }
            },
        ) {
            DiscoveryRow(
                cellar, photos, discovery,
                onClick = { actions.open(discovery) },
                onLongClick = { menu = true },
                showsSource = true,
                modifier = Modifier.semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(editLabel) { actions.open(discovery); true },
                        CustomAccessibilityAction(addLabel) { actions.addToCellar(discovery); true },
                        CustomAccessibilityAction(buyLabel) { actions.toggleToBuy(discovery); true },
                        CustomAccessibilityAction(deleteLabel) { actions.delete(discovery); true },
                    )
                },
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(editLabel) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; actions.open(discovery) })
            DropdownMenuItem(text = { Text(addLabel) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; actions.addToCellar(discovery) })
            DropdownMenuItem(
                text = { Text(buyLabel) },
                leadingIcon = { Icon(if (discovery.wantsToBuy) Icons.Outlined.RemoveShoppingCart else Icons.Outlined.AddShoppingCart, null) },
                onClick = { menu = false; actions.toggleToBuy(discovery) },
            )
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; actions.delete(discovery) },
            )
        }
    }
}

/**
 * One wine tasted elsewhere: photo or bottle end, where, rating, note and #tags.
 * [showsSource] (in the Journal): says in words that it was tasted elsewhere, and where,
 * and TalkBack reads the row as one sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoveryRow(
    cellar: Cellar,
    photos: PhotoStore,
    discovery: Discovery,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showsWine: Boolean = true,
    showsSource: Boolean = false,
) {
    val wine = cellar.wines[discovery.wineId]
    val source = elsewhereText(discovery)
    // The style in words, not only by the dot's color.
    val style = wine?.color?.let { stringResource(it.labelRes) }
    val price = discovery.price?.let { Formats.money(it, discovery.currency ?: cellar.settings.currency) }
    val description = listOfNotNull(
        source,
        wine?.displayName ?: stringResource(R.string.unknown_wine),
        style,
        app.encave.ui.components.spokenRating(discovery.rating, discovery.score100),
        Formats.day(discovery.date),
        when {
            discovery.wantsToBuy -> stringResource(R.string.discovery_to_buy)
            discovery.boughtAt != null -> stringResource(R.string.discovery_bought)
            else -> null
        },
        discovery.note,
        price,
        discovery.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(", ")
    ListItem(
        // The clicks before clearAndSetSemantics, so TalkBack still finds the row's actions.
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (showsSource) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier),
        overlineContent = if (showsSource) {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Icon(discovery.setting?.icon ?: Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            null
        },
        leadingContent = if (showsWine) {
            {
                if (wine != null && photos.hasPicture(wine)) {
                    app.encave.ui.components.WineImage(
                        wine, modifier = Modifier.size(44.dp, 56.dp), maxPixelSize = 200, inset = 3.dp, corner = 8.dp,
                    )
                } else {
                    // The slot of a picture, so names line up with the Journal's cellar rows.
                    Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                        WineColorDot(wine?.color, size = 16.dp)
                    }
                }
            }
        } else {
            null
        },
        headlineContent = {
            Text(
                if (showsWine) wine?.displayName ?: stringResource(R.string.unknown_wine) else Formats.day(discovery.date),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // In the Journal the place is already in the line above.
                val where = if (showsSource) null else discovery.place ?: discovery.setting?.let { stringResource(it.labelRes) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    if (where != null) {
                        Icon(discovery.setting?.icon ?: Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(where, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    if (showsWine) {
                        val tail = listOfNotNull(style, Formats.day(discovery.date)).joinToString(" · ")
                        Text(if (where != null) "· $tail" else tail, maxLines = 1)
                    }
                }
                discovery.rating?.let { app.encave.ui.components.RatingDisplay(it, discovery.score100) }
                discovery.note?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                val extras = listOfNotNull(
                    price,
                    discovery.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                )
                if (extras.isNotEmpty()) {
                    Text(extras.joinToString("  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = when {
            discovery.wantsToBuy -> {
                { Icon(Icons.Filled.ShoppingCart, contentDescription = stringResource(R.string.discovery_to_buy), tint = MaterialTheme.colorScheme.primary) }
            }
            discovery.boughtAt != null -> {
                { Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.discovery_bought)) }
            }
            else -> null
        },
    )
}
