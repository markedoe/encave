package app.encave.ui.cellar

import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import app.encave.core.WindowSource
import app.encave.ui.components.ArrivalLabel
import app.encave.ui.components.displayLocation
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Maturity
import app.encave.core.ServingAdvice
import app.encave.core.discoveriesOf
import app.encave.ui.discoveries.DiscoveryRow
import app.encave.ui.navigation.DiscoveryEditorRoute
import app.encave.core.Wine
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.EmptyState
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.MaturityLabel
import app.encave.ui.components.RatingStars
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.StyleLabel
import app.encave.ui.components.labelRes
import app.encave.ui.components.sizeLabel
import app.encave.ui.components.tint
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.EditWineRoute
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.TastingRoute
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.SerifDisplay
import app.encave.core.search.WebSearchQuery
import app.encave.ui.search.PriceOptions
import app.encave.ui.search.WebSearchSheet
import app.encave.ui.search.WinePictureSheet
import app.encave.ui.search.webSearchPreferences
import java.util.UUID
import app.encave.ui.components.hasPicture

@Composable
fun WineDetailScreen(
    viewModel: AppViewModel,
    navigator: AppNavigator,
    wineId: String,
    showBack: Boolean,
    onBack: (() -> Unit)? = null,
    /** False beside a list that shows the messages already (two panes). */
    showMessages: Boolean = true,
) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val id = remember(wineId) { runCatching { UUID.fromString(wineId) }.getOrNull() }
    val wine = id?.let { cellar.wines[it] }
    val back = onBack ?: { navigator.back() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var drinking by remember { mutableStateOf<Bottle?>(null) }
    var deletingBottle by remember { mutableStateOf<Bottle?>(null) }
    var reserving by remember { mutableStateOf<Bottle?>(null) }
    var findingPrice by remember { mutableStateOf(false) }
    var searchingOnline by remember { mutableStateOf(false) }
    var choosingPicture by remember { mutableStateOf(false) }
    val webSearch by webSearchPreferences().settings.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(wine?.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (wine != null) {
                        IconButton(onClick = { navigator.open(EditWineRoute(wineId)) }, modifier = Modifier.testTag("action.edit_wine")) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { if (showMessages) SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (wine == null) {
            EmptyState(
                icon = Icons.Outlined.LocalBar,
                title = stringResource(R.string.wine_not_found),
                body = "",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val bottles = cellar.bottlesOf(wine.id)
        val orders = cellar.bottlesOnOrder.filter { it.wineId == wine.id }
            .sortedWith(compareBy<Bottle>({ it.expectedAt ?: java.time.Instant.MAX }, { it.addedAt }))
        val tastings = cellar.tastingsOf(wine.id)
        LazyColumn(Modifier.fillMaxSize().testTag("wine.detail"), contentPadding = padding) {
            item {
                if (viewModel.photos.hasPicture(wine)) {
                    app.encave.ui.components.WineImage(
                        wine,
                        contentDescription = stringResource(R.string.photo_description, wine.displayName),
                        modifier = Modifier.padding(horizontal = Dimens.l).padding(top = Dimens.s)
                            .fillMaxWidth().height(240.dp),
                        inset = 18.dp,
                    )
                }
                Hero(wine)
                if (webSearch.isEnabled || wine.coverPhotoId != null) {
                    TextButton(
                        onClick = { choosingPicture = true },
                        modifier = Modifier.padding(horizontal = Dimens.s).testTag("action.change_picture"),
                    ) { Text(stringResource(R.string.detail_change_picture)) }
                }
            }
            item {
                WindowCard(
                    wine, viewModel.year,
                    onMoveEnd = { years -> viewModel.updateWine(wine.movingWindowEnd(years), quiet = true) },
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    Button(
                        onClick = { drinking = bottles.first() }, enabled = bottles.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("action.drink"),
                    ) { ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar) }
                    FilledTonalButton(
                        onClick = { navigator.open(AddBottleRoute(wineId = wineId)) },
                        modifier = Modifier.weight(1f).testTag("action.add_bottles"),
                    ) { ActionContent(stringResource(R.string.action_add_bottles), Icons.Filled.Add) }
                }
            }
            if (orders.isNotEmpty()) {
                item { SectionHeader(pluralStringResource(R.plurals.detail_on_order, orders.size, orders.size), Modifier.testTag("detail.orders")) }
                items(orders, key = { "o" + it.id }) { bottle ->
                    OrderedBottleRow(
                        cellar, bottle,
                        onReceive = { viewModel.receive(listOf(bottle.id)) },
                        onCancel = { viewModel.cancelOrder(bottle.id) },
                    )
                }
                item {
                    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        OutlinedButton(
                            onClick = { viewModel.receive(orders.map { it.id }) },
                            modifier = Modifier.testTag("action.order.receive"),
                        ) { ActionContent(stringResource(R.string.action_mark_all_received), Icons.Outlined.LocalShipping) }
                        Text(
                            stringResource(R.string.detail_orders_footer),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (bottles.isNotEmpty() || orders.isEmpty()) {
                item { SectionHeader("${stringResource(R.string.detail_bottles)} (${bottles.size})") }
            }
            if (bottles.isEmpty() && orders.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.detail_no_bottles),
                        Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(bottles, key = { it.id.toString() }) { bottle ->
                BottleRow(
                    cellar, bottle,
                    onDrink = { drinking = bottle },
                    onShowInRack = bottle.slot?.let { slot ->
                        { navigator.open(RackDetailRoute(slot.rackId.toString(), bottle.id.toString())) }
                    },
                    onGone = { viewModel.markGone(bottle.id) },
                    onDelete = { deletingBottle = bottle },
                    onReserve = { reserving = bottle },
                    onFree = { viewModel.reserve(listOf(bottle.id), null) },
                )
            }
            ServingAdvice.advice(wine, viewModel.year)?.let { advice -> item { Serving(advice) } }
            val discoveries = cellar.discoveriesOf(wine.id)
            if (discoveries.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.journal_tasted_elsewhere)) }
                items(discoveries, key = { "d" + it.id }) { discovery ->
                    DiscoveryRow(
                        cellar, viewModel.photos, discovery, showsWine = false,
                        onClick = { navigator.open(DiscoveryEditorRoute(discovery.id.toString())) },
                    )
                }
            }
            item { Details(wine) }
            if (wine.currentPrice != null || webSearch.isEnabled) {
                item {
                    ValueSection(
                        wine, cellar.settings.currency, webSearch.isEnabled,
                        onFindPrice = { findingPrice = true },
                        onSearch = { searchingOnline = true },
                        onRemove = { viewModel.updateWine(wine.copy(currentPrice = null, currentPriceCurrency = null, currentPriceDate = null), quiet = true) },
                    )
                }
            }
            item { MorePhotosSection(viewModel, wine) }
            if (tastings.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_tastings)) }
                items(tastings, key = { "t" + it.id }) { bottle ->
                    val tasting = bottle.tasting ?: return@items
                    ListItem(
                        modifier = Modifier.clickable { navigator.open(TastingRoute(bottle.id.toString())) },
                        headlineContent = { Text(Formats.day(tasting.date)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                RatingStars(tasting.rating)
                                listOfNotNull(tasting.note, tasting.occasion).filter { it.isNotBlank() }.forEach { Text(it) }
                            }
                        },
                    )
                }
            }
            item { Box(Modifier.height(Dimens.xxl)) }
        }
    }

    drinking?.let { bottle ->
        DrinkSheet(
            cellar = cellar,
            bottles = cellar.bottlesOf(bottle.wineId),
            initialBottleId = bottle.id,
            onDismiss = { drinking = null },
            onDrink = { chosen, tasting -> viewModel.drink(chosen.id, tasting) },
        )
    }
    if (confirmDelete && wine != null) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_wine_title),
            body = stringResource(R.string.confirm_delete_wine_body, wine.displayName),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.deleteWine(wine.id); back() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (findingPrice && wine != null) {
        val sizeML = cellar.bottlesOf(wine.id).groupingBy { it.sizeML }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key ?: 750
        WebSearchSheet(
            initialQuery = WebSearchQuery.priceTerms(wine, sizeML, stringResource(R.string.search_price_word)),
            onDismiss = { findingPrice = false },
            price = PriceOptions(sizeML, wine.currentPriceCurrency ?: cellar.settings.currency, wine.currentPrice) { amount, code ->
                cellar.wines[wine.id]?.let { latest ->
                    viewModel.updateWine(
                        latest.copy(currentPrice = amount, currentPriceCurrency = code, currentPriceDate = app.encave.core.ReferenceDates.now()),
                        quiet = true,
                    )
                }
            },
        )
    }
    if (choosingPicture && wine != null) {
        WinePictureSheet(viewModel, wine, onDismiss = { choosingPicture = false })
    }
    if (searchingOnline && wine != null) {
        WebSearchSheet(
            initialQuery = WebSearchQuery.terms(wine, stringResource(R.string.search_wine_word)),
            onDismiss = { searchingOnline = false },
        )
    }
    reserving?.let { bottle ->
        ReserveDialog(
            initial = cellar.reservations.firstOrNull().orEmpty(),
            onKeep = { occasion -> viewModel.reserve(listOf(bottle.id), occasion) },
            onDismiss = { reserving = null },
        )
    }
    deletingBottle?.let { bottle ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_bottle_title),
            body = stringResource(R.string.confirm_delete_bottle_body),
            confirm = stringResource(R.string.action_delete),
            onConfirm = {
                val last = cellar.bottles.values.count { it.wineId == bottle.wineId } == 1
                viewModel.deleteBottle(bottle.id)
                if (last) back()
            },
            onDismiss = { deletingBottle = null },
        )
    }
}

