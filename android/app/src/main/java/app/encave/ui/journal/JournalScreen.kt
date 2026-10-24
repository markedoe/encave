package app.encave.ui.journal

import app.encave.ui.components.measure
import app.encave.ui.components.rememberFabSpace
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import app.encave.ui.drink.DiscardNoteDialog
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import app.encave.ui.devices.SettingsIcon
import app.encave.ui.discoveries.DiscoveryActions
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.DiscoveryEditorRoute
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WhenLoaded
import app.encave.ui.components.rememberRetained
import app.encave.ui.components.RatingStars
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.StyleLabel
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.labelRes
import app.encave.ui.drink.TastingFields
import app.encave.ui.drink.TastingFormState
import app.encave.ui.navigation.InsightsRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.navigation.TastingRoute
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.SerifDisplay
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.encave.core.Bottle
import app.encave.core.Tasting
import app.encave.core.Cellar
import app.encave.core.WineGroup
import app.encave.core.DiaryEntry
import app.encave.core.DiaryFilter
import app.encave.core.diary
import app.encave.core.discoveryTags
import app.encave.core.shoppingListCount
import app.encave.ui.discoveries.ElsewhereRow
import app.encave.ui.components.WineGroupRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import app.encave.ui.components.ListDetailPanes
import app.encave.ui.discoveries.DiscoveryEditorScreen
import androidx.compose.ui.res.pluralStringResource
import app.encave.core.bottleToOpen
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.drink.rememberDrinking
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color

/**
 * The diary: bottles opened from the cellar and wines tasted elsewhere, newest first,
 * grouped by month, with filters. "Add a tasting" first asks where the wine was drunk.
 */
@Composable
fun JournalScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    ListDetailPanes(
        navigator = navigator,
        placeholder = stringResource(R.string.journal_select_entry),
        list = { open, selected -> JournalList(viewModel, navigator, open, selected) },
        detail = { key, paneNavigator, _ ->
            val id = key.substringAfter(':')
            if (key.startsWith(TASTING_KEY)) TastingScreen(viewModel, paneNavigator, id)
            else DiscoveryEditorScreen(viewModel, paneNavigator, id)
        },
    )
}

/** Keys of the Journal's details: a tasting from the cellar (bottle ID) or a wine tasted elsewhere. */
private const val TASTING_KEY = "tasting:"
private const val DISCOVERY_KEY = "discovery:"

