package app.encave.ui.journal

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.encave.core.DiscoveryFilter
import app.encave.ui.discoveries.DiscoveryActions
import app.encave.ui.discoveries.DiscoveryFilterBar
import app.encave.ui.discoveries.discoveryItems
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
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.getValue
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

/** Tastings grouped by month, most recent first. */
@Composable
fun JournalScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val months = remember(cellar) {
        cellar.consumedBottles.filter { it.tasting != null }
            .groupBy { YearMonth.from(it.tasting!!.date.atZone(zone)) }
            .toList()
    }
    val locale = LocalConfiguration.current.locales[0]
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showDiscoveries by rememberSaveable { mutableStateOf(false) }
    var discoveryFilter by remember { mutableStateOf(DiscoveryFilter()) }
    val actions = remember(viewModel, navigator) {
        DiscoveryActions(
            open = { navigator.open(DiscoveryEditorRoute(it.id.toString())) },
            addToCellar = { navigator.open(AddBottleRoute(wineId = it.wineId.toString(), discoveryId = it.id.toString())) },
            toggleToBuy = { viewModel.toggleToBuy(it.id) },
            delete = { viewModel.deleteDiscovery(it.id) },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("action.remember"),
                onClick = { navigator.open(DiscoveryEditorRoute()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.discovery_new)) },
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
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s)) {
                SegmentedButton(selected = !showDiscoveries, onClick = { showDiscoveries = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.journal_tastings))
                }
                SegmentedButton(
                    selected = showDiscoveries, onClick = { showDiscoveries = true }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.testTag("journal.discoveries"),
                ) {
                    Text(stringResource(R.string.journal_discoveries))
                }
            }
            if (showDiscoveries) {
                if (cellar.discoveries.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.AutoAwesome,
                        title = stringResource(R.string.discovery_empty_title),
                        body = stringResource(R.string.discovery_empty_body),
                    ) {
                        Button(onClick = { navigator.open(DiscoveryEditorRoute()) }) { Text(stringResource(R.string.discovery_new)) }
                    }
                } else {
                    OutlinedTextField(
                        value = discoveryFilter.query,
                        onValueChange = { discoveryFilter = discoveryFilter.copy(query = it) },
                        placeholder = { Text(stringResource(R.string.discovery_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (discoveryFilter.query.isNotEmpty()) {
                                IconButton(onClick = { discoveryFilter = discoveryFilter.copy(query = "") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.xs),
                    )
                    DiscoveryFilterBar(cellar, discoveryFilter) { discoveryFilter = it }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        discoveryItems(cellar, viewModel.photos, discoveryFilter, actions)
                    }
                }
            } else if (months.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.AutoStories,
                    title = stringResource(R.string.journal_empty_title),
                    body = stringResource(R.string.journal_empty_body),
                ) {
                    if (cellar.bottlesInCellar.isNotEmpty()) {
                        OutlinedButton(onClick = { navigator.open(InsightsRoute) }) { Text(stringResource(R.string.action_insights)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    months.forEach { (month, bottles) ->
                        item(key = month.toString()) {
                            SectionHeader(monthFormat.format(month).replaceFirstChar { it.titlecase(locale) })
                        }
                        items(bottles, key = { it.id.toString() }) { bottle ->
                            TastingRow(cellar, bottle) { navigator.open(TastingRoute(bottle.id.toString())) }
                            HorizontalDivider(Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TastingRow(cellar: app.encave.core.Cellar, bottle: app.encave.core.Bottle, onClick: () -> Unit) {
    val tasting = bottle.tasting ?: return
    val wine = cellar.wines[bottle.wineId]
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { WineColorDot(wine?.color, size = 16.dp) },
        headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        overlineContent = { Text(Formats.day(tasting.date)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RatingStars(tasting.rating)
                tasting.note?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                tasting.occasion?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    )
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
    val tasting = bottle?.tasting
    val wine = bottle?.let { cellar.wines[it.wineId] }
    // Kept while the activity is recreated; started from the tasting once it is there.
    val form = tasting?.let { rememberRetained("tasting.form") { TastingFormState(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasting_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
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
                    modifier = Modifier.clickable { navigator.open(WineDetailRoute(wine.id.toString())) },
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
