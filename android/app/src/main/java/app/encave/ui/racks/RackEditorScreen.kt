package app.encave.ui.racks

import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Hive
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.StorageType
import app.encave.core.storageType
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.rememberDiscardGuard
import app.encave.ui.components.Hint
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WhenLoaded
import app.encave.ui.components.rememberRetained
import app.encave.ui.components.Stepper
import app.encave.ui.components.bodyRes
import app.encave.ui.components.columnsRes
import app.encave.ui.components.labelRes
import app.encave.ui.components.rowsRes
import app.encave.ui.theme.Dimens
import java.util.UUID

private const val MAX_SIZE = 40

/**
 * One shelf of a fridge with bottles in layers, while editing: 1 to 3 layers, and the
 * bottles of each layer from the bottom (always three values, so changing the number of
 * layers keeps what was typed).
 */
data class ShelfDraft(
    val layers: Int = 2,
    val counts: List<Int> = quincunx(6),
    /** A divider above this shelf: it starts a new temperature zone. */
    val startsZone: Boolean = false,
    /** Stays with the shelf while it is edited, moved or applied to. */
    val id: String = UUID.randomUUID().toString(),
    /** The shelf of the saved fridge this one was (0 is the top one); null for a new shelf. */
    val originalShelf: Int? = null,
) {
    /** A new shelf like this one (Duplicate, Add shelf): its own identity, no zone break. */
    fun duplicated(): ShelfDraft = copy(startsZone = false, id = UUID.randomUUID().toString(), originalShelf = null)

    /** A new bottom layer; the layers above follow it while they keep their usual count. */
    fun withBottom(value: Int): ShelfDraft {
        val old = counts[0]
        return copy(
            counts = listOf(
                value,
                if (counts[1] == maxOf(1, old - 1)) maxOf(1, value - 1) else counts[1],
                if (counts[2] == old) value else counts[2],
            ),
        )
    }

    fun withLayer(layer: Int, value: Int): ShelfDraft =
        if (layer == 0) withBottom(value) else copy(counts = counts.toMutableList().also { it[layer] = value })

    /** The rows of this shelf, from the top: its top layer first. */
    val widths: List<Int> get() = counts.take(layers).reversed()

    companion object {
        /** A bottom layer of [bottom] bottles, each layer above resting in the gaps of the one below. */
        fun quincunx(bottom: Int) = listOf(bottom, maxOf(1, bottom - 1), bottom)

        /** The shelves of a fridge with layers, as the editor shows them. */
        fun of(rack: Rack): List<ShelfDraft> = rack.shelfRows.mapIndexed { shelf, rows ->
            val fromBottom = rows.reversed().map { rack.columnsInRow(it) }
            val bottom = fromBottom.first()
            ShelfDraft(
                rows.count(), (fromBottom + quincunx(bottom).drop(fromBottom.size)).take(3), rack.zoneBreaks.orEmpty().contains(shelf),
                originalShelf = shelf,
            )
        }

        /** The rack these shelves make (rows are the layers, from the top). */
        fun rack(id: UUID, name: String, depth: Int, shelves: List<ShelfDraft>, backReversed: Boolean = false): Rack {
            val widths = shelves.flatMap { it.widths }
            return Rack(
                id = id, name = name, kind = RackKind.Staggered, rows = widths.size.coerceAtLeast(1),
                columns = shelves.maxOfOrNull { it.counts[0] } ?: 1, depth = depth, rowColumns = widths,
                shelfLayers = shelves.map { it.layers },
                zoneBreaks = shelves.indices.filter { it > 0 && shelves[it].startsZone },
                backReversed = backReversed,
            ).normalized()
        }
    }
}

val RackKind.icon: ImageVector
    get() = when (this) {
        RackKind.Grid -> Icons.Outlined.GridOn
        RackKind.Staggered -> Icons.Outlined.Hive
        RackKind.Shelves -> Icons.Outlined.Kitchen
        RackKind.Bins -> Icons.Outlined.Diamond
    }

