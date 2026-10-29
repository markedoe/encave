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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import app.encave.core.BinStyle
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.DiamondEdge
import app.encave.core.diamondCellMap
import app.encave.core.diamondLayout
import app.encave.core.diamondRack
import app.encave.core.keepingEdgeValues
import app.encave.core.orderedEdges
import app.encave.core.triangleCapacity
import app.encave.core.editsAsCrates
import app.encave.core.maxEditableBins
import app.encave.core.savingBins
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
import app.encave.ui.navigation.RacksRoute
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
 * layers keeps what was typed), how many deep, and whether its back bottles lie head-to-tail.
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
    /** Bottles one behind the other on this shelf, 1 or 2. */
    val depth: Int = 2,
    /** Two deep: the back bottles lie head-to-tail, necks facing you (only read when [depth] is 2). */
    val headToTail: Boolean = true,
) {
    /**
     * This shelf as changed in the editor; turned two deep, it lies head-to-tail when another
     * shelf two deep of [shelves] does (the same rule as the Apple editor).
     */
    fun changedFrom(old: ShelfDraft, shelves: List<ShelfDraft>): ShelfDraft =
        if (depth == 2 && old.depth != 2) copy(headToTail = shelves.any { it.id != id && it.depth == 2 && it.headToTail }) else this

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
            val depth = rack.depthOfShelf(shelf).coerceIn(1, Rack.MAX_STAGGERED_DEPTH)
            ShelfDraft(
                rows.count(), (fromBottom + quincunx(bottom).drop(fromBottom.size)).take(3), rack.zoneBreaks.orEmpty().contains(shelf),
                originalShelf = shelf, depth = depth,
                // A shelf one deep turned two deep later follows the fridge's other shelves (see [ShelfDraft.withDepth]).
                headToTail = depth == 2 && rack.isBackReversedShelf(shelf),
            )
        }

        /**
         * The rack these shelves make (rows are the layers, from the top): each layer as deep as
         * its shelf, head-to-tail as its shelf; the rack as deep as its deepest shelf.
         */
        fun rack(id: UUID, name: String, shelves: List<ShelfDraft>): Rack {
            val widths = shelves.flatMap { it.widths }
            val depths = shelves.flatMap { shelf -> List(shelf.layers) { shelf.depth.coerceIn(1, Rack.MAX_STAGGERED_DEPTH) } }
            val reversed = shelves.flatMap { shelf -> List(shelf.layers) { shelf.headToTail && shelf.depth == 2 } }
            return Rack(
                id = id, name = name, kind = RackKind.Staggered, rows = widths.size.coerceAtLeast(1),
                columns = shelves.maxOfOrNull { it.counts[0] } ?: 1, depth = depths.maxOrNull() ?: 1, rowColumns = widths,
                shelfLayers = shelves.map { it.layers },
                zoneBreaks = shelves.indices.filter { it > 0 && shelves[it].startsZone },
                backReversed = true in reversed,
                rowDepths = depths, rowBackReversed = reversed,
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
 * Edit a storage: the settings of its type, its size, rows (a fridge's shelves) set one by one
 * (positions, depth, head-to-tail), with a live preview. New storages are made step by step in [RackWizardScreen].
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
    // A diamond rack with edge triangles: edited as its rows and cells of diamonds, the triangles switched on below.
    val layout = remember(rackId) { existing.diamondLayout }
    var name by rememberSaveable { mutableStateOf(existing.name) }
    var kind by rememberSaveable { mutableStateOf(existing.kind) }
    var rows by rememberSaveable { mutableIntStateOf(layout?.rows ?: existing.rows) }
    var columns by rememberSaveable { mutableIntStateOf(layout?.columns ?: existing.columns) }
    // Diamond bins: the sides whose edge triangles hold bottles ("top,left"), and how many each holds.
    var edgesText by rememberSaveable { mutableStateOf(layout?.edges.orEmpty().joinToString(",") { it.raw }) }
    val edges = edgesText.split(",").mapNotNull { DiamondEdge.of(it) }.toSet()
    var triangleCapacity by rememberSaveable { mutableIntStateOf(existing.triangleCapacity) }
    var depth by rememberSaveable { mutableIntStateOf(existing.depth) }
    var binCapacity by rememberSaveable { mutableIntStateOf(existing.binCapacity) }
    // A fridge with bottles in layers (a honeycomb on shelves), edited shelf by shelf.
    val onShelves = existing.hasShelfLayers
    // Rows set one by one: their positions and, but for bins, their depth.
    var customRows by rememberSaveable {
        mutableStateOf(!onShelves && layout == null && (existing.rowColumns != null || existing.rowsDiffer))
    }
    val rowColumns = rememberRetained("rack.rowColumns") {
        mutableStateListOf<Int>().apply { if (customRows) addAll((0 until existing.rows).map { existing.columnsInRow(it) }) }
    }
    val rowDepths = rememberRetained("rack.rowDepths") {
        mutableStateListOf<Int>().apply { if (customRows) addAll((0 until existing.rows).map { existing.depthOfRow(it) }) }
    }
    val shelves = rememberRetained("rack.shelves") { mutableStateListOf<ShelfDraft>().apply { addAll(ShelfDraft.of(existing)) } }
    val layered = kind == RackKind.Staggered && onShelves
    // The type of the storage being edited: only its settings are shown.
    val storageType = existing.storageType
    // A rack stays a rack (grid or honeycomb); diamond bins, a fridge and crates keep their kind.
    val kinds = if (storageType == StorageType.Rack && existing.kind != RackKind.Bins) {
        listOf(RackKind.Grid, RackKind.Staggered)
    } else {
        emptyList()
    }
    // Bins saved without a type (older versions): the type picked here, null until then.
    var chosenStyle by rememberSaveable { mutableStateOf<BinStyle?>(null) }
    // Their type can be picked, shown under it from then on.
    val offersBinType = existing.kind == RackKind.Bins && existing.binStyle == null
    // Crates saved (or picked) as such are edited as a number of bins, laid out as the wizard
    // lays them out when their count changes.
    val isCrates = existing.editsAsCrates(chosenStyle)
    var bins by rememberSaveable { mutableIntStateOf(existing.cellCount) }
    // Rows set one by one fit a rack and a fridge's shelves (in layers, each shelf is set below); crates have none.
    val offersCustomRows = !layered && !isCrates
    val custom = customRows && offersCustomRows
    // Each row's depth, but for bins: 1 to 3 (grid, shelves) or 2 (honeycomb), as on Apple platforms.
    val maxRowDepth = when (kind) {
        RackKind.Shelves, RackKind.Grid -> Rack.MAX_DEPTH
        RackKind.Bins -> 1
        else -> Rack.MAX_STAGGERED_DEPTH
    }
    val rowDepthRange = 1..maxRowDepth
    val perRowDepth = custom && kind != RackKind.Bins
    // Diamond bins whose rows are all the same can hold bottles in their edge triangles.
    val pickedType = chosenStyle?.let { if (it == BinStyle.Crates) StorageType.Crates else StorageType.Rack } ?: storageType
    val offersEdges = kind == RackKind.Bins && !isCrates && pickedType == StorageType.Rack && !custom
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisplaced by remember { mutableStateOf(false) }
    val id = existing.id

    fun resizeRows(count: Int) {
        while (rowColumns.size < count) rowColumns.add(rowColumns.lastOrNull() ?: columns)
        while (rowColumns.size > count) rowColumns.removeAt(rowColumns.lastIndex)
        while (rowDepths.size < count) rowDepths.add(rowDepths.lastOrNull() ?: depth)
        while (rowDepths.size > count) rowDepths.removeAt(rowDepths.lastIndex)
    }

    fun preview(): Rack {
        if (layered) return ShelfDraft.rack(id, name.trim(), shelves)
        val shape = if (custom) {
            customShape(id, name.trim(), kind, rowColumns.toList(), if (kind == RackKind.Bins) null else rowDepths.toList(), depth, binCapacity)
        } else if (offersEdges && edges.isNotEmpty()) {
            diamondRack(
                id = id, name = name.trim(), rows = rows, columns = columns, binCapacity = binCapacity,
                // The sides already there keep their order, so no row of triangles moves; new ones follow.
                edges = orderedEdges(edges, existing),
                triangleCapacity = triangleCapacity.coerceAtMost(binCapacity),
            )
        } else {
            Rack(id = id, name = name.trim(), kind = kind, rows = rows, columns = columns, depth = depth, binCapacity = binCapacity).normalized()
        }
        // Bins keep their saved type unless one is picked; crates keep their shape unless their count changes.
        if (kind != RackKind.Bins) return shape
        val saved = existing.savingBins(name.trim(), binCapacity, chosenStyle, bins, shape)
        // Triangles are for diamond bins: saved as such, so their shape never reads as crates.
        val styled = if (offersEdges && edges.isNotEmpty()) saved.copy(binStyleValue = BinStyle.Diamond.raw) else saved
        // Last: what a newer version wrote that this one does not apply is kept.
        return styled.keepingEdgeValues(existing)
    }

    fun layerCount() = shelves.sumOf { it.layers }

    // Where the bottles of an edited fridge go: each stays on its shelf and layer.
    fun rowMap(rack: Rack): Map<Int, Int?> =
        if (layered) Rack.shelfRowMap(existing, rack, shelves.map { it.originalShelf }) else emptyMap()

    // Where the bottles of a diamond rack go when its triangles change: each keeps its diamond or triangle.
    fun cellMap(rack: Rack): Map<RackCell, RackCell?> = Rack.diamondCellMap(existing, rack)

    // What a save would change: the name, the shape, the triangles, and which shelf is which.
    fun signature() = "${name.trim()}|$chosenStyle|${preview().shape}|${preview().isBackReversed}|" +
        "${preview().diamondLayout}|${preview().triangleCapacityValue}|${if (layered) shelves.map { it.originalShelf } else ""}"
    val initialSignature = rememberSaveable { signature() }
    val isDirty = signature() != initialSignature

    fun save(takingOut: Boolean = false) {
        showErrors = true
        if (name.isBlank()) return
        val rack = preview()
        val map = rowMap(rack)
        val cells = cellMap(rack)
        if (!takingOut && cellar.displacedBottles(rack, map, cells).isNotEmpty()) {
            confirmDisplaced = true
            return
        }
        viewModel.updateRack(rack, map, takingOut, cells) { navigator.back() }
    }

    // Back and Close ask before unsaved changes are lost.
    val close = rememberDiscardGuard(isDirty) { navigator.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                // Close and Save at the edges of the form, not of a tablet.
                modifier = Modifier.fillMaxWidth().readableWidth().fillMaxWidth(),
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
        val displaced = cellar.displacedBottles(rack, rowMap(rack), cellMap(rack)).size
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
                            // Grids and honeycombs hold at most two bottles one behind the other; rows set one by one stay.
                            depth = depth.coerceAtMost(Rack.MAX_STAGGERED_DEPTH)
                            rowDepths.indices.forEach { rowDepths[it] = rowDepths[it].coerceAtMost(Rack.MAX_STAGGERED_DEPTH) }
                        }
                    }
                }

                // What the chosen layout is, as in the wizard.
                Hint(stringResource(if (kind == RackKind.Staggered) R.string.layout_honeycomb_body else R.string.layout_grid_body))
            }

            if (offersBinType) {
                Header(R.string.kind_title)
                val picked = chosenStyle?.let { if (it == BinStyle.Crates) StorageType.Crates else StorageType.Rack } ?: storageType
                val options = listOf(StorageType.Rack to BinStyle.Diamond, StorageType.Crates to BinStyle.Crates)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("rack.bin_type")) {
                    options.forEachIndexed { index, (type, style) ->
                        SegmentedButton(
                            selected = picked == type, onClick = { chosenStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(type.labelRes)) }
                    }
                }
            }

            Header(R.string.rack_size_title)
            if (isCrates) {
                SizeRow(stringResource(R.string.wizard_bins), bins, { bins = it }, 1..existing.maxEditableBins)
                Hint(stringResource(R.string.wizard_bins_body))
            }
            if (!layered && !isCrates) {
                SizeRow(stringResource(kind.rowsRes), rows, { rows = it; if (customRows) resizeRows(it) }, 1..MAX_SIZE)
                Hint(stringResource(if (kind == RackKind.Shelves) R.string.rack_field_shelves else R.string.help_field_rows))
            }
            if (!custom && !layered && !isCrates) {
                SizeRow(stringResource(kind.columnsRes), columns, { columns = it }, 1..MAX_SIZE)
                Hint(stringResource(R.string.help_field_columns))
            }
            when (kind) {
                RackKind.Grid -> if (!perRowDepth) {
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
                RackKind.Shelves -> if (!perRowDepth) {
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
                    Hint(stringResource(if (storageType == StorageType.Crates) R.string.wizard_crate_body else R.string.help_field_bin))
                }
                // A fridge in layers sets its depth and head-to-tail shelf by shelf, below.
                RackKind.Staggered -> if (!layered && !perRowDepth) {
                    Text(stringResource(R.string.rack_rows_deep), style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        (1..Rack.MAX_STAGGERED_DEPTH).forEach { value ->
                            SegmentedButton(
                                selected = depth == value, onClick = { depth = value },
                                shape = SegmentedButtonDefaults.itemShape(value - 1, Rack.MAX_STAGGERED_DEPTH),
                            ) { Text("$value") }
                        }
                    }
                    Hint(stringResource(R.string.help_field_staggered_depth))
                }
            }

            if (offersEdges) {
                DiamondEdgesSection(
                    edges = edges, triangleCapacity = triangleCapacity.coerceAtMost(binCapacity), binCapacity = binCapacity,
                    onEdges = { picked -> edgesText = DiamondEdge.entries.filter { it in picked }.joinToString(",") { it.raw } },
                    onTriangleCapacity = { triangleCapacity = it },
                )
            }

            if (layered) {
                Header(R.string.rack_shelves_title)
                shelves.forEachIndexed { index, shelf ->
                    ShelfCard(
                        number = index + 1, shelf = shelf, onChange = { shelves[index] = it.changedFrom(shelves[index], shelves) },
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
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(customRows, role = Role.Switch) { on ->
                        if (on) {
                            // Rows set one by one have no edge triangles; each row starts as the rack makes it now.
                            edgesText = ""
                            val uniform = preview()
                            rowColumns.clear()
                            rowDepths.clear()
                            repeat(rows) { row ->
                                rowColumns.add(uniform.columnsInRow(row))
                                rowDepths.add(uniform.depthOfRow(row).coerceIn(rowDepthRange))
                            }
                        }
                        customRows = on
                    }.testTag("rack.rows_differ"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                        Text(
                            stringResource(if (kind == RackKind.Shelves) R.string.rack_custom_shelves else R.string.rack_custom_rows),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.rack_custom_rows_body),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = customRows, onCheckedChange = null)
                }
                if (custom) {
                    rowColumns.forEachIndexed { row, count ->
                        val title = if (kind == RackKind.Shelves) {
                            stringResource(R.string.rack_shelf, row + 1)
                        } else {
                            stringResource(R.string.rack_row, Rack.rowLetters(row))
                        }
                        if (kind == RackKind.Bins) {
                            // Bins: only how many in the row.
                            SizeRow(title, count, { rowColumns[row] = it }, 1..60)
                        } else {
                            RowEditor(
                                title = title, kind = kind, positions = count, onPositions = { rowColumns[row] = it },
                                depth = rowDepths.getOrElse(row) { depth }, depthRange = rowDepthRange,
                                onDepth = { if (row in rowDepths.indices) rowDepths[row] = it },
                                tag = "rack.edit_row.$row",
                            )
                        }
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
        val count = cellar.displacedBottles(preview(), rowMap(preview()), cellMap(preview())).size
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
                // Back to the Storage list, whether the editor was opened from the storage or the list.
                navigator.back(to = RacksRoute)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * A rack whose rows are set one by one: [widths] places in each row and, but for bins, [depths]
 * bottles one behind the other in each; the rack as wide as its widest row and as deep as its
 * deepest ([depth] when the rows have none). Widths are left out when the rack makes them anyway
 * (a honeycomb's usual rows, a grid's equal ones), depths when they are all the same.
 */
internal fun customShape(
    id: UUID, name: String, kind: RackKind, widths: List<Int>, depths: List<Int>?, depth: Int, binCapacity: Int,
): Rack {
    val base = Rack(
        id = id, name = name, kind = kind, rows = widths.size.coerceAtLeast(1), columns = widths.maxOrNull() ?: 1,
        depth = depths?.maxOrNull() ?: depth, binCapacity = binCapacity, rowDepths = depths,
    ).normalized()
    if ((0 until base.rows).map { base.columnsInRow(it) } == widths) return base
    return base.copy(rowColumns = widths).normalized()
}

/** One row of a rack (a shelf of a fridge) set on its own: its positions and its depth, each said with the row's name. */
@Composable
private fun RowEditor(
    title: String,
    kind: RackKind,
    positions: Int,
    onPositions: (Int) -> Unit,
    depth: Int,
    depthRange: IntRange,
    onDepth: (Int) -> Unit,
    tag: String,
) {
    val resources = LocalResources.current
    fun spoken(label: Int) = resources.getString(R.string.rack_row_control, title, resources.getString(label))
    val positionsLabel = if (kind == RackKind.Shelves) R.string.rack_row_bottles else R.string.rack_row_positions
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        SizeRow(stringResource(positionsLabel), positions, onPositions, 1..60, spokenLabel = spoken(positionsLabel))
        RowChoice(R.string.rack_rows_deep, spoken(R.string.rack_rows_deep), depth, depthRange.last, "$tag.depth", onDepth)
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
    val title = stringResource(R.string.rack_shelf, number)
    val resources = LocalResources.current
    // "Shelf 2, Rows deep": TalkBack names the shelf each control sets.
    fun spoken(label: Int) = resources.getString(R.string.rack_row_control, title, resources.getString(label))
    OutlinedCard(Modifier.fillMaxWidth().testTag("rack.shelf")) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall,
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
            SizeRow(
                stringResource(R.string.rack_layer_bottom), shelf.counts[0], { onChange(shelf.withLayer(0, it)) }, 1..MAX_SIZE,
                spokenLabel = spoken(R.string.rack_layer_bottom),
            )
            if (shelf.layers == 3) {
                SizeRow(
                    stringResource(R.string.rack_layer_middle), shelf.counts[1], { onChange(shelf.withLayer(1, it)) }, 1..MAX_SIZE,
                    spokenLabel = spoken(R.string.rack_layer_middle),
                )
            }
            if (shelf.layers > 1) {
                val top = shelf.layers - 1
                SizeRow(
                    stringResource(R.string.rack_layer_top), shelf.counts[top], { onChange(shelf.withLayer(top, it)) }, 1..MAX_SIZE,
                    spokenLabel = spoken(R.string.rack_layer_top),
                )
            }
            Hint(
                when (shelf.layers) {
                    1 -> stringResource(R.string.rack_layers_hint_1, shelf.counts[0])
                    2 -> stringResource(R.string.rack_layers_hint_2, shelf.counts[0], shelf.counts[1])
                    else -> stringResource(R.string.rack_layers_hint_3, shelf.counts[0], shelf.counts[1], shelf.counts[2])
                },
            )
            // This shelf's depth and, two deep, how its back bottles lie.
            RowChoice(
                R.string.rack_rows_deep, spoken(R.string.rack_rows_deep), shelf.depth, Rack.MAX_STAGGERED_DEPTH, "rack.shelf.$number.depth",
            ) { onChange(shelf.copy(depth = it)) }
            Hint(stringResource(R.string.help_field_layer_depth))
            if (shelf.depth == 2) {
                val label = spoken(R.string.rack_back_reversed)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(shelf.headToTail, role = Role.Switch) { onChange(shelf.copy(headToTail = it)) }
                        .semantics { contentDescription = label }
                        .testTag("rack.shelf.$number.head_to_tail"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = Dimens.l).clearAndSetSemantics { }) {
                        Text(stringResource(R.string.rack_back_reversed), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.help_field_back_reversed),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = shelf.headToTail, onCheckedChange = null)
                }
            }
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
internal fun SizeRow(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange, spokenLabel: String = label) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // [spokenLabel] names the row or shelf a control sets ("Row B, Positions").
        Stepper(
            value = value, onChange = onChange, range = range,
            decreaseLabel = stringResource(R.string.value_decrease, spokenLabel),
            increaseLabel = stringResource(R.string.value_increase, spokenLabel),
        )
    }
}