@Composable
private fun Hero(wine: Wine) {
    Column(Modifier.fillMaxWidth().padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
            StyleLabel(wine.color)
            Text(
                wine.vintage?.toString() ?: stringResource(R.string.non_vintage),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(wine.name, style = SerifDisplay, modifier = Modifier.semantics { heading() })
        val origin = listOfNotNull(wine.producer, wine.appellation, wine.region, wine.country).filter { it.isNotBlank() }
        if (origin.isNotEmpty()) {
            Text(origin.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WindowCard(wine: Wine, year: Int, onMoveEnd: (Int) -> Unit) {
    val maturity = Maturity.of(wine, year)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            // The maturity under the title: long labels ("No drinking window") never squeeze it.
            Text(stringResource(R.string.detail_window), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            MaturityLabel(maturity)
            val from = wine.drinkFrom
            val until = wine.drinkUntil
            if (from != null && until != null && from <= until) {
                DrinkingWindowGauge(from, until, year, maturity)
                WindowExplanation(wine, onMoveEnd)
            } else {
                Text(stringResource(R.string.detail_window_missing), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Bar from a little before the window to a little after it, with the window
 * filled and a "now" marker. Read by TalkBack as a sentence; the maturity is
 * also written next to it, so color is never the only cue.
 */
@Composable
fun DrinkingWindowGauge(from: Int, until: Int, year: Int, maturity: Maturity, modifier: Modifier = Modifier) {
    val start = minOf(from, year) - 1
    val end = maxOf(until, year) + 1
    val track = MaterialTheme.colorScheme.surfaceVariant
    val window = maturity.tint
    val marker = MaterialTheme.colorScheme.onSurface
    val description = stringResource(R.string.detail_window_description, from, until, stringResource(maturity.labelRes))
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val span = (end - start + 1).toFloat()
            fun x(yearValue: Float) = size.width * (yearValue - start) / span
            val barTop = size.height * 0.3f
            val barHeight = size.height * 0.4f
            val radius = CornerRadius(barHeight / 2, barHeight / 2)
            drawRoundRect(track, Offset(0f, barTop), Size(size.width, barHeight), radius)
            drawRoundRect(window, Offset(x(from.toFloat()), barTop), Size(x(until + 1f) - x(from.toFloat()), barHeight), radius)
            // "Now" marker: a vertical line with a ring, drawn over the bar.
            val nowX = x(year + 0.5f)
            drawLine(marker, Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(marker, radius = 5.dp.toPx(), center = Offset(nowX, size.height / 2), style = Stroke(2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(from.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                "${stringResource(R.string.detail_window_now)} · $year",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(until.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** Names the occasion a bottle is kept for. */
@Composable
private fun ReserveDialog(initial: String, onKeep: (String) -> Unit, onDismiss: () -> Unit) {
    var occasion by remember { mutableStateOf(initial) }
    // Full width with margins: a text field in a default-width dialog never settles in tests.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.reserve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(R.string.reserve_body))
                androidx.compose.material3.OutlinedTextField(
                    value = occasion, onValueChange = { occasion = it },
                    label = { Text(stringResource(R.string.field_occasion)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reserve.field"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onKeep(occasion); onDismiss() }, enabled = occasion.isNotBlank(),
                modifier = Modifier.testTag("reserve.keep"),
            ) { Text(stringResource(R.string.action_keep_bottle)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Why the window is what it is, and a quick way to move its end. */
@Composable
private fun WindowExplanation(wine: Wine, onMoveEnd: (Int) -> Unit) {
    val text = when (val source = wine.windowSource) {
        is WindowSource.Estimated -> stringResource(R.string.window_estimated, source.yearsAfterHarvest, source.yearsAtBest)
        else -> stringResource(R.string.window_entered)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            onClick = { onMoveEnd(-1) },
            enabled = (wine.drinkUntil ?: 0) > (wine.drinkFrom ?: 0),
            modifier = Modifier.testTag("action.window.sooner"),
        ) { ActionContent(stringResource(R.string.action_drink_sooner), Icons.Outlined.RemoveCircleOutline) }
        TextButton(
            onClick = { onMoveEnd(1) },
            modifier = Modifier.testTag("action.window.longer"),
        ) { ActionContent(stringResource(R.string.action_keep_longer), Icons.Outlined.AddCircleOutline) }
    }
}

/** A bottle bought but not delivered yet. */
@Composable
private fun OrderedBottleRow(cellar: Cellar, bottle: Bottle, onReceive: () -> Unit, onCancel: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val markLabel = stringResource(R.string.action_mark_received)
    val cancelLabel = stringResource(R.string.action_cancel_order)
    val details = listOfNotNull(
        bottle.purchasePrice?.let { Formats.money(it, bottle.currency ?: cellar.settings.currency) },
        bottle.purchasePlace?.takeIf { it.isNotBlank() },
        if (bottle.sizeML != 750) sizeLabel(bottle.sizeML) else null,
    )
    ListItem(
        modifier = Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(markLabel) { onReceive(); true },
                CustomAccessibilityAction(cancelLabel) { onCancel(); true },
            )
        },
        headlineContent = { ArrivalLabel(bottle.expectedAt, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (details.isEmpty()) null else ({ Text(details.joinToString(" · ")) }),
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(markLabel) },
                        leadingIcon = { Icon(Icons.Outlined.LocalShipping, contentDescription = null) },
                        onClick = { menu = false; onReceive() },
                    )
                    DropdownMenuItem(
                        text = { Text(cancelLabel, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onCancel() },
                    )
                }
            }
        },
    )
}

@Composable
private fun BottleRow(
    cellar: Cellar,
    bottle: Bottle,
    onDrink: () -> Unit,
    onShowInRack: (() -> Unit)?,
    onGone: () -> Unit,
    onDelete: () -> Unit,
    onReserve: () -> Unit,
    onFree: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val details = buildList {
        add(sizeLabel(bottle.sizeML))
        bottle.purchasePrice?.let { add(Formats.money(it, bottle.currency)) }
        bottle.purchasePlace?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(stringResource(R.string.detail_added, Formats.day(bottle.addedAt)))
    }
    ListItem(
        modifier = if (bottle.reservedFor != null) Modifier.testTag("bottle.reserved") else Modifier,
        headlineContent = { Text(cellar.displayLocation(bottle) ?: stringResource(R.string.location_none)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(details.joinToString(" · "))
                bottle.reservedFor?.let { occasion ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.reserved_for, occasion), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_drink)) },
                        leadingIcon = { Icon(Icons.Outlined.LocalBar, contentDescription = null) },
                        onClick = { menu = false; onDrink() },
                    )
                    if (onShowInRack != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_show_in_rack)) },
                            leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                            onClick = { menu = false; onShowInRack() },
                        )
                    }
                    if (bottle.reservedFor == null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_keep_for_occasion)) },
                            leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null) },
                            onClick = { menu = false; onReserve() },
                            modifier = Modifier.testTag("action.reserve"),
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_free_bottle)) },
                            leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null) },
                            onClick = { menu = false; onFree() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_mark_gone)) },
                        leadingIcon = { Icon(Icons.Outlined.VolunteerActivism, contentDescription = null) },
                        onClick = { menu = false; onGone() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}