/** The diary; [onOpenEntry] shows an entry beside the list on wide windows. */
@Composable
private fun JournalList(viewModel: AppViewModel, navigator: AppNavigator, onOpenEntry: ((String) -> Unit)?, selectedKey: String?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val filter by viewModel.journalFilter.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val chosenTag by viewModel.journalTag.collectAsStateWithLifecycle()
    // The chosen tag while some wine tasted elsewhere still has it and the filter shows them.
    val tag = chosenTag?.takeIf { filter.showsElsewhere && it in cellar.discoveryTags }
    val zone = ZoneId.systemDefault()
    val months = remember(cellar, filter, query, tag) {
        cellar.diary(filter, query, tag).groupBy { YearMonth.from(it.date.atZone(zone)) }.toList()
    }
    val isEmpty = cellar.consumedBottles.isEmpty() && cellar.discoveries.isEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var drinking by rememberDrinking()
    val actions = remember(viewModel, navigator, onOpenEntry) {
        DiscoveryActions(
            open = { onOpenEntry?.invoke(DISCOVERY_KEY + it.id) ?: navigator.open(DiscoveryEditorRoute(it.id.toString())) },
            addToCellar = { navigator.open(AddBottleRoute(wineId = it.wineId.toString(), discoveryId = it.id.toString())) },
            toggleToBuy = { viewModel.toggleToBuy(it.id) },
            delete = { viewModel.deleteDiscovery(it.id) },
        )
    }

    val fabSpace = rememberFabSpace()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.measure(fabSpace).testTag("action.add_tasting"),
                onClick = { choosing = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.journal_add)) },
            )
        },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_journal)) },
                actions = {
                    IconButton(onClick = { navigator.open(InsightsRoute) }) {
                        Icon(Icons.Outlined.Insights, contentDescription = stringResource(R.string.action_insights))
                    }
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        SettingsIcon()
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Outlined.AutoStories,
                    title = stringResource(R.string.journal_empty_title),
                    body = stringResource(R.string.journal_empty_body),
                ) {
                    Button(onClick = { choosing = true }) { Text(stringResource(R.string.journal_add)) }
                    if (cellar.bottlesInCellar.isNotEmpty()) {
                        OutlinedButton(onClick = { navigator.open(InsightsRoute) }) { Text(stringResource(R.string.action_insights)) }
                    }
                }
                return@Column
            }
            SearchField(query, onChange = { query = it }, hint = stringResource(R.string.discovery_search_hint))
            DiaryFilterBar(
                cellar, filter, tag,
                onFilter = { viewModel.journalFilter.value = it },
                onTag = { viewModel.journalTag.value = if (tag == it) null else it },
            )
            if (months.isEmpty()) {
                when {
                    query.isNotBlank() -> Text(
                        stringResource(R.string.journal_none_match),
                        Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    filter == DiaryFilter.Elsewhere -> EmptyState(
                        Icons.Outlined.Place, stringResource(R.string.journal_elsewhere_empty_title), stringResource(R.string.journal_elsewhere_empty_body),
                    )
                    filter == DiaryFilter.ToBuy -> EmptyState(
                        Icons.Filled.ShoppingCart, stringResource(R.string.journal_to_buy_empty_title), stringResource(R.string.journal_to_buy_empty_body),
                    )
                    else -> EmptyState(
                        Icons.Outlined.Home, stringResource(R.string.journal_cellar_empty_title), stringResource(R.string.journal_cellar_empty_body),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("journal.list"), contentPadding = PaddingValues(bottom = fabSpace.listBottom)) {
                months.forEach { (month, entries) ->
                    item(key = month.toString()) {
                        SectionHeader(monthFormat.format(month).replaceFirstChar { it.titlecase(locale) })
                    }
                    items(entries, key = { it.id.toString() }) { entry ->
                        when (entry) {
                            is DiaryEntry.Opened -> TastingRow(
                                cellar, entry.bottle, Modifier.animateItem(),
                                onClick = {
                                    onOpenEntry?.invoke(TASTING_KEY + entry.bottle.id) ?: navigator.open(TastingRoute(entry.bottle.id.toString()))
                                },
                                selected = selectedKey == TASTING_KEY + entry.bottle.id,
                                onShowWine = { navigator.open(WineDetailRoute(entry.bottle.wineId.toString())) },
                                onAddBottles = { navigator.open(AddBottleRoute(wineId = entry.bottle.wineId.toString())) },
                                onPutBack = { viewModel.putBack(entry.bottle.id) },
                            )
                            is DiaryEntry.Elsewhere -> ElsewhereRow(
                                cellar, viewModel.photos, entry.discovery, actions, Modifier.animateItem().testTag("journal.row.elsewhere"),
                            )
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }

    if (choosing) {
        AddTastingSheet(
            canOpenBottle = cellar.bottlesInCellar.isNotEmpty(),
            onDismiss = { choosing = false },
            onCellar = { choosing = false; picking = true },
            onElsewhere = { choosing = false; navigator.open(DiscoveryEditorRoute()) },
        )
    }
    if (picking) {
        BottlePickerSheet(cellar, viewModel.year, onDismiss = { picking = false }) { group ->
            picking = false
            drinking = group.bottles.bottleToOpen?.id?.toString()
        }
    }
    drinking?.let { id ->
        DrinkSheet(cellar, id, onDismiss = { drinking = null }, onDrink = { b, t -> viewModel.drink(b.id, t) })
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs),
    )
}

/** All · From my cellar · Tasted elsewhere · To buy, then the tags of wines tasted elsewhere. */
@Composable
private fun DiaryFilterBar(cellar: Cellar, filter: DiaryFilter, tag: String?, onFilter: (DiaryFilter) -> Unit, onTag: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l),
        horizontalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        val chips = listOf(
            Triple(DiaryFilter.All, stringResource(R.string.journal_filter_all), Icons.Outlined.AutoStories),
            Triple(DiaryFilter.Cellar, stringResource(R.string.journal_from_cellar), Icons.Outlined.Home),
            Triple(DiaryFilter.Elsewhere, stringResource(R.string.journal_tasted_elsewhere), Icons.Outlined.Place),
            Triple(DiaryFilter.ToBuy, stringResource(R.string.discovery_to_buy_count, cellar.shoppingListCount), Icons.Filled.ShoppingCart),
        )
        chips.forEach { (value, label, icon) ->
            FilterChip(
                selected = filter == value,
                onClick = { onFilter(value) },
                label = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("journal.filter.${value.name.lowercase()}"),
            )
        }
        if (filter.showsElsewhere) {
            cellar.discoveryTags.forEach { name ->
                FilterChip(
                    selected = tag == name,
                    onClick = { onTag(name) },
                    label = { Text(name) },
                    leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** Where was it drunk? From the cellar (the bottle leaves it) or somewhere else (it does not change). */
@Composable
private fun AddTastingSheet(canOpenBottle: Boolean, onDismiss: () -> Unit, onCellar: () -> Unit, onElsewhere: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
            Text(
                stringResource(R.string.journal_add_where),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { heading() },
            )
            ListItem(
                modifier = Modifier.testTag("journal.add.cellar").clickable(enabled = canOpenBottle, onClick = onCellar).alpha(if (canOpenBottle) 1f else 0.5f),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Home, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.journal_add_cellar)) },
                supportingContent = {
                    Text(stringResource(if (canOpenBottle) R.string.journal_add_cellar_body else R.string.journal_add_cellar_none))
                },
            )
            ListItem(
                modifier = Modifier.testTag("journal.add.elsewhere").clickable(onClick = onElsewhere),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Place, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.journal_add_elsewhere)) },
                supportingContent = { Text(stringResource(R.string.journal_add_elsewhere_body)) },
            )
        }
    }
}

