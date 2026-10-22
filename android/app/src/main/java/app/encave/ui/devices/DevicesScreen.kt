package app.encave.ui.devices

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.EncaveApplication
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.core.sync.Member
import app.encave.core.sync.RelayMailboxStore
import app.encave.core.sync.RemoteSyncError
import app.encave.core.sync.RemoteSyncException
import app.encave.core.sync.WebDavMailboxStore
import app.encave.data.SyncFrequency
import app.encave.sync.SyncManager
import app.encave.ui.AppNavigator
import app.encave.ui.components.ActionContent
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.NotificationSwitch
import app.encave.ui.components.SectionHeader
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.navigation.InviteDeviceRoute
import app.encave.ui.navigation.JoinDeviceRoute
import app.encave.ui.navigation.RemoteSyncRoute
import app.encave.ui.theme.Dimens
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun syncManager(): SyncManager = (LocalContext.current.applicationContext as EncaveApplication).sync

/**
 * Android 17 asks the user before an app talks to the local network. Runs [then] once
 * the permission is granted (or not needed).
 */
@Composable
internal fun rememberLocalNetworkAccess(then: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    return {
        if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        ) {
            then()
        } else {
            pending = then
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
}

private const val LOCAL_NETWORK_SDK = 37

internal val SyncManager.Problem.text: Int
    get() = when (this) {
        SyncManager.Problem.Removed -> R.string.sync_problem_removed
        SyncManager.Problem.SaysRemoved -> R.string.sync_problem_says_removed
        SyncManager.Problem.NotVerified -> R.string.sync_problem_not_verified
        SyncManager.Problem.OtherVersion -> R.string.sync_problem_version
        SyncManager.Problem.Unfinished -> R.string.sync_problem_unfinished
        SyncManager.Problem.CannotListen -> R.string.sync_problem_cannot_listen
    }

/** For a WebDAV folder, the problems that differ from a relay's. */
private fun SyncManager.RemoteProblem.text(webDav: Boolean): Int = when {
    webDav && this == SyncManager.RemoteProblem.Refused -> R.string.remote_problem_webdav_refused
    webDav && this == SyncManager.RemoteProblem.Full -> R.string.remote_webdav_full
    webDav && this == SyncManager.RemoteProblem.FileTooLarge -> R.string.remote_problem_webdav_too_large
    else -> text
}

private val SyncManager.RemoteProblem.text: Int
    get() = when (this) {
        SyncManager.RemoteProblem.Refused -> R.string.remote_problem_refused
        SyncManager.RemoteProblem.Full -> R.string.remote_problem_full
        SyncManager.RemoteProblem.FileTooLarge -> R.string.remote_problem_too_large
        SyncManager.RemoteProblem.Unreachable -> R.string.remote_problem_unreachable
        SyncManager.RemoteProblem.RemovalPending -> R.string.remote_problem_removal
        SyncManager.RemoteProblem.Unreadable -> R.string.remote_problem_unreadable
    }

/** "2 minutes ago", lowercase to go inside "Synced …" (French writes "Il y a…"). */
private fun relative(date: Instant, now: Long = System.currentTimeMillis()): String =
    ago(date, now).replaceFirstChar { it.lowercase() }

/** "2 minutes ago", on its own. */
private fun ago(date: Instant, now: Long): String =
    DateUtils.getRelativeTimeSpanString(minOf(date.toEpochMilli(), now), now, DateUtils.MINUTE_IN_MILLIS).toString()

private val SyncFrequency.label: Int
    get() = when (this) {
        SyncFrequency.EveryMinute -> R.string.sync_frequency_1
        SyncFrequency.Every5Minutes -> R.string.sync_frequency_5
        SyncFrequency.Every15Minutes -> R.string.sync_frequency_15
        SyncFrequency.Manual -> R.string.sync_frequency_manual
    }

/** Problems shown at the top of Devices, with a way out; the others are in the status. */
private val SyncManager.Problem.isRemoval: Boolean
    get() = this == SyncManager.Problem.Removed || this == SyncManager.Problem.SaysRemoved

/** A line of a status row; [isError] lines are also shown in the error color (their words say it too). */
private data class StatusLine(val text: String, val isError: Boolean = false)

/** On the same Wi-Fi: running, the last success or failure, and whether a device is around. */
private fun localStatus(resources: Resources, status: SyncManager.Status, others: List<Member>, now: Long): List<StatusLine> = buildList {
    val problem = status.problem?.takeUnless { it.isRemoval }
    val last = others.mapNotNull { status.lastSync[it.id] }.maxOrNull()
    when {
        status.syncing.isNotEmpty() -> add(StatusLine(resources.getString(R.string.devices_status_syncing)))
        problem != null -> {
            add(StatusLine(resources.getString(R.string.sync_last_failed, resources.getString(problem.text)), isError = true))
            status.problemAt?.let { add(StatusLine(ago(it, now))) }
        }
        last != null -> add(StatusLine(resources.getString(R.string.devices_status_synced, relative(last, now))))
        else -> add(StatusLine(resources.getString(R.string.devices_status_never)))
    }
    if (status.nearby.isEmpty() && status.syncing.isEmpty()) add(StatusLine(resources.getString(R.string.sync_no_device_nearby)))
}

/** Away from home: running, the last success or failure, changes not sent yet, and the next sync. */
private fun remoteStatus(resources: Resources, status: SyncManager.Status, frequency: SyncFrequency, now: Long): List<StatusLine> = buildList {
    val isWebDav = status.remoteStorage?.startsWith(SyncManager.WEBDAV_PREFIX) == true
    val problem = status.remoteProblem
    val last = status.lastRemoteSync
    when {
        status.syncingRemotely -> add(StatusLine(resources.getString(R.string.devices_status_syncing)))
        // Rounds wait for a login on this device.
        status.needsWebDavSignIn ->
            add(StatusLine(resources.getString(R.string.sync_last_failed, resources.getString(R.string.devices_webdav_sign_in)), isError = true))
        problem != null -> {
            add(StatusLine(resources.getString(R.string.sync_last_failed, resources.getString(problem.text(isWebDav))), isError = true))
            status.remoteProblemAt?.let { add(StatusLine(ago(it, now))) }
        }
        last != null -> add(StatusLine(resources.getString(R.string.devices_status_synced, relative(last, now))))
        else -> add(StatusLine(resources.getString(R.string.devices_status_never)))
    }
    if (status.unsentChanges) add(StatusLine(resources.getString(R.string.sync_unsent)))
    if (status.needsWebDavSignIn) return@buildList
    if (frequency == SyncFrequency.Manual) {
        add(StatusLine(resources.getString(R.string.sync_next_manual)))
    } else {
        val next = status.nextRemoteSync
        if (next != null && !status.syncingRemotely) {
            val minutes = ((next.toEpochMilli() - now + 59_999) / 60_000).toInt().coerceAtLeast(1)
            add(StatusLine(resources.getQuantityString(R.plurals.sync_next_in, minutes, minutes)))
        }
        add(StatusLine(resources.getString(R.string.sync_background)))
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, lines: List<StatusLine>, syncing: Boolean, tag: String) {
    ListItem(
        // Read as one by TalkBack: the title, then each line.
        modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                lines.forEach { line ->
                    Text(line.text, color = if (line.isError) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                }
            }
        },
        trailingContent = if (syncing) {
            { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            null
        },
    )
}

/** How often this device syncs away from home: a menu of the four choices, the current one checked. */
@Composable
private fun FrequencyRow(frequency: SyncFrequency, onChange: (SyncFrequency) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.testTag("devices.frequency").clickable(role = Role.DropdownList) { choosing = true },
            leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.sync_frequency_title)) },
            supportingContent = { Text(stringResource(frequency.label)) },
        )
        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            SyncFrequency.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = {
                        if (option == frequency) Icon(Icons.Outlined.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                    },
                    modifier = Modifier.testTag("devices.frequency.${option.raw}").semantics { selected = option == frequency },
                    onClick = {
                        choosing = false
                        onChange(option)
                    },
                )
            }
        }
    }
}