@Composable
private fun Serving(advice: ServingAdvice) {
    SectionHeader(stringResource(R.string.detail_serving))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Thermostat, contentDescription = null) },
        overlineContent = { Text(stringResource(R.string.serving_temperature)) },
        headlineContent = { Text(stringResource(R.string.serving_temperature_value, advice.minTemperature, advice.maxTemperature)) },
    )
    ListItem(
        leadingContent = { Icon(Icons.Outlined.WineBar, contentDescription = null) },
        overlineContent = { Text(stringResource(R.string.serving_decanting)) },
        headlineContent = {
            Text(advice.decantMinutes?.let { stringResource(R.string.serving_decant_value, it) } ?: stringResource(R.string.serving_no_decant))
        },
    )
}

/** The price of a bottle today, and the web searches. */
@Composable
private fun ValueSection(
    wine: Wine,
    cellarCurrency: String,
    canSearch: Boolean,
    onFindPrice: () -> Unit,
    onSearch: () -> Unit,
    onRemove: () -> Unit,
) {
    SectionHeader(stringResource(R.string.detail_value))
    Column(Modifier.fillMaxWidth()) {
        wine.currentPrice?.let { price ->
            ListItem(
                overlineContent = { Text(stringResource(R.string.price_today)) },
                headlineContent = { Text(Formats.money(price, wine.currentPriceCurrency ?: cellarCurrency)) },
                supportingContent = { wine.currentPriceDate?.let { date -> Text(stringResource(R.string.price_set_on, Formats.day(date))) } },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            if (canSearch) {
                OutlinedButton(onClick = onFindPrice, modifier = Modifier.testTag("action.find_price")) {
                    Text(stringResource(R.string.search_find_price))
                }
                OutlinedButton(onClick = onSearch, modifier = Modifier.testTag("action.search_online")) {
                    Text(stringResource(R.string.search_online))
                }
            }
        }
        if (wine.currentPrice != null) {
            TextButton(onClick = onRemove, modifier = Modifier.padding(horizontal = Dimens.s)) {
                Text(stringResource(R.string.price_remove))
            }
        }
        Text(
            stringResource(R.string.detail_value_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
        )
    }
}

@Composable
private fun Details(wine: Wine) {
    val rows = listOfNotNull(
        wine.producer?.let { R.string.field_producer to it },
        wine.appellation?.let { R.string.field_appellation to it },
        wine.region?.let { R.string.field_region to it },
        wine.country?.let { R.string.field_country to it },
        wine.grapes.takeIf { it.isNotEmpty() }?.let { R.string.field_grapes to it.joinToString(", ") },
        wine.tags?.takeIf { it.isNotEmpty() }?.let { R.string.field_tags to it.joinToString(", ") },
        R.string.field_style to stringResource(wine.color.labelRes),
        wine.notes?.takeIf { it.isNotBlank() }?.let { R.string.field_notes to it },
    ).filter { it.second.isNotBlank() }
    SectionHeader(stringResource(R.string.detail_details))
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, value) ->
            ListItem(
                overlineContent = { Text(stringResource(label)) },
                headlineContent = { Text(value) },
            )
        }
    }
}
