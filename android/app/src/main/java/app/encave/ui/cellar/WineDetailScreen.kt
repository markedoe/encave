package app.encave.ui.cellar

import app.encave.ui.components.ActionButtonPair
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
import androidx.compose.material.icons.outlined.OpenWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
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
import app.encave.core.bottleCount
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
import app.encave.ui.components.EmptyState
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.MaturityLabel
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.StyleLabel
import app.encave.ui.components.labelRes
import app.encave.ui.components.sizeLabel
import app.encave.ui.components.tint
import app.encave.core.bottleToOpen
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.drink.rememberDrinking
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
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import app.encave.core.WineAttribute
import app.encave.core.winesSharing
import app.encave.ui.add.WineFormField
import app.encave.ui.components.WineColorDot

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
    // Deleted while shown (here, beside the list, or on another device): back to the list
    // rather than an empty page.
    var shown by remember(wineId) { mutableStateOf(false) }
    LaunchedEffect(wine != null) {
        if (wine != null) shown = true else if (shown) back()
    }
    var menu by remember { mutableStateOf(false) }
    // With Undo, or asked first for a wine with several bottles.
    val deleteWines = rememberWineDeletion(cellar, viewModel)
    var drinking by rememberDrinking()
    // Kept when the activity is recreated (rotation, folding, dark mode), as the sheets below.
    var reservingId by rememberSaveable { mutableStateOf<String?>(null) }
    /** A bottle being moved to a place, another rack, or out of the racks. */
    var movingBottle by remember { mutableStateOf<UUID?>(null) }
    var findingPrice by rememberSaveable { mutableStateOf(false) }
    var searchingOnline by rememberSaveable { mutableStateOf(false) }
    var choosingPicture by rememberSaveable { mutableStateOf(false) }
    var showingPhoto by rememberSaveable { mutableStateOf(false) }
    /** "Search the web" on one detail: its value only, shown and editable before it is sent. */
    var searchingDetail by rememberSaveable { mutableStateOf<String?>(null) }
    /** A bottle whose documents are shown, and whether the file picker opens at once. */
    var bottleDocuments by remember { mutableStateOf<Pair<UUID, Boolean>?>(null) }
    val webSearch by webSearchPreferences().settings.collectAsStateWithLifecycle()
    val edit: (WineFormField) -> Unit = { field -> navigator.open(EditWineRoute(wineId, field.name)) }

    /** Where the header (name, producer) ends in the list, to know when it is under the top bar. */
    var heroEnd by remember(wineId) { mutableFloatStateOf(Float.MAX_VALUE) }
    var listTop by remember { mutableFloatStateOf(0f) }
    val heroGone = heroEnd < listTop
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                // The name is the serif title right under the bar: here only once it has scrolled away.
                title = {
                    androidx.compose.animation.AnimatedVisibility(heroGone, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                        Text(wine?.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
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
                                    // With Undo (asked first with several bottles); the page closes once the wine is gone.
                                    onClick = { menu = false; deleteWines(setOf(wine.id)) },
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
        listTop = with(LocalDensity.current) { padding.calculateTopPadding().toPx() }
        LazyColumn(Modifier.fillMaxSize().testTag("wine.detail"), contentPadding = padding) {
            item {
                if (viewModel.photos.hasPicture(wine)) {
                    app.encave.ui.components.WineImage(
                        wine,
                        contentDescription = stringResource(R.string.photo_description, wine.displayName),
                        modifier = Modifier.padding(horizontal = Dimens.l).padding(top = Dimens.s)
                            .fillMaxWidth().height(240.dp)
                            .clickable(onClickLabel = stringResource(R.string.photo_show_large), role = Role.Button) { showingPhoto = true }
                            .testTag("detail.photo"),
                        inset = 18.dp,
                    )
                }
                Box(Modifier.onGloballyPositioned { heroEnd = it.positionInParent().y + it.size.height }) { Hero(wine) }
                if (webSearch.isEnabled || wine.coverPhotoId != null) {
                    TextButton(
                        onClick = { choosingPicture = true },
                        modifier = Modifier.padding(horizontal = Dimens.s).testTag("action.change_picture"),
                    ) { Text(stringResource(R.string.detail_change_picture)) }
                }
            }
            item {
                ActionButtonPair(
                    Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
                    first = { width ->
                        Button(
                            // A free bottle first: one kept for an occasion only when no other is left.
                            onClick = { drinking = bottles.bottleToOpen?.id?.toString() }, enabled = bottles.isNotEmpty(),
                            modifier = width.testTag("action.drink"),
                        ) { ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar) }
                    },
                    second = { width ->
                        FilledTonalButton(
                            onClick = { navigator.open(AddBottleRoute(wineId = wineId)) },
                            modifier = width.testTag("action.add_bottles"),
                        ) { ActionContent(stringResource(R.string.action_add_bottles), Icons.Filled.Add) }
                    },
                )
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
                    onDrink = { drinking = bottle.id.toString() },
                    onShowInRack = bottle.slot?.let { slot ->
                        { navigator.open(RackDetailRoute(slot.rackId.toString(), bottle.id.toString())) }
                    },
                    onMove = { movingBottle = bottle.id },
                    onGone = { viewModel.markGone(bottle.id) },
                    // With Undo; the wine goes with its last bottle, and the page closes.
                    onDelete = { viewModel.deleteBottle(bottle.id) },
                    onReserve = { reservingId = bottle.id.toString() },
                    onFree = { viewModel.reserve(listOf(bottle.id), null) },
                    onDocuments = { importing -> bottleDocuments = bottle.id to importing },
                )
            }
            // The same order as on Apple devices: where the bottles are first, then when to
            // drink them, how to serve them, their value, the details, photos and documents.
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
            item {
                WindowCard(
                    wine, viewModel.year,
                    onMoveEnd = { years -> viewModel.updateWine(wine.movingWindowEnd(years), quiet = true) },
                    onEdit = { edit(WineFormField.Window) },
                )
            }
            ServingAdvice.advice(wine, viewModel.year)?.let { advice -> item { Serving(advice, onEdit = { edit(WineFormField.Style) }) } }
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
            item {
                Details(
                    cellar, wine,
                    onExplore = { attribute -> navigator.open(attribute.route(wine.id)) },
                    onEdit = edit,
                    onSearchWeb = if (webSearch.isEnabled) ({ value -> searchingDetail = value }) else null,
                )
            }
            item {
                // Typed by the person; saved on the latest version of the wine (a sync may have changed it meanwhile).
                CriticScoresSection(wine.criticScores.orEmpty()) { change ->
                    viewModel.cellar.value.wines[wine.id]?.let { latest ->
                        viewModel.updateWine(latest.copy(criticScores = change(latest.criticScores.orEmpty()).ifEmpty { null }), quiet = true)
                    }
                }
            }
            item { MorePhotosSection(viewModel, wine) }
            item { WineDocumentsSection(viewModel, wine) }
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
            if (tastings.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.detail_tastings)) }
                items(tastings, key = { "t" + it.id }) { bottle ->
                    val tasting = bottle.tasting ?: return@items
                    ListItem(
                        modifier = Modifier.clickable { navigator.open(TastingRoute(bottle.id.toString())) },
                        headlineContent = { Text(Formats.day(tasting.date)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                app.encave.ui.components.RatingDisplay(tasting.rating, tasting.score100)
                                listOfNotNull(tasting.note, tasting.occasion).filter { it.isNotBlank() }.forEach { Text(it) }
                            }
                        },
                    )
                }
            }
            item { Box(Modifier.height(Dimens.xxl)) }
        }
    }

    drinking?.let { id ->
        DrinkSheet(
            cellar = cellar,
            bottleId = id,
            onDismiss = { drinking = null },
            onDrink = { chosen, tasting -> viewModel.drink(chosen.id, tasting) },
        )
    }
    if (findingPrice && wine != null) {
        val sizeML = cellar.bottlesOf(wine.id).groupingBy { it.sizeML }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key ?: 750
        WebSearchSheet(
            initialQuery = WebSearchQuery.priceTerms(wine, sizeML, stringResource(R.string.search_price_word)),
            onDismiss = { findingPrice = false },
            price = PriceOptions(
                sizeML, wine.currentPriceCurrency ?: cellar.settings.currency, wine.currentPrice, wine.name, wine.vintage,
                producer = wine.producer,
                onChangeSize = { newSize, amount, code -> viewModel.changeSize(wine.id, sizeML, newSize, amount, code) },
            ) { amount, code ->
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
    searchingDetail?.let { terms ->
        WebSearchSheet(initialQuery = terms, onDismiss = { searchingDetail = null })
    }
    if (showingPhoto && wine != null) {
        val photoId = listOfNotNull(wine.coverPhotoId, wine.photoId).firstOrNull { viewModel.photos.exists(it) }
        photoId?.let { LargePhoto(viewModel.photos, it, onRemove = null, onDismiss = { showingPhoto = false }) }
    }
    bottleDocuments?.let { (bottleId, importing) ->
        BottleDocumentsSheet(viewModel, bottleId, startPicking = importing, onDismiss = { bottleDocuments = null })
    }
    movingBottle?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id == id } }?.let { bottle ->
        app.encave.ui.racks.MoveBottleSheet(viewModel, cellar, bottle, onDismiss = { movingBottle = null })
    }
    reservingId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }?.let { bottle ->
        ReserveDialog(
            initial = cellar.reservations.firstOrNull().orEmpty(),
            onKeep = { occasion -> viewModel.reserve(listOf(bottle.id), occasion) },
            onDismiss = { reservingId = null },
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
            Text(origin.joinToString(app.encave.ui.components.SEPARATOR), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowCard(wine: Wine, year: Int, onMoveEnd: (Int) -> Unit, onEdit: () -> Unit) {
    val maturity = Maturity.of(wine, year)
    val from = wine.drinkFrom
    val until = wine.drinkUntil
    val hasWindow = from != null && until != null && from <= until
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(bottom = Dimens.s)) {
            // The title, maturity and gauge edit the window; the buttons below move its end.
            Box {
                Column(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.detail_action_window),
                            onLongClickLabel = stringResource(R.string.detail_more_actions),
                            onLongClick = { menu = true },
                            onClick = onEdit,
                        )
                        .testTag("detail.edit.window")
                        .padding(start = Dimens.l, end = Dimens.l, top = Dimens.l),
                    verticalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    // The maturity under the title: long labels ("No drinking window") never squeeze it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.detail_window), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        Icon(
                            Icons.Outlined.Edit, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                    }
                    MaturityLabel(maturity)
                    if (hasWindow) {
                        DrinkingWindowGauge(from!!, until!!, year, maturity)
                    } else {
                        Text(stringResource(R.string.detail_window_missing), style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(Modifier.height(Dimens.xs))
                }
                DetailMenu(
                    expanded = menu, onDismiss = { menu = false },
                    copyText = if (hasWindow) stringResource(R.string.detail_window_range, from!!, until!!) else null,
                    onEdit = onEdit,
                )
            }
            if (hasWindow) {
                Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    WindowExplanation(wine, onMoveEnd)
                }
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

/** Names the occasion a bottle (or, [plural], the bottles of several wines) is kept for. */
@Composable
internal fun ReserveDialog(initial: String, onKeep: (String) -> Unit, onDismiss: () -> Unit, plural: Boolean = false) {
    var occasion by rememberSaveable { mutableStateOf(initial) }
    // Full width with margins: a text field in a default-width dialog never settles in tests.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.reserve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(if (plural) R.string.reserve_body_plural else R.string.reserve_body))
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
    onMove: () -> Unit,
    onGone: () -> Unit,
    onDelete: () -> Unit,
    onReserve: () -> Unit,
    onFree: () -> Unit,
    /** Opens the bottle's documents; true opens the file picker at once. */
    onDocuments: (Boolean) -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                bottle.attachments?.size?.let { count ->
                    DocumentCount(count, Modifier.clickable(onClickLabel = stringResource(R.string.action_documents)) { onDocuments(false) }.padding(Dimens.xs))
                }
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move_ellipsis)) },
                            leadingIcon = { Icon(Icons.Outlined.OpenWith, contentDescription = null) },
                            onClick = { menu = false; onMove() },
                            modifier = Modifier.testTag("action.move_bottle"),
                        )
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
                        BottleDocumentMenuItems(bottle.attachments != null, onDocuments) { menu = false }
                        DropdownMenuItem(
                            text = { MenuText(stringResource(R.string.action_mark_gone), stringResource(R.string.action_mark_gone_detail)) },
                            leadingIcon = { Icon(Icons.Outlined.VolunteerActivism, contentDescription = null) },
                            onClick = { menu = false; onGone() },
                            modifier = Modifier.testTag("action.bottle.gone"),
                        )
                        DropdownMenuItem(
                            text = {
                                MenuText(
                                    stringResource(R.string.action_delete_bottle), stringResource(R.string.action_delete_bottle_detail),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                            modifier = Modifier.testTag("action.bottle.delete"),
                        )
                    }
                }
            }
        },
    )
}

