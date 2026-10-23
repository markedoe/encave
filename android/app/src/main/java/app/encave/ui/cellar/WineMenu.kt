package app.encave.ui.cellar

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.WineGroup
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.navigation.AddBottleRoute
import app.encave.ui.navigation.EditWineRoute
import app.encave.ui.navigation.RackDetailRoute

private class WineAction(
    val label: String,
    val icon: ImageVector,
    val tag: String,
    val destructive: Boolean = false,
    val run: () -> Unit,
)

/**
 * A wine row of the Cellar or Tonight with its actions, in the order of the Apple app's
 * menu: Drink, Show in rack, Move, Add bottles, Edit, (Select), Delete. [row] gets the
 * modifier giving TalkBack the same actions, and opens the menu on a long press. Delete
 * follows [rememberWineDeletion]: Undo for a wine with one bottle, a question first otherwise.
 */
@Composable
fun WineMenuBox(
    group: WineGroup,
    cellar: Cellar,
    viewModel: AppViewModel,
    navigator: AppNavigator,
    onDrink: () -> Unit,
    modifier: Modifier = Modifier,
    /** Starts choosing several wines (Cellar only). */
    onSelect: (() -> Unit)? = null,
    row: @Composable (rowModifier: Modifier, openMenu: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // With Undo, or asked first for a wine with several bottles.
    val deleteWines = rememberWineDeletion(cellar, viewModel)
    // A bottle without a place and several racks: the menu lists the racks to move it to.
    var pickingRack by remember { mutableStateOf(false) }
    // The bottle Drink would suggest first: one not kept for an occasion.
    val bottle = group.bottles.firstOrNull { it.reservedFor == null } ?: group.bottles.firstOrNull()
    val slot = bottle?.slot
    val placed = group.bottles.firstNotNullOfOrNull { b -> b.slot?.let { b to it } }
    val racks = cellar.racks
    fun moveIn(rackId: String) {
        if (bottle != null) navigator.open(RackDetailRoute(rackId, moveBottleId = bottle.id.toString()))
    }
    val move: (() -> Unit)? = when {
        bottle == null -> null
        slot != null -> { { moveIn(slot.rackId.toString()) } }
        racks.size == 1 -> { { moveIn(racks.single().id.toString()) } }
        racks.size > 1 -> { { pickingRack = true; expanded = true } }
        else -> null
    }
    val actions = listOfNotNull(
        WineAction(stringResource(R.string.action_drink), Icons.Outlined.LocalBar, "drink", run = onDrink).takeIf { bottle != null },
        placed?.let { (shown, at) ->
            WineAction(stringResource(R.string.action_show_in_rack), Icons.Outlined.GridView, "show_in_rack") {
                navigator.open(RackDetailRoute(at.rackId.toString(), shown.id.toString()))
            }
        },
        move?.let { WineAction(stringResource(R.string.action_move), Icons.Outlined.OpenWith, "move", run = it) },
        WineAction(stringResource(R.string.action_add_bottles), Icons.Filled.Add, "add_bottles") {
            navigator.open(AddBottleRoute(wineId = group.id.toString()))
        },
        WineAction(stringResource(R.string.action_edit), Icons.Outlined.Edit, "edit") { navigator.open(EditWineRoute(group.id.toString())) },
        onSelect?.let { WineAction(stringResource(R.string.action_select), Icons.Outlined.Checklist, "select", run = it) },
        WineAction(stringResource(R.string.action_delete), Icons.Outlined.Delete, "delete", destructive = true) {
            deleteWines(setOf(group.id))
        },
    )
    fun dismiss() {
        expanded = false
        pickingRack = false
    }
    Box(modifier) {
        row(
            Modifier.semantics { customActions = actions.map { CustomAccessibilityAction(it.label) { it.run(); true } } },
        ) {
            pickingRack = false
            expanded = true
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::dismiss) {
            if (pickingRack) {
                racks.forEach { rack ->
                    DropdownMenuItem(
                        text = { Text(rack.name) },
                        onClick = { dismiss(); moveIn(rack.id.toString()) },
                        leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                    )
                }
            } else {
                actions.forEach { action ->
                    val error = MaterialTheme.colorScheme.error
                    DropdownMenuItem(
                        text = { if (action.destructive) Text(action.label, color = error) else Text(action.label) },
                        onClick = {
                            // Move may keep the menu open to list the racks.
                            dismiss()
                            action.run()
                        },
                        leadingIcon = {
                            if (action.destructive) Icon(action.icon, contentDescription = null, tint = error)
                            else Icon(action.icon, contentDescription = null)
                        },
                        modifier = Modifier.testTag("wine.menu.${action.tag}"),
                    )
                }
            }
        }
    }
}
