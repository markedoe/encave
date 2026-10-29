package app.encave.ui.racks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.DiamondEdge
import app.encave.core.defaultTriangleCapacity
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackLayout
import app.encave.core.StorageRowSettings
import app.encave.core.StorageSettings
import app.encave.core.StorageType
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.Hint
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.WhenLoaded
import app.encave.ui.components.bodyRes
import app.encave.ui.components.labelRes
import app.encave.ui.components.readableWidth
import app.encave.ui.components.storageSummary
import app.encave.ui.theme.Dimens

private const val STEPS = 3

/** Keeps the wizard's settings across rotation and process death. */
internal val SettingsSaver = listSaver<StorageSettings, Any>(
    save = {
        listOf(
            it.type.name, it.layout.name, it.rows, it.columns, it.depth, it.bottlesPerBin, it.shelves, it.bottlesPerShelf,
            it.layersPerShelf, it.headToTail, it.bins, encodeRows(it.rowSettings),
            it.diamondEdges.joinToString(",") { edge -> edge.raw }, it.bottlesPerTriangle ?: 0,
        )
    },
    restore = {
        StorageSettings(
            type = StorageType.valueOf(it[0] as String), layout = RackLayout.valueOf(it[1] as String), rows = it[2] as Int,
            columns = it[3] as Int, depth = it[4] as Int, bottlesPerBin = it[5] as Int, shelves = it[6] as Int,
            bottlesPerShelf = it[7] as Int, layersPerShelf = it[8] as Int, headToTail = it[9] as Boolean, bins = it[10] as Int,
            rowSettings = decodeRows(it.getOrNull(11) as? String),
            diamondEdges = (it.getOrNull(12) as? String).orEmpty().split(",").mapNotNull { raw -> DiamondEdge.of(raw) }.toSet(),
            bottlesPerTriangle = (it.getOrNull(13) as? Int)?.takeIf { count -> count > 0 },
        )
    },
)

/** Rows set one by one, as "positions:depth:headToTail:layers" joined by ";"; empty while rows are all the same. */
internal fun encodeRows(rows: List<StorageRowSettings>?): String =
    rows?.joinToString(";") { "${it.positions}:${it.depth}:${if (it.headToTail) 1 else 0}:${it.layers}" } ?: ""

internal fun decodeRows(text: String?): List<StorageRowSettings>? {
    if (text.isNullOrEmpty()) return null
    return text.split(";").mapNotNull { row ->
        val parts = row.split(":").map { it.toIntOrNull() ?: return@mapNotNull null }
        if (parts.size != 4) return@mapNotNull null
        StorageRowSettings(positions = parts[0], depth = parts[1], headToTail = parts[2] == 1, layers = parts[3])
    }.takeIf { it.isNotEmpty() }
}

/**
 * Create a storage in three steps: its type, the settings of that type with a live preview,
 * then its name. Editing a storage later uses [RackEditorScreen].
 */
@Composable
fun RackWizardScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    WhenLoaded(viewModel) { RackWizard(viewModel, navigator) }
}

