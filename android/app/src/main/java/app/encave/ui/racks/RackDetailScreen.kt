package app.encave.ui.racks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Maturity
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import app.encave.ui.cellar.BottleDocumentsSheet
import app.encave.ui.cellar.DocumentCount
import app.encave.ui.components.ActionContent
import app.encave.ui.components.BottleEnd
import app.encave.ui.components.EmptyState
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.rememberRetained
import app.encave.ui.components.MaturityLabel
import app.encave.ui.components.displayLabel
import app.encave.ui.drink.DrinkSheet
import app.encave.ui.drink.rememberDrinking
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.RackDetailRoute
import app.encave.ui.navigation.RackEditorRoute
import app.encave.ui.navigation.WineDetailRoute
import app.encave.ui.theme.Dimens
import java.util.UUID
import kotlinx.coroutines.delay

private fun RackCell.key() = "$row:$column"

private fun Rack.cellFor(key: String?): RackCell? =
    key?.split(":")?.let { (row, column) -> RackCell(id, row.toInt(), column.toInt()) }?.takeIf { contains(it) }

/**
 * One rack, drawn as it is built, zoomable. Tap a bottle to see what it is in a card
 * under the rack; a place holding several bottles opens their list; an empty place adds
 * a bottle there. "Move" (or a long press) then a tap on another place moves or swaps it.
 * The key at the bottom lists the rack's wines: tap one to see where its bottles are.
 */
