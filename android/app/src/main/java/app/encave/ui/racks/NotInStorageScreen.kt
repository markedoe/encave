package app.encave.ui.racks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Slot
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.cellar.MenuText
import app.encave.ui.components.ActionContent
import app.encave.ui.components.BottlePlaceIcons
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WineThumbnail
import app.encave.ui.components.locationIcon
import app.encave.ui.components.placeLabel
import app.encave.ui.components.styleLine
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.drink.rememberDrinking
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import java.util.UUID

/** Those of [bottles] whose wine matches every word of [query] (all when it is blank). */
fun Cellar.looseBottles(bottles: List<Bottle>, query: String): List<Bottle> {
    val needle = query.trim()
    if (needle.isEmpty()) return bottles
    return bottles.filter { wines[it.wineId]?.matches(needle) == true }
}

/** The bottles not in storage, wine by wine (by name), oldest first within a wine. */
val Cellar.looseBottlesByWine: List<Bottle>
    get() = bottlesNotInStorage.sortedWith(
        compareBy<Bottle, String>(String.CASE_INSENSITIVE_ORDER) { wines[it.wineId]?.displayName.orEmpty() }
            .thenBy { it.wineId.toString() }
            .then(app.encave.core.bottleAddedOrder),
    )

/**
 * The bottles of the cellar without a position: each is put away from here (the Move
 * sheet), drunk, or its wine shown. The same list as on Apple devices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotInStorageScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    var moving by rememberSaveable { mutableStateOf<String?>(null) }
    var drinking by rememberDrinking()
    var query by rememberSaveable { mutableStateOf("") }
    val all = cellar.looseBottlesByWine
    val bottles = cellar.looseBottles(all, query)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.not_in_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                // All at once: each beside its wine's other bottles, else where there is most room.
                actions = {
                    TextButton(
                        onClick = { viewModel.putAllAway() }, enabled = all.isNotEmpty() && cellar.racks.isNotEmpty(),
                        modifier = Modifier.testTag("not_in_storage.put_all"),
                    ) { Text(stringResource(R.string.action_put_all_away)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (all.isEmpty()) {
            EmptyState(
                icon = BottlePlaceIcons.NotInStorage,
                title = stringResource(R.string.not_in_storage_empty_title),
                body = stringResource(R.string.not_in_storage_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().testTag("not_in_storage.list"), contentPadding = padding) {
            item {
                LooseSearchField(query, onChange = { query = it }, Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s))
            }
            items(bottles, key = { it.id.toString() }) { bottle ->
                LooseBottleRow(
                    cellar, bottle,
                    onPutAway = { moving = bottle.id.toString() },
                    onDrink = { drinking = bottle.id.toString() },
                    onShowWine = { navigator.open(WineDetailRoute(bottle.wineId.toString())) },
                    onGone = { viewModel.markGone(bottle.id) },
                )
                HorizontalDivider()
            }
            item {
                Text(
                    stringResource(R.string.not_in_storage_footer),
                    Modifier.padding(Dimens.l),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    moving?.let { id -> cellar.bottles[UUID.fromString(id)] }?.let { bottle ->
        MoveBottleSheet(viewModel, cellar, bottle) { moving = null }
    }
    drinking?.let { id ->
        DrinkSheet(cellar, id, onDismiss = { drinking = null }, onDrink = { b, t -> viewModel.drink(b.id, t) })
    }
}

/** Searches the bottles out of storage by their wine, as the Cellar's search does. */
@Composable
private fun LooseSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint_short)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        modifier = modifier.fillMaxWidth().testTag("not_in_storage.search"),
    )
}

/** A bottle out of storage: the wine, where it is (its note or "Not in storage"), the occasion it is kept for. */
@Composable
fun LooseBottleText(cellar: Cellar, bottle: Bottle) {
    val wine = cellar.wines[bottle.wineId]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(wine?.displayName ?: stringResource(R.string.unknown_wine), style = MaterialTheme.typography.titleMedium)
        wine?.styleLine()?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(cellar.locationIcon(bottle), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(cellar.placeLabel(bottle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bottle.reservedFor?.let { occasion ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.reserved_for, occasion), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One bottle of the list: Put away in words; touch and hold for Drink, Show wine, No longer in cellar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LooseBottleRow(
    cellar: Cellar,
    bottle: Bottle,
    onPutAway: () -> Unit,
    onDrink: () -> Unit,
    onShowWine: () -> Unit,
    onGone: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val putAway = stringResource(R.string.action_put_away)
    val drink = stringResource(R.string.action_drink)
    val showWine = stringResource(R.string.action_show_wine)
    val gone = stringResource(R.string.action_mark_gone)
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(onClickLabel = putAway, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.action_more), onClick = onPutAway)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(drink) { onDrink(); true },
                        CustomAccessibilityAction(showWine) { onShowWine(); true },
                        CustomAccessibilityAction(gone) { onGone(); true },
                    )
                }
                .testTag("not_in_storage.row"),
            leadingContent = { WineThumbnail(cellar.wines[bottle.wineId]) },
            headlineContent = { LooseBottleText(cellar, bottle) },
            trailingContent = {
                OutlinedButton(onClick = onPutAway, modifier = Modifier.testTag("not_in_storage.put_away")) {
                    ActionContent(putAway, BottlePlaceIcons.PutAway)
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(drink) }, leadingIcon = { Icon(Icons.Outlined.LocalBar, null) }, onClick = { menu = false; onDrink() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_put_away_ellipsis)) },
                leadingIcon = { Icon(BottlePlaceIcons.PutAway, null) }, onClick = { menu = false; onPutAway() },
            )
            DropdownMenuItem(text = { Text(showWine) }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; onShowWine() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { MenuText(gone, stringResource(R.string.action_mark_gone_detail)) },
                leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) }, onClick = { menu = false; onGone() },
            )
        }
    }
}

/** A free position tapped while bottles are out of storage: one of them goes there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PutBottleHereSheet(viewModel: AppViewModel, cellar: Cellar, slot: Slot, title: String, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
            Text(
                title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { heading() },
            )
            LooseSearchField(query, onChange = { query = it }, Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s))
            LazyColumn {
                items(cellar.looseBottles(cellar.looseBottlesByWine, query), key = { it.id.toString() }) { bottle ->
                    ListItem(
                        modifier = Modifier.clickable {
                            // Taken meanwhile (sync): nothing moves.
                            if (cellar.bottleAt(slot) == null) viewModel.move(bottle.id, slot)
                            onDismiss()
                        }.testTag("put_here.row"),
                        leadingContent = { WineThumbnail(cellar.wines[bottle.wineId]) },
                        headlineContent = { LooseBottleText(cellar, bottle) },
                    )
                }
            }
        }
    }
}
