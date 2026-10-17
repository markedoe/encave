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
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.Hint
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.Stepper
import app.encave.ui.components.bodyRes
import app.encave.ui.components.columnsRes
import app.encave.ui.components.labelRes
import app.encave.ui.components.rowsRes
import app.encave.ui.theme.Dimens
import java.util.UUID

private const val MAX_SIZE = 40

/** A ready-made size for a kind of storage. */
private data class RackPreset(val title: Int, val kind: RackKind, val rows: Int, val columns: Int, val depth: Int = 1, val binCapacity: Int = 10)

private val presets = listOf(
    RackPreset(R.string.preset_grid_6x8, RackKind.Grid, 6, 8),
    RackPreset(R.string.preset_grid_double, RackKind.Grid, 6, 8, depth = 2),
    RackPreset(R.string.preset_grid_large, RackKind.Grid, 10, 12),
    RackPreset(R.string.preset_staggered_5x8, RackKind.Staggered, 5, 8),
    RackPreset(R.string.preset_staggered_8x10, RackKind.Staggered, 8, 10),
    RackPreset(R.string.preset_fridge_6x6, RackKind.Shelves, 6, 6),
    RackPreset(R.string.preset_fridge_2deep, RackKind.Shelves, 6, 7, depth = 2),
    RackPreset(R.string.preset_cabinet, RackKind.Shelves, 10, 8, depth = 2),
    RackPreset(R.string.preset_bins_2x3, RackKind.Bins, 2, 3, binCapacity = 10),
    RackPreset(R.string.preset_crates_6, RackKind.Bins, 3, 4, binCapacity = 6),
    RackPreset(R.string.preset_crates_12, RackKind.Bins, 2, 4, binCapacity = 12),
)

val RackKind.icon: ImageVector
    get() = when (this) {
        RackKind.Grid -> Icons.Outlined.GridOn
        RackKind.Staggered -> Icons.Outlined.Hive
        RackKind.Shelves -> Icons.Outlined.Kitchen
        RackKind.Bins -> Icons.Outlined.Diamond
    }

/** Create or edit a rack: the kind of storage, its size, rows of different lengths, with a live preview. */
@Composable
fun RackEditorScreen(viewModel: AppViewModel, navigator: AppNavigator, rackId: String?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val existing = remember(rackId) { rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } } }
    val defaultName = if (cellar.racks.isEmpty()) stringResource(R.string.sample_main_rack)
    else stringResource(R.string.rack_default_name, cellar.racks.size + 1)
    var name by rememberSaveable { mutableStateOf(existing?.name ?: defaultName) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: RackKind.Grid) }
    var rows by rememberSaveable { mutableIntStateOf(existing?.rows ?: 6) }
    var columns by rememberSaveable { mutableIntStateOf(existing?.columns ?: 8) }
    var depth by rememberSaveable { mutableIntStateOf(existing?.depth ?: 1) }
    var binCapacity by rememberSaveable { mutableIntStateOf(existing?.binCapacity ?: 10) }
    var customRows by rememberSaveable { mutableStateOf(existing?.rowColumns != null) }
    val rowColumns = remember { mutableStateListOf<Int>().apply { existing?.rowColumns?.let { addAll(it) } } }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: UUID.randomUUID() }

    fun resizeRows(count: Int) {
        while (rowColumns.size < count) rowColumns.add(rowColumns.lastOrNull() ?: columns)
        while (rowColumns.size > count) rowColumns.removeAt(rowColumns.lastIndex)
    }

    fun preview() = Rack(
        id = id, name = name.trim(), kind = kind, rows = rows,
        columns = if (customRows && kind != RackKind.Staggered) rowColumns.maxOrNull() ?: columns else columns,
        depth = depth, binCapacity = binCapacity,
        rowColumns = if (customRows && kind != RackKind.Staggered) rowColumns.toList() else null,
    ).normalized()

    fun apply(preset: RackPreset) {
        kind = preset.kind
        rows = preset.rows
        columns = preset.columns
        depth = preset.depth
        binCapacity = preset.binCapacity
        customRows = false
    }

    fun matches(preset: RackPreset) = !customRows && rows == preset.rows && columns == preset.columns && when (kind) {
        RackKind.Bins -> binCapacity == preset.binCapacity
        RackKind.Staggered -> true
        else -> depth == preset.depth
    }

    fun save() {
        showErrors = true
        if (name.isBlank()) return
        val rack = preview()
        if (existing == null) {
            viewModel.addRack(rack)
            navigator.back()
        } else {
            viewModel.updateRack(rack) { navigator.back() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.rack_editor_new else R.string.rack_editor_edit)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = { TextButton(onClick = ::save) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        val rack = preview()
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Dimens.l),
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

            Header(R.string.kind_title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                RackKind.entries.forEach { option ->
                    KindCard(option, selected = kind == option) {
                        kind = option
                        if (option == RackKind.Staggered) customRows = false
                        if (existing == null) presets.firstOrNull { it.kind == option }?.let(::apply)
                    }
                }
            }

            Hint(stringResource(R.string.help_kind_footer))

            Header(R.string.rack_size_title)
            SizeRow(stringResource(kind.rowsRes), rows, { rows = it; if (customRows) resizeRows(it) }, 1..MAX_SIZE)
            Hint(stringResource(R.string.help_field_rows))
            if (!customRows) {
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
                RackKind.Staggered -> Unit
            }

            if (kind != RackKind.Staggered) {
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
                    Column(Modifier.weight(1f)) {
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
                        SizeRow(stringResource(R.string.rack_row, Rack.rowLetters(row)), count, { rowColumns[row] = it }, 1..60)
                    }
                }
            }

            if (existing == null) {
                Header(R.string.rack_quick_start)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    presets.filter { it.kind == kind }.forEach { preset ->
                        FilterChip(selected = matches(preset), onClick = { apply(preset) }, label = { Text(stringResource(preset.title)) })
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
            Text(stringResource(R.string.help_rack_holds, rack.capacity), style = MaterialTheme.typography.bodyMedium)

            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("action.delete_rack"),
                ) {
                    ActionContent(stringResource(R.string.rack_delete), Icons.Outlined.Delete)
                }
            }
            HelpLink(HelpTopic.Racks, R.string.help_link_racks)
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.rack_delete_title),
            body = stringResource(R.string.rack_delete_body),
            confirm = stringResource(R.string.action_delete),
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
private fun KindCard(kind: RackKind, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = Modifier.widthIn(min = 150.dp, max = 220.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(Dimens.radiusCard),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) scheme.primaryContainer else scheme.surface),
    ) {
        Column(Modifier.padding(Dimens.m).heightIn(min = 96.dp), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
            Icon(kind.icon, contentDescription = null, tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Text(stringResource(kind.labelRes), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(kind.bodyRes), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Header(id: Int) {
    Text(
        stringResource(id), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SizeRow(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Stepper(
            value = value, onChange = onChange, range = range,
            decreaseLabel = stringResource(R.string.value_decrease, label),
            increaseLabel = stringResource(R.string.value_increase, label),
        )
    }
}
