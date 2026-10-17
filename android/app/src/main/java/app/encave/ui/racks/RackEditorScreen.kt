package app.encave.ui.racks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Rack
import app.encave.core.RackLayout
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.Stepper
import app.encave.ui.components.labelRes
import app.encave.ui.theme.Dimens
import java.util.UUID

private const val MAX_SIZE = 40

/** Name, rows, columns and layout, with a live preview. */
@Composable
fun RackEditorScreen(viewModel: AppViewModel, navigator: AppNavigator, rackId: String?) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val existing = rackId?.let { id -> cellar.racks.firstOrNull { it.id.toString() == id } }
    val defaultName = stringResource(R.string.rack_default_name, cellar.racks.size + 1)
    var name by rememberSaveable { mutableStateOf(existing?.name ?: defaultName) }
    var rows by rememberSaveable { mutableIntStateOf(existing?.rows ?: 6) }
    var columns by rememberSaveable { mutableIntStateOf(existing?.columns ?: 8) }
    var layout by rememberSaveable { mutableStateOf(existing?.layout ?: RackLayout.Grid) }
    var showErrors by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: UUID.randomUUID() }

    fun save() {
        showErrors = true
        if (name.isBlank()) return
        val rack = Rack(id = id, name = name.trim(), rows = rows, columns = columns, layout = layout)
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
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.l),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                isError = showErrors && name.isBlank(),
                supportingText = if (showErrors && name.isBlank()) {
                    { Text(stringResource(R.string.rack_name_required)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            SizeRow(stringResource(R.string.field_rows), rows, { rows = it }, Rack.rowLetters(rows - 1))
            SizeRow(stringResource(R.string.field_columns), columns, { columns = it }, columns.toString())
            Text(stringResource(R.string.field_layout), style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RackLayout.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = layout == option,
                        onClick = { layout = option },
                        shape = SegmentedButtonDefaults.itemShape(index, RackLayout.entries.size),
                    ) { Text(stringResource(option.labelRes)) }
                }
            }
            Text(stringResource(R.string.rack_preview), style = MaterialTheme.typography.titleSmall)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Box(Modifier.fillMaxWidth().padding(Dimens.l), contentAlignment = Alignment.Center) {
                    val preview = Rack(id = id, name = name, rows = rows, columns = columns, layout = layout)
                    val filled = cellar.bottlesInCellar.mapNotNull { it.slot }.filter { it.rackId == id }.toSet()
                    RackPreview(preview, Modifier.clearAndSetSemantics { }, maxSize = 240.dp, filled = filled)
                }
            }
            Text(
                "${stringResource(layout.labelRes)} · ${stringResource(R.string.rack_size, rows, columns)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(stringResource(R.string.rack_delete), Modifier.padding(start = Dimens.s))
                }
            }
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

@Composable
private fun SizeRow(label: String, value: Int, onChange: (Int) -> Unit, valueText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Stepper(
            value = value, onChange = onChange, range = 1..MAX_SIZE,
            decreaseLabel = stringResource(R.string.value_decrease, label), increaseLabel = stringResource(R.string.value_increase, label),
            valueText = if (valueText == value.toString()) valueText else "$value ($valueText)",
        )
    }
}