@Composable
private fun RackWizard(viewModel: AppViewModel, navigator: AppNavigator) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var step by rememberSaveable { mutableIntStateOf(1) }
    var settings by rememberSaveable(stateSaver = SettingsSaver) { mutableStateOf(StorageSettings(StorageType.Rack)) }
    fun defaultName(type: StorageType) = StorageSettings.uniqueName(resources.getString(type.labelRes), cellar.racks.map { it.name })
    var name by rememberSaveable { mutableStateOf(defaultName(settings.type)) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val rack = settings.rack(name = name.trim())

    fun choose(type: StorageType) {
        if (type == settings.type) return
        // A name still the default follows the type.
        if (name.isBlank() || name == defaultName(settings.type)) name = defaultName(type)
        settings = StorageSettings(type)
    }

    // The storage being saved: Create (button or keyboard) counts once, and the wizard closes
    // once it is in the cellar (also after a rotation meanwhile), not on a second tap.
    var creatingId by rememberSaveable { mutableStateOf<String?>(null) }
    val creating = creatingId != null
    var left by remember { mutableStateOf(false) }
    LaunchedEffect(creatingId, cellar) {
        if (!left && creatingId != null && cellar.racks.any { it.id.toString() == creatingId }) {
            left = true
            navigator.back()
        }
    }

    fun create() {
        showErrors = true
        // The state itself, not [creating]: a second tap can come before the screen is drawn again.
        if (name.isBlank() || creatingId != null) return
        val new = rack
        creatingId = new.id.toString()
        viewModel.addRack(new) { saved -> if (!saved) creatingId = null }
    }

    // Back goes to the previous step; from the first one it leaves. Not while saving.
    BackHandler(enabled = step > 1 || creating) { if (!creating) step-- }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.fillMaxWidth().readableWidth().fillMaxWidth(),
                title = {
                    Column {
                        Text(stringResource(if (step == 1) R.string.wizard_title else if (step == 2) settings.type.labelRes else R.string.wizard_name_title))
                        Text(
                            stringResource(R.string.wizard_step, step, STEPS),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                // Close leaves the wizard from every step, as Cancel on Apple; Back below goes to the previous step.
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }, enabled = !creating, modifier = Modifier.testTag("wizard.close")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().readableWidth().fillMaxWidth().padding(horizontal = Dimens.l),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.s, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step > 1) {
                        OutlinedButton(onClick = { step-- }, enabled = !creating, modifier = Modifier.testTag("wizard.back")) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                    if (step < STEPS) {
                        Button(onClick = { step++ }, modifier = Modifier.testTag("wizard.next")) {
                            Text(stringResource(R.string.action_next))
                        }
                    } else {
                        Button(onClick = ::create, enabled = !creating, modifier = Modifier.testTag("wizard.create")) {
                            Text(stringResource(R.string.action_create))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).readableWidth()
                .padding(vertical = Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            when (step) {
                1 -> TypeStep(settings.type, ::choose)
                2 -> Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    Preview(rack)
                    SettingsStep(settings) { settings = it }
                }
                else -> Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.field_name)) },
                        isError = showErrors && name.isBlank(),
                        supportingText = {
                            Text(stringResource(if (showErrors && name.isBlank()) R.string.rack_name_required else R.string.help_field_rack_name))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("wizard.name"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { create() }),
                    )
                    ListItem(
                        leadingContent = { Icon(rack.icon, contentDescription = null) },
                        headlineContent = { Text(rack.storageSummary(resources)) },
                        supportingContent = { Text(pluralStringResource(R.plurals.rack_holds, rack.capacity, rack.capacity)) },
                    )
                }
            }
        }
    }
}

/** Step 1: the three types, as radio buttons. */
@Composable
private fun TypeStep(selected: StorageType, onSelect: (StorageType) -> Unit) {
    Column(Modifier.selectableGroup()) {
        StorageType.entries.forEach { type ->
            ListItem(
                modifier = Modifier.selectable(type == selected, role = Role.RadioButton) { onSelect(type) }
                    .testTag("wizard.type.${type.name.lowercase()}"),
                leadingContent = { Icon(type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(type.labelRes)) },
                supportingContent = { Text(stringResource(type.bodyRes)) },
                trailingContent = { RadioButton(selected = type == selected, onClick = null) },
            )
        }
    }
    Hint(stringResource(R.string.wizard_type_footer), Modifier.padding(horizontal = Dimens.l))
}

