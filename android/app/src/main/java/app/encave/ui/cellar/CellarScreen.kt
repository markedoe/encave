package app.encave.ui.cellar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import app.encave.ui.navigation.InsightsRoute
import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.CellarSort
import app.encave.core.Maturity
import app.encave.core.statistics
import app.encave.core.WineColor
import app.encave.core.WineGroup
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.EmptyState
import androidx.compose.ui.res.pluralStringResource
import app.encave.ui.components.Formats
import androidx.compose.ui.platform.LocalDensity
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.UuidSetSaver
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.WineGroupRow
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.components.sampleLabels
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.DiscoveryEditorRoute
import app.encave.ui.navigation.EditWineRoute
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Cellar list and wine detail: one pane on phones, side by side on tablets and foldables. */
@Composable
fun CellarScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    // Wide screens can show the cellar as a sortable table (remembered).
    val wide = androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val table by viewModel.preferences.cellarTable.collectAsStateWithLifecycle()
    if (wide && table) {
        CellarTableScreen(viewModel, navigator, onShowList = { viewModel.preferences.setCellarTable(false) })
        return
    }
    val paneNavigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val selectedId = paneNavigator.currentDestination?.contentKey
    val twoPane = paneNavigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
        paneNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

    NavigableListDetailPaneScaffold(
        navigator = paneNavigator,
        listPane = {
            AnimatedPane {
                CellarList(
                    viewModel, navigator,
                    onShowTable = if (wide) ({ viewModel.preferences.setCellarTable(true) }) else null,
                    selectedId = if (twoPane) selectedId else null,
                    onOpen = { id -> scope.launch { paneNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                if (selectedId != null) {
                    WineDetailScreen(
                        viewModel, navigator, selectedId,
                        showBack = !twoPane,
                        onBack = { scope.launch { paneNavigator.navigateBack() } },
                        showMessages = !twoPane,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.cellar_select_wine),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Dimens.xl),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun CellarList(
    viewModel: AppViewModel,
    navigator: AppNavigator,
    onShowTable: (() -> Unit)?,
    selectedId: String?,
    onOpen: (String) -> Unit,
) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val listState = rememberLazyListState()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var drinking by remember { mutableStateOf<WineGroup?>(null) }
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isEmpty = cellar.bottlesInCellar.isEmpty() && cellar.bottlesOnOrder.isEmpty()
    val orders = remember(cellar) { cellar.orderGroups() }
    val showOrders = orders.isNotEmpty() && !filter.isActive && filter.query.isEmpty()
    val wineTags = remember(cellar) { cellar.wineTags }
    val reservations = remember(cellar) { cellar.reservations }
    val wineGrapes = remember(cellar) { cellar.wineGrapes }
    // Only the styles and maturities of bottles in the cellar get a filter chip.
    val presentStats = remember(cellar, viewModel.year) { cellar.statistics(viewModel.year) }
    val presentColors = remember(presentStats) { presentStats.byColor.mapNotNull { it.color }.toSet() }
    val presentMaturities = remember(presentStats) { presentStats.byMaturity.filterValues { it > 0 }.keys }
    // Choosing several wines: a contextual top bar acts on all of them, with one Undo.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable(stateSaver = UuidSetSaver) { mutableStateOf(setOf<java.util.UUID>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var reservingPicked by remember { mutableStateOf(false) }
    var namingSearch by remember { mutableStateOf(false) }
    var confirmSample by remember { mutableStateOf(false) }
    val shared by viewModel.sharedWithDevices.collectAsStateWithLifecycle()
    fun endSelection() {
        selecting = false
        picked = emptySet()
    }
    androidx.activity.compose.BackHandler(selecting) { endSelection() }
    // Wines that left the list (filter, deleted elsewhere) are no longer ticked.
    val shownPicked = picked.filter { id -> groups.any { it.id == id } }.toSet()

    Scaffold(
        topBar = {
            if (selecting) {
                val allIds = groups.map { it.id }.toSet()
                SelectionTopBar(
                    count = shownPicked.size,
                    racks = cellar.racks,
                    allSelected = allIds.isNotEmpty() && shownPicked.containsAll(allIds),
                    onSelectAll = { all -> picked = if (all) allIds else emptySet() },
                    onClose = ::endSelection,
                    onPlace = { rack -> viewModel.placeWines(shownPicked, rack.id); endSelection() },
                    onTakeOut = { viewModel.unplaceWines(shownPicked); endSelection() },
                    onReserve = { reservingPicked = true },
                    onGone = {
                        // The bottles shown for these wines (those the filters keep), with one Undo.
                        viewModel.markGone(groups.filter { it.id in shownPicked }.flatMap { it.bottles }.map { it.id }.toSet())
                        endSelection()
                    },
                    onDelete = { confirmBulkDelete = true },
                )
            } else {
                CellarSearchBar(
                    cellar = cellar,
                    query = filter.query,
                    onQueryChange = { viewModel.filter.value = filter.copy(query = it) },
                    expanded = searchExpanded,
                    onExpandedChange = { searchExpanded = it },
                    onOpen = { id ->
                        searchExpanded = false
                        onOpen(id)
                    },
                    onSettings = { navigator.open(SettingsRoute) },
                    onShowTable = onShowTable,
                )
            }
        },
        floatingActionButton = {
            if (!isEmpty && !selecting) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    SmallFloatingActionButton(onClick = { navigator.open(DiscoveryEditorRoute()) }) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = stringResource(R.string.discovery_new_long))
                    }
                    ExtendedFloatingActionButton(
                        modifier = Modifier.testTag("action.fab_add"),
                        onClick = { navigator.open(AddBottleRoute()) },
                        expanded = fabExpanded,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_add_bottle)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        when {
            !loaded -> Box(Modifier.padding(padding))
            isEmpty -> EmptyState(
                icon = Icons.Outlined.WineBar,
                title = stringResource(R.string.cellar_empty_title),
                body = stringResource(R.string.cellar_empty_body),
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = { navigator.open(AddBottleRoute()) }, modifier = Modifier.testTag("action.empty_add")) {
                    ActionContent(stringResource(R.string.action_add_bottle), Icons.Filled.Add)
                }
                // Only when everything is empty (no racks or discoveries either) and no other device shares the cellar.
                if (viewModel.canLoadSample(cellar, shared)) {
                    OutlinedButton(onClick = { confirmSample = true }) {
                        Text(stringResource(R.string.action_load_sample))
                    }
                }
                TextButton(onClick = { navigator.open(DiscoveryEditorRoute()) }) {
                    Text(stringResource(R.string.discovery_new_long))
                }
            }
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                FilterBar(
                    colors = filter.colors,
                    maturities = filter.maturities,
                    presentColors = presentColors,
                    presentMaturities = presentMaturities,
                    tags = wineTags,
                    tag = filter.tag,
                    grapes = wineGrapes,
                    grape = filter.grape,
                    onToggleGrape = { g ->
                        viewModel.filter.value = filter.copy(grape = if (filter.grape.equals(g, ignoreCase = true)) null else g)
                    },
                    reservations = reservations,
                    reservation = filter.reservation,
                    onToggleReservation = { r ->
                        viewModel.filter.value = filter.copy(reservation = if (filter.reservation.equals(r, ignoreCase = true)) null else r)
                    },
                    sort = sort,
                    onToggleColor = { c ->
                        viewModel.filter.value = filter.copy(colors = if (c in filter.colors) filter.colors - c else filter.colors + c)
                    },
                    onToggleMaturity = { m ->
                        viewModel.filter.value = filter.copy(
                            maturities = if (m in filter.maturities) filter.maturities - m else filter.maturities + m,
                        )
                    },
                    onToggleTag = { t -> viewModel.filter.value = filter.copy(tag = if (filter.tag == t) null else t) },
                    onSort = { viewModel.sort.value = it },
                    onSelect = { selecting = true },
                    savedSearches = cellar.settings.savedSearches.orEmpty(),
                    current = filter,
                    onApplySearch = { search ->
                        viewModel.filter.value = if (filter == search.filter) app.encave.core.CellarFilter() else search.filter
                    },
                    onDeleteSearch = { viewModel.deleteSearch(it.id) },
                    onSaveSearch = if (filter.isActive || filter.query.isNotBlank()) ({ namingSearch = true }) else null,
                )
                if (groups.isEmpty() && !showOrders) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(R.string.cellar_no_match),
                        body = stringResource(R.string.cellar_no_match_body),
                    ) {
                        TextButton(onClick = { viewModel.filter.value = app.encave.core.CellarFilter() }) {
                            Text(stringResource(R.string.action_clear_filters))
                        }
                    }
                } else {
                    val scope = rememberCoroutineScope()
                    val showsSummary = filter.query.isEmpty() && !selecting
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize().testTag("cellar.list"),
                    ) {
                        if (showsSummary) {
                            item(key = "summary") {
                                CellarSummary(
                                    cellar = cellar,
                                    year = viewModel.year,
                                    filter = filter,
                                    onBottles = { navigator.open(InsightsRoute) },
                                    onWines = {
                                        viewModel.filter.value = app.encave.core.CellarFilter()
                                        // The first wine, just under the figures.
                                        scope.launch { listState.animateScrollToItem(1) }
                                    },
                                    onSoon = { viewModel.filter.value = it },
                                )
                            }
                        }
                        if (showOrders && !selecting) {
                            item(key = "orders.header") { app.encave.ui.components.SectionHeader(stringResource(R.string.section_on_order)) }
                            items(orders, key = { "order." + it.id }) { group ->
                                OrderRow(
                                    group = group, cellar = cellar, year = viewModel.year,
                                    selected = group.id.toString() == selectedId,
                                    onOpen = { onOpen(group.id.toString()) },
                                    onReceive = { viewModel.receive(group.bottles.map { it.id }) },
                                    modifier = Modifier.animateItem().testTag("order.row"),
                                )
                                HorizontalDivider(Modifier.padding(start = 56.dp))
                            }
                            if (groups.isNotEmpty()) {
                                item(key = "cellar.header") { app.encave.ui.components.SectionHeader(stringResource(R.string.nav_cellar)) }
                            }
                        }
                        if (selecting) {
                            items(groups, key = { "select." + it.id }) { group ->
                                WineGroupRow(
                                    group = group, cellar = cellar, year = viewModel.year,
                                    checked = group.id in shownPicked,
                                    onClick = { picked = if (group.id in picked) picked - group.id else picked + group.id },
                                    modifier = Modifier.testTag("wine.select.row"),
                                )
                                HorizontalDivider(Modifier.padding(start = 56.dp))
                            }
                            return@LazyColumn
                        }
                        items(groups, key = { it.id.toString() }) { group ->
                            CellarRow(
                                group = group,
                                cellar = cellar,
                                year = viewModel.year,
                                selected = group.id.toString() == selectedId,
                                onOpen = { onOpen(group.id.toString()) },
                                onDrink = { drinking = group },
                                onShowInRack = group.bottles.firstOrNull { it.slot != null }?.let { bottle ->
                                    { navigator.open(RackDetailRoute(bottle.slot!!.rackId.toString(), bottle.id.toString())) }
                                },
                                onEdit = { navigator.open(EditWineRoute(group.id.toString())) },
                                // With Undo, on screen longer than for other changes.
                                onDelete = { viewModel.deleteWine(group.id) },
                                onSelect = {
                                    selecting = true
                                    picked = setOf(group.id)
                                },
                                modifier = Modifier.animateItem().testTag("wine.row"),
                            )
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
        }
    }

    drinking?.let { group ->
        DrinkSheet(
            cellar = cellar,
            bottles = group.bottles,
            onDismiss = { drinking = null },
            onDrink = { bottle, tasting -> viewModel.drink(bottle.id, tasting) },
        )
    }
    if (namingSearch) {
        SaveSearchDialog(
            initial = filter.query.trim(),
            onSave = { name -> viewModel.saveSearch(name, filter) },
            onDismiss = { namingSearch = false },
        )
    }
    if (confirmSample) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sample_confirm_title),
            body = stringResource(R.string.settings_sample_confirm_body),
            confirm = stringResource(R.string.action_load_sample),
            onConfirm = { viewModel.loadSample(sampleLabels(resources)) },
            onDismiss = { confirmSample = false },
            destructive = false,
        )
    }
    if (confirmBulkDelete) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_wines_title),
            body = stringResource(R.string.confirm_delete_wines_body),
            confirm = stringResource(R.string.action_delete_wines),
            onConfirm = { viewModel.deleteWines(shownPicked); endSelection() },
            onDismiss = { confirmBulkDelete = false },
        )
    }
    if (reservingPicked) {
        ReserveDialog(
            initial = cellar.reservations.firstOrNull().orEmpty(),
            onKeep = { occasion ->
                viewModel.reserve(groups.filter { it.id in shownPicked }.flatMap { it.bottles }.map { it.id }, occasion)
                endSelection()
            },
            onDismiss = { reservingPicked = false },
            plural = true,
        )
    }
}