@Composable
fun RackDetailScreen(viewModel: AppViewModel, navigator: AppNavigator, route: RackDetailRoute) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val rack = cellar.racks.firstOrNull { it.id.toString() == route.rackId }
    var movingId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    var openCell by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusWineId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightId by rememberSaveable { mutableStateOf(route.highlightBottleId) }
    var showsList by rememberSaveable { mutableStateOf(false) }
    var drinking by rememberDrinking()
    /** A bottle whose documents are shown, and whether the file picker opens at once. */
    var bottleDocuments by remember { mutableStateOf<Pair<UUID, Boolean>?>(null) }
    // Checking the rack: the places whose bottles were found.
    var auditing by rememberSaveable { mutableStateOf(false) }
    var checked by rememberRetained("rack.audit.checked") { mutableStateOf(setOf<Slot>()) }
    var confirmingAudit by remember { mutableStateOf(false) }
    fun endAudit() {
        auditing = false
        checked = emptySet()
        confirmingAudit = false
    }
    androidx.activity.compose.BackHandler(auditing) { endAudit() }
    val inRack = rack?.let { r -> cellar.bottlesInCellar.filter { it.slot?.rackId == r.id } }.orEmpty()
    val missing = inRack.filter { it.slot !in checked }
    val moving = movingId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    val highlighted = highlightId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id }?.slot }
        ?.takeIf { it.rackId == rack?.id }
    val selectedCell = rack?.cellFor(selectedKey)
    val selectedBottle = selectedCell?.let { cell -> cellar.bottlesIn(cell).firstOrNull() }
    val focusWine = focusWineId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    // Tablets and large screens: drag a bottle to another place (touch and hold, then drag).
    val dragAndDrop = androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // A bottle shown from elsewhere ("Show in rack") or picked in the list: select it, bring it
    // into view, and stop pointing to it after a moment.
    val zoom = rack?.let { rememberRackZoomState(it) }
    LaunchedEffect(highlighted, rack?.id) {
        if (highlighted == null || rack == null) return@LaunchedEffect
        if (rack.positionsPerCell == 1) selectedKey = highlighted.cell.key()
        zoom?.show(rack, highlighted.cell)
        delay(4000)
        highlightId = null
    }
    // The card closes once its bottle is gone (drunk, moved).
    LaunchedEffect(selectedKey, selectedBottle) {
        if (selectedKey != null && selectedBottle == null) selectedKey = null
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
                        IconButton(
                            onClick = {
                                selectedKey = null
                                movingId = null
                                checked = emptySet()
                                auditing = true
                            },
                            enabled = !auditing,
                            modifier = Modifier.testTag("rack.audit"),
                        ) {
                            Icon(Icons.Outlined.Checklist, contentDescription = stringResource(R.string.action_check_rack))
                        }
                        IconButton(onClick = { showsList = true }, modifier = Modifier.testTag("action.bottle_list")) {
                            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.action_bottle_list))
                        }
                        IconButton(
                            onClick = { navigator.open(RackEditorRoute(rack.id.toString())) },
                            modifier = Modifier.testTag("action.edit_rack"),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rack_editor_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (rack == null || zoom == null) {
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
            val moveHere = stringResource(R.string.rack_cell_move_here)
            val addHere = stringResource(R.string.rack_cell_add)
            val actions = stringResource(R.string.rack_cell_actions)
            val foundLabel = stringResource(R.string.audit_found_state)
            ZoomableRack(rack, zoom, Modifier.weight(1f).fillMaxWidth()) { cellSize, zoomHere ->
                RackGrid(
                    rack = rack,
                    cellar = cellar,
                    cellSize = cellSize,
                    highlighted = highlighted,
                    selected = if (auditing) checked else setOfNotNull(moving?.slot),
                    selectedCell = selectedCell.takeIf { moving == null && !auditing },
                    focusWine = focusWine.takeIf { moving == null && !auditing },
                    clickLabel = { bottles ->
                        when {
                            auditing -> foundLabel
                            moving != null -> moveHere
                            bottles.isEmpty() -> addHere
                            else -> actions
                        }
                    },
                    onCellClick = { cell, bottles ->
                        val source = moving
                        when {
                            // A tap says "the bottles of this place are here" (or undoes it).
                            auditing -> {
                                val slots = bottles.mapNotNull { it.slot }.toSet()
                                if (slots.isNotEmpty()) checked = if (checked.containsAll(slots)) checked - slots else checked + slots
                            }
                            source != null -> {
                                movingId = null
                                val from = source.slot
                                if (from == null || from.cell == cell) return@RackGrid
                                val free = cellar.freeSlot(cell)
                                when {
                                    free != null -> viewModel.move(source.id, free)
                                    rack.positionsPerCell == 1 -> viewModel.swap(from, Slot(cell.rackId, cell.row, cell.column))
                                    else -> viewModel.showMessage(UiText.Res(R.string.rack_cell_full))
                                }
                            }
                            bottles.isEmpty() -> cellar.freeSlot(cell)?.let { free ->
                                selectedKey = null
                                navigator.open(
                                    AddBottleRoute(rackId = rack.id.toString(), row = free.row, column = free.column, depth = free.depth),
                                )
                            }
                            // One bottle per place: show it in the card. Several: open the list of the place.
                            rack.positionsPerCell == 1 -> selectedKey = cell.key()
                            else -> {
                                selectedKey = null
                                openCell = cell.key()
                            }
                        }
                    },
                    onCellLongClick = if (dragAndDrop) null else { _, bottles ->
                        if (auditing) return@RackGrid
                        bottles.firstOrNull()?.let {
                            selectedKey = null
                            movingId = it.id.toString()
                        }
                    },
                    onCellDoubleClick = { zoomHere() },
                    onDrop = if (!dragAndDrop || auditing || moving != null) null else { bottle, cell ->
                        val from = bottle.slot
                        when {
                            from == null -> Unit
                            // Held without moving: tap where to put it, as on phones.
                            from.cell == cell -> {
                                selectedKey = null
                                movingId = bottle.id.toString()
                            }
                            else -> {
                                val free = cellar.freeSlot(cell)
                                when {
                                    free != null -> viewModel.move(bottle.id, free)
                                    rack.positionsPerCell == 1 -> viewModel.swap(from, Slot(cell.rackId, cell.row, cell.column))
                                    else -> viewModel.showMessage(UiText.Res(R.string.rack_cell_full))
                                }
                                if (rack.positionsPerCell == 1) selectedKey = cell.key()
                            }
                        }
                    },
                )
            }
            if (auditing) {
                AuditBar(
                    checked = inRack.size - missing.size,
                    total = inRack.size,
                    onFinish = {
                        if (missing.isEmpty()) {
                            endAudit()
                            viewModel.showMessage(UiText.Res(R.string.audit_all_found))
                        } else {
                            confirmingAudit = true
                        }
                    },
                    onCancel = ::endAudit,
                )
            } else if (moving == null) {
                AnimatedVisibility(selectedBottle != null, Modifier.align(Alignment.CenterHorizontally)) {
                    // Keep the last bottle while the card slides away.
                    val shown = remember { arrayOfNulls<Bottle>(1) }
                    if (selectedBottle != null) shown[0] = selectedBottle
                    shown[0]?.let { bottle ->
                        SelectedBottleCard(
                            rack = rack,
                            bottle = bottle,
                            cellar = cellar,
                            year = viewModel.year,
                            onDrink = { drinking = bottle.id.toString() },
                            onMove = {
                                selectedKey = null
                                movingId = bottle.id.toString()
                            },
                            onWinePage = { navigator.open(WineDetailRoute(bottle.wineId.toString())) },
                            onDocuments = { importing -> bottleDocuments = bottle.id to importing },
                            onClose = { selectedKey = null },
                            modifier = Modifier.padding(horizontal = Dimens.l).padding(bottom = Dimens.s),
                        )
                    }
                }
                RackKey(rack, cellar, focusWine) { focusWineId = it?.toString() }
            }
        }
    }

    if (showsList && rack != null) {
        BottleListSheet(
            rack = rack,
            cellar = cellar,
            onDismiss = { showsList = false },
            onPick = { bottle ->
                // Chosen in the list: close it and point to the bottle in the drawing.
                showsList = false
                highlightId = bottle.id.toString()
                bottle.slot?.let { if (rack.positionsPerCell == 1) selectedKey = it.cell.key() }
            },
        )
    }
    val cellRef = rack?.cellFor(openCell)
    if (cellRef != null && rack != null) {
        CellSheet(
            rack = rack,
            cell = cellRef,
            cellar = cellar,
            onDismiss = { openCell = null },
            onBottle = { actionsId = it.id.toString() },
            onAdd = cellar.freeSlot(cellRef)?.let { free ->
                { navigator.open(AddBottleRoute(rackId = rack.id.toString(), row = free.row, column = free.column, depth = free.depth)) }
            },
        )
    }
    val actionBottle = actionsId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    if (actionBottle != null && rack != null) {
        BottleActionsSheet(
            rack = rack,
            bottle = actionBottle,
            title = cellar.wines[actionBottle.wineId]?.displayName.orEmpty(),
            onDismiss = { actionsId = null },
            onOpen = { navigator.open(WineDetailRoute(actionBottle.wineId.toString())) },
            onDrink = { drinking = actionBottle.id.toString() },
            onMove = { movingId = actionBottle.id.toString() },
            onTakeOut = { viewModel.move(actionBottle.id, null) },
            onGone = { viewModel.markGone(actionBottle.id) },
            onDocuments = { importing -> bottleDocuments = actionBottle.id to importing },
        )
    }
    bottleDocuments?.let { (bottleId, importing) ->
        BottleDocumentsSheet(viewModel, bottleId, startPicking = importing, onDismiss = { bottleDocuments = null })
    }
    if (confirmingAudit && missing.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingAudit = false },
            title = { Text(stringResource(R.string.audit_missing_title, missing.size)) },
            text = { Text(stringResource(R.string.audit_missing_body)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = { viewModel.takeOut(missing.map { it.id }.toSet()); endAudit() },
                        modifier = Modifier.testTag("audit.take_out"),
                    ) { Text(stringResource(R.string.action_take_them_out)) }
                    TextButton(onClick = { viewModel.markGone(missing.map { it.id }.toSet()); endAudit() }) {
                        Text(stringResource(R.string.action_mark_them_gone), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { confirmingAudit = false }) { Text(stringResource(R.string.action_keep_checking)) }
                }
            },
        )
    }
    drinking?.let { id ->
        DrinkSheet(
            cellar = cellar,
            bottleId = id,
            onDismiss = { drinking = null },
            onDrink = { chosen, tasting -> viewModel.drink(chosen.id, tasting) },
        )
    }
}