/** The wines with bottles in the cellar, searchable, to choose the one opened. */
@Composable
private fun BottlePickerSheet(cellar: Cellar, year: Int, onDismiss: () -> Unit, onPick: (WineGroup) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(cellar, query) { cellar.groups(query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            Text(
                stringResource(R.string.drink_pick_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.xl, vertical = Dimens.s).semantics { heading() },
            )
            Text(
                stringResource(R.string.drink_pick_footer),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.xl),
            )
            SearchField(query, onChange = { query = it }, hint = stringResource(R.string.search_hint))
            LazyColumn(Modifier.fillMaxWidth()) {
                if (groups.isEmpty()) {
                    item { Text(stringResource(R.string.cellar_no_match), Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(groups, key = { it.id.toString() }) { group ->
                    WineGroupRow(
                        group = group, cellar = cellar, year = year,
                        onClick = { onPick(group) },
                        modifier = Modifier.testTag("drink.pick.row"),
                    )
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

/**
 * A bottle opened from the cellar, saying so; TalkBack reads it as one sentence. Long press
 * (or TalkBack's actions): show the wine, edit the note, add bottles, put it back in the cellar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TastingRow(
    cellar: Cellar,
    bottle: Bottle,
    modifier: Modifier,
    onClick: () -> Unit,
    onShowWine: () -> Unit,
    onAddBottles: () -> Unit,
    onPutBack: () -> Unit,
    selected: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val showLabel = stringResource(R.string.action_show_wine)
    val editLabel = stringResource(R.string.action_edit_note)
    val addLabel = stringResource(R.string.action_add_bottles)
    val putBackLabel = stringResource(R.string.action_put_back)
    val tasting = bottle.tasting
    val wine = cellar.wines[bottle.wineId]
    val source = stringResource(R.string.journal_from_cellar)
    val date = tasting?.date ?: bottle.addedAt
    // The style in words, not only by the dot's color.
    val style = wine?.color?.let { stringResource(it.labelRes) }
    val description = listOfNotNull(
        source,
        wine?.displayName ?: stringResource(R.string.unknown_wine),
        style,
        tasting?.rating?.let { pluralStringResource(R.plurals.journal_rating_stars, it, it) },
        Formats.day(date),
        tasting?.occasion?.takeIf { it.isNotBlank() },
        tasting?.note?.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    Box(modifier) {
        ListItem(
            // The click before clearAndSetSemantics, so TalkBack still finds the row's actions.
            modifier = Modifier
                .testTag("journal.row.cellar")
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = stringResource(R.string.action_more))
                .clearAndSetSemantics {
                    contentDescription = description
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction(showLabel) { onShowWine(); true },
                        CustomAccessibilityAction(addLabel) { onAddBottles(); true },
                        CustomAccessibilityAction(putBackLabel) { onPutBack(); true },
                    )
                },
            colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
            leadingContent = { WineColorDot(wine?.color, size = 16.dp) },
            overlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(listOfNotNull(source, style, Formats.day(date)).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    tasting?.rating?.let { RatingStars(it) }
                    tasting?.note?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    tasting?.occasion?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(showLabel) }, leadingIcon = { Icon(Icons.Outlined.WineBar, null) }, onClick = { menu = false; onShowWine() })
            DropdownMenuItem(text = { Text(editLabel) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onClick() })
            DropdownMenuItem(text = { Text(addLabel) }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; onAddBottles() })
            DropdownMenuItem(
                text = { Text(putBackLabel) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null) },
                onClick = { menu = false; onPutBack() },
                modifier = Modifier.testTag("journal.menu.put_back"),
            )
        }
    }
}

/** View and edit a full tasting note; a bottle can also be put back if it was marked by mistake. */
@Composable
fun TastingScreen(viewModel: AppViewModel, navigator: AppNavigator, bottleId: String) {
    WhenLoaded(viewModel) { TastingForm(viewModel, navigator, bottleId) }
}

@Composable
private fun TastingForm(viewModel: AppViewModel, navigator: AppNavigator, bottleId: String) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val bottle = cellar.bottles.values.firstOrNull { it.id.toString() == bottleId }
    val wine = bottle?.let { cellar.wines[it.wineId] }
    // Kept while the activity is recreated; started from the tasting once it is there
    // (a bottle opened without one, such as an imported one, starts from the day it was added).
    val form = bottle?.takeIf { it.status == Bottle.Status.Consumed }?.let { opened ->
        rememberRetained("tasting.form") { TastingFormState(opened.tasting ?: Tasting(date = opened.addedAt)) }
    }

    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val leave = { if (form?.isDirty == true) confirmingDiscard = true else navigator.back() }
    BackHandler(enabled = form?.isDirty == true) { confirmingDiscard = true }
    if (confirmingDiscard) {
        DiscardNoteDialog(onDiscard = { confirmingDiscard = false; navigator.back() }, onKeepEditing = { confirmingDiscard = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasting_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (bottle != null && form != null) {
                        TextButton(onClick = {
                            viewModel.updateTasting(bottle.id, form.toTasting())
                            navigator.back()
                        }) { Text(stringResource(R.string.action_save)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (bottle == null || form == null) {
            EmptyState(Icons.Outlined.AutoStories, stringResource(R.string.tasting_not_found), "", Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            if (wine != null) {
                StyleLabel(wine.color)
                Text(
                    wine.displayName, style = SerifDisplay,
                    modifier = Modifier.clickable(onClickLabel = stringResource(R.string.action_show_wine)) {
                        navigator.open(WineDetailRoute(wine.id.toString()))
                    },
                )
                wine.producer?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TastingFields(form)
            TextButton(onClick = {
                viewModel.putBack(bottle.id)
                navigator.back()
            }) { Text(stringResource(R.string.action_put_back)) }
        }
    }
}
