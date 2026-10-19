package app.encave.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import app.encave.R
import app.encave.reminders.Reminders

/**
 * A switch for notifications worked out on the device (reminders, changes from other
 * devices). Turning it on asks for the notification permission on Android 13 and later;
 * if notifications are off for Encave, it stays off and says where to turn them on.
 * [isOn]: the saved choice; [onChange] saves a new one.
 */
@Composable
fun NotificationSwitch(
    title: String,
    footer: String,
    icon: ImageVector,
    tag: String,
    isOn: () -> Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isOn() && Reminders.canNotify(context)) }
    var refused by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refused = !granted
        enabled = granted
        onChange(granted)
    }
    fun change(on: Boolean) {
        if (!on) {
            enabled = false
            refused = false
            onChange(false)
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!Reminders.canNotify(context)) {
            refused = true
        } else {
            enabled = true
            refused = false
            onChange(true)
        }
    }
    ListItem(
        modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = ::change).testTag(tag),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                if (refused) stringResource(R.string.reminders_refused) else footer,
                color = if (refused) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
    )
}
