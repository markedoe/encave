package app.encave.ui.cellar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.DeletionGuard
import app.encave.core.bottleRecordCount
import app.encave.core.deletionGuard
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import java.util.UUID

/**
 * One rule for deleting wines, as on Apple (core [app.encave.core.DeletionRule]): a wine with
 * at most one bottle goes at once, with the Undo snackbar; a wine with several bottles, or
 * several wines, is confirmed first in an alert naming it and saying how many bottles and
 * tastings go with it. Returns the function that deletes, or asks first; [onDeleted] runs once
 * they are deleted.
 */
@Composable
internal fun rememberWineDeletion(cellar: Cellar, viewModel: AppViewModel, onDeleted: () -> Unit = {}): (Set<UUID>) -> Unit {
    var confirming by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val current by rememberUpdatedState(cellar)
    val done by rememberUpdatedState(onDeleted)
    val ids = confirming.map(UUID::fromString).filter { cellar.wines[it] != null }.toSet()
    if (ids.isNotEmpty()) {
        val single = ids.singleOrNull()?.let { cellar.wines[it] }
        val bottles = cellar.bottleRecordCount(ids)
        // Several wines: counted in bottles first ("Delete 15 bottles (2 wines)?").
        ConfirmDialog(
            title = when {
                single != null -> stringResource(R.string.confirm_delete_wine_title, single.displayName)
                bottles > 0 -> pluralStringResource(
                    R.plurals.confirm_delete_bottles_of_wines, bottles, bottles, pluralStringResource(R.plurals.wine_count, ids.size, ids.size),
                )
                else -> pluralStringResource(R.plurals.confirm_delete_wines_count, ids.size, ids.size)
            },
            body = if (single == null && bottles > 0) stringResource(R.string.confirm_delete_wines_body)
            else pluralStringResource(R.plurals.confirm_delete_wines_bottles, bottles, bottles),
            confirm = stringResource(if (single != null) R.string.action_delete_wine else R.string.action_delete_wines),
            onConfirm = {
                if (single != null) viewModel.deleteWine(single.id) else viewModel.deleteWines(ids)
                done()
            },
            onDismiss = { confirming = emptyList() },
        )
    }
    return remember(viewModel) {
        { wineIds: Set<UUID> ->
            val live = wineIds.filter { current.wines[it] != null }.toSet()
            when {
                live.isEmpty() -> Unit
                current.deletionGuard(live) == DeletionGuard.Confirm -> confirming = live.map(UUID::toString)
                else -> {
                    viewModel.deleteWine(live.first())
                    done()
                }
            }
        }
    }
}