/** The bottle tapped in the rack: what it is and where, with its actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedBottleCard(
    rack: Rack,
    bottle: Bottle,
    cellar: Cellar,
    year: Int,
    onDrink: () -> Unit,
    onMove: () -> Unit,
    onWinePage: () -> Unit,
    /** The bottle's documents; true opens the file picker at once. */
    onDocuments: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wine = cellar.wines[bottle.wineId]
    val scheme = MaterialTheme.colorScheme
    val resources = LocalResources.current
    Surface(
        modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("rack.selection"),
        shape = RoundedCornerShape(Dimens.radiusCard),
        color = scheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                BottleEnd(wine?.color, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        wine?.displayName ?: stringResource(R.string.unknown_wine),
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    wine?.producer?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        bottle.slot?.let { slot ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                                Icon(Icons.Outlined.Place, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Text(rack.displayLabel(slot, resources), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                            }
                        }
                        wine?.let { MaturityLabel(Maturity.of(it, year)) }
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.testTag("rack.selection.close")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDrink, modifier = Modifier.testTag("action.rack_drink")) {
                    ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar)
                }
                OutlinedButton(onClick = onMove, modifier = Modifier.testTag("action.rack_move")) {
                    ActionContent(stringResource(R.string.action_move), Icons.Outlined.OpenWith)
                }
                Spacer(Modifier.weight(1f))
                val documents = bottle.attachments?.size
                if (documents != null) {
                    TextButton(onClick = { onDocuments(false) }, modifier = Modifier.testTag("rack.selection.documents")) {
                        DocumentCount(documents)
                    }
                } else {
                    IconButton(onClick = { onDocuments(true) }, modifier = Modifier.testTag("rack.selection.documents")) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.action_attach_document))
                    }
                }
                FilledTonalIconButton(onClick = onWinePage, modifier = Modifier.testTag("rack.selection.wine")) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_wine_page))
                }
            }
        }
    }
}