private val Member.icon: ImageVector
    get() = when (platform) {
        "macos" -> Icons.Outlined.Laptop
        "ipados" -> Icons.Outlined.Tablet
        else -> Icons.Outlined.Smartphone
    }

@Composable
private fun Back(navigator: AppNavigator) {
    IconButton(onClick = { navigator.back() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

/** Settings › Devices: the devices that share this cellar, and adding one. */
@Composable
fun DevicesScreen(navigator: AppNavigator) {
    val sync = syncManager()
    val status by sync.state.collectAsStateWithLifecycle()
    val preferences = (LocalContext.current.applicationContext as EncaveApplication).preferences
    val frequency by preferences.syncFrequency.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    // The status says "3 minutes ago" and "in about 4 minutes": kept current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    var removing by remember { mutableStateOf<Member?>(null) }
    var confirmingLeave by rememberSaveable { mutableStateOf(false) }
    val others = status.membership?.active.orEmpty().filter { it.id != sync.identity.id }
    val withNetwork = rememberLocalNetworkAccess { }
    val invite = rememberLocalNetworkAccess { navigator.open(InviteDeviceRoute) }
    val join = rememberLocalNetworkAccess { navigator.open(JoinDeviceRoute) }
    val syncNow = rememberLocalNetworkAccess { sync.syncEverywhereNow() }
    LaunchedEffect(others.isEmpty()) { if (others.isNotEmpty()) withNetwork() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.devices_title)) }, navigationIcon = { Back(navigator) }) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(app.encave.ui.components.LocalSnackbarHostState.current) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) {
            status.problem?.takeIf { it.isRemoval || status.wasRemoved }?.let { problem ->
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(problem.text)) },
                    supportingContent = if (status.wasRemoved || problem == SyncManager.Problem.SaysRemoved) {
                        { TextButton(onClick = { sync.leave() }) { Text(stringResource(R.string.devices_leave)) } }
                    } else {
                        null
                    },
                )
            }

            SectionHeader(stringResource(R.string.devices_section))
            DeviceRow(sync.me, isThisDevice = true, status = null)
            others.forEach { member ->
                val text = when {
                    member.id in status.syncing -> stringResource(R.string.devices_status_syncing)
                    status.lastSync[member.id] != null -> stringResource(R.string.devices_status_synced, relative(status.lastSync.getValue(member.id)))
                    member.id in status.nearby -> stringResource(R.string.devices_status_nearby)
                    else -> stringResource(R.string.devices_status_never)
                }
                RemovableDeviceRow(member, text) { removing = member }
            }
            Footer(stringResource(if (others.isEmpty()) R.string.devices_empty_footer else R.string.devices_footer))

            ListItem(
                modifier = Modifier.testTag("devices.add").clickable { invite() },
                leadingContent = { Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.devices_add), color = MaterialTheme.colorScheme.primary) },
            )
            ListItem(
                modifier = Modifier.testTag("devices.join").clickable { join() },
                leadingContent = { Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.devices_join), color = MaterialTheme.colorScheme.primary) },
            )
            if (others.isNotEmpty()) {
                val enabled = (status.nearby.isNotEmpty() || status.remoteStorage != null) && status.syncing.isEmpty() && !status.syncingRemotely
                ListItem(
                    modifier = Modifier.testTag("devices.sync_now").clickable(enabled = enabled) { syncNow() },
                    leadingContent = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                    headlineContent = {
                        Text(
                            stringResource(R.string.devices_sync_now),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                )
            }
            Footer(stringResource(R.string.devices_add_footer))

            if (others.isNotEmpty() && !status.wasRemoved) {
                SectionHeader(stringResource(R.string.remote_status))
                StatusRow(
                    Icons.Outlined.Wifi,
                    stringResource(R.string.sync_status_local),
                    localStatus(resources, status, others, now),
                    syncing = status.syncing.isNotEmpty(),
                    tag = "devices.status_local",
                )
                if (status.remoteStorage != null) {
                    StatusRow(
                        Icons.Outlined.Public,
                        stringResource(R.string.sync_status_away),
                        remoteStatus(resources, status, frequency, now),
                        syncing = status.syncingRemotely,
                        tag = "devices.status_away",
                    )
                }
            }

            if (others.isNotEmpty()) {
                ListItem(
                    modifier = Modifier.testTag("devices.remote").clickable { navigator.open(RemoteSyncRoute) },
                    leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.devices_remote)) },
                    supportingContent = {
                        val storage = status.remoteStorage
                        Text(
                            stringResource(
                                when {
                                    storage == null -> R.string.devices_remote_off
                                    storage.startsWith(SyncManager.WEBDAV_PREFIX) -> R.string.devices_remote_webdav
                                    else -> R.string.devices_remote_relay
                                },
                            ),
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                )
                if (status.needsWebDavSignIn) {
                    // The folder came from another device: this one needs its own login.
                    ListItem(
                        modifier = Modifier.testTag("devices.webdav_sign_in").clickable { navigator.open(RemoteSyncRoute) },
                        leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        headlineContent = { Text(stringResource(R.string.devices_webdav_sign_in), color = MaterialTheme.colorScheme.error) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    )
                }
                Footer(stringResource(R.string.devices_remote_footer))
                if (status.remoteStorage != null) {
                    FrequencyRow(frequency) { sync.setFrequency(it) }
                    Footer(stringResource(R.string.sync_frequency_footer))
                }

                SectionHeader(stringResource(R.string.sync_changes_channel))
                NotificationSwitch(
                    title = stringResource(R.string.sync_notify),
                    footer = stringResource(R.string.sync_notify_footer),
                    icon = Icons.Outlined.NotificationsActive,
                    tag = "devices.notify",
                    isOn = { preferences.syncNotifications.value },
                    onChange = { preferences.setSyncNotifications(it) },
                )
            }

            SectionHeader(stringResource(R.string.devices_e2e))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.devices_e2e_body), style = MaterialTheme.typography.bodyMedium) },
            )
            HelpLink(HelpTopic.Devices, R.string.devices_help)

            if (status.membership != null && others.isNotEmpty()) {
                ListItem(
                    modifier = Modifier.clickable { confirmingLeave = true },
                    headlineContent = { Text(stringResource(R.string.devices_leave), color = MaterialTheme.colorScheme.error) },
                )
                Footer(stringResource(R.string.devices_leave_footer))
            }
            Spacer(Modifier.padding(bottom = Dimens.xl))
        }
    }

    removing?.let { member ->
        ConfirmDialog(
            title = stringResource(R.string.devices_remove_title, member.name),
            body = stringResource(R.string.devices_remove_body),
            confirm = stringResource(R.string.devices_remove_device),
            onConfirm = { sync.remove(member.id) },
            onDismiss = { removing = null },
        )
    }
    if (confirmingLeave) {
        ConfirmDialog(
            title = stringResource(R.string.devices_leave_title),
            body = stringResource(R.string.devices_leave_footer),
            confirm = stringResource(R.string.devices_leave_confirm),
            onConfirm = { sync.leave() },
            onDismiss = { confirmingLeave = false },
        )
    }
}

