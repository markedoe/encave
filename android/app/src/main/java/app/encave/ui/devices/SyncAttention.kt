package app.encave.ui.devices

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.sync.SyncHealth
import app.encave.sync.LocalNetwork
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.theme.Dimens
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether sync needs the user, kept current (a failure becomes "Not syncing" after an hour). */
@Composable
fun rememberSyncHealth(): SyncHealth {
    val sync = syncManager()
    val status by sync.state.collectAsStateWithLifecycle()
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    return status.health(sync.identity.id, now)
}

/** The Settings icon of the top bars, with a badge when sync needs attention (said to TalkBack too). */
@Composable
fun SettingsIcon() {
    val health = rememberSyncHealth()
    if (health == SyncHealth.Ok) {
        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
    } else {
        BadgedBox(badge = { Badge() }) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_sync_attention))
        }
    }
}

/** "Sign-in needed", "Sync paused" or "Not syncing", with a warning symbol; nothing when sync is fine. */
@Composable
fun SyncHealthLabel(health: SyncHealth, modifier: Modifier = Modifier) {
    val text = when (health) {
        SyncHealth.Ok -> return
        is SyncHealth.NeedsAction ->
            if (health.reason == SyncHealth.Reason.SignInNeeded) R.string.sync_health_sign_in else R.string.sync_health_paused
        SyncHealth.Failing -> R.string.sync_health_failing
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Pull down to sync now ([enabled] when other devices share the cellar): with the devices on
 * the network, when local network access allows, and away from home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncRefreshBox(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val sync = syncManager()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                try {
                    sync.syncEverywhere(local = LocalNetwork.isAllowed(context))
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize().testTag("cellar.refresh"),
    ) { content() }
}

/** Opens this app's page in the system settings (permissions, local network). */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Android 17 asks the user before an app talks to the local network. Runs [then] once the
 * permission is granted (or not needed). Explains first when Android suggests it, asks at
 * most once per screen, and otherwise says why nothing happens, with a way to the settings.
 */
@Composable
internal fun rememberLocalNetworkAccess(then: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var explaining by remember { mutableStateOf(false) }
    val denied = {
        scope.launch {
            val result = snackbar.showSnackbar(
                resources.getString(R.string.local_network_needed),
                actionLabel = resources.getString(R.string.action_open_settings),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
        }
        Unit
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke() else denied()
        pending = null
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = {
                explaining = false
                pending = null
            },
            title = { Text(stringResource(R.string.local_network_rationale_title)) },
            text = { Text(stringResource(R.string.local_network_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    asked = true
                    launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }) { Text(stringResource(R.string.local_network_rationale_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explaining = false
                    pending = null
                }) { Text(stringResource(R.string.local_network_rationale_later)) }
            },
        )
    }
    return {
        when {
            LocalNetwork.isAllowed(context) -> then()
            // Asked already on this screen: Android would not show its dialog again.
            asked -> denied()
            else -> {
                pending = then
                if (context.activity()?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK) == true) {
                    explaining = true
                } else {
                    asked = true
                    launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }
    }
}
