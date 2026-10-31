package app.encave.ui.cellar

import java.util.UUID
import app.encave.ui.navigation.RacksRoute
import app.encave.ui.racks.MoveBottleSheet
import app.encave.core.bottleToPutAway
import app.encave.core.bottleToOpen
import app.encave.ui.components.BottlePlaceIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
 * menu: Drink, Put away or Move, Show in storage, Add bottles, Edit wine, (Select), Delete
 * wine. Put away places the wine's bottle out of storage (`bottleToPutAway`); Move, the
 * bottle Drink would open; both in the one Move sheet. [row] gets the modifier giving
 * TalkBack the same actions, and opens the menu on a long press. Delete follows
 * [rememberWineDeletion]: Undo for a wine with one bottle, a question first otherwise.
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
    /** The bottle shown in the Move sheet. */
    var moving by remember { mutableStateOf<UUID?>(null) }
    // The bottle Drink would suggest first: one not kept for an occasion.
    val bottle = group.bottles.bottleToOpen
    val loose = group.bottles.bottleToPutAway
    // Spread over several storages: the one holding most of them.
    val placed = cellar.bottleToShow(group.bottles)
    val move: WineAction? = when {
        // A bottle out of storage: put it away (with no storage yet, the Storage section).
        loose != null -> WineAction(stringResource(R.string.action_put_away_ellipsis), BottlePlaceIcons.PutAway, "put_away") {
            if (cellar.racks.isEmpty()) navigator.open(RacksRoute) else moving = loose.id
        }
        bottle?.slot != null -> WineAction(stringResource(R.string.action_move_ellipsis), Icons.Outlined.OpenWith, "move") { moving = bottle.id }
        else -> null
    }
    val actions = listOfNotNull(
        WineAction(stringResource(R.string.action_drink), Icons.Outlined.LocalBar, "drink", run = onDrink).takeIf { bottle != null },
        move,
        placed?.let { shown ->
            WineAction(stringResource(R.string.action_show_in_rack), BottlePlaceIcons.Show, "show_in_rack") {
                navigator.open(RackDetailRoute(shown.slot!!.rackId.toString(), shown.id.toString()))
            }
        },
        WineAction(stringResource(R.string.action_add_bottles), Icons.Filled.Add, "add_bottles") {
            navigator.open(AddBottleRoute(wineId = group.id.toString()))
        },
        WineAction(stringResource(R.string.action_edit_wine), Icons.Outlined.Edit, "edit") { navigator.open(EditWineRoute(group.id.toString())) },
        onSelect?.let { WineAction(stringResource(R.string.action_select), Icons.Outlined.Checklist, "select", run = it) },
        WineAction(stringResource(R.string.action_delete_wine), Icons.Outlined.Delete, "delete", destructive = true) {
            deleteWines(setOf(group.id))
        },
    )
    Box(modifier) {
        row(
            Modifier.semantics { customActions = actions.map { CustomAccessibilityAction(it.label) { it.run(); true } } },
        ) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                val error = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { if (action.destructive) Text(action.label, color = error) else Text(action.label) },
                    onClick = {
                        expanded = false
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
    moving?.let { cellar.bottles[it] }?.let { shown ->
        MoveBottleSheet(viewModel, cellar, shown) { moving = null }
    }
}
