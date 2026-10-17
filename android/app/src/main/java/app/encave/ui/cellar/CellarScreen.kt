package app.encave.ui.cellar

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
import app.encave.core.WineColor
import app.encave.core.WineGroup
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
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
    var deleting by remember { mutableStateOf<WineGroup?>(null) }
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val isEmpty = cellar.bottlesInCellar.isEmpty()

    Scaffold(
        topBar = {
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
            )
        },
        floatingActionButton = {
            if (!isEmpty) {
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
                OutlinedButton(onClick = { viewModel.loadSample(sampleLabels(resources)) }) {
                    Text(stringResource(R.string.action_load_sample))
                }
                TextButton(onClick = { navigator.open(DiscoveryEditorRoute()) }) {
                    Text(stringResource(R.string.discovery_new_long))
                }
            }
            else -> Column(Modifier.padding(padding).fillMaxSize()) {
                FilterBar(
                    colors = filter.colors,
                    maturities = filter.maturities,
                    sort = sort,
                    onToggleColor = { c ->
                        viewModel.filter.value = filter.copy(colors = if (c in filter.colors) filter.colors - c else filter.colors + c)
                    },
                    onToggleMaturity = { m ->
                        viewModel.filter.value = filter.copy(
                            maturities = if (m in filter.maturities) filter.maturities - m else filter.maturities + m,
                        )
                    },
                    onSort = { viewModel.sort.value = it },
                )
                if (groups.isEmpty()) {
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
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 88.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
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
                                onDelete = { deleting = group },
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
    deleting?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_wine_title),
            body = stringResource(R.string.confirm_delete_wine_body, group.wine.displayName),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.deleteWine(group.id) },
            onDismiss = { deleting = null },
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
) {
    val suggestions = remember(cellar, query) {
        cellar.wineSuggestions(query).filter { wine -> cellar.bottlesInCellar.any { it.wineId == wine.id } }
    }
    if (!expanded) {
        // Collapsed: a pill that opens the search. A focusable text field here would
        // expand the search as soon as it gets focus (keyboard navigation, launch).
        CollapsedSearch(query, onOpen = { onExpandedChange(true) }, onClear = { onQueryChange("") }, onSettings = onSettings)
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
        }
    }
}

@Composable
private fun CollapsedSearch(query: String, onOpen: () -> Unit, onClear: () -> Unit, onSettings: () -> Unit) {
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
    sort: CellarSort,
    onToggleColor: (WineColor) -> Unit,
    onToggleMaturity: (Maturity) -> Unit,
    onSort: (CellarSort) -> Unit,
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
            }
        }
        WineColor.entries.forEach { color ->
            FilterChip(
                selected = color in colors,
                onClick = { onToggleColor(color) },
                label = { Text(stringResource(color.labelRes)) },
                leadingIcon = { WineColorDot(color) },
            )
        }
        listOf(Maturity.DrinkSoon, Maturity.Ready, Maturity.PastPeak, Maturity.Young).forEach { maturity ->
            FilterChip(
                selected = maturity in maturities,
                onClick = { onToggleMaturity(maturity) },
                label = { Text(stringResource(maturity.labelRes)) },
                leadingIcon = { Icon(maturity.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
    Spacer(Modifier.height(Dimens.xs))
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
                text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            )
        }
    }
}