@Composable
private fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
    )
}

@Composable
private fun DeviceRow(member: Member, isThisDevice: Boolean, status: String?, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    ListItem(
        modifier = modifier,
        leadingContent = { Icon(member.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(member.name) },
        supportingContent = (if (isThisDevice) stringResource(R.string.devices_this_device) else status)?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

/** A device that can be removed: swipe it, open its menu, or use the TalkBack action. */
@Composable
private fun RemovableDeviceRow(member: Member, status: String, onRemove: () -> Unit) {
    val removeLabel = stringResource(R.string.devices_remove)
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onRemove()
            state.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    var menu by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(horizontal = Dimens.l), contentAlignment = Alignment.CenterEnd) {
                Text(removeLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { onRemove(); true }) },
    ) {
        DeviceRow(member, isThisDevice = false, status = status) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.devices_remove_device)) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** What went wrong when checking a WebDAV folder or its login. */
private fun webDavError(error: Throwable): Int = when ((error as? RemoteSyncException)?.error) {
    RemoteSyncError.Unauthorized -> R.string.remote_webdav_refused
    RemoteSyncError.QuotaExceeded -> R.string.remote_webdav_full
    else -> R.string.remote_webdav_not_webdav
}

/** Username and password for a WebDAV folder; the password can be shown, with a TalkBack label. */
@Composable
private fun LoginFields(username: String, onUsername: (String) -> Unit, password: String, onPassword: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = username,
        onValueChange = onUsername,
        label = { Text(stringResource(R.string.remote_webdav_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).testTag("remote.webdav_username"),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPassword,
        label = { Text(stringResource(R.string.remote_webdav_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.remote_hide_password else R.string.remote_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).testTag("remote.webdav_password"),
    )
}

/** Syncing away from home through a WebDAV folder or a relay, and what they can see. */
@Composable
fun RemoteSyncScreen(navigator: AppNavigator) {
    val sync = syncManager()
    val status by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val candidate = address.trim().let { if (it.contains("://")) it else "https://$it" }
    val valid = address.isNotBlank() && RelayMailboxStore.isAllowed(candidate)
    var folder by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Not saved with the screen's state: it is a secret.
    var password by remember { mutableStateOf("") }
    var changingLogin by remember { mutableStateOf(false) }
    var webDavError by remember { mutableStateOf<Int?>(null) }
    val folderValid = WebDavMailboxStore.folderUrl(folder) != null

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.remote_title)) }, navigationIcon = { Back(navigator) }) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(app.encave.ui.components.LocalSnackbarHostState.current) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) {
            val storage = status.remoteStorage
            if (storage != null) {
                val isWebDav = storage.startsWith(SyncManager.WEBDAV_PREFIX)
                val kind = if (isWebDav) R.string.devices_remote_webdav else R.string.devices_remote_relay
                val shown = storage.removePrefix(SyncManager.WEBDAV_PREFIX)
                SectionHeader(stringResource(kind))
                ListItem(
                    headlineContent = { Text(stringResource(kind)) },
                    supportingContent = { Text(runCatching { URI(shown).host }.getOrNull() ?: shown) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.remote_status)) },
                    supportingContent = {
                        val problem = status.remoteProblem
                        when {
                            status.syncingRemotely -> Text(stringResource(R.string.devices_status_syncing))
                            problem != null -> Text(stringResource(problem.text(isWebDav)), color = MaterialTheme.colorScheme.error)
                            status.lastRemoteSync != null -> Text(stringResource(R.string.devices_status_synced, relative(status.lastRemoteSync!!)))
                            else -> Text(stringResource(R.string.devices_status_never))
                        }
                    },
                )
                Row(Modifier.padding(horizontal = Dimens.l), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.s)) {
                    Button(onClick = { scope.launch { sync.remoteRound() } }, enabled = !status.syncingRemotely, modifier = Modifier.testTag("action.remote_sync")) {
                        ActionContent(stringResource(R.string.devices_sync_now), Icons.Outlined.Sync)
                    }
                    OutlinedButton(onClick = { scope.launch { sync.setRemoteStorage(null) } }) {
                        Text(stringResource(R.string.remote_turn_off), color = MaterialTheme.colorScheme.error)
                    }
                }
                Footer(stringResource(R.string.remote_on_footer))
                if (isWebDav) {
                    if (status.needsWebDavSignIn || changingLogin) {
                        SectionHeader(stringResource(R.string.devices_webdav_sign_in))
                        LoginFields(username, { username = it; webDavError = null }, password, { password = it; webDavError = null })
                        webDavError?.let { ErrorText(it) }
                        Button(
                            onClick = {
                                checking = true
                                webDavError = null
                                scope.launch {
                                    runCatching { sync.signInToWebDav(username.trim(), password) }
                                        .onSuccess {
                                            password = ""
                                            changingLogin = false
                                        }
                                        .onFailure { webDavError = webDavError(it) }
                                    checking = false
                                }
                            },
                            enabled = username.isNotBlank() && password.isNotEmpty() && !checking,
                            modifier = Modifier.padding(horizontal = Dimens.l).fillMaxWidth().testTag("action.webdav_sign_in"),
                        ) {
                            ActionContent(stringResource(R.string.remote_webdav_sign_in))
                        }
                        Footer(stringResource(R.string.remote_webdav_sign_in_footer))
                    } else {
                        TextButton(onClick = { changingLogin = true }, modifier = Modifier.padding(horizontal = Dimens.s)) {
                            Text(stringResource(R.string.remote_webdav_change_login))
                        }
                    }
                }
            } else {
                SectionHeader(stringResource(R.string.remote_webdav))
                OutlinedTextField(
                    value = folder,
                    onValueChange = {
                        folder = it
                        webDavError = null
                    },
                    label = { Text(stringResource(R.string.remote_webdav_address)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = folder.isNotBlank() && !folderValid,
                    supportingText = { if (folder.isNotBlank() && !folderValid) Text(stringResource(R.string.remote_webdav_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).testTag("remote.webdav_address"),
                )
                LoginFields(username, { username = it; webDavError = null }, password, { password = it; webDavError = null })
                webDavError?.let { ErrorText(it) }
                Button(
                    onClick = {
                        checking = true
                        webDavError = null
                        scope.launch {
                            runCatching { sync.setWebDav(folder, username.trim(), password) }
                                .onSuccess { password = "" }
                                .onFailure { webDavError = webDavError(it) }
                            checking = false
                        }
                    },
                    enabled = folderValid && username.isNotBlank() && password.isNotEmpty() && !checking,
                    modifier = Modifier.padding(horizontal = Dimens.l).fillMaxWidth().testTag("action.webdav_check"),
                ) {
                    ActionContent(stringResource(R.string.remote_check))
                }
                Footer(stringResource(R.string.remote_webdav_footer))

                SectionHeader(stringResource(R.string.devices_remote_relay))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.remote_address)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = error != null || (address.isNotBlank() && !valid),
                    supportingText = {
                        val message = error ?: if (address.isNotBlank() && !valid) R.string.remote_invalid else null
                        message?.let { Text(stringResource(it)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).testTag("remote.address"),
                )
                Button(
                    onClick = {
                        checking = true
                        error = null
                        scope.launch {
                            runCatching { sync.setRemoteStorage(candidate) }.onFailure { error = R.string.remote_not_relay }
                            checking = false
                        }
                    },
                    enabled = valid && !checking,
                    modifier = Modifier.padding(horizontal = Dimens.l).fillMaxWidth().testTag("action.remote_check"),
                ) {
                    ActionContent(stringResource(R.string.remote_check))
                }
                Footer(stringResource(R.string.remote_relay_footer))
            }

            SectionHeader(stringResource(R.string.remote_privacy))
            PrivacyRow(Icons.Outlined.Lock, R.string.remote_privacy_1)
            PrivacyRow(Icons.Outlined.Visibility, R.string.remote_privacy_2)
            PrivacyRow(Icons.Outlined.Key, R.string.remote_privacy_3)
            Footer(stringResource(R.string.remote_privacy_footer))

            ListItem(
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, RELAY_README.toUri())) }
                },
                leadingContent = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.remote_how_to)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            )
            HelpLink(HelpTopic.Devices, R.string.devices_help)
            Spacer(Modifier.width(1.dp).padding(bottom = Dimens.xl))
        }
    }
}

@Composable
private fun ErrorText(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
    )
}

@Composable
private fun PrivacyRow(icon: ImageVector, text: Int) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
        headlineContent = { Text(stringResource(text), style = MaterialTheme.typography.bodyMedium) },
        colors = ListItemDefaults.colors(),
    )
}

private const val RELAY_README = "https://github.com/markedoe/encave/blob/main/relay/README.md"
