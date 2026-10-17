package app.encave.ui.racks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Rack
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.RackEditorRoute
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import kotlin.math.roundToInt

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 3f

/** Lays the content out at its natural size times [scale], so scrolling covers the zoomed rack. */
private fun Modifier.scaled(scale: Float) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

/** Two-finger pinch only, so one finger still scrolls and taps cells. */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onZoom(zoom)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * One rack, drawn as it is built. Tap a bottle for its actions, an empty slot
 * to add a bottle there; "Move" then a tap on another slot moves (or swaps) it.
 */
@Composable
fun RackDetailScreen(viewModel: AppViewModel, navigator: AppNavigator, route: RackDetailRoute) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val rack = cellar.racks.firstOrNull { it.id.toString() == route.rackId }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    var drinking by remember { mutableStateOf<Bottle?>(null) }
    val moving = movingId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    val highlighted = route.highlightBottleId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id }?.slot }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current

    // "Show in rack": bring the highlighted slot into view.
    LaunchedEffect(highlighted, rack) {
        if (highlighted == null || rack == null) return@LaunchedEffect
        val step = with(density) { (Dimens.touchTarget + 12.dp).toPx() }
        horizontal.animateScrollTo((highlighted.column * step - step).roundToInt().coerceAtLeast(0))
        vertical.animateScrollTo((highlighted.row * step - step).roundToInt().coerceAtLeast(0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(rack?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (rack != null) {
                            Text(
                                stringResource(R.string.rack_occupancy, cellar.occupancy(rack), rack.capacity),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (rack != null) {
                        IconButton(onClick = { navigator.open(RackEditorRoute(rack.id.toString())) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rack_editor_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (rack == null) {
            EmptyState(Icons.Outlined.GridView, stringResource(R.string.rack_not_found), "", Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (moving != null) {
                val name = cellar.wines[moving.wineId]?.displayName.orEmpty()
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.rack_move_hint, name),
                            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { movingId = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pinchToZoom { zoom -> scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM) },
            ) {
                Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal).padding(Dimens.l)) {
                    val moveHere = stringResource(R.string.rack_cell_move_here)
                    val addHere = stringResource(R.string.rack_cell_add)
                    val actions = stringResource(R.string.rack_cell_actions)
                    RackGrid(
                        rack = rack,
                        cellar = cellar,
                        modifier = Modifier.scaled(scale),
                        highlighted = highlighted,
                        selected = setOfNotNull(moving?.slot),
                        clickLabel = { bottle -> if (moving != null) moveHere else if (bottle == null) addHere else actions },
                        onCellClick = { slot, bottle ->
                            val source = moving
                            when {
                                source != null -> {
                                    val from = source.slot
                                    when {
                                        from == slot -> Unit
                                        bottle == null -> viewModel.move(source.id, slot)
                                        from != null -> viewModel.swap(from, slot)
                                        else -> Unit
                                    }
                                    movingId = null
                                }
                                bottle != null -> actionsId = bottle.id.toString()
                                else -> navigator.open(AddBottleRoute(rackId = rack.id.toString(), row = slot.row, column = slot.column))
                            }
                        },
                        onCellLongClick = { _, bottle -> if (bottle != null) movingId = bottle.id.toString() },
                    )
                }
                ZoomControls(
                    onZoomIn = { scale = (scale * 1.25f).coerceAtMost(MAX_ZOOM) },
                    onZoomOut = { scale = (scale / 1.25f).coerceAtLeast(MIN_ZOOM) },
                    onReset = { scale = 1f },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Dimens.l).navigationBarsPadding(),
                )
            }
        }
    }

    val actionBottle = actionsId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    if (actionBottle != null && rack != null) {
        BottleActionsSheet(
            rack = rack,
            bottle = actionBottle,
            title = cellar.wines[actionBottle.wineId]?.displayName.orEmpty(),
            onDismiss = { actionsId = null },
            onOpen = { navigator.open(WineDetailRoute(actionBottle.wineId.toString())) },
            onDrink = { drinking = actionBottle },
            onMove = { movingId = actionBottle.id.toString() },
            onTakeOut = { viewModel.move(actionBottle.id, null) },
            onGone = { viewModel.markGone(actionBottle.id) },
        )
    }
    drinking?.let { bottle ->
        DrinkSheet(
            cellar = cellar,
            bottles = listOf(bottle),
            onDismiss = { drinking = null },
            onDrink = { chosen, tasting -> viewModel.drink(chosen.id, tasting) },
        )
    }
}

@Composable
private fun ZoomControls(onZoomIn: () -> Unit, onZoomOut: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        FilledTonalIconButton(onClick = onZoomIn) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_zoom_in)) }
        FilledTonalIconButton(onClick = onZoomOut) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.action_zoom_out)) }
        FilledTonalIconButton(onClick = onReset) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = stringResource(R.string.action_zoom_reset))
        }
    }
}

@Composable
private fun BottleActionsSheet(
    rack: Rack,
    bottle: Bottle,
    title: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDrink: () -> Unit,
    onMove: () -> Unit,
    onTakeOut: () -> Unit,
    onGone: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text("${rack.name} · ${bottle.slot?.let(rack::label).orEmpty()}") },
            )
            listOf(
                Triple(Icons.AutoMirrored.Outlined.OpenInNew, R.string.action_open_wine, onOpen),
                Triple(Icons.Outlined.LocalBar, R.string.action_drink, onDrink),
                Triple(Icons.Outlined.OpenWith, R.string.action_move, onMove),
                Triple(Icons.AutoMirrored.Outlined.Logout, R.string.action_remove_from_rack, onTakeOut),
                Triple(Icons.Outlined.VolunteerActivism, R.string.action_mark_gone, onGone),
            ).forEach { (icon, label, action) ->
                ListItem(
                    leadingContent = { Icon(icon, contentDescription = null) },
                    headlineContent = { Text(stringResource(label)) },
                    modifier = Modifier.clickable { onDismiss(); action() },
                )
            }
        }
    }
}