@Composable
private fun CellarSearchBar(
    cellar: Cellar,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onShowTable: (() -> Unit)?,
) {
    val suggestions = remember(cellar, query) { cellar.wineSuggestions(query, inCellarOnly = true) }
    if (!expanded) {
        // Collapsed: a pill that opens the search. A focusable text field here would
        // expand the search as soon as it gets focus (keyboard navigation, launch).
        CollapsedSearch(query, onOpen = { onExpandedChange(true) }, onClear = { onQueryChange("") }, onSettings = onSettings, onShowTable = onShowTable)
        return
    }
    SearchBar(
        modifier = Modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onExpandedChange(false) },
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    IconButton(onClick = { onExpandedChange(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        if (suggestions.isNotEmpty()) {
            Text(
                stringResource(R.string.search_suggestions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
        }
        LazyColumn {
            items(suggestions, key = { it.id.toString() }) { wine ->
                ListItem(
                    headlineContent = { Text(wine.displayName) },
                    supportingContent = { Text(listOfNotNull(wine.producer, wine.region).joinToString(" · ")) },
                    leadingContent = { WineColorDot(wine.color, size = 16.dp) },
                    modifier = Modifier.clickable { onOpen(wine.id.toString()) },
                )
            }
            // Never a blank page: say so, or show the list the search filters.
            if (query.isNotBlank() && suggestions.isEmpty()) {
                item(key = "none") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.search_no_suggestion)) },
                        leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.testTag("search.none"),
                    )
                }
            }
            if (query.isNotBlank()) {
                item(key = "results") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.search_show_results)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                        modifier = Modifier.clickable { onExpandedChange(false) }.testTag("search.results"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedSearch(query: String, onOpen: () -> Unit, onClear: () -> Unit, onSettings: () -> Unit, onShowTable: (() -> Unit)?) {
    Surface(
        onClick = onOpen,
        shape = SearchBarDefaults.inputFieldShape,
        color = SearchBarDefaults.colors().containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(SearchBarDefaults.windowInsets)
            .padding(horizontal = Dimens.l, vertical = Dimens.s)
            .heightIn(min = SearchBarDefaults.InputFieldHeight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Dimens.l)) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                query.ifEmpty { stringResource(R.string.search_hint) },
                modifier = Modifier.weight(1f).padding(horizontal = Dimens.l),
                color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
            if (onShowTable != null) {
                IconButton(onClick = onShowTable, modifier = Modifier.testTag("cellar.layout")) {
                    Icon(Icons.Outlined.TableChart, contentDescription = stringResource(R.string.action_show_table))
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("action.settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        }
    }
}

@Composable
private fun FilterBar(
    colors: Set<WineColor>,
    maturities: Set<Maturity>,
    presentColors: Set<WineColor>,
    presentMaturities: Set<Maturity>,
    tags: List<String>,
    tag: String?,
    grapes: List<String>,
    grape: String?,
    onToggleGrape: (String) -> Unit,
    reservations: List<String>,
    reservation: String?,
    onToggleReservation: (String) -> Unit,
    sort: CellarSort,
    onToggleColor: (WineColor) -> Unit,
    onToggleMaturity: (Maturity) -> Unit,
    onToggleTag: (String) -> Unit,
    onSort: (CellarSort) -> Unit,
    onSelect: () -> Unit,
    savedSearches: List<app.encave.core.SavedSearch>,
    current: app.encave.core.CellarFilter,
    onApplySearch: (app.encave.core.SavedSearch) -> Unit,
    onDeleteSearch: (app.encave.core.SavedSearch) -> Unit,
    onSaveSearch: (() -> Unit)?,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = sort != CellarSort.Name,
                onClick = { sortMenu = true },
                modifier = Modifier.testTag("cellar.sort"),
                label = { Text(stringResource(sort.labelRes)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort)) },
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                CellarSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = { onSort(option); sortMenu = false },
                        leadingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp)) },
                    )
                }
                HorizontalDivider()
                if (onSaveSearch != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_save_search)) },
                        onClick = { sortMenu = false; onSaveSearch() },
                        leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                        modifier = Modifier.testTag("cellar.save_search"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_wines)) },
                    onClick = { sortMenu = false; onSelect() },
                    leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                    modifier = Modifier.testTag("cellar.select"),
                )
            }
        }
        // Saved searches first: a tap applies one (or clears it), a long press offers to delete it.
        savedSearches.forEach { search ->
            SavedSearchChip(search, selected = current == search.filter, onClick = { onApplySearch(search) }, onDelete = { onDeleteSearch(search) })
        }
        // A chip for a style or maturity no bottle has left stays while it filters the list.
        WineColor.entries.filter { it in presentColors || it in colors }.forEach { color ->
            FilterChip(
                selected = color in colors,
                onClick = { onToggleColor(color) },
                label = { Text(stringResource(color.labelRes)) },
                leadingIcon = { WineColorDot(color) },
            )
        }
        listOf(Maturity.DrinkSoon, Maturity.Ready, Maturity.PastPeak, Maturity.Young)
            .filter { it in presentMaturities || it in maturities }.forEach { maturity ->
            FilterChip(
                selected = maturity in maturities,
                onClick = { onToggleMaturity(maturity) },
                label = { Text(stringResource(maturity.labelRes)) },
                leadingIcon = { Icon(maturity.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        // A grape no longer in the cellar stays visible while it filters the list.
        (grapes + listOfNotNull(grape?.takeIf { g -> grapes.none { it.equals(g, ignoreCase = true) } })).forEach { option ->
            FilterChip(
                selected = grape.equals(option, ignoreCase = true),
                onClick = { onToggleGrape(option) },
                label = { Text(option) },
                leadingIcon = { Icon(Icons.Outlined.Grass, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("filter.grape"),
            )
        }
        // A tag no longer used by a wine in the cellar stays visible while it filters the list.
        (tags + listOfNotNull(tag?.takeIf { t -> tags.none { it.equals(t, ignoreCase = true) } })).forEach { option ->
            FilterChip(
                selected = tag.equals(option, ignoreCase = true),
                onClick = { onToggleTag(option) },
                label = { Text(option) },
                leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("filter.tag"),
            )
        }
        (reservations + listOfNotNull(reservation?.takeIf { r -> reservations.none { it.equals(r, ignoreCase = true) } })).forEach { option ->
            FilterChip(
                selected = reservation.equals(option, ignoreCase = true),
                onClick = { onToggleReservation(option) },
                label = { Text(option) },
                leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("filter.reservation"),
            )
        }
    }
    Spacer(Modifier.height(Dimens.xs))
}

/** A saved search among the filter chips; the delete menu opens on a long press or a TalkBack action. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SavedSearchChip(search: app.encave.core.SavedSearch, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val hint = stringResource(R.string.saved_search_hint)
    val deleteLabel = stringResource(R.string.action_delete_search)
    Box {
        Surface(
            shape = androidx.compose.material3.FilterChipDefaults.shape,
            color = if (selected) scheme.secondaryContainer else scheme.surface,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier
                // 32dp tall like the other chips, with a 48dp touch target.
                .minimumInteractiveComponentSize()
                .heightIn(min = 32.dp)
                .clip(androidx.compose.material3.FilterChipDefaults.shape)
                .combinedClickable(onClickLabel = hint, onLongClick = { menu = true }, onClick = onClick)
                .semantics {
                    this.selected = selected
                    role = Role.Checkbox
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
                .testTag("filter.saved"),
        ) {
            Row(
                Modifier.padding(start = Dimens.s, end = Dimens.l).heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            ) {
                Icon(
                    if (selected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = if (selected) scheme.onSecondaryContainer else scheme.primary,
                )
                Text(
                    search.name, style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(deleteLabel, color = scheme.error) },
                onClick = { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = scheme.error) },
            )
        }
    }
}

/** Names the current search and filters. */
@Composable
private fun SaveSearchDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    // Full width with margins: a text field in a default-width dialog never settles in tests.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.save_search_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(R.string.save_search_body))
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("save_search.field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name); onDismiss() }, enabled = name.isNotBlank(), modifier = Modifier.testTag("save_search.save")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Swipe right to drink; long press (or TalkBack actions) for the other actions. */
@Composable
private fun CellarRow(
    group: WineGroup,
    cellar: Cellar,
    year: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    onDrink: () -> Unit,
    onShowInRack: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val drinkLabel = stringResource(R.string.action_drink)
    val rackLabel = stringResource(R.string.action_show_in_rack)
    val editLabel = stringResource(R.string.action_edit)
    val deleteLabel = stringResource(R.string.action_delete)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onDrink()
            false // The row snaps back; the drink sheet takes over.
        },
    )
    Box(modifier) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    Icon(Icons.Outlined.LocalBar, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(drinkLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            },
        ) {
            WineGroupRow(
                group = group, cellar = cellar, year = year, selected = selected,
                onClick = onOpen, onLongClick = { menu = true },
                modifier = Modifier.semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(drinkLabel) { onDrink(); true },
                        onShowInRack?.let { CustomAccessibilityAction(rackLabel) { it(); true } },
                        CustomAccessibilityAction(editLabel) { onEdit(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                },
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(drinkLabel) }, onClick = { menu = false; onDrink() },
                leadingIcon = { Icon(Icons.Outlined.LocalBar, contentDescription = null) },
            )
            if (onShowInRack != null) {
                DropdownMenuItem(
                    text = { Text(rackLabel) }, onClick = { menu = false; onShowInRack() },
                    leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                )
            }
            DropdownMenuItem(
                text = { Text(editLabel) }, onClick = { menu = false; onEdit() },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_select)) }, onClick = { menu = false; onSelect() },
                leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}

/**
 * Contextual top bar while choosing several wines: select all, place in a rack, delete,
 * and under More, take out, keep for an occasion or no longer in the cellar.
 */
@Composable
private fun SelectionTopBar(
    count: Int,
    racks: List<app.encave.core.Rack>,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPlace: (app.encave.core.Rack) -> Unit,
    onTakeOut: () -> Unit,
    onReserve: () -> Unit,
    onGone: () -> Unit,
    onDelete: () -> Unit,
) {
    var rackMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    androidx.compose.material3.TopAppBar(
        title = { Text(stringResource(R.string.selection_count, count), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("cellar.select.done")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_end_selection))
            }
        },
        actions = {
            IconButton(onClick = { onSelectAll(!allSelected) }, modifier = Modifier.testTag("cellar.select.all")) {
                Icon(
                    if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                    contentDescription = stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all),
                )
            }
            Box {
                IconButton(
                    onClick = { rackMenu = true }, enabled = count > 0 && racks.isNotEmpty(),
                    modifier = Modifier.testTag("cellar.select.place"),
                ) { Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.action_place_in_rack)) }
                DropdownMenu(expanded = rackMenu, onDismissRequest = { rackMenu = false }) {
                    racks.forEach { rack ->
                        DropdownMenuItem(text = { Text(rack.name) }, onClick = { rackMenu = false; onPlace(rack) })
                    }
                }
            }
            IconButton(onClick = onDelete, enabled = count > 0, modifier = Modifier.testTag("cellar.select.delete")) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
            Box {
                IconButton(onClick = { moreMenu = true }, enabled = count > 0, modifier = Modifier.testTag("cellar.select.more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_take_out_of_racks)) },
                        leadingIcon = { Icon(Icons.Outlined.Outbox, contentDescription = null) },
                        onClick = { moreMenu = false; onTakeOut() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_keep_for_occasion)) },
                        leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null) },
                        onClick = { moreMenu = false; onReserve() },
                        modifier = Modifier.testTag("cellar.select.reserve"),
                    )
                    DropdownMenuItem(
                        text = { MenuText(stringResource(R.string.action_mark_gone), stringResource(R.string.action_mark_gone_detail)) },
                        leadingIcon = { Icon(Icons.Outlined.VolunteerActivism, contentDescription = null) },
                        onClick = { moreMenu = false; onGone() },
                        modifier = Modifier.testTag("cellar.select.gone"),
                    )
                }
            }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** Bottles on order: swipe right (or touch and hold, or a TalkBack action) when they arrive. */
