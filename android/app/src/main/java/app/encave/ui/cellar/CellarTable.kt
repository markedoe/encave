package app.encave.ui.cellar

import app.encave.core.bottleToOpen
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.ui.devices.SettingsIcon
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.CellarFilter
import app.encave.core.CellarSort
import app.encave.core.Maturity
import app.encave.core.WineGroup
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.Formats
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.MaturityLabel
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.labelRes
import app.encave.ui.components.locationsText
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.theme.Dimens
import java.math.BigDecimal

/** Columns of the cellar table, in order, with their width. */
private enum class Column(val label: Int, val width: Dp, val end: Boolean = false) {
    Wine(R.string.table_wine, 220.dp),
    Vintage(R.string.sort_vintage, 120.dp),
    Producer(R.string.field_producer, 170.dp),
    Style(R.string.field_style, 130.dp),
    Region(R.string.field_region, 140.dp),
    When(R.string.table_when, 150.dp),
    Bottles(R.string.pdf_bottles, 90.dp, end = true),
    Location(R.string.field_location, 220.dp),
    Value(R.string.pdf_purchase_value, 120.dp, end = true),
}

/** One wine of the table, with the values it sorts by. */
private class TableRow(val group: WineGroup, cellar: Cellar, year: Int) {
    val maturity = Maturity.of(group.wine, year)
    val totals: Map<String, BigDecimal> = group.bottles.filter { it.purchasePrice != null }
        .groupBy { it.currency ?: cellar.settings.currency }
        .mapValues { (_, bottles) -> bottles.fold(BigDecimal.ZERO) { sum, b -> sum + b.purchasePrice!! } }
    val value: BigDecimal = totals.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val region: String = listOfNotNull(group.wine.region, group.wine.country).joinToString(", ")
}

/**
 * The order of a column, then the wine's name for rows alike. Non-vintage wines come after
 * every year, and places follow the racks' order, then row, column and depth, as numbers;
 * bottles without a place come last.
 */
private fun comparator(column: Column, racks: List<app.encave.core.Rack>): Comparator<TableRow> {
    val collator = Cellar.collator()
    val byName = Comparator<TableRow> { a, b -> Cellar.nameOrder(a.group.wine, b.group.wine, collator) }
    val rackIndex = racks.withIndex().associate { it.value.id to it.index }
    val slotOrder = compareBy<app.encave.core.Slot>({ rackIndex[it.rackId] ?: Int.MAX_VALUE }, { it.row }, { it.column }, { it.depth })
    val order: Comparator<TableRow> = when (column) {
        Column.Wine -> byName
        Column.Vintage -> compareBy { it.group.wine.vintage ?: Int.MAX_VALUE }
        Column.Producer -> Comparator { a, b -> collator.compare(a.group.wine.producer.orEmpty(), b.group.wine.producer.orEmpty()) }
        Column.Style -> compareBy { it.group.wine.color?.ordinal ?: Int.MAX_VALUE }
        Column.Region -> Comparator { a, b -> collator.compare(a.region, b.region) }
        Column.When -> compareBy { it.maturity.ordinal }
        Column.Bottles -> compareBy { it.group.count }
        Column.Location -> Comparator { a, b ->
            val sa = a.group.bottles.mapNotNull { it.slot }.minWithOrNull(slotOrder)
            val sb = b.group.bottles.mapNotNull { it.slot }.minWithOrNull(slotOrder)
            when {
                sa == null && sb == null -> 0
                sa == null -> 1
                sb == null -> -1
                else -> slotOrder.compare(sa, sb)
            }
        }
        Column.Value -> compareBy { it.value }
    }
    return if (column == Column.Wine) order else order.then(byName)
}

/**
 * Wide screens: the cellar as a table that sorts by any column, one row per wine, with
 * the selected wine beside it. Like a spreadsheet, for large cellars.
 */