/** Step 2: the settings of the chosen type only, each with a line saying what it is. */
@Composable
private fun SettingsStep(settings: StorageSettings, onChange: (StorageSettings) -> Unit) {
    val max = StorageSettings.MAX_COUNT
    // The rows (a fridge's shelves) are set one by one: the uniform settings they override are hidden.
    val rowsDiffer = settings.rowSettings != null
    when (settings.type) {
        StorageType.Rack -> {
            Header(R.string.rack_layout)
            Column(Modifier.selectableGroup()) {
                listOf(
                    RackLayout.Grid to R.string.layout_grid, RackLayout.Honeycomb to R.string.layout_honeycomb,
                    RackLayout.Diamond to R.string.layout_diamond,
                ).forEach { (layout, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(settings.layout == layout, role = Role.RadioButton) { onChange(withLayout(settings, layout)) }
                            .testTag("wizard.layout.${layout.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.layout == layout, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = Dimens.m), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Hint(
                stringResource(
                    when (settings.layout) {
                        RackLayout.Grid -> R.string.layout_grid_body
                        RackLayout.Honeycomb -> R.string.layout_honeycomb_body
                        RackLayout.Diamond -> R.string.layout_diamond_body
                    },
                ),
            )
            // Rows set one by one follow their number.
            Count(R.string.rows_grid, settings.rows, 1..max, R.string.help_field_rows) { onChange(settings.withRowCount(it)) }
            if (!rowsDiffer) {
                Count(
                    when (settings.layout) {
                        RackLayout.Grid -> R.string.columns_grid
                        RackLayout.Honeycomb -> R.string.columns_staggered
                        RackLayout.Diamond -> R.string.wizard_bins_per_row
                    },
                    settings.columns, 1..max, R.string.help_field_columns,
                ) { onChange(settings.copy(columns = it)) }
            }
            if (settings.layout == RackLayout.Diamond) {
                Count(R.string.wizard_bottles_per_bin, settings.bottlesPerBin, 2..max, R.string.help_field_bin) {
                    onChange(settings.copy(bottlesPerBin = it))
                }
                if (settings.offersDiamondEdges) {
                    DiamondEdgesSection(
                        edges = settings.diamondEdges,
                        // Half a bin until set, never more than a bin.
                        triangleCapacity = minOf(
                            settings.bottlesPerBin, settings.bottlesPerTriangle ?: defaultTriangleCapacity(settings.bottlesPerBin),
                        ),
                        binCapacity = settings.bottlesPerBin,
                        onEdges = { onChange(settings.copy(diamondEdges = it)) },
                        onTriangleCapacity = { onChange(settings.copy(bottlesPerTriangle = it)) },
                    )
                }
            } else if (!rowsDiffer) {
                Depth(settings.depth, R.string.help_field_staggered_depth) { onChange(settings.copy(depth = it)) }
            }
            RowsSection(settings, onChange)
        }
        StorageType.WineFridge -> {
            Count(R.string.rows_shelves, settings.shelves, 1..StorageSettings.MAX_SHELVES, R.string.rack_field_shelves) {
                onChange(settings.withRowCount(it))
            }
            if (!rowsDiffer) UniformFridgeSettings(settings, onChange)
            RowsSection(settings, onChange)
        }
        StorageType.Crates -> {
            Count(R.string.wizard_bins, settings.bins, 1..max, R.string.wizard_bins_body) { onChange(settings.copy(bins = it)) }
            Count(R.string.wizard_bottles_per_bin, settings.bottlesPerBin, 2..max, R.string.wizard_crate_body) {
                onChange(settings.copy(bottlesPerBin = it))
            }
        }
    }
}

/** Another layout: rows set one by one start again from the uniform settings, as they apply to it. */
internal fun withLayout(settings: StorageSettings, layout: RackLayout): StorageSettings {
    val changed = settings.copy(layout = layout)
    return if (settings.rowSettings == null || layout == settings.layout) changed else changed.withRowsDiffer(false).withRowsDiffer(true)
}

/** A fridge whose shelves are all alike: bottles, layers, depth and head-to-tail for all. */
@Composable
private fun UniformFridgeSettings(settings: StorageSettings, onChange: (StorageSettings) -> Unit) {
    val max = StorageSettings.MAX_COUNT
    Count(R.string.columns_shelves, settings.bottlesPerShelf, 1..max, R.string.wizard_bottles_per_shelf_body) {
        onChange(settings.copy(bottlesPerShelf = it))
    }
    Choice(R.string.rack_layers_per_shelf, settings.layersPerShelf, Rack.MAX_LAYERS, R.string.wizard_layers_body, "wizard.layers") {
        onChange(settings.copy(layersPerShelf = it))
    }
    Depth(settings.depth, R.string.help_field_shelf_depth) { onChange(settings.copy(depth = it)) }
    if (settings.offersHeadToTail) {
        Row(
            Modifier.fillMaxWidth().toggleable(settings.headToTail, role = Role.Switch) { onChange(settings.copy(headToTail = it)) }
                .testTag("wizard.head_to_tail"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                Text(stringResource(R.string.rack_back_reversed), style = MaterialTheme.typography.bodyLarge)
                Hint(stringResource(R.string.help_field_back_reversed))
            }
            Switch(checked = settings.headToTail, onCheckedChange = null)
        }
    }
}

/**
 * Off by default: a switch, then, once on, each row (a fridge's shelf) with its own settings:
 * its positions, its depth, its layers and head-to-tail, as they apply.
 */
@Composable
private fun RowsSection(settings: StorageSettings, onChange: (StorageSettings) -> Unit) {
    if (!settings.offersRowSettings) return
    val fridge = settings.type == StorageType.WineFridge
    val rows = settings.rowSettings
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(rows != null, role = Role.Switch) { onChange(settings.withRowsDiffer(it)) }
                .testTag("wizard.rows_differ"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (fridge) R.string.rack_custom_shelves else R.string.rack_custom_rows),
                Modifier.weight(1f).padding(end = Dimens.l), style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = rows != null, onCheckedChange = null)
        }
        Hint(stringResource(if (fridge) R.string.wizard_shelves_differ_body else R.string.wizard_rows_differ_body))
    }
    if (rows == null) return
    Header(if (fridge) R.string.rack_shelves_title else R.string.rack_rows_title)
    rows.forEachIndexed { index, row ->
        RowSettingsCard(
            settings, index, row,
            onChange = { onChange(settings.withRow(index, it)) },
            onApplyToAll = { onChange(settings.copy(rowSettings = List(rows.size) { row })) },
        )
    }
}