@Composable
private fun OrderRow(
    group: WineGroup,
    cellar: Cellar,
    year: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val receivedLabel = stringResource(R.string.action_received)
    val markLabel = stringResource(R.string.action_mark_received)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onReceive()
            false
        },
    )
    Box(modifier) {
        SwipeToDismissBox(
            state = swipe,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = Dimens.xl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    Icon(Icons.Outlined.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(receivedLabel, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            },
        ) {
            WineGroupRow(
                group = group, cellar = cellar, year = year, selected = selected,
                onClick = onOpen, onLongClick = { menu = true },
                modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(markLabel) { onReceive(); true }) },
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(markLabel) }, onClick = { menu = false; onReceive() },
                leadingIcon = { Icon(Icons.Outlined.LocalShipping, contentDescription = null) },
            )
        }
    }
}

/**
 * The three figures at the top of the cellar, each a shortcut: bottles opens the cellar
 * in figures, wines shows the whole list, and "to drink soon" filters the list (tap again
 * to show all).
 */
@Composable
private fun CellarSummary(
    cellar: Cellar,
    year: Int,
    filter: app.encave.core.CellarFilter,
    onBottles: () -> Unit,
    onWines: () -> Unit,
    onSoon: (app.encave.core.CellarFilter) -> Unit,
) {
    val stats = remember(cellar, year) { cellar.statistics(year) }
    val soonOnly = filter.maturities == setOf(Maturity.DrinkSoon) && filter.colors.isEmpty() && filter.rackId == null && filter.tag == null &&
        filter.reservation == null && filter.grape == null
    // Side by side, or one above the other from 150 % font scale so captions are not clipped.
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val bottles: @Composable (Modifier) -> Unit = { modifier ->
        StatTile(
            value = stats.bottleCount, caption = pluralStringResource(R.plurals.stat_bottles, stats.bottleCount), icon = Icons.Outlined.WineBar,
            hint = stringResource(R.string.cellar_stat_bottles_hint), opens = true, onClick = onBottles,
            modifier = modifier.testTag("cellar.stat.bottles"), stacked = stacked,
        )
    }
    val wines: @Composable (Modifier) -> Unit = { modifier ->
        StatTile(
            value = stats.wineCount, caption = pluralStringResource(R.plurals.stat_wines, stats.wineCount), icon = Icons.AutoMirrored.Outlined.List,
            hint = stringResource(R.string.cellar_stat_wines_hint), onClick = onWines,
            modifier = modifier.testTag("cellar.stat.wines"), stacked = stacked,
        )
    }
    val soon: @Composable (Modifier) -> Unit = { modifier ->
        StatTile(
            value = stats.byMaturity[Maturity.DrinkSoon] ?: 0, caption = stringResource(R.string.cellar_stat_soon),
            icon = Maturity.DrinkSoon.icon, hint = stringResource(R.string.cellar_stat_soon_hint), isSelected = soonOnly,
            onClick = {
                onSoon(
                    if (soonOnly) app.encave.core.CellarFilter(query = filter.query)
                    else app.encave.core.CellarFilter(query = filter.query, maturities = setOf(Maturity.DrinkSoon)),
                )
            },
            modifier = modifier.testTag("cellar.stat.soon"), stacked = stacked,
        )
    }
    if (stacked) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            listOf(bottles, wines, soon).forEach { it(Modifier.fillMaxWidth()) }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = Dimens.l, vertical = Dimens.s),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            listOf(bottles, wines, soon).forEach { it(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/** A figure with its caption. A tile that opens a screen has a chevron; a filter tile is outlined while on. */
@Composable
private fun StatTile(
    value: Int,
    caption: String,
    icon: ImageVector,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    opens: Boolean = false,
    /** One tile a row, at large font scales: the caption takes the lines it needs. */
    stacked: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.radiusCard)
    Surface(
        onClick = onClick,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "${Formats.integer(value)} $caption. $hint"
            selected = isSelected
            role = Role.Button
        },
        shape = shape,
        color = if (isSelected) scheme.primaryContainer else scheme.surfaceContainerHigh,
        border = if (isSelected) BorderStroke(1.5.dp, scheme.primary) else null,
    ) {
        Box(Modifier.padding(Dimens.m)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    Formats.integer(value),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                Text(
                    caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                )
            }
            if (opens) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.outline,
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
                )
            }
        }
    }
}