@Composable
fun CellarTableScreen(viewModel: AppViewModel, navigator: AppNavigator, onShowList: () -> Unit) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    var sortColumn by rememberSaveable { mutableStateOf(Column.Wine) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var drinking by app.encave.ui.drink.rememberDrinking()
    val year = viewModel.year
    // The list's search and filters, as on Apple: switching between them keeps both.
    val rows = remember(cellar, filter, sortColumn, ascending) {
        val order = comparator(sortColumn, cellar.racks).let { if (ascending) it else it.reversed() }
        cellar.groups(filter, CellarSort.Name, year).map { TableRow(it, cellar, year) }.sortedWith(order)
    }
    val horizontal = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = filter.query,
                        onValueChange = { viewModel.filter.value = filter.copy(query = it) },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (filter.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.filter.value = filter.copy(query = "") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth().padding(end = Dimens.l).testTag("table.search"),
                    )
                },
                actions = {
                    IconButton(onClick = onShowList, modifier = Modifier.testTag("cellar.layout")) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.action_show_list))
                    }
                    IconButton(onClick = { navigator.open(AddBottleRoute()) }, modifier = Modifier.testTag("action.table_add")) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_bottle))
                    }
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        SettingsIcon()
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (rows.isEmpty()) {
                    EmptyState(Icons.Filled.Search, stringResource(R.string.cellar_no_match), stringResource(R.string.cellar_no_match_body)) {
                        if (filter.isActive) {
                            TextButton(onClick = { viewModel.filter.value = CellarFilter(query = filter.query) }) {
                                Text(stringResource(R.string.action_clear_filters))
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        HeaderRow(horizontal, sortColumn, ascending) { column ->
                            if (column == sortColumn) ascending = !ascending else {
                                sortColumn = column
                                ascending = true
                            }
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.fillMaxSize().testTag("cellar.table")) {
                            items(rows, key = { it.group.id.toString() }) { row ->
                                val id = row.group.id.toString()
                                // Touch and hold: the wine's menu, as in the list (and on Apple).
                                WineMenuBox(row.group, cellar, viewModel, navigator, onDrink = { drinking = row.group.bottles.bottleToOpen?.id?.toString() }) { rowModifier, openMenu ->
                                    DataRow(row, cellar, horizontal, selected = id == selectedId, onClick = { selectedId = id }, onLongClick = openMenu, modifier = rowModifier)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    // More columns to the right (the wine beside the table takes room): the last one
                    // shown fades out, so a cut column reads as "scroll", not as a clipped word.
                    if (horizontal.canScrollForward) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(48.dp)
                                .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0f), MaterialTheme.colorScheme.surface))),
                        )
                    }
                }
            }
            // The selected wine beside the table; its back arrow closes it.
            val id = selectedId?.takeIf { s -> rows.any { it.group.id.toString() == s } }
            if (id != null) {
                VerticalDivider()
                Box(Modifier.width(380.dp).fillMaxHeight()) {
                    WineDetailScreen(viewModel, navigator, id, showBack = true, onBack = { selectedId = null }, showMessages = false)
                }
            }
        }
    }
    drinking?.let { id ->
        app.encave.ui.drink.DrinkSheet(cellar, id, onDismiss = { drinking = null }, onDrink = { b, t -> viewModel.drink(b.id, t) })
    }
}

@Composable
private fun HeaderRow(scroll: ScrollState, sortColumn: Column, ascending: Boolean, onSort: (Column) -> Unit) {
    val up = stringResource(R.string.table_sorted_up)
    val down = stringResource(R.string.table_sorted_down)
    val hint = stringResource(R.string.table_sort_hint)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(scroll)
            .padding(horizontal = Dimens.s),
    ) {
        Column.entries.forEach { column ->
            val sorted = column == sortColumn
            Row(
                Modifier.width(column.width).heightIn(min = 48.dp)
                    .clickable(onClickLabel = hint, role = Role.Button) { onSort(column) }
                    .semantics {
                        heading()
                        selected = sorted
                        if (sorted) stateDescription = if (ascending) up else down
                    }
                    .padding(horizontal = Dimens.s)
                    .testTag("table.header.${column.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (column.end) Arrangement.End else Arrangement.Start,
            ) {
                Text(
                    stringResource(column.label), style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sorted) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sorted) {
                    Icon(
                        if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                        contentDescription = null, modifier = Modifier.padding(start = Dimens.xs).size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DataRow(
    row: TableRow,
    cellar: Cellar,
    scroll: ScrollState,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wine = row.group.wine
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.action_more))
            .testTag("table.row"),
    ) {
        Row(
            Modifier.horizontalScroll(scroll).heightIn(min = 52.dp).padding(horizontal = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column.entries.forEach { column ->
                Box(
                    Modifier.width(column.width).padding(horizontal = Dimens.s, vertical = Dimens.xs),
                    contentAlignment = if (column.end) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    when (column) {
                        Column.Wine -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                            WineColorDot(wine.color, size = 16.dp)
                            Text(wine.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column.Vintage -> Text(
                            wine.vintage?.toString() ?: stringResource(R.string.non_vintage),
                            color = if (wine.vintage == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Column.Producer -> Text(wine.producer.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Column.Style -> wine.color?.let { Text(stringResource(it.labelRes)) }
                        Column.Region -> Text(row.region, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Column.When -> if (row.maturity != Maturity.Unknown) MaturityLabel(row.maturity, short = true)
                        Column.Bottles -> Text(row.group.count.toString())
                        Column.Location -> Text(
                            locationsText(cellar, row.group.bottles), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                        Column.Value -> Text(row.totals.keys.sorted().joinToString(" + ") { Formats.money(row.totals.getValue(it), it) })
                    }
                }
            }
        }
    }
}
