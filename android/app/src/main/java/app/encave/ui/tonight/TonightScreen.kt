package app.encave.ui.tonight

import androidx.compose.material3.OutlinedButton
import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.displayLocation
import app.encave.core.Cellar
import app.encave.core.DishCategory
import app.encave.core.Maturity
import app.encave.core.WineGroup
import app.encave.core.pairings
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.MaturityLabel
import app.encave.ui.cellar.WineMenuBox
import app.encave.ui.components.WineGroupRow
import app.encave.ui.components.icon
import app.encave.ui.components.labelRes
import app.encave.ui.components.tint
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.navigation.PairingRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.SerifDisplay

/** What to open tonight: a surprise pick, food pairing, then bottles by maturity, most urgent first. */
@Composable
fun TonightScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val year = viewModel.year
    val sections = remember(cellar, year) { cellar.groupsByMaturity(year) }
    val candidates = remember(sections) {
        sections.filter { it.maturity == Maturity.DrinkSoon || it.maturity == Maturity.Ready }.flatMap { it.groups }
    }
    var surpriseSeed by rememberSaveable { mutableIntStateOf(0) }
    var drinking by remember { mutableStateOf<WineGroup?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.nav_tonight)) },
                actions = {
                    IconButton(onClick = { navigator.open(SettingsRoute) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (cellar.bottlesInCellar.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.LocalBar,
                title = stringResource(R.string.tonight_empty_title),
                body = stringResource(R.string.tonight_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Dimens.xxl)) {
            if (candidates.isNotEmpty()) {
                item {
                    val pick = candidates[Math.floorMod(surpriseSeed * 7919 + year, candidates.size)]
                    SurpriseCard(
                        group = pick, cellar = cellar, year = year,
                        onOpen = { navigator.open(WineDetailRoute(pick.id.toString())) },
                        onDrink = { drinking = pick },
                        onShuffle = { surpriseSeed++ },
                    )
                }
            }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s).clickable { navigator.open(PairingRoute) },
                    shape = RoundedCornerShape(Dimens.radiusCard),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(Dimens.l), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Restaurant, contentDescription = null, modifier = Modifier.size(32.dp))
                        Column(Modifier.padding(start = Dimens.l).weight(1f)) {
                            Text(stringResource(R.string.tonight_pairing_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.tonight_pairing_body), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            sections.forEach { section ->
                item(key = section.maturity.raw) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.l, top = Dimens.xl, bottom = Dimens.xs)
                            .semantics(mergeDescendants = true) { heading() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(section.maturity.icon, contentDescription = null, tint = section.maturity.tint)
                        Text(
                            stringResource(section.maturity.labelRes),
                            style = MaterialTheme.typography.titleMedium, color = section.maturity.tint,
                            modifier = Modifier.padding(start = Dimens.s).weight(1f),
                        )
                        Text(section.groups.sumOf { it.count }.toString(), style = MaterialTheme.typography.labelLarge)
                    }
                }
                items(section.groups, key = { section.maturity.raw + it.id }) { group ->
                    // The same menu as in the Cellar (long press or TalkBack actions).
                    WineMenuBox(group, cellar, viewModel, navigator, onDrink = { drinking = group }) { rowModifier, openMenu ->
                        WineGroupRow(
                            group = group, cellar = cellar, year = year, showMaturity = false,
                            onClick = { navigator.open(WineDetailRoute(group.id.toString())) },
                            onLongClick = openMenu,
                            modifier = rowModifier.testTag("tonight.row"),
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
    drinking?.let { group ->
        DrinkSheet(cellar, group.bottles, onDismiss = { drinking = null }, onDrink = { b, t -> viewModel.drink(b.id, t) })
    }
}

@Composable
private fun SurpriseCard(group: WineGroup, cellar: Cellar, year: Int, onOpen: () -> Unit, onDrink: () -> Unit, onShuffle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text(
                    stringResource(R.string.tonight_surprise_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = Dimens.s).semantics { heading() },
                )
            }
            Column(Modifier.clickable(onClick = onOpen).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(group.wine.displayName, style = SerifDisplay)
                Text(
                    listOfNotNull(group.wine.producer, group.wine.region).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    cellar.displayLocation(group.bottles.first()) ?: stringResource(R.string.location_none),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
                MaturityLabel(Maturity.of(group.wine, year), Modifier.padding(top = Dimens.xs))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                // A filled button: a tonal one is hardly visible on the card's own container color.
                Button(onClick = onDrink, modifier = Modifier.weight(1f).testTag("action.surprise_drink")) {
                    ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar)
                }
                OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f).testTag("action.surprise_next")) {
                    ActionContent(stringResource(R.string.action_surprise), Icons.Outlined.Casino)
                }
            }
        }
    }
}

/** Pick a dish, see the best bottles for it with the reasons. */
@Composable
fun PairingScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    var dish by rememberSaveable { mutableStateOf<DishCategory?>(null) }
    val suggestions = remember(cellar, dish) { dish?.let { cellar.pairings(it, viewModel.year) } ?: emptyList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = Dimens.xxl)) {
            item {
                Text(
                    stringResource(R.string.pairing_pick), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { heading() },
                )
                FlowRow(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    DishCategory.entries.forEach { option ->
                        FilterChip(selected = dish == option, onClick = { dish = option }, label = { Text(stringResource(option.labelRes)) })
                    }
                }
            }
            if (dish != null && suggestions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.pairing_none), Modifier.padding(Dimens.l),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(suggestions, key = { it.id.toString() }) { suggestion ->
                WineGroupRow(
                    group = suggestion.group, cellar = cellar, year = viewModel.year,
                    onClick = { navigator.open(WineDetailRoute(suggestion.id.toString())) },
                    extra = {
                        Text(
                            stringResource(R.string.pairing_score, suggestion.score),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val reasons = suggestion.reasons.map { stringResource(it.labelRes) }
                        Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
    }
}
