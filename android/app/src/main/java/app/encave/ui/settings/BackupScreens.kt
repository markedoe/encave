package app.encave.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.Bottle
import app.encave.core.CellarArchive
import app.encave.core.CellarVersion
import app.encave.core.RestoreMode
import app.encave.core.RestorePreview
import app.encave.data.RestoreSource
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.readableWidth
import app.encave.ui.navigation.PreviousVersionsRoute
import app.encave.ui.navigation.RestoreRoute
import app.encave.ui.navigation.SettingsRoute
import app.encave.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "21 October 2026, 22:14" in the user's language. */
private fun formatDate(date: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).format(date.atZone(ZoneId.systemDefault()))

/** Settings › Your data: Back up now, Previous versions and the weekly backup. */
@Composable
fun BackupSettingsItems(viewModel: AppViewModel, navigator: AppNavigator) {
    val context = LocalContext.current
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val weekly by viewModel.backups.weeklyEnabled.collectAsStateWithLifecycle()
    val last by viewModel.backups.lastBackup.collectAsStateWithLifecycle()
    val working by viewModel.backups.working.collectAsStateWithLifecycle()
    val backUp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.backUpNow(context.contentResolver, it) }
    }

    ListItem(
        modifier = Modifier.clickable(enabled = !working && !cellar.isEmpty) { backUp.launch(CellarArchive.fileName(Instant.now())) }
            .testTag("settings.backup"),
        leadingContent = {
            if (working) CircularProgressIndicator(Modifier.padding(Dimens.xs)) else Icon(Icons.Outlined.Archive, contentDescription = null)
        },
        headlineContent = { Text(stringResource(if (working) R.string.settings_backing_up else R.string.settings_backup_now)) },
        supportingContent = { Text(stringResource(R.string.settings_backup_now_body)) },
    )
    ListItem(
        modifier = Modifier.clickable { navigator.open(PreviousVersionsRoute) }.testTag("settings.versions"),
        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.versions_title)) },
        supportingContent = { Text(stringResource(R.string.settings_versions_body)) },
    )
    val status = last?.let {
        stringResource(
            R.string.settings_last_backup,
            DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
    ListItem(
        modifier = Modifier.toggleable(weekly, role = Role.Switch) { viewModel.backups.setWeeklyEnabled(it) }.testTag("settings.weekly_backup"),
        leadingContent = { Icon(Icons.Outlined.EventRepeat, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_weekly_backup)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_weekly_backup_body))
                if (status != null) Text(status)
            }
        },
        trailingContent = { Switch(checked = weekly, onCheckedChange = null) },
    )
    if (viewModel.backups.reminderDue(cellar)) {
        ListItem(
            modifier = Modifier.testTag("settings.backup_reminder"),
            leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
            headlineContent = { Text(stringResource(R.string.settings_backup_reminder)) },
        )
    }
}

/** The earlier versions of the cellar kept on this device, newest first. */
@Composable
fun PreviousVersionsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val last by viewModel.backups.lastBackup.collectAsStateWithLifecycle()
    val rows by produceState<List<Pair<CellarVersion, Pair<Int, Int>>>?>(null, last) {
        val versions = viewModel.backups.versions
        value = withContext(Dispatchers.IO) {
            versions.versions().map { version ->
                val cellar = runCatching { versions.cellar(version) }.getOrNull()
                version to ((cellar?.bottles?.values?.count { it.status == Bottle.Status.InCellar } ?: 0) to (cellar?.wines?.size ?: 0))
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.versions_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize()) { CircularProgressIndicator(Modifier.padding(Dimens.xl)) }
            list.isEmpty() -> Column(Modifier.padding(padding).padding(Dimens.l).readableWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.versions_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.versions_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().readableWidth().testTag("versions.list")) {
                items(list, key = { it.first.id }) { (version, counts) ->
                    val (bottles, wines) = counts
                    ListItem(
                        modifier = Modifier.clickable { viewModel.openVersion(version) { navigator.open(RestoreRoute) } }.testTag("versions.row"),
                        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                        headlineContent = { Text(formatDate(version.date)) },
                        supportingContent = {
                            Column {
                                Text(
                                    pluralStringResource(R.plurals.bottle_count, bottles, bottles) + " · " +
                                        pluralStringResource(R.plurals.wine_count, wines, wines),
                                )
                                if (version.isBeforeReplacing) Text(stringResource(R.string.versions_before_replacing))
                            }
                        },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.versions_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.m),
                    )
                }
            }
        }
    }
}

