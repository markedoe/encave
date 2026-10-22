package app.encave.ui.settings

import app.encave.ui.ProjectLinks
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Description
import androidx.compose.ui.platform.testTag
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.MoveToInbox
import app.encave.reminders.Reminders
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.BuildConfig
import app.encave.R
import app.encave.ui.components.readableWidth
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.components.LocalSnackbarHostState
import app.encave.ui.components.NotificationSwitch
import app.encave.ui.components.SectionHeader
import app.encave.ui.navigation.HelpRoute
import app.encave.ui.navigation.DevicesRoute
import androidx.compose.material.icons.outlined.Devices
import app.encave.ui.components.commonCurrencies
import app.encave.ui.components.sampleLabels
import java.time.LocalDate
import androidx.compose.material.icons.outlined.AutoAwesome
import app.encave.ui.theme.Dimens

@Composable
fun SettingsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.preferences.dynamicColor.collectAsStateWithLifecycle()
    val studioLook by viewModel.preferences.studioLook.collectAsStateWithLifecycle()
    var currencyMenu by remember { mutableStateOf(false) }
    var confirmSample by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val resolver = context.contentResolver
    val today = LocalDate.now()

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportCsv(resolver, it) }
    }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { viewModel.exportPdf(resolver, it, resources) }
    }
    // A backup (archive or JSON) is shown first, to add what is missing or replace the cellar.
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(resolver, it) { navigator.open(app.encave.ui.navigation.RestoreRoute) } }
    }
    val shared by viewModel.sharedWithDevices.collectAsStateWithLifecycle()
    val readingBackup by viewModel.readingBackup.collectAsStateWithLifecycle()
    // A cellar file that could not be read at launch, kept aside: it can be saved elsewhere.
    var keptChanged by remember { mutableStateOf(0) }
    val keptAside = remember(cellar, keptChanged) { viewModel.keptAside().lastOrNull() }
    var confirmDeleteKept by remember { mutableStateOf(false) }
    val saveKept = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && keptAside != null) viewModel.exportKeptAside(resolver, uri, keptAside)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) {
            ListItem(
                modifier = Modifier.testTag("settings.help").clickable { navigator.open(HelpRoute()) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.help_title)) },
                supportingContent = { Text(stringResource(R.string.help_encave)) },
            )

            SectionHeader(stringResource(R.string.settings_devices))
            ListItem(
                modifier = Modifier.testTag("settings.devices").clickable { navigator.open(DevicesRoute) },
                leadingContent = { Icon(Icons.Outlined.Devices, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.devices_title)) },
                supportingContent = {
                    // Sync that needs the user says so here, in words and with a symbol.
                    val health = app.encave.ui.devices.rememberSyncHealth()
                    if (health == app.encave.core.sync.SyncHealth.Ok) {
                        Text(stringResource(R.string.settings_devices_summary))
                    } else {
                        app.encave.ui.devices.SyncHealthLabel(health, Modifier.testTag("settings.devices.health"))
                    }
                },
            )

            RemindersSection()

            SectionHeader(stringResource(R.string.settings_cellar))
            ListItem(
                modifier = Modifier.clickable { currencyMenu = true },
                leadingContent = { Icon(Icons.Outlined.Payments, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_currency)) },
                supportingContent = { Text(currencyName(cellar.settings.currency)) },
                trailingContent = {
                    DropdownMenu(expanded = currencyMenu, onDismissRequest = { currencyMenu = false }) {
                        (listOf(cellar.settings.currency) + commonCurrencies).distinct().forEach { code ->
                            DropdownMenuItem(text = { Text(currencyName(code)) }, onClick = { viewModel.setCurrency(code); currencyMenu = false })
                        }
                    }
                },
            )

            SectionHeader(stringResource(R.string.settings_appearance))
            ListItem(
                modifier = Modifier.toggleable(dynamicColor, role = Role.Switch) { viewModel.setDynamicColor(it) },
                leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                supportingContent = { Text(stringResource(R.string.settings_dynamic_color_body)) },
                trailingContent = { Switch(checked = dynamicColor, onCheckedChange = null) },
            )
            ListItem(
                modifier = Modifier.toggleable(studioLook, role = Role.Switch) { viewModel.setStudioLook(it) },
                leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_studio_look)) },
                supportingContent = { Text(stringResource(R.string.settings_studio_look_body)) },
                trailingContent = { Switch(checked = studioLook, onCheckedChange = null) },
            )

            AiSettingsSection(viewModel)

            WebSearchSettingsSection()

            SectionHeader(stringResource(R.string.settings_privacy))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = {
                    val sync by (context.applicationContext as app.encave.EncaveApplication).sync.state.collectAsStateWithLifecycle()
                    val shared = (sync.membership?.members?.size ?: 0) > 1
                    Text(
                        stringResource(
                            when {
                                sync.remoteStorage != null -> R.string.settings_privacy_stored_remote
                                shared -> R.string.settings_privacy_stored_devices
                                else -> R.string.settings_privacy_stored
                            },
                        ),
                    )
                },
                supportingContent = { Text(stringResource(R.string.settings_privacy_body)) },
            )

            SectionHeader(stringResource(R.string.settings_data))
            BackupSettingsItems(viewModel, navigator)
            ListItem(
                modifier = Modifier.clickable { exportCsv.launch("encave-$today.csv") },
                leadingContent = { Icon(Icons.Outlined.TableChart, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_export_csv)) },
                supportingContent = { Text(stringResource(R.string.settings_export_csv_body)) },
            )
            ListItem(
                modifier = Modifier.clickable { exportPdf.launch("encave-inventory-$today.pdf") }.testTag("settings.pdf"),
                leadingContent = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_export_pdf)) },
                supportingContent = { Text(stringResource(R.string.settings_export_pdf_body)) },
            )
            ListItem(
                modifier = Modifier.clickable(enabled = !readingBackup) {
                    import.launch(
                        arrayOf(
                            "application/zip", "application/x-zip-compressed", "application/json", "text/csv",
                            "text/comma-separated-values", "text/plain", "application/octet-stream",
                        ),
                    )
                }.testTag("settings.import"),
                leadingContent = {
                    if (readingBackup) {
                        CircularProgressIndicator(Modifier.padding(Dimens.xs))
                    } else {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null)
                    }
                },
                headlineContent = { Text(stringResource(if (readingBackup) R.string.settings_reading_backup else R.string.settings_import)) },
                supportingContent = { Text(stringResource(R.string.settings_import_body)) },
            )
            ListItem(
                modifier = Modifier.clickable {
                    import.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
                }.testTag("settings.import_other"),
                leadingContent = { Icon(Icons.Outlined.MoveToInbox, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_import_other)) },
                supportingContent = { Text(stringResource(R.string.settings_import_other_body)) },
            )

            if (keptAside != null) {
                RestoreLatestVersionItem(viewModel, navigator)
                ListItem(
                    modifier = Modifier.clickable { saveKept.launch(keptAside.name) }.testTag("settings.save_unreadable"),
                    leadingContent = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_save_unreadable)) },
                    supportingContent = { Text(stringResource(R.string.settings_save_unreadable_body, keptAside.name)) },
                )
                ListItem(
                    modifier = Modifier.clickable { confirmDeleteKept = true },
                    leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(stringResource(R.string.settings_delete_unreadable), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.settings_delete_unreadable_body)) },
                )
            }

            // Only on an empty cellar that no other device shares, in beta builds: the sample never replaces bottles.
            if (viewModel.canLoadSample(cellar, shared)) {
                SectionHeader(stringResource(R.string.settings_try))
                ListItem(
                    modifier = Modifier.clickable { confirmSample = true },
                    leadingContent = { Icon(Icons.Outlined.Science, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.action_load_sample)) },
                    supportingContent = { Text(stringResource(R.string.settings_sample_body)) },
                )
            }

            SectionHeader(stringResource(R.string.settings_reset))
            val canErase = !cellar.isEmpty
            ListItem(
                modifier = Modifier.clickable(enabled = canErase) { confirmErase = true }.testTag("settings.erase"),
                leadingContent = {
                    Icon(
                        Icons.Outlined.DeleteForever, contentDescription = null,
                        tint = if (canErase) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                },
                headlineContent = {
                    Text(
                        stringResource(R.string.settings_erase),
                        color = if (canErase) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                },
            )

            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = {
                    val number = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    Text(if (BuildConfig.BETA) number + " · " + stringResource(R.string.settings_beta) else number)
                },
            )
            ListItem(
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ProjectLinks.PRIVACY_POLICY.toUri())) }
                },
                leadingContent = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_privacy_policy)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ProjectLinks.SOURCE.toUri())) }
                },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_source)) },
                supportingContent = { Text(stringResource(R.string.settings_source_body)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            )
            ListItem(
                modifier = Modifier.testTag("settings.licences").clickable { navigator.open(app.encave.ui.navigation.LicencesRoute) },
                leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.settings_licences)) },
            )
            if (BuildConfig.BETA) {
                Text(
                    stringResource(R.string.settings_beta_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
                )
            }
            HorizontalDivider()
        }
    }

    if (confirmSample) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sample_confirm_title),
            body = stringResource(R.string.settings_sample_confirm_body),
            confirm = stringResource(R.string.action_load_sample),
            onConfirm = { viewModel.loadSample(sampleLabels(resources)) },
            onDismiss = { confirmSample = false },
            destructive = false,
        )
    }
    if (confirmDeleteKept && keptAside != null) {
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_unreadable_title),
            body = stringResource(R.string.settings_delete_unreadable_confirm, keptAside.name),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.deleteKeptAside(keptAside) { keptChanged++ } },
            onDismiss = { confirmDeleteKept = false },
        )
    }
    if (confirmErase) {
        ConfirmDialog(
            title = stringResource(R.string.settings_erase_title),
            body = stringResource(if (shared) R.string.settings_erase_body_devices else R.string.settings_erase_body),
            confirm = stringResource(R.string.settings_erase),
            onConfirm = { viewModel.eraseAll() },
            onDismiss = { confirmErase = false },
        )
    }
}

/** Monthly and delivery reminders, worked out on the device. Asks for the notification permission when turned on. */
@Composable
private fun RemindersSection() {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.reminders_title))
    NotificationSwitch(
        title = stringResource(R.string.reminders_title),
        footer = stringResource(R.string.reminders_footer),
        icon = Icons.Outlined.NotificationsActive,
        tag = "settings.reminders",
        isOn = { Reminders.isEnabled(context) },
        onChange = { Reminders.setEnabled(context, it) },
    )
}

/** "Euro (EUR)", in the language of the device. */
private fun currencyName(code: String): String =
    runCatching { java.util.Currency.getInstance(code).getDisplayName(java.util.Locale.getDefault()) }
        .getOrNull()?.takeIf { it.isNotBlank() && it != code }?.let { "$it ($code)" } ?: code
