package app.encave.ui.racks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackLayout
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
private val SettingsSaver = listSaver<StorageSettings, Any>(
    save = {
        listOf(
            it.type.name, it.layout.name, it.rows, it.columns, it.depth, it.bottlesPerBin, it.shelves, it.bottlesPerShelf,
            it.layersPerShelf, it.headToTail, it.bins,
        )
    },
    restore = {
        StorageSettings(
            StorageType.valueOf(it[0] as String), RackLayout.valueOf(it[1] as String), it[2] as Int, it[3] as Int, it[4] as Int,
            it[5] as Int, it[6] as Int, it[7] as Int, it[8] as Int, it[9] as Boolean, it[10] as Int,
        )
    },
)

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

    fun create() {
        showErrors = true
        if (name.isBlank()) return
        viewModel.addRack(rack)
        navigator.back()
    }

    // Back goes to the previous step; from the first one it leaves.
    BackHandler(enabled = step > 1) { step-- }

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
                navigationIcon = {
                    if (step == 1) {
                        IconButton(onClick = { navigator.back() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    } else {
                        IconButton(onClick = { step-- }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
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
                        OutlinedButton(onClick = { step-- }, modifier = Modifier.testTag("wizard.back")) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                    if (step < STEPS) {
                        Button(onClick = { step++ }, modifier = Modifier.testTag("wizard.next")) {
                            Text(stringResource(R.string.action_next))
                        }
                    } else {
                        Button(onClick = ::create, modifier = Modifier.testTag("wizard.create")) {
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
                            .selectable(settings.layout == layout, role = Role.RadioButton) { onChange(settings.copy(layout = layout)) }
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
            Count(R.string.rows_grid, settings.rows, 1..max, R.string.help_field_rows) { onChange(settings.copy(rows = it)) }
            Count(
                when (settings.layout) {
                    RackLayout.Grid -> R.string.columns_grid
                    RackLayout.Honeycomb -> R.string.columns_staggered
                    RackLayout.Diamond -> R.string.wizard_bins_per_row
                },
                settings.columns, 1..max, R.string.help_field_columns,
            ) { onChange(settings.copy(columns = it)) }
            if (settings.layout == RackLayout.Diamond) {
                Count(R.string.wizard_bottles_per_bin, settings.bottlesPerBin, 2..max, R.string.help_field_bin) {
                    onChange(settings.copy(bottlesPerBin = it))
                }
            } else {
                Depth(settings.depth, R.string.help_field_staggered_depth) { onChange(settings.copy(depth = it)) }
            }
        }
        StorageType.WineFridge -> {
            Count(R.string.rows_shelves, settings.shelves, 1..StorageSettings.MAX_SHELVES, R.string.rack_field_shelves) {
                onChange(settings.copy(shelves = it))
            }
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
        StorageType.Crates -> {
            Count(R.string.wizard_bins, settings.bins, 1..max, R.string.wizard_bins_body) { onChange(settings.copy(bins = it)) }
            Count(R.string.wizard_bottles_per_bin, settings.bottlesPerBin, 2..max, R.string.wizard_crate_body) {
                onChange(settings.copy(bottlesPerBin = it))
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

/** The drawing of the storage being set up, small, and what it holds. */
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
        Box(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()).padding(Dimens.m).clearAndSetSemantics { },
        ) {
            RackGrid(rack, Cellar(racks = listOf(rack)), cellSize = 18.dp, showHeaders = false)
        }
    }
    Text(pluralStringResource(R.plurals.rack_holds, rack.capacity, rack.capacity), style = MaterialTheme.typography.bodyMedium)
}