/** What a backup or an earlier version holds and how it differs from the cellar, with the two ways to restore it. */
@Composable
fun RestorePreviewScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val pending by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val shared by viewModel.sharedWithDevices.collectAsStateWithLifecycle()
    var confirmReplace by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val source = pending
    LaunchedEffect(source == null, working) { if (source == null && !working) navigator.back() }
    // Leaving without restoring: the files read from the backup go (then the effect above goes
    // back). Not while restoring: the files are being copied.
    BackHandler { if (!working) viewModel.discardRestore() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (source?.kind == RestoreSource.Kind.Version) R.string.restore_title_version else R.string.restore_title_backup))
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.discardRestore() }, enabled = !working) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        if (source == null) return@Scaffold
        val preview = remember(source, cellar) { RestorePreview.of(source.cellar, source.date, cellar) }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().testTag("restore.preview")) {
            ListItem(
                headlineContent = {
                    Text(stringResource(if (source.kind == RestoreSource.Kind.Version) R.string.restore_saved_on else R.string.restore_made_on))
                },
                supportingContent = { Text(formatDate(source.date)) },
            )
            SectionHeader(stringResource(R.string.restore_in_backup))
            Count(R.string.restore_wines, preview.wines)
            Count(R.string.restore_bottles, preview.bottles)
            Count(R.string.restore_racks, preview.racks)
            Count(R.string.restore_journal, preview.journalEntries)
            Count(R.string.restore_photos, preview.photos)
            Count(R.string.restore_documents, preview.documents)
            SectionHeader(stringResource(R.string.restore_compared))
            if (preview.isSame) {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.restore_same)) },
                )
            } else {
                Count(R.string.restore_added, preview.added)
                Count(R.string.restore_removed, preview.removed)
                Count(R.string.restore_changed, preview.changed)
            }
            Note(stringResource(R.string.restore_compared_footer))
            if (source.kind == RestoreSource.Kind.Json && preview.photos + preview.documents > 0) {
                Note(stringResource(R.string.restore_json_note))
            } else if (source.kind == RestoreSource.Kind.Archive) {
                Count(R.string.restore_files_included, source.files.size)
            }
            HorizontalDivider(Modifier.padding(vertical = Dimens.s))
            Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                OutlinedButton(
                    onClick = {
                        working = true
                        viewModel.restore(RestoreMode.AddMissing) { done -> if (done) navigator.back(to = SettingsRoute) else working = false }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("restore.add"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.restore_add), Modifier.padding(start = Dimens.s))
                }
                Button(
                    onClick = { confirmReplace = true },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("restore.replace"),
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Text(stringResource(R.string.restore_replace), Modifier.padding(start = Dimens.s))
                }
            }
            Note(stringResource(R.string.restore_footer_settings))
        }
    }
    if (confirmReplace) {
        ConfirmDialog(
            title = stringResource(R.string.restore_replace_title),
            body = stringResource(if (shared) R.string.restore_replace_body_devices else R.string.restore_replace_body),
            confirm = stringResource(R.string.restore_replace),
            onConfirm = {
                working = true
                viewModel.restore(RestoreMode.Replace) { done -> if (done) navigator.back(to = SettingsRoute) else working = false }
            },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun Count(label: Int, value: Int) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        trailingContent = { Text(value.toString(), style = MaterialTheme.typography.bodyLarge) },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
    )
}

/** The Settings entry for a cellar file that could not be read: its newest previous version can be restored. */
@Composable
fun RestoreLatestVersionItem(viewModel: AppViewModel, navigator: AppNavigator) {
    val available by produceState(false) {
        value = withContext(Dispatchers.IO) { viewModel.backups.versions.versions().isNotEmpty() }
    }
    if (!available) return
    ListItem(
        modifier = Modifier.clickable { viewModel.openLatestVersion { navigator.open(RestoreRoute) } }.testTag("settings.restore_latest"),
        leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_restore_latest)) },
        supportingContent = { Text(stringResource(R.string.settings_restore_latest_body)) },
    )
}
