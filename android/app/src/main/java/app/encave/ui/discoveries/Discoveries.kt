package app.encave.ui.discoveries

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RemoveShoppingCart
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.Discovery
import app.encave.core.DiscoveryFilter
import app.encave.core.discoveries
import app.encave.core.discoveryTags
import app.encave.core.shoppingListCount
import app.encave.data.PhotoStore
import app.encave.ui.add.WinePhoto
import app.encave.ui.components.Formats
import app.encave.ui.components.RatingStars
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.theme.Dimens

/** Actions available on a discovery row. */
class DiscoveryActions(
    val open: (Discovery) -> Unit,
    val addToCellar: (Discovery) -> Unit,
    val toggleToBuy: (Discovery) -> Unit,
    val delete: (Discovery) -> Unit,
)

/** "To buy" chip with the count, then tag chips. */
@Composable
fun DiscoveryFilterBar(cellar: Cellar, filter: DiscoveryFilter, onChange: (DiscoveryFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        FilterChip(
            selected = filter.toBuyOnly,
            onClick = { onChange(filter.copy(toBuyOnly = !filter.toBuyOnly)) },
            label = { Text(stringResource(R.string.discovery_to_buy_count, cellar.shoppingListCount)) },
            leadingIcon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        cellar.discoveryTags.forEach { tag ->
            FilterChip(
                selected = filter.tag == tag,
                onClick = { onChange(filter.copy(tag = if (filter.tag == tag) null else tag)) },
                label = { Text(tag) },
                leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** The discoveries list items, for a LazyColumn. */
fun LazyListScope.discoveryItems(
    cellar: Cellar,
    photos: PhotoStore,
    filter: DiscoveryFilter,
    actions: DiscoveryActions,
) {
    val items = cellar.discoveries(filter)
    if (items.isEmpty()) {
        item {
            Text(
                stringResource(R.string.discovery_none_match),
                Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(items, key = { "d" + it.id }) { discovery ->
        SwipeableDiscoveryRow(cellar, photos, discovery, actions, Modifier.animateItem())
        HorizontalDivider(Modifier.padding(start = 72.dp))
    }
}

/** Swipe right: add to cellar. Swipe left: to buy on or off. Long press: all actions. */
@Composable
private fun SwipeableDiscoveryRow(cellar: Cellar, photos: PhotoStore, discovery: Discovery, actions: DiscoveryActions, modifier: Modifier) {
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
                modifier = Modifier.semantics {
                    customActions = listOf(
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

/** One wine found elsewhere: photo or bottle end, where, rating, note and #tags. */
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
) {
    val wine = cellar.wines[discovery.wineId]
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = if (showsWine) {
            {
                if (photos.exists(wine?.photoId)) {
                    WinePhoto(
                        photos, wine?.photoId, contentDescription = null, height = 56.dp,
                        modifier = Modifier.size(44.dp, 56.dp),
                    )
                } else {
                    WineColorDot(wine?.color, size = 30.dp)
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
                val where = discovery.place ?: discovery.setting?.let { stringResource(it.labelRes) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    if (where != null) {
                        Icon(discovery.setting?.icon ?: Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(where, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    if (showsWine) Text(if (where != null) "· ${Formats.day(discovery.date)}" else Formats.day(discovery.date), maxLines = 1)
                }
                discovery.rating?.let { RatingStars(it) }
                discovery.note?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                val extras = listOfNotNull(
                    discovery.price?.let { Formats.money(it, discovery.currency ?: cellar.settings.currency) },
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