/**
 * The wines of the rack, most bottles first. Tap one to see where its bottles are:
 * they stand out and the others fade. Tap it again to show all.
 */
@Composable
private fun RackKey(rack: Rack, cellar: Cellar, focusWine: UUID?, onFocus: (UUID?) -> Unit) {
    val bottles = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }
    val entries = bottles.groupingBy { it.wineId }.eachCount()
        .mapNotNull { (id, count) -> cellar.wines[id]?.let { it to count } }
        .sortedWith(compareByDescending<Pair<Wine, Int>> { it.second }.thenBy { it.first.displayName })
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("rack.key")) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l, vertical = Dimens.s),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(rack.kind.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.rack_key_count, bottles.size, rack.capacity),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = Dimens.xs),
            )
            entries.forEach { (wine, count) ->
                val isOn = focusWine == wine.id
                val description = pluralStringResource(R.plurals.rack_key_wine, count, wine.displayName, count)
                FilterChip(
                    selected = isOn,
                    onClick = { onFocus(if (isOn) null else wine.id) },
                    label = {
                        Row(Modifier.clearAndSetSemantics { }) {
                            Text(wine.displayName, maxLines = 1)
                            Text(" ×$count", color = if (isOn) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    leadingIcon = { BottleEnd(wine.color, size = 16.dp) },
                    trailingIcon = if (isOn) {
                        { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("rack.key.wine").semantics { contentDescription = description },
                )
            }
        }
    }
}

/** The bottles of the rack, place by place. Picking one closes the list and selects it in the drawing. */
@Composable
private fun BottleListSheet(rack: Rack, cellar: Cellar, onDismiss: () -> Unit, onPick: (Bottle) -> Unit) {
    val resources = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val bottles = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }
        .sortedWith(compareBy({ it.slot?.row ?: 0 }, { it.slot?.column ?: 0 }, { it.slot?.depth ?: 0 }))
    val rows = bottles.groupBy { it.slot?.row ?: 0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding().testTag("rack.list")) {
            item {
                ListItem(
                    leadingContent = { Icon(rack.kind.icon, contentDescription = null) },
                    headlineContent = { Text(rack.name, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = { Text(stringResource(R.string.rack_key_count, bottles.size, rack.capacity)) },
                )
                LinearProgressIndicator(
                    progress = { bottles.size.toFloat() / rack.capacity.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).padding(bottom = Dimens.s),
                )
            }
            if (bottles.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rack_list_empty_title)) },
                        supportingContent = { Text(stringResource(R.string.rack_list_empty)) },
                    )
                }
            }
            rows.keys.sorted().forEach { row ->
                item(key = "row-$row") {
                    val header = if (rack.kind == RackKind.Shelves) {
                        rack.displayLabel(RackCell(rack.id, row, 0), resources).substringBefore(",")
                    } else {
                        stringResource(R.string.rack_row, Rack.rowLetters(row))
                    }
                    Text(
                        header,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.primary,
                        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s).semantics { heading() },
                    )
                }
                items(rows[row].orEmpty(), key = { it.id.toString() }) { bottle ->
                    val wine = cellar.wines[bottle.wineId]
                    ListItem(
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                                Text(
                                    bottle.slot?.let { rack.label(it) }.orEmpty(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.width(44.dp),
                                )
                                BottleEnd(wine?.color, size = 20.dp)
                            }
                        },
                        headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = wine?.producer?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = bottle.attachments?.size?.let { { DocumentCount(it) } },
                        modifier = Modifier.clickable { onPick(bottle) }.testTag("rack.list.row"),
                    )
                }
            }
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
    onDocuments: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text("${rack.name} · ${bottle.slot?.let { rack.displayLabel(it, LocalResources.current) }.orEmpty()}") },
            )
            listOf(
                Triple(Icons.AutoMirrored.Outlined.OpenInNew, R.string.action_open_wine, onOpen),
                Triple(Icons.Outlined.LocalBar, R.string.action_drink, onDrink),
                Triple(Icons.Outlined.OpenWith, R.string.action_move, onMove),
                Triple(Icons.AutoMirrored.Outlined.Logout, R.string.action_remove_from_rack, onTakeOut),
                Triple(Icons.Outlined.VolunteerActivism, R.string.action_mark_gone, onGone),
            ).let { actions ->
                // The documents before the bottle leaves: "Documents…" when it has some.
                val documents = listOfNotNull(
                    if (bottle.attachments != null) Triple(Icons.Outlined.AttachFile, R.string.action_documents, { onDocuments(false) }) else null,
                    Triple(Icons.Outlined.UploadFile, R.string.action_attach_document, { onDocuments(true) }),
                )
                actions.dropLast(1) + documents + actions.last()
            }.forEach { (icon, label, action) ->
                ListItem(
                    leadingContent = { Icon(icon, contentDescription = null) },
                    headlineContent = { Text(stringResource(label)) },
                    modifier = Modifier.clickable { onDismiss(); action() },
                )
            }
        }
    }
}

