package app.encave.ui.racks

import app.encave.ui.components.placeLabel
import app.encave.ui.components.locationIcon
import app.encave.ui.components.WineThumbnail
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.ui.AppViewModel
import app.encave.ui.UiText
import app.encave.ui.components.Hint
import app.encave.ui.theme.Dimens

/** The choice "no rack" in the rack menu. */
private const val NO_RACK = ""

/**
 * Move a bottle to a place of any rack, or out of the racks with a note on where it is; for
 * a bottle out of storage, Put away. The one sheet every Move and Put away opens, as on Apple.
 * Tap a free place to move it there; another bottle swaps them (one bottle per place);
 * a bin takes it if it has room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveBottleSheet(viewModel: AppViewModel, cellar: Cellar, bottle: Bottle, onDismiss: () -> Unit) {
    // The bottle's rack first, then the others in their order.
    val racks = cellar.racks.sortedBy { if (it.id == bottle.slot?.rackId) 0 else 1 }
    // Its own storage; out of storage, where its wine's other bottles are.
    var rackId by rememberSaveable { mutableStateOf(cellar.storageToOpen(bottle)?.id?.toString() ?: NO_RACK) }
    var note by rememberSaveable { mutableStateOf(bottle.storageNote.orEmpty()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rack = racks.firstOrNull { it.id.toString() == rackId }
    val noRack = stringResource(R.string.move_no_rack)
    val moveHere = stringResource(R.string.rack_cell_move_here)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.l)
                .padding(bottom = Dimens.l).testTag("move.sheet"),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Text(
                stringResource(if (bottle.slot == null) R.string.move_title_put_away else R.string.move_title),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
            )
            // Which bottle, and where it is now.
            val wine = cellar.wines[bottle.wineId]
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Dimens.m), verticalAlignment = Alignment.CenterVertically,
            ) {
                WineThumbnail(wine)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(wine?.displayName ?: stringResource(R.string.unknown_wine), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                        Icon(
                            cellar.locationIcon(bottle), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                        )
                        Text(cellar.placeLabel(bottle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = rack?.name ?: noRack,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.move_rack)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("move.rack"),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    racks.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name) }, onClick = { rackId = option.id.toString(); expanded = false })
                    }
                    DropdownMenuItem(text = { Text(noRack) }, onClick = { rackId = NO_RACK; expanded = false })
                }
            }
            if (rack != null) {
                // Pinch or double-tap to zoom in on a large rack, as on the rack screen.
                key(rack.id) {
                    ZoomableRack(rack, rememberRackZoomState(rack), Modifier.fillMaxWidth().height(360.dp), padding = Dimens.xs) { cellSize, zoomHere ->
                        RackGrid(
                            rack = rack,
                            cellar = cellar,
                            cellSize = cellSize,
                            highlighted = bottle.slot?.takeIf { it.rackId == rack.id },
                            clickLabel = { moveHere },
                            onCellClick = { place, _ ->
                                val from = bottle.slot
                                val free = cellar.freeSlotAt(place)
                                when {
                                    from != null && from in place -> onDismiss()
                                    free != null -> {
                                        viewModel.move(bottle.id, free)
                                        onDismiss()
                                    }
                                    place.holdsOne(rack, cellar) && from != null -> {
                                        viewModel.swap(from, place.firstSlot)
                                        onDismiss()
                                    }
                                    else -> viewModel.showMessage(UiText.Res(R.string.rack_cell_full))
                                }
                            },
                            onCellDoubleClick = { _ -> zoomHere() },
                        )
                    }
                }
                Hint(stringResource(R.string.move_grid_hint))
            } else {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.field_storage)) },
                    placeholder = { Text(stringResource(R.string.field_storage_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("move.note"),
                )
                Button(
                    onClick = {
                        viewModel.move(bottle.id, null, note)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End).testTag("move.save"),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