val StorageType.icon: ImageVector
    get() = when (this) {
        StorageType.Rack -> RackKind.Grid.icon
        StorageType.WineFridge -> RackKind.Shelves.icon
        StorageType.Crates -> Icons.Outlined.Inventory2
    }

/** The symbol of its type of storage; a honeycomb rack keeps its own. */
val Rack.icon: ImageVector get() = if (storageType == StorageType.Rack) kind.icon else storageType.icon

/**
 * Edit a storage: the settings of its type, its size, rows of different lengths, with a live
 * preview. New storages are made step by step in [RackWizardScreen].
 */
@Composable
fun RackEditorScreen(viewModel: AppViewModel, navigator: AppNavigator, rackId: String) {
    WhenLoaded(viewModel) { RackEditorForm(viewModel, navigator, rackId) }
}

@Composable
private fun RackEditorForm(viewModel: AppViewModel, navigator: AppNavigator, rackId: String) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val existing = remember(rackId) { cellar.racks.firstOrNull { it.id.toString() == rackId } }
    // Removed meanwhile (on another device): nothing to edit, and saving would add it again.
    if (existing == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { navigator.back() }
        return
    }
    var name by rememberSaveable { mutableStateOf(existing.name) }
    var kind by rememberSaveable { mutableStateOf(existing.kind) }
    var rows by rememberSaveable { mutableIntStateOf(existing.rows) }
    var columns by rememberSaveable { mutableIntStateOf(existing.columns) }
    var depth by rememberSaveable { mutableIntStateOf(existing.depth) }
    // A fridge on shelves two deep: the back bottles lie head-to-tail.
    var backReversed by rememberSaveable { mutableStateOf(existing.isBackReversed) }
    var binCapacity by rememberSaveable { mutableIntStateOf(existing.binCapacity) }
    var customRows by rememberSaveable { mutableStateOf(existing.rowColumns != null) }
    val rowColumns = rememberRetained("rack.rowColumns") { mutableStateListOf<Int>().apply { existing.rowColumns?.let { addAll(it) } } }
    // A fridge with bottles in layers (a honeycomb on shelves), edited shelf by shelf.
    val onShelves = existing.hasShelfLayers
    val shelves = rememberRetained("rack.shelves") { mutableStateListOf<ShelfDraft>().apply { addAll(ShelfDraft.of(existing)) } }
    val layered = kind == RackKind.Staggered && onShelves
    // The type of the storage being edited: only its settings are shown.
    val storageType = existing.storageType
    // A rack stays a rack (grid or honeycomb); a fridge and crates keep their kind.
    val kinds = if (storageType == StorageType.Rack) listOf(RackKind.Grid, RackKind.Staggered) else emptyList()
    // Rows of different lengths fit a rack or crates; a fridge only keeps the ones it has.
    val offersCustomRows = kind != RackKind.Staggered && (storageType != StorageType.WineFridge || existing.rowColumns != null)
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisplaced by remember { mutableStateOf(false) }
    val id = existing.id

    fun resizeRows(count: Int) {
        while (rowColumns.size < count) rowColumns.add(rowColumns.lastOrNull() ?: columns)
        while (rowColumns.size > count) rowColumns.removeAt(rowColumns.lastIndex)
    }

    fun preview() = if (layered) {
        ShelfDraft.rack(id, name.trim(), depth, shelves, backReversed)
    } else {
        Rack(
            id = id, name = name.trim(), kind = kind, rows = rows,
            columns = if (customRows && kind != RackKind.Staggered) rowColumns.maxOrNull() ?: columns else columns,
            depth = depth, binCapacity = binCapacity,
            rowColumns = if (customRows && kind != RackKind.Staggered) rowColumns.toList() else null,
        ).normalized()
    }

    fun layerCount() = shelves.sumOf { it.layers }

    // Where the bottles of an edited fridge go: each stays on its shelf and layer.
    fun rowMap(rack: Rack): Map<Int, Int?> =
        if (layered) Rack.shelfRowMap(existing, rack, shelves.map { it.originalShelf }) else emptyMap()

    // What a save would change: the name, the shape, and which shelf is which.
    fun signature() = "${name.trim()}|${preview().shape}|${preview().isBackReversed}|${if (layered) shelves.map { it.originalShelf } else ""}"
    val initialSignature = rememberSaveable { signature() }
    val isDirty = signature() != initialSignature

    fun save(takingOut: Boolean = false) {
        showErrors = true
        if (name.isBlank()) return
        val rack = preview()
        val map = rowMap(rack)
        if (!takingOut && cellar.displacedBottles(rack, map).isNotEmpty()) {
            confirmDisplaced = true
            return
        }
        viewModel.updateRack(rack, map, takingOut) { navigator.back() }
    }

    // Back and Close ask before unsaved changes are lost.
    val close = rememberDiscardGuard(isDirty) { navigator.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rack_editor_edit)) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = { TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        val rack = preview()
        val displaced = cellar.displacedBottles(rack, rowMap(rack)).size
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth().padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.l),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                isError = showErrors && name.isBlank(),
                supportingText = {
                    Text(stringResource(if (showErrors && name.isBlank()) R.string.rack_name_required else R.string.help_field_rack_name))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            if (kinds.size > 1) {
                Header(R.string.kind_title)
                // Two cards a row, the same width, so the choices line up.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s),
                    maxItemsInEachRow = 2,
                ) {
                    kinds.forEach { option ->
                        KindCard(option, selected = kind == option, Modifier.weight(1f)) {
                            kind = option
                            if (option == RackKind.Staggered) {
                                customRows = false
                                depth = depth.coerceAtMost(Rack.MAX_STAGGERED_DEPTH)
                            }
                        }
                    }
                }

                // What the chosen layout is, as in the wizard.
                Hint(stringResource(if (kind == RackKind.Staggered) R.string.layout_honeycomb_body else R.string.layout_grid_body))
            }

            Header(R.string.rack_size_title)
            if (!layered) {
                SizeRow(stringResource(kind.rowsRes), rows, { rows = it; if (customRows) resizeRows(it) }, 1..MAX_SIZE)
                Hint(stringResource(if (kind == RackKind.Shelves) R.string.rack_field_shelves else R.string.help_field_rows))
            }
            if (!customRows && !layered) {
                SizeRow(stringResource(kind.columnsRes), columns, { columns = it }, 1..MAX_SIZE)
                Hint(stringResource(R.string.help_field_columns))
            }
            when (kind) {
                RackKind.Grid -> {
                    Text(stringResource(R.string.rack_bottles_per_cell), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(1 to R.string.rack_depth_one, 2 to R.string.rack_depth_two).forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = depth.coerceAtMost(2) == value, onClick = { depth = value },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    Hint(stringResource(R.string.help_field_grid_depth))
                }
                RackKind.Shelves -> {
                    Text(stringResource(R.string.rack_rows_deep), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        (1..Rack.MAX_DEPTH).forEach { value ->
                            SegmentedButton(
                                selected = depth == value, onClick = { depth = value },
                                shape = SegmentedButtonDefaults.itemShape(value - 1, Rack.MAX_DEPTH),
                            ) { Text("$value") }
                        }
                    }
                    Hint(stringResource(R.string.help_field_shelf_depth))
                }
                RackKind.Bins -> {
                    SizeRow(stringResource(R.string.rack_bottles_per_bin), binCapacity, { binCapacity = it }, 2..40)
                    Hint(stringResource(R.string.help_field_bin))
                }
                RackKind.Staggered -> {
                    Text(stringResource(R.string.rack_rows_deep), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        (1..Rack.MAX_STAGGERED_DEPTH).forEach { value ->
                            SegmentedButton(
                                selected = depth == value, onClick = { depth = value },
                                shape = SegmentedButtonDefaults.itemShape(value - 1, Rack.MAX_STAGGERED_DEPTH),
                            ) { Text("$value") }
                        }
                    }
                    Hint(stringResource(if (layered) R.string.help_field_layer_depth else R.string.help_field_staggered_depth))
                    if (layered && depth == 2) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(backReversed, role = Role.Switch) { backReversed = it }
                                .testTag("rack.back_reversed"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                                Text(stringResource(R.string.rack_back_reversed), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.help_field_back_reversed),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = backReversed, onCheckedChange = null)
                        }
                    }
                }
            }

            if (layered) {
                Header(R.string.rack_shelves_title)
                shelves.forEachIndexed { index, shelf ->
                    ShelfCard(
                        number = index + 1, shelf = shelf, onChange = { shelves[index] = it },
                        canAdd = layerCount() + shelf.layers <= Rack.MAX_SIZE,
                        onDuplicate = { shelves.add(index + 1, shelf.duplicated()) },
                        onApplyToAll = {
                            shelves.indices.forEach {
                                val target = shelves[it]
                                shelves[it] = shelf.copy(startsZone = target.startsZone, id = target.id, originalShelf = target.originalShelf)
                            }
                        },
                        onDelete = if (shelves.size > 1) ({ shelves.removeAt(index) }) else null,
                    )
                }
                OutlinedButton(
                    onClick = { shelves.add(shelves.lastOrNull()?.duplicated() ?: ShelfDraft()) },
                    enabled = layerCount() + Rack.MAX_LAYERS <= Rack.MAX_SIZE,
                    modifier = Modifier.fillMaxWidth().testTag("rack.shelf.add"),
                ) { Text(stringResource(R.string.rack_shelf_add)) }
            }

            if (displaced > 0) {
                Text(
                    pluralStringResource(R.plurals.rack_displaced, displaced, displaced),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("rack.displaced").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (offersCustomRows) {
                Row(
                    Modifier.fillMaxWidth().toggleable(customRows, role = Role.Switch) { on ->
                        customRows = on
                        if (on) {
                            rowColumns.clear()
                            repeat(rows) { rowColumns.add(columns) }
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                        Text(stringResource(R.string.rack_custom_rows), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.rack_custom_rows_body),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = customRows, onCheckedChange = null)
                }
                if (customRows) {
                    rowColumns.forEachIndexed { row, count ->
                        val title = if (kind == RackKind.Shelves) {
                            stringResource(R.string.rack_shelf, row + 1)
                        } else {
                            stringResource(R.string.rack_row, Rack.rowLetters(row))
                        }
                        SizeRow(title, count, { rowColumns[row] = it }, 1..60)
                    }
                }
            }

            Header(R.string.rack_preview)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).padding(Dimens.m).clearAndSetSemantics { },
                ) {
                    RackGrid(rack, previewCellar(cellar, rack), cellSize = 26.dp, showHeaders = false)
                }
            }
            Text(
                stringResource(
                    if (layered || kind == RackKind.Shelves) R.string.help_rack_holds_shelves else R.string.help_rack_holds,
                    rack.capacity,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("action.delete_rack"),
            ) {
                ActionContent(stringResource(R.string.rack_delete), Icons.Outlined.Delete)
            }
            HelpLink(HelpTopic.Racks, R.string.help_link_racks)
        }
    }
    if (confirmDisplaced) {
        val count = cellar.displacedBottles(preview(), rowMap(preview())).size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.rack_displaced, count, count),
            body = stringResource(R.string.rack_displaced_body),
            confirm = stringResource(R.string.rack_displaced_confirm),
            onConfirm = { save(takingOut = true) },
            onDismiss = { confirmDisplaced = false },
        )
    }
    if (confirmDelete) {
        val count = cellar.occupancy(existing)
        ConfirmDialog(
            title = stringResource(R.string.rack_delete_title, existing.name),
            body = if (count == 0) stringResource(R.string.rack_delete_empty) else pluralStringResource(R.plurals.rack_delete_body, count, count),
            confirm = stringResource(R.string.rack_delete),
            onConfirm = {
                viewModel.removeRack(existing.id)
                navigator.back()
                navigator.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The rack being edited with the bottles already in it (those that still fit). */
private fun previewCellar(cellar: Cellar, rack: Rack): Cellar {
    val inRack = cellar.bottlesInCellar.filter { it.slot?.rackId == rack.id && rack.contains(it.slot!!) }
    return Cellar(cellar.wines.values, inRack, listOf(rack))
}

@Composable
private fun KindCard(kind: RackKind, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(Dimens.radiusCard),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) scheme.primaryContainer else scheme.surface),
    ) {
        Column(Modifier.padding(Dimens.m).heightIn(min = 128.dp), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            Icon(kind.icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Text(stringResource(kind.labelRes), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(kind.bodyRes), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** A shelf of a fridge with layers: its layers and the bottles of each, with what can be done to it. */
@Composable
private fun ShelfCard(
    number: Int,
    shelf: ShelfDraft,
    onChange: (ShelfDraft) -> Unit,
    canAdd: Boolean,
    onDuplicate: () -> Unit,
    onApplyToAll: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("rack.shelf")) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(
                stringResource(R.string.rack_shelf, number), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.rack_layers_per_shelf), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (1..Rack.MAX_LAYERS).forEach { value ->
                    SegmentedButton(
                        selected = shelf.layers == value, onClick = { onChange(shelf.copy(layers = value)) },
                        shape = SegmentedButtonDefaults.itemShape(value - 1, Rack.MAX_LAYERS),
                    ) { Text("$value") }
                }
            }
            SizeRow(stringResource(R.string.rack_layer_bottom), shelf.counts[0], { onChange(shelf.withLayer(0, it)) }, 1..MAX_SIZE)
            if (shelf.layers == 3) {
                SizeRow(stringResource(R.string.rack_layer_middle), shelf.counts[1], { onChange(shelf.withLayer(1, it)) }, 1..MAX_SIZE)
            }
            if (shelf.layers > 1) {
                val top = shelf.layers - 1
                SizeRow(stringResource(R.string.rack_layer_top), shelf.counts[top], { onChange(shelf.withLayer(top, it)) }, 1..MAX_SIZE)
            }
            Hint(
                when (shelf.layers) {
                    1 -> stringResource(R.string.rack_layers_hint_1, shelf.counts[0])
                    2 -> stringResource(R.string.rack_layers_hint_2, shelf.counts[0], shelf.counts[1])
                    else -> stringResource(R.string.rack_layers_hint_3, shelf.counts[0], shelf.counts[1], shelf.counts[2])
                },
            )
            if (number > 1) {
                Row(
                    Modifier.fillMaxWidth().toggleable(shelf.startsZone, role = Role.Switch) { onChange(shelf.copy(startsZone = it)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                        Text(stringResource(R.string.rack_shelf_new_zone), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.rack_shelf_new_zone_body),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = shelf.startsZone, onCheckedChange = null)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                TextButton(onClick = onDuplicate, enabled = canAdd) { Text(stringResource(R.string.rack_shelf_duplicate)) }
                TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.rack_shelf_apply_all)) }
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.rack_shelf_delete)) }
                }
            }
        }
    }
}

@Composable
internal fun Header(id: Int) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
internal fun SizeRow(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Stepper(
            value = value, onChange = onChange, range = range,
            decreaseLabel = stringResource(R.string.value_decrease, label),
            increaseLabel = stringResource(R.string.value_increase, label),
        )
    }
}
