package app.encave.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.encave.R

/**
 * Asks "Discard changes?" before a form with unsaved input is left: Back (including the
 * predictive back gesture, skipped while [isDirty]) and the returned action, for the
 * form's Close button. [leave] runs at once when nothing was changed, or once confirmed.
 * Shown beside a list, opening another row asks the same, and opens it once confirmed.
 */
@Composable
fun rememberDiscardGuard(isDirty: Boolean, leave: () -> Unit): () -> Unit {
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isDirty) { asking = true }
    // Beside a list, opening another row asks too.
    val switch = rememberPaneSwitch(isDirty)
    if (asking || switch != null) {
        AlertDialog(
            onDismissRequest = { asking = false; switch?.cancel() },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = { asking = false; if (switch != null) switch.confirm() else leave() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("discard.confirm"),
                ) { Text(stringResource(R.string.discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false; switch?.cancel() }, modifier = Modifier.testTag("discard.keep")) {
                    Text(stringResource(R.string.discard_keep))
                }
            },
        )
    }
    return { if (isDirty) asking = true else leave() }
}
