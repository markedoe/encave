package app.encave.ui.components

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import app.encave.ui.theme.Dimens
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.encave.R
import app.encave.reminders.Reminders

/**
 * A switch for notifications worked out on the device (reminders, changes from other
 * devices). Turning it on asks for the notification permission on Android 13 and later;
 * if notifications are off for Encave, it stays off and says where to turn them on
 * (read aloud by TalkBack). Checked again on return to the app, as notifications may have
 * been turned on or off in the system settings meanwhile.
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
    LifecycleResumeEffect(Unit) {
        val allowed = Reminders.canNotify(context)
        enabled = isOn() && allowed
        if (allowed) refused = false
        onPauseOrDispose {}
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
    Column {
        ListItem(
            modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = ::change).testTag(tag),
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    if (refused) stringResource(R.string.reminders_refused) else footer,
                    color = if (refused) MaterialTheme.colorScheme.error else Color.Unspecified,
                    // The switch stays off when notifications are refused: say why.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        )
        if (refused) {
            OpenNotificationSettingsButton(Modifier.padding(start = Dimens.l).testTag("$tag.open_settings"))
        }
    }
}

/** "Open settings", under the message that notifications are off: Encave's notification settings. */
@Composable
fun OpenNotificationSettingsButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.action_open_settings), Modifier.padding(start = Dimens.s))
    }
}
