package app.encave.ui.cellar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.WineAttribute
import app.encave.core.WineColor
import app.encave.core.winesSharing
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.add.WineFormField
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.WineGroupRow
import app.encave.ui.components.labelRes
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.navigation.WineExploreRoute
import app.encave.ui.theme.Dimens

/** The details of a wine that other wines can share, as written in [WineExploreRoute]. */
enum class ExploreKind { Producer, Region, Appellation, Country, Grape, Vintage, Style, Tag }

val WineAttribute.kind: ExploreKind
    get() = when (this) {
        is WineAttribute.Producer -> ExploreKind.Producer
        is WineAttribute.Region -> ExploreKind.Region
        is WineAttribute.Appellation -> ExploreKind.Appellation
        is WineAttribute.Country -> ExploreKind.Country
        is WineAttribute.Grape -> ExploreKind.Grape
        is WineAttribute.Vintage -> ExploreKind.Vintage
        is WineAttribute.Color -> ExploreKind.Style
        is WineAttribute.Tag -> ExploreKind.Tag
    }

/** The value as stored in a route (the style by its file name). */
private val WineAttribute.rawValue: String
    get() = when (this) {
        is WineAttribute.Producer -> value.trim()
        is WineAttribute.Region -> value.trim()
        is WineAttribute.Appellation -> value.trim()
        is WineAttribute.Country -> value.trim()
        is WineAttribute.Grape -> value.trim()
        is WineAttribute.Tag -> value.trim()
        is WineAttribute.Vintage -> year.toString()
        is WineAttribute.Color -> color.raw
    }

fun WineAttribute.route(currentWineId: java.util.UUID? = null) = WineExploreRoute(kind.name, rawValue, currentWineId?.toString())

fun WineExploreRoute.attribute(): WineAttribute? = when (ExploreKind.entries.firstOrNull { it.name == kind }) {
    ExploreKind.Producer -> WineAttribute.Producer(value)
    ExploreKind.Region -> WineAttribute.Region(value)
    ExploreKind.Appellation -> WineAttribute.Appellation(value)
    ExploreKind.Country -> WineAttribute.Country(value)
    ExploreKind.Grape -> WineAttribute.Grape(value)
    ExploreKind.Vintage -> value.toIntOrNull()?.let { WineAttribute.Vintage(it) }
    ExploreKind.Style -> WineColor.entries.firstOrNull { it.raw == value }?.let { WineAttribute.Color(it) }
    ExploreKind.Tag -> WineAttribute.Tag(value)
    null -> null
}

/** The value as shown, copied and searched. */
@Composable
fun WineAttribute.displayValue(): String = when (this) {
    is WineAttribute.Color -> stringResource(color.labelRes)
    else -> rawValue
}

/** The field of the wine form that holds it. */
val WineAttribute.field: WineFormField
    get() = when (kind) {
        ExploreKind.Producer -> WineFormField.Producer
        ExploreKind.Region -> WineFormField.Region
        ExploreKind.Appellation -> WineFormField.Appellation
        ExploreKind.Country -> WineFormField.Country
        ExploreKind.Grape -> WineFormField.Grapes
        ExploreKind.Vintage -> WineFormField.Vintage
        ExploreKind.Style -> WineFormField.Style
        ExploreKind.Tag -> WineFormField.Tags
    }

/**
 * Only what describes the wine on its label may go to the web search (see the privacy
 * rules): never tags, notes, prices or places.
 */
val WineAttribute.isSearchable: Boolean
    get() = this is WineAttribute.Producer || this is WineAttribute.Appellation

/** What a tap does, read by TalkBack after "Double-tap to". */
@Composable
fun WineAttribute.actionLabel(): String = stringResource(
    when (kind) {
        ExploreKind.Producer -> R.string.explore_action_producer
        ExploreKind.Region -> R.string.explore_action_region
        ExploreKind.Appellation -> R.string.explore_action_appellation
        ExploreKind.Country -> R.string.explore_action_country
        ExploreKind.Grape -> R.string.explore_action_grape
        ExploreKind.Vintage -> R.string.explore_action_vintage
        ExploreKind.Style -> R.string.explore_action_style
        ExploreKind.Tag -> R.string.explore_action_tag
    },
)

/** The wines of the cellar that share a detail of a wine, opened from the wine's page. */
@Composable
fun WineExploreScreen(viewModel: AppViewModel, navigator: AppNavigator, route: WineExploreRoute) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val attribute = route.attribute()
    val all = attribute?.let { cellar.winesSharing(it) }.orEmpty()
    val current = all.firstOrNull { it.id.toString() == route.currentWineId }
    val groups = all.filter { it !== current }
    val bottles = groups.sumOf { it.count }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(attribute?.displayValue().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (groups.isEmpty() && current == null) {
            EmptyState(
                icon = Icons.Outlined.LocalBar,
                title = stringResource(R.string.explore_empty_title),
                body = stringResource(R.string.explore_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().testTag("explore.list"), contentPadding = padding) {
            if (current != null) {
                item { SectionHeader(stringResource(R.string.explore_this_wine)) }
                item(key = "current") {
                    // Its own page is the one this list was opened from: back to it.
                    WineGroupRow(current, cellar, viewModel.year, onClick = { navigator.back() }, modifier = Modifier.testTag("explore.current"))
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.explore_no_other),
                        Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    val wines = if (current != null) pluralStringResource(R.plurals.other_wine_count, groups.size, groups.size)
                    else pluralStringResource(R.plurals.wine_count, groups.size, groups.size)
                    SectionHeader(wines + " · " + pluralStringResource(R.plurals.bottle_count, bottles, bottles))
                }
            }
            items(groups, key = { it.id.toString() }) { group ->
                WineGroupRow(
                    group, cellar, viewModel.year,
                    onClick = { navigator.open(WineDetailRoute(group.id.toString())) },
                    modifier = Modifier.testTag("explore.row"),
                )
            }
            item { Box(Modifier.height(Dimens.xxl)) }
        }
    }
}

/** "3 wines", shown under a detail when other wines share it. */
@Composable
internal fun sharedCount(count: Int): String? =
    if (count > 1) pluralStringResource(R.plurals.wine_count, count, count) else null
