package app.encave.ui.racks

import app.encave.ui.components.styleLine
import app.encave.ui.components.WineThumbnail
import androidx.compose.material.icons.outlined.CardGiftcard
import app.encave.ui.components.BottlePlaceIcons
import app.encave.core.Slot
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import app.encave.ui.components.depthName
import app.encave.ui.components.labelRes
import app.encave.ui.components.zoneName
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
import app.encave.core.Wine
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import app.encave.ui.cellar.BottleDocumentsSheet
import app.encave.ui.cellar.DocumentCount
import app.encave.ui.components.ActionButtonPair
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalDensity
import app.encave.ui.components.STACKED_ACTIONS_FONT_SCALE
import app.encave.ui.components.ActionContent
import app.encave.ui.components.BottleEnd
import app.encave.ui.components.icon
import app.encave.core.styleMark
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
    var movingId by rememberSaveable { mutableStateOf(route.moveBottleId) }
    var actionsId by rememberSaveable { mutableStateOf<String?>(null) }
    /** A bottle being moved to another rack (or out of the racks) in the Move sheet. */
    var moveSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    /** While checking: a place whose bottles are checked one by one. */
    var checkingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openCell by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusWineId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightId by rememberSaveable { mutableStateOf(route.highlightBottleId) }
    var showsList by rememberSaveable { mutableStateOf(false) }
    /** A bottle being kept for an occasion (the occasion is asked first). */
    var reservingId by rememberSaveable { mutableStateOf<String?>(null) }
    /** A free position tapped while bottles are out of storage: add one, or put one there. */
    var freePlace by remember { mutableStateOf<Slot?>(null) }
    var puttingHere by remember { mutableStateOf<Slot?>(null) }
    var drinking by rememberDrinking()
    /** A bottle whose documents are shown, and whether the file picker opens at once. */
    var bottleDocuments by remember { mutableStateOf<Pair<UUID, Boolean>?>(null) }
    // Checking the rack: the bottles found (by ID, so a bottle found and then moved stays found).
    var auditing by rememberSaveable { mutableStateOf(false) }
    var checked by rememberRetained("rack.audit.checked") { mutableStateOf(setOf<UUID>()) }
    // Deleted (from its editor beside the list, or on another device): closes as a screen popped off.
    var seen by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(rack != null) { if (rack != null) seen = true else if (seen) navigator.back() }
    var confirmingAudit by remember { mutableStateOf(false) }
    fun endAudit() {
        auditing = false
        checked = emptySet()
        confirmingAudit = false
    }
    androidx.activity.compose.BackHandler(auditing) { endAudit() }
    // Back ends choosing where to move a bottle, then closes the bottle's card, before leaving the rack.
    androidx.activity.compose.BackHandler(enabled = movingId != null && !auditing) { movingId = null }
    androidx.activity.compose.BackHandler(enabled = selectedKey != null && movingId == null && !auditing) { selectedKey = null }
    val inRack = remember(cellar, rack) { rack?.let { r -> cellar.bottlesInCellar.filter { it.slot?.rackId == r.id } }.orEmpty() }
    val missing = remember(cellar, rack, checked) { rack?.let { cellar.missingBottles(it.id, checked) }.orEmpty() }
    // Where the bottles found are now.
    val foundSlots = remember(inRack, checked) { inRack.filter { it.id in checked }.mapNotNull { it.slot }.toSet() }
    val moving = movingId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    val highlighted = highlightId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id }?.slot }
        ?.takeIf { it.rackId == rack?.id }
    val selectedPlace = rack?.let { RackPlace.of(selectedKey, it) }
    val selectedBottle = selectedPlace?.let { place -> cellar.bottlesAt(place).firstOrNull() }
    val focusWine = focusWineId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    // Tablets and large screens: drag a bottle to another place (touch and hold, then drag).
    val dragAndDrop = androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // A bottle shown from elsewhere ("Show in rack") or picked in the list: select it, bring it
    // into view, and stop pointing to it after a moment.
    val zoom = rack?.let { rememberRackZoomState(it) }
    LaunchedEffect(highlighted, rack?.id) {
        if (highlighted == null || rack == null) return@LaunchedEffect
        RackPlace.of(highlighted, rack).takeIf { it.holdsOne(rack, cellar) }?.let { selectedKey = it.key() }
        // A back place only on a shelf drawn front and back.
        zoom?.show(rack, highlighted.cell, RackPlace.of(highlighted, rack).depth)
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
                        // A long name gets smaller before it is cut, beside the three buttons;
                        // on two lines at the largest font sizes.
                        Text(
                            rack?.name.orEmpty(), overflow = TextOverflow.Ellipsis,
                            maxLines = if (LocalDensity.current.fontScale >= STACKED_ACTIONS_FONT_SCALE) 2 else 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                        )
                        if (rack != null) {
                            Text(
                                stringResource(R.string.rack_occupancy, cellar.occupancy(rack), cellar.capacity(rack)),
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
                        TextButton(
                            onClick = { movingId = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            val moveHere = stringResource(R.string.rack_cell_move_here)
            val addHere = stringResource(R.string.rack_cell_add)
            val actions = stringResource(R.string.rack_cell_actions)
            val foundLabel = stringResource(R.string.audit_found_state)
            val moveLabel = stringResource(R.string.action_move)
            val checkLabel = stringResource(R.string.rack_check_one_by_one)
            val drinkLabel = stringResource(R.string.action_drink)
            val winePageLabel = stringResource(R.string.action_show_wine)
            ZoomableRack(rack, zoom, Modifier.weight(1f).fillMaxWidth()) { cellSize, zoomHere ->
                RackGrid(
                    rack = rack,
                    cellar = cellar,
                    cellSize = cellSize,
                    highlighted = highlighted,
                    selected = if (auditing) foundSlots else setOfNotNull(moving?.slot),
                    selectedPlace = selectedPlace.takeIf { moving == null && !auditing },
                    focusWine = focusWine.takeIf { moving == null && !auditing },
                    countsFound = auditing,
                    quietEmpty = moving == null && !auditing,
                    clickLabel = { bottles ->
                        when {
                            auditing -> foundLabel
                            moving != null -> moveHere
                            bottles.isEmpty() -> addHere
                            else -> actions
                        }
                    },
                    onCellClick = { place, bottles ->
                        val source = moving
                        when {
                            // A tap says "the bottles of this place are here" (or undoes it).
                            auditing -> {
                                val ids = bottles.map { it.id }.toSet()
                                if (ids.isNotEmpty()) checked = if (checked.containsAll(ids)) checked - ids else checked + ids
                            }
                            source != null -> {
                                movingId = null
                                // A bottle without a place (Move in a wine's menu) takes a free place.
                                val from = source.slot
                                if (from != null && from in place) return@RackGrid
                                val free = cellar.freeSlotAt(place)
                                when {
                                    free != null -> viewModel.move(source.id, free)
                                    from != null && place.holdsOne(rack, cellar) -> viewModel.swap(from, place.firstSlot)
                                    else -> viewModel.showMessage(UiText.Res(R.string.rack_cell_full))
                                }
                            }
                            bottles.isEmpty() -> cellar.freeSlotAt(place)?.let { free ->
                                selectedKey = null
                                // Bottles out of storage: one of them may go here instead of a new one.
                                if (cellar.bottlesNotInStorage.isEmpty()) {
                                    navigator.open(
                                        AddBottleRoute(rackId = rack.id.toString(), row = free.row, column = free.column, depth = free.depth),
                                    )
                                } else {
                                    freePlace = free
                                }
                            }
                            // One bottle per place: show it in the card. Several: open the list of the place.
                            place.holdsOne(rack, cellar) -> selectedKey = place.key()
                            else -> {
                                selectedKey = null
                                openCell = place.cell.key()
                            }
                        }
                    },
                    onCellLongClick = { place, bottles ->
                        if (auditing) {
                            checkingKey = place.cell.key()
                        } else {
                            bottles.firstOrNull()?.let {
                                selectedKey = null
                                movingId = it.id.toString()
                            }
                        }
                    },
                    longClickLabel = { bottles ->
                        when {
                            // Some of a place's bottles found: check them one by one.
                            auditing -> checkLabel.takeIf { bottles.size > 1 }
                            moving != null || dragAndDrop -> null
                            else -> moveLabel.takeIf { bottles.isNotEmpty() }
                        }
                    },
                    customActions = { place, bottles ->
                        val bottle = bottles.singleOrNull()
                        if (bottle == null || auditing || moving != null || !place.holdsOne(rack, cellar)) {
                            emptyList()
                        } else {
                            listOf(
                                CustomAccessibilityAction(drinkLabel) { drinking = bottle.id.toString(); true },
                                // Move, as a long press does: then the new position.
                                CustomAccessibilityAction(moveLabel) { selectedKey = null; movingId = bottle.id.toString(); true },
                                CustomAccessibilityAction(winePageLabel) { navigator.open(WineDetailRoute(bottle.wineId.toString())); true },
                            )
                        }
                    },
                    // Taps answer at once while checking or moving; double-tap zooms only when browsing.
                    onCellDoubleClick = if (auditing || moving != null) null else { _ -> zoomHere() },
                    onDrop = if (!dragAndDrop || auditing || moving != null) null else { bottle, place ->
                        val from = bottle.slot
                        when {
                            from == null -> Unit
                            // Held without moving: tap where to put it, as on phones.
                            from in place -> {
                                selectedKey = null
                                movingId = bottle.id.toString()
                            }
                            else -> {
                                val free = cellar.freeSlotAt(place)
                                when {
                                    free != null -> viewModel.move(bottle.id, free)
                                    place.holdsOne(rack, cellar) -> viewModel.swap(from, place.firstSlot)
                                    else -> viewModel.showMessage(UiText.Res(R.string.rack_cell_full))
                                }
                                if (place.holdsOne(rack, cellar)) selectedKey = place.key()
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
                            onMoveElsewhere = { moveSheetId = bottle.id.toString() },
                            onReserve = {
                                if (bottle.reservedFor == null) reservingId = bottle.id.toString()
                                else viewModel.reserve(listOf(bottle.id), null)
                            },
                            onTakeOut = { viewModel.move(bottle.id, null) },
                            onGone = { viewModel.markGone(bottle.id) },
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
                bottle.slot?.let { RackPlace.of(it, rack).takeIf { place -> place.holdsOne(rack, cellar) }?.let { place -> selectedKey = place.key() } }
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
            onMoveElsewhere = { moveSheetId = actionBottle.id.toString() },
            onReserve = {
                if (actionBottle.reservedFor == null) reservingId = actionBottle.id.toString()
                else viewModel.reserve(listOf(actionBottle.id), null)
            },
            onTakeOut = { viewModel.move(actionBottle.id, null) },
            onGone = { viewModel.markGone(actionBottle.id) },
            onDocuments = { importing -> bottleDocuments = actionBottle.id to importing },
        )
    }
    reservingId?.let { id ->
        app.encave.ui.cellar.ReserveDialog(
            initial = cellar.reservations.firstOrNull().orEmpty(),
            onKeep = { occasion -> viewModel.reserve(listOf(UUID.fromString(id)), occasion) },
            onDismiss = { reservingId = null },
        )
    }
    val moveSheetBottle = moveSheetId?.let { id -> cellar.bottlesInCellar.firstOrNull { it.id.toString() == id } }
    if (moveSheetBottle != null) {
        MoveBottleSheet(viewModel, cellar, moveSheetBottle, onDismiss = { moveSheetId = null })
    }
    val checkingCell = rack?.cellFor(checkingKey)?.takeIf { auditing }
    if (checkingCell != null && rack != null) {
        CheckOneByOneSheet(
            rack = rack,
            cell = checkingCell,
            cellar = cellar,
            checked = checked,
            onChange = { checked = it },
            onDismiss = { checkingKey = null },
        )
    }
    bottleDocuments?.let { (bottleId, importing) ->
        BottleDocumentsSheet(viewModel, bottleId, startPicking = importing, onDismiss = { bottleDocuments = null })
    }
    // A free position while bottles are out of storage: a new bottle, or one of those.
    freePlace?.let { slot ->
        val placeName = rack?.let { "${it.name} · ${it.displayLabel(slot, LocalResources.current)}" }.orEmpty()
        ModalBottomSheet(onDismissRequest = { freePlace = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l)) {
                ListItem(
                    headlineContent = { Text(placeName, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.not_in_storage_count, cellar.bottlesNotInStorage.size, cellar.bottlesNotInStorage.size))
                    },
                )
                ListItem(
                    leadingContent = { Icon(BottlePlaceIcons.PutAway, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.rack_put_here)) },
                    modifier = Modifier.clickable { freePlace = null; puttingHere = slot }.testTag("rack.free.put_here"),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.rack_add_here)) },
                    modifier = Modifier.clickable {
                        freePlace = null
                        navigator.open(AddBottleRoute(rackId = slot.rackId.toString(), row = slot.row, column = slot.column, depth = slot.depth))
                    },
                )
            }
        }
    }
    puttingHere?.let { slot ->
        val placeName = rack?.let { "${it.name} · ${it.displayLabel(slot, LocalResources.current)}" }.orEmpty()
        PutBottleHereSheet(viewModel, cellar, slot, placeName) { puttingHere = null }
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
    onMoveElsewhere: () -> Unit,
    /** Keep for an occasion, or free a kept bottle. */
    onReserve: () -> Unit,
    onTakeOut: () -> Unit,
    onGone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wine = cellar.wines[bottle.wineId]
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val resources = LocalResources.current
    // From this font scale on, the card uses full-width rows so no title is squeezed.
    val large = LocalDensity.current.fontScale >= STACKED_ACTIONS_FONT_SCALE
    val details: @Composable (Modifier) -> Unit = { textModifier ->
        Column(textModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                wine?.displayName ?: stringResource(R.string.unknown_wine),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            // The style in words, then the producer and appellation, as in the list and on Apple.
            wine?.styleLine()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                bottle.slot?.let { slot ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(rack.displayLabel(slot, resources), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    }
                }
                wine?.let { MaturityLabel(Maturity.of(it, year), short = true) }
            }
        }
    }
    val headerButtons: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("rack.selection.more")) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            // The bottle's other actions, in the same order as everywhere (and on Apple).
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (bottle.reservedFor == null) R.string.action_keep_for_occasion else R.string.action_free_bottle)) },
                    leadingIcon = { Icon(Icons.Outlined.CardGiftcard, contentDescription = null) },
                    onClick = { menu = false; onReserve() },
                    modifier = Modifier.testTag("rack.selection.reserve"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_other_rack)) },
                    leadingIcon = { Icon(Icons.Outlined.OpenWith, contentDescription = null) },
                    onClick = { menu = false; onMoveElsewhere() },
                    modifier = Modifier.testTag("rack.selection.move_elsewhere"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_take_out_of_rack)) },
                    leadingIcon = { Icon(Icons.Outlined.Outbox, contentDescription = null) },
                    onClick = { menu = false; onTakeOut() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_mark_gone)) },
                    leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
                    onClick = { menu = false; onGone() },
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("rack.selection.close")) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
    val actions: @Composable () -> Unit = {
        ActionButtonPair(
            equalWidths = large,
            first = { width ->
                Button(onClick = onDrink, modifier = width.testTag("action.rack_drink")) {
                    ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar)
                }
            },
            second = { width ->
                OutlinedButton(onClick = onMove, modifier = width.testTag("action.rack_move")) {
                    ActionContent(stringResource(R.string.action_move), Icons.Outlined.OpenWith)
                }
            },
        )
    }
    val icons: @Composable () -> Unit = {
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
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_show_wine))
        }
    }
    Surface(
        modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("rack.selection"),
        shape = RoundedCornerShape(Dimens.radiusCard),
        color = scheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            if (large) {
                // Large font sizes: the bottle and its buttons on a row, the text and the actions full width below.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WineThumbnail(wine, width = 40.dp)
                    Spacer(Modifier.weight(1f))
                    headerButtons()
                }
                details(Modifier.fillMaxWidth())
                actions()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    icons()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    WineThumbnail(wine, width = 40.dp)
                    details(Modifier.weight(1f))
                    headerButtons()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
                    actions()
                    Spacer(Modifier.weight(1f))
                    icons()
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
    val bottles = remember(cellar, rack) { cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id } }
    val entries = remember(bottles) {
        bottles.groupingBy { it.wineId }.eachCount()
            .mapNotNull { (id, count) -> cellar.wines[id]?.let { it to count } }
            .sortedWith(compareByDescending<Pair<Wine, Int>> { it.second }.thenBy { it.first.displayName })
    }
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("rack.key")) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.l, vertical = Dimens.s),
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(rack.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.rack_key_count, bottles.size, cellar.capacity(rack)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = Dimens.xs),
            )
            entries.forEach { (wine, count) ->
                val isOn = focusWine == wine.id
                // The style by its symbol, not only the bottle's color; its name is for TalkBack only,
                // so the wine's name has the room.
                val style = wine.color?.let { stringResource(it.labelRes) }
                val named = style?.let { stringResource(R.string.cell_wine_style, wine.displayName, it) } ?: wine.displayName
                val description = pluralStringResource(R.plurals.rack_key_wine, count, named, count)
                FilterChip(
                    selected = isOn,
                    onClick = { onFocus(if (isOn) null else wine.id) },
                    label = {
                        // A long name ends with an ellipsis; the style's symbol and the count always show.
                        Row(Modifier.widthIn(max = 300.dp * maxOf(1f, LocalDensity.current.fontScale)).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                            Text(wine.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val color = wine.color
                            if (style != null && color != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    color.styleMark.icon, contentDescription = null, tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp).testTag("rack.key.style"),
                                )
                            }
                            Text(" ×$count", color = if (isOn) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
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
    // A fridge on shelves goes shelf by shelf (bottom layer first); other racks row by row.
    val bottles = remember(cellar, rack) {
        cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id }.sortedWith(
            compareBy(
                { b -> b.slot?.let { rack.shelfPlace(it.row)?.shelf } ?: 0 },
                { b -> b.slot?.let { rack.shelfPlace(it.row)?.layer } ?: 0 },
                { it.slot?.row ?: 0 }, { it.slot?.column ?: 0 }, { it.slot?.depth ?: 0 },
            ),
        )
    }
    val rows = bottles.groupBy { b -> b.slot?.let { slot -> rack.shelfPlace(slot.row)?.shelf ?: slot.row } ?: 0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding().testTag("rack.list")) {
            item {
                ListItem(
                    leadingContent = { Icon(rack.icon, contentDescription = null) },
                    headlineContent = { Text(rack.name, style = MaterialTheme.typography.titleLarge) },
                    supportingContent = { Text(stringResource(R.string.rack_key_count, bottles.size, cellar.capacity(rack))) },
                )
                LinearProgressIndicator(
                    progress = { bottles.size.toFloat() / cellar.capacity(rack).coerceAtLeast(1) },
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
            // A diamond rack lists its top triangles first and its bottom ones last, as drawn.
            val drawn = rack.drawingRows()
            rows.keys.sortedBy { row -> drawn.indexOf(row).takeIf { it >= 0 } ?: row }.forEach { row ->
                item(key = "row-$row") {
                    val header = when {
                        rack.hasShelfLayers -> {
                            val shelf = stringResource(R.string.rack_shelf, row + 1)
                            if (rack.zoneCount > 1) {
                                stringResource(R.string.rack_list_shelf_zone, shelf, rack.zoneName(rack.zoneOfShelf(row), resources))
                            } else {
                                shelf
                            }
                        }
                        rack.kind == RackKind.Shelves -> rack.displayLabel(RackCell(rack.id, row, 0), resources).substringBefore(",")
                        rack.triangleRow(row) != null -> stringResource(rack.triangleRow(row)!!.togglesRes)
                        else -> stringResource(R.string.rack_row, Rack.rowLetters(row))
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
                    // "B3, back · Red · Producer": the place named as everywhere else.
                    val line = listOfNotNull(
                        bottle.slot?.let { rack.displayLabel(it, resources) },
                        wine?.color?.let { stringResource(it.labelRes) },
                        wine?.producer?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    ListItem(
                        leadingContent = { BottleEnd(wine?.color, size = 24.dp) },
                        headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
    onMoveElsewhere: () -> Unit,
    onReserve: () -> Unit,
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
            // The bottle's actions in the same order as everywhere (and on Apple): Drink, Move,
            // Keep, its documents, Show wine, then Take out and No longer in cellar.
            run {
                val documents = listOfNotNull(
                    if (bottle.attachments != null) Triple(Icons.Outlined.AttachFile, R.string.action_documents, { onDocuments(false) }) else null,
                    Triple(Icons.Outlined.UploadFile, R.string.action_attach_document, { onDocuments(true) }),
                )
                listOf(
                    Triple(Icons.Outlined.LocalBar, R.string.action_drink, onDrink),
                    Triple(Icons.Outlined.OpenWith, R.string.action_move, onMove),
                    Triple(Icons.Outlined.OpenWith, R.string.action_move_other_rack, onMoveElsewhere),
                    Triple(Icons.Outlined.CardGiftcard, if (bottle.reservedFor == null) R.string.action_keep_for_occasion else R.string.action_free_bottle, onReserve),
                ) + documents + listOf(
                    Triple(Icons.Outlined.Info, R.string.action_show_wine, onOpen),
                    Triple(Icons.Outlined.Outbox, R.string.action_take_out_of_rack, onTakeOut),
                    Triple(Icons.Outlined.RemoveCircleOutline, R.string.action_mark_gone, onGone),
                )
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
                    leadingContent = { WineThumbnail(wine, width = 28.dp) },
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

/**
 * While checking a rack: the bottles of a place holding several, each found or not, for a
 * place where only some are there.
 */
@Composable
private fun CheckOneByOneSheet(
    rack: Rack,
    cell: RackCell,
    cellar: Cellar,
    checked: Set<UUID>,
    onChange: (Set<UUID>) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val bottles = cellar.bottlesIn(cell)
    val ids = bottles.filter { it.slot != null }.map { it.id }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Dimens.l).testTag("rack.check.sheet")) {
            ListItem(
                headlineContent = {
                    Text(rack.displayLabel(cell, resources), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                },
                supportingContent = { Text(stringResource(R.string.audit_found, ids.count { it in checked }, ids.size)) },
            )
            bottles.forEachIndexed { index, bottle ->
                val slot = bottle.slot ?: return@forEachIndexed
                val wine = cellar.wines[bottle.wineId]
                val found = bottle.id in checked
                ListItem(
                    leadingContent = { BottleEnd(wine?.color, size = 28.dp) },
                    headlineContent = { Text(wine?.displayName ?: stringResource(R.string.unknown_wine)) },
                    supportingContent = {
                        Text(
                            if (rack.kind == RackKind.Bins) stringResource(R.string.rack_check_bottle, index + 1) else rack.depthName(slot.row, slot.depth, resources),
                        )
                    },
                    trailingContent = { Switch(checked = found, onCheckedChange = null) },
                    modifier = Modifier.toggleable(found, role = Role.Switch) { onChange(if (it) checked + bottle.id else checked - bottle.id) }
                        .testTag("rack.check.bottle"),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.l),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s, Alignment.End),
            ) {
                TextButton(onClick = { onChange(checked + ids) }, modifier = Modifier.testTag("rack.check.all")) {
                    Text(stringResource(R.string.rack_check_all_here))
                }
                androidx.compose.material3.Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
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