/** A menu item's title with a line saying what it is for. */
@Composable
internal fun MenuText(title: String, detail: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column {
        Text(title, color = color)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Worked out from the style and the vintage: a tap edits the style. */
@Composable
private fun Serving(advice: ServingAdvice, onEdit: () -> Unit) {
    SectionHeader(stringResource(R.string.detail_serving))
    val action = stringResource(R.string.detail_action_serving)
    DetailItem(
        label = stringResource(R.string.serving_temperature),
        value = stringResource(R.string.serving_temperature_value, advice.minTemperature, advice.maxTemperature),
        actionLabel = action, explores = false, onClick = onEdit, onEdit = onEdit,
        leading = { Icon(Icons.Outlined.Thermostat, contentDescription = null) },
    )
    DetailItem(
        label = stringResource(R.string.serving_decanting),
        value = advice.decantMinutes?.let { stringResource(R.string.serving_decant_value, it) } ?: stringResource(R.string.serving_no_decant),
        actionLabel = action, explores = false, onClick = onEdit, onEdit = onEdit,
        copyText = null,
        leading = { Icon(Icons.Outlined.WineBar, contentDescription = null) },
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
            // Opens the price sheet, which also takes a price typed in without searching.
            DetailItem(
                label = stringResource(R.string.price_today),
                value = Formats.money(price, wine.currentPriceCurrency ?: cellarCurrency),
                actionLabel = stringResource(R.string.detail_action_price),
                explores = false,
                onClick = onFindPrice,
                onEdit = onFindPrice,
                modifier = Modifier.testTag("detail.edit.price"),
                supporting = wine.currentPriceDate?.let { date -> stringResource(R.string.price_set_on, Formats.day(date)) },
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

/**
 * The wine's details. Those other wines can share (producer, vintage, style, origin, each
 * grape and tag) list them; the others edit the wine. Touch and hold for Copy, Edit and,
 * for the producer and the appellation when web search is on, Search the web.
 */
@Composable
private fun Details(
    cellar: Cellar,
    wine: Wine,
    onExplore: (WineAttribute) -> Unit,
    onEdit: (WineFormField) -> Unit,
    onSearchWeb: ((String) -> Unit)?,
) {
    fun distinct(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }
    val attributes = listOfNotNull(
        wine.color?.let { R.string.field_style to WineAttribute.Color(it) },
        wine.producer?.let { R.string.field_producer to WineAttribute.Producer(it) },
        wine.vintage?.let { R.string.field_vintage to WineAttribute.Vintage(it) },
        wine.appellation?.let { R.string.field_appellation to WineAttribute.Appellation(it) },
        wine.region?.let { R.string.field_region to WineAttribute.Region(it) },
        wine.country?.let { R.string.field_country to WineAttribute.Country(it) },
    ) + distinct(wine.grapes).map { R.string.field_grape to WineAttribute.Grape(it) } +
        distinct(wine.tags.orEmpty()).map { R.string.field_tag to WineAttribute.Tag(it) }
    SectionHeader(stringResource(R.string.detail_details))
    Column(Modifier.fillMaxWidth()) {
        if (wine.color == null) {
            DetailItem(
                label = stringResource(R.string.field_style), value = stringResource(wine.color.labelRes),
                actionLabel = stringResource(R.string.detail_action_style), explores = false,
                onClick = { onEdit(WineFormField.Style) }, onEdit = { onEdit(WineFormField.Style) }, copyText = null,
            )
        }
        attributes.forEach { (label, attribute) ->
            val value = attribute.displayValue()
            if (value.isBlank()) return@forEach
            // No other wine shares it: a list of this wine alone would lead nowhere, so it edits.
            val sharing = cellar.winesSharing(attribute)
            val explores = sharing.any { it.id != wine.id }
            DetailItem(
                label = stringResource(label),
                value = value,
                actionLabel = if (explores) attribute.actionLabel() else stringResource(R.string.detail_action_edit_detail),
                explores = explores,
                onClick = { if (explores) onExplore(attribute) else onEdit(attribute.field) },
                onEdit = { onEdit(attribute.field) },
                modifier = Modifier.testTag((if (explores) "detail.explore." else "detail.edit.") + attribute.kind.name.lowercase()),
                onSearchWeb = if (attribute.isSearchable) onSearchWeb?.let { search -> { search(value) } } else null,
                supporting = if (explores) sharedCount(sharing.bottleCount) else null,
                leading = if (attribute is WineAttribute.Color) ({ WineColorDot(attribute.color, size = 16.dp) }) else null,
            )
        }
        // The details under "More details" in the form: a tap opens it there.
        wineDetailRows(wine, androidx.compose.ui.platform.LocalResources.current).forEach { (label, value) ->
            DetailItem(
                label = stringResource(label), value = value,
                actionLabel = stringResource(R.string.detail_action_more_details), explores = false,
                onClick = { onEdit(WineFormField.Details) }, onEdit = { onEdit(WineFormField.Details) },
                modifier = Modifier.testTag("detail.edit.details"),
            )
        }
        wine.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            DetailItem(
                label = stringResource(R.string.field_notes), value = notes,
                actionLabel = stringResource(R.string.detail_action_notes), explores = false,
                onClick = { onEdit(WineFormField.Notes) }, onEdit = { onEdit(WineFormField.Notes) },
                modifier = Modifier.testTag("detail.edit.notes"),
            )
        }
        wine.aiCompletedAt?.let { completedAt ->
            app.encave.ui.add.AiCompletedCaption(
                completedAt, Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).testTag("detail.ai_completed"),
            )
        }
    }
}

/**
 * One detail of the wine's page: a tap explores ([explores], with a chevron) or edits (with
 * a pencil); touch and hold for Copy, Edit and, when given, Search the web.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailItem(
    label: String,
    value: String,
    actionLabel: String,
    explores: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    copyText: String? = value,
    onSearchWeb: (() -> Unit)? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = modifier.combinedClickable(
                role = Role.Button,
                onClickLabel = actionLabel,
                onLongClickLabel = stringResource(R.string.detail_more_actions),
                onLongClick = { menu = true },
                onClick = onClick,
            ),
            leadingContent = leading,
            overlineContent = { Text(label) },
            headlineContent = { Text(value) },
            supportingContent = if (supporting == null) null else ({ Text(supporting) }),
            trailingContent = {
                Icon(
                    if (explores) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (explores) 24.dp else 20.dp),
                )
            },
        )
        DetailMenu(expanded = menu, onDismiss = { menu = false }, copyText = copyText, onEdit = onEdit, onSearchWeb = onSearchWeb)
    }
}

/** Copy, Edit and Search the web for one detail. */
@Composable
private fun DetailMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    copyText: String?,
    onEdit: () -> Unit,
    onSearchWeb: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (copyText != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                onClick = {
                    onDismiss()
                    // Android shows that the text was copied.
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(copyText, copyText))
                },
                modifier = Modifier.testTag("detail.menu.copy"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            onClick = { onDismiss(); onEdit() },
        )
        if (onSearchWeb != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_search_web)) },
                leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                onClick = { onDismiss(); onSearchWeb() },
            )
        }
    }
}