/** The bottles in one cell (several for double depth, shelves and bins), and room to add one. */
@Composable
private fun CellSheet(
    rack: Rack,
    cell: app.encave.core.RackCell,
    cellar: app.encave.core.Cellar,
    onDismiss: () -> Unit,
    onBottle: (Bottle) -> Unit,
    onAdd: (() -> Unit)?,
) {
    val resources = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
            ListItem(
                headlineContent = { Text(rack.displayLabel(cell, resources), style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text(rack.name) },
            )
            cellar.bottlesIn(cell).forEach { bottle ->
                val wine = cellar.wines[bottle.wineId]
                ListItem(
                    leadingContent = { app.encave.ui.components.WineColorDot(wine?.color, size = 28.dp) },
                    headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine)) },
                    supportingContent = { Text(bottle.slot?.let { rack.displayLabel(it, resources) }.orEmpty()) },
                    trailingContent = bottle.attachments?.size?.let { { DocumentCount(it) } },
                    modifier = Modifier.clickable { onDismiss(); onBottle(bottle) },
                )
            }
            if (onAdd != null) {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.rack_add_here)) },
                    modifier = Modifier.clickable { onDismiss(); onAdd() },
                )
            }
        }
    }
}

/** While checking a rack: how many bottles were found, and End check. */
@Composable
private fun AuditBar(checked: Int, total: Int, onFinish: () -> Unit, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimens.radiusCard),
        modifier = Modifier.fillMaxWidth().padding(Dimens.l),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Icon(Icons.Outlined.Checklist, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.audit_hint), style = MaterialTheme.typography.titleSmall)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { checked.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.audit_found, checked, total),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Dimens.s),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("sheet.cancel")) { Text(stringResource(R.string.action_cancel)) }
                androidx.compose.material3.Button(onClick = onFinish, modifier = Modifier.testTag("action.audit.finish")) {
                    app.encave.ui.components.ActionContent(stringResource(R.string.action_end_check), Icons.Filled.Check)
                }
            }
        }
    }
}