/** One row (a fridge's shelf) set on its own; TalkBack says the row before each control ("Row B, Rows deep"). */
@Composable
private fun RowSettingsCard(
    settings: StorageSettings,
    index: Int,
    row: StorageRowSettings,
    onChange: (StorageRowSettings) -> Unit,
    onApplyToAll: () -> Unit,
) {
    val fridge = settings.type == StorageType.WineFridge
    val title = if (fridge) stringResource(R.string.rack_shelf, index + 1) else stringResource(R.string.rack_row, Rack.rowLetters(index))
    val resources = LocalResources.current
    fun spoken(label: Int) = resources.getString(R.string.rack_row_control, title, resources.getString(label))
    val positions = when {
        settings.type == StorageType.Rack && settings.layout == RackLayout.Diamond -> R.string.wizard_bins
        fridge && row.layers > 1 -> R.string.rack_layer_bottom
        fridge -> R.string.rack_row_bottles
        else -> R.string.rack_row_positions
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("wizard.row.$index")) {
        Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (fridge) {
                RowChoice(
                    R.string.rack_layers_per_shelf, spoken(R.string.rack_layers_per_shelf), row.layers, Rack.MAX_LAYERS,
                    "wizard.row.$index.layers",
                ) { onChange(row.copy(layers = it)) }
            }
            SizeRow(
                stringResource(positions), row.positions, { onChange(row.copy(positions = it)) }, 1..StorageSettings.MAX_COUNT,
                spokenLabel = spoken(positions),
            )
            if (settings.offersRowDepth) {
                RowChoice(
                    R.string.rack_rows_deep, spoken(R.string.rack_rows_deep), row.depth, StorageSettings.MAX_ROW_DEPTH,
                    "wizard.row.$index.depth",
                ) { onChange(row.copy(depth = it)) }
            }
            if (settings.offersHeadToTail(row)) {
                val label = spoken(R.string.rack_back_reversed)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(row.headToTail, role = Role.Switch) { onChange(row.copy(headToTail = it)) }
                        .semantics { contentDescription = label }
                        .testTag("wizard.row.$index.head_to_tail"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rack_back_reversed),
                        Modifier.weight(1f).padding(end = Dimens.l).clearAndSetSemantics { }, style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = row.headToTail, onCheckedChange = null)
                }
            }
            val apply = if (fridge) R.string.rack_shelf_apply_all else R.string.rack_row_apply_all
            val applyLabel = spoken(apply)
            TextButton(
                onClick = onApplyToAll,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = applyLabel }.testTag("wizard.row.$index.apply_all"),
            ) { Text(stringResource(apply)) }
        }
    }
}

/** 1 to [count] as segmented buttons with a label above; [spoken] names the row for TalkBack. */
@Composable
internal fun RowChoice(label: Int, spoken: String, value: Int, count: Int, tag: String, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics { })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            (1..count).forEach { option ->
                // Said in place of the number: "Row B, Rows deep, 2".
                val description = stringResource(R.string.rack_row_control, spoken, "$option")
                SegmentedButton(
                    selected = value == option, onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(option - 1, count),
                    modifier = Modifier.semantics { contentDescription = description }.testTag("$tag.$option"),
                ) { Text("$option") }
            }
        }
    }
}

/** A count with − and +, and the line under it. */
@Composable
private fun Count(label: Int, value: Int, range: IntRange, footer: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        SizeRow(stringResource(label), value, onChange, range)
        Hint(stringResource(footer))
    }
}

/** One to two bottles one behind the other. */
@Composable
private fun Depth(value: Int, footer: Int, onChange: (Int) -> Unit) =
    Choice(R.string.rack_rows_deep, value, 2, footer, "wizard.depth", onChange)

/** 1 to [count] as segmented buttons, with a label above and a line under. */
@Composable
private fun Choice(label: Int, value: Int, count: Int, footer: Int, tag: String, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            (1..count).forEach { option ->
                SegmentedButton(
                    selected = value == option, onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(option - 1, count),
                    modifier = Modifier.testTag("$tag.$option"),
                ) { Text("$option") }
            }
        }
        Hint(stringResource(footer))
    }
}

/** The drawing of the whole storage being set up, as large as the card allows, and what it holds. */
@Composable
private fun Preview(rack: Rack) {
    Text(
        stringResource(R.string.rack_preview), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        val density = LocalDensity.current
        // At most 60 % of the screen's height (a fridge two deep is tall), so the settings
        // follow; a small storage takes less. A very large one scrolls.
        val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * PREVIEW_SHARE
        val labelHeight = with(density) { (MaterialTheme.typography.labelSmall.lineHeight * 1.25f).toDp() }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(Dimens.m).clearAndSetSemantics { }) {
            val cell = fittingCellSize(rack, maxWidth, maxHeight, showHeaders = false, labelHeight = labelHeight)
            Box(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                RackGrid(
                    rack, Cellar(racks = listOf(rack)), cellSize = cell, showHeaders = false, isPicture = true,
                    captionWidth = this@BoxWithConstraints.maxWidth,
                )
            }
        }
    }
    Text(pluralStringResource(R.plurals.rack_holds, rack.capacity, rack.capacity), style = MaterialTheme.typography.bodyMedium)
}

/** The preview's largest height, as a share of the screen's. */
internal const val PREVIEW_SHARE = 0.6f
