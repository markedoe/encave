package app.encave.ui.devices

import app.encave.core.containsSample
import app.encave.core.isOnlySample
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.sync.PairingCode
import app.encave.sync.PairingSession
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.ActionButtonPair
import app.encave.ui.components.ActionContent
import app.encave.ui.components.ConfirmDialog
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Holds one pairing attempt across configuration changes; cancels it when the screen goes away. */
class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val sync = (application as EncaveApplication).sync
    private val current = MutableStateFlow<PairingSession?>(null)
    val session = current.asStateFlow()

    fun invite() {
        current.value?.cancel()
        current.value = null
        viewModelScope.launch { current.value = PairingSession.invite(sync, viewModelScope) }
    }

    fun join(code: PairingCode, commitment: ByteArray?, replace: Boolean) {
        current.value?.cancel()
        current.value = PairingSession.join(code, commitment, replace, sync, viewModelScope)
    }

    fun reset() {
        current.value?.cancel()
        current.value = null
    }

    override fun onCleared() {
        current.value?.cancel()
    }
}

@Composable
private fun PairingScaffold(title: Int, finished: Boolean, navigator: AppNavigator, content: @Composable () -> Unit) {
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(app.encave.ui.components.LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }, modifier = Modifier.testTag("sheet.cancel")) {
                        Icon(
                            if (finished) Icons.Outlined.Check else Icons.Filled.Close,
                            contentDescription = stringResource(if (finished) R.string.action_done else R.string.action_cancel),
                        )
                    }
                },
            )
        },
    ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
}

/** Shows a code and a QR code, then the confirmation step. */
@Composable
fun InviteDeviceScreen(navigator: AppNavigator) {
    val model: PairingViewModel = viewModel()
    val session by model.session.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (model.session.value == null) model.invite() }
    val phase = session?.phase?.collectAsStateWithLifecycle()?.value
    PairingScaffold(R.string.invite_title, phase.isFinished, navigator) {
        val current = session
        if (current != null && phase != null) {
            PairingProgress(current, phase, retry = { model.invite() }) { CodeCard(current) }
        }
    }
}

/** The code to type and the QR code to scan on the other device. */
@Composable
private fun CodeCard(session: PairingSession) {
    val digits = session.code.digits
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        Text(
            stringResource(R.string.invite_instructions),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        QrCodeImage(session.qrPayload, stringResource(R.string.invite_qr), Modifier.size(240.dp).testTag("devices.qr"))
        Text(
            digits.take(3) + " " + digits.takeLast(3),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            modifier = Modifier.testTag("devices.code").clearAndSetSemantics { contentDescription = digits.toList().joinToString(" ") },
        )
        Text(stringResource(R.string.invite_valid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            // A still icon, not a spinner: the screen stays idle (and screenshots can be taken).
            Icon(Icons.Outlined.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.invite_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val PairingSession.Phase?.isFinished: Boolean
    get() = this is PairingSession.Phase.Done || this is PairingSession.Phase.AddedNotSynced

/**
 * Types or scans the code shown on the other device. [linkCode] and [linkCommitment] come from
 * a pairing link (the QR code scanned with the camera app): filled in, the user taps Join.
 */
@Composable
fun JoinDeviceScreen(viewModel: AppViewModel, navigator: AppNavigator, linkCode: String? = null, linkCommitment: String? = null) {
    val model: PairingViewModel = viewModel()
    val session by model.session.collectAsStateWithLifecycle()
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val sync = syncManager()
    val status by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val hasBottles = cellar.bottles.isNotEmpty()
    var digits by rememberSaveable { mutableStateOf(linkCode?.filter { it in '0'..'9' }?.take(6).orEmpty()) }
    var commitment by rememberSaveable { mutableStateOf(linkCommitment) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var confirmingLeave by rememberSaveable { mutableStateOf(false) }
    var confirmingReplace by rememberSaveable { mutableStateOf(false) }
    val others = status.membership?.active.orEmpty().filter { it.id != sync.identity.id }
    val code = PairingCode(digits)
    val begin = rememberLocalNetworkAccess {
        // Read when it runs, not when composed: a scanned code is set just before.
        val typed = PairingCode(digits)
        // Only the sample cellar here: the other device's cellar replaces it, never joins it.
        val replacing = replace || viewModel.cellar.value.isOnlySample
        if (typed.isValid) model.join(typed, commitment?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }, replacing)
    }
    // Replacing bottles of the user's own asks first; the sample is replaced without asking.
    val checkReplace: () -> Unit = {
        if (replace && hasBottles) {
            confirmingReplace = true
        } else {
            begin()
        }
    }
    // Already sharing a cellar: joining another one leaves it first, once confirmed.
    val start: () -> Unit = {
        if (PairingCode(digits).isValid) {
            if (others.isNotEmpty()) {
                confirmingLeave = true
            } else {
                checkReplace()
            }
        }
    }
    val phase = session?.phase?.collectAsStateWithLifecycle()?.value

    if (confirmingLeave) {
        val names = android.icu.text.ListFormatter.getInstance().format(others.map { it.name })
        ConfirmDialog(
            title = stringResource(R.string.join_leave_title),
            body = stringResource(R.string.join_leave_body, names),
            confirm = stringResource(R.string.join_leave_confirm),
            onConfirm = {
                scope.launch {
                    sync.leave()
                    checkReplace()
                }
            },
            onDismiss = { confirmingLeave = false },
        )
    }
    if (confirmingReplace) {
        val count = cellar.bottles.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.join_replace_confirm_title, count, count),
            body = stringResource(R.string.join_replace_confirm_body),
            confirm = stringResource(R.string.join_replace_confirm),
            onConfirm = { begin() },
            onDismiss = { confirmingReplace = false },
        )
    }

    if (scanning) {
        QrScanner(
            onScan = { payload ->
                PairingCode.parse(payload)?.let { (parsed, scanned) ->
                    scanning = false
                    digits = parsed.digits
                    commitment = scanned?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    start()
                }
            },
            onCancel = { scanning = false },
        )
        return
    }

    PairingScaffold(R.string.join_title, phase.isFinished, navigator) {
        val current = session
        if (current != null && phase != null) {
            PairingProgress(current, phase, retry = {
                model.reset()
                digits = ""
                commitment = null
            }) {
                Column(
                    Modifier.fillMaxSize().padding(Dimens.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
                    Text(
                        stringResource(R.string.join_looking, current.code.digits),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Dimens.l),
                    )
                }
            }
            return@PairingScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Dimens.m)) {
            ListItem(
                modifier = Modifier.testTag("devices.scan").selectable(selected = false, role = Role.Button) { scanning = true },
                leadingContent = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(stringResource(R.string.join_scan), color = MaterialTheme.colorScheme.primary) },
            )
            OutlinedTextField(
                value = digits.let { if (it.length > 3) it.take(3) + " " + it.drop(3) else it },
                onValueChange = { text ->
                    val clean = text.filter { it in '0'..'9' }.take(6)
                    if (clean != digits) commitment = null
                    digits = clean
                },
                label = { Text(stringResource(R.string.join_code)) },
                placeholder = { Text("123 456") },
                supportingText = { Text(stringResource(R.string.join_code_footer)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, fontFeatureSettings = "tnum"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.l).testTag("devices.codeField"),
            )
            if (cellar.isOnlySample) {
                Text(
                    stringResource(R.string.join_replaces_sample),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, top = Dimens.l),
                )
            } else if (hasBottles) {
                Text(
                    stringResource(R.string.join_has_bottles),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Dimens.l, end = Dimens.l, top = Dimens.l).semantics { heading() },
                )
                Column(Modifier.selectableGroup()) {
                    listOf(false to R.string.join_combine, true to R.string.join_replace).forEach { (value, label) ->
                        ListItem(
                            modifier = Modifier.selectable(selected = replace == value, role = Role.RadioButton) { replace = value },
                            leadingContent = { RadioButton(selected = replace == value, onClick = null) },
                            headlineContent = { Text(stringResource(label)) },
                        )
                    }
                }
                Text(
                    stringResource(
                        when {
                            replace -> R.string.join_replace_footer
                            cellar.containsSample -> R.string.join_combine_sample_footer
                            else -> R.string.join_combine_footer
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.l),
                )
            }
            Button(
                onClick = start,
                enabled = code.isValid,
                modifier = Modifier.fillMaxWidth().padding(Dimens.l).testTag("action.join"),
            ) { ActionContent(stringResource(R.string.join_button)) }
        }
    }
}

/** The steps after the code: compare, wait, sync, done. */
@Composable
private fun PairingProgress(session: PairingSession, phase: PairingSession.Phase, retry: () -> Unit, waiting: @Composable () -> Unit) {
    when (phase) {
        PairingSession.Phase.Waiting -> waiting()
        is PairingSession.Phase.Confirm -> ConfirmCode(phase.code, phase.device) { session.answer(it) }
        PairingSession.Phase.WaitingForOther -> StatusView(
            Icons.Outlined.HourglassEmpty, stringResource(R.string.pair_waiting_other), stringResource(R.string.pair_waiting_other_body), true,
        )
        PairingSession.Phase.Syncing -> StatusView(
            Icons.Outlined.Sync, stringResource(R.string.pair_syncing), stringResource(R.string.pair_syncing_body), true,
        )
        is PairingSession.Phase.Done -> {
            val remote = syncManager().state.collectAsStateWithLifecycle().value.remoteStorage != null
            StatusView(
                Icons.Outlined.CheckCircle, stringResource(R.string.pair_done, phase.device),
                stringResource(if (remote) R.string.pair_done_body_remote else R.string.pair_done_body), false,
                Modifier.testTag("devices.done"),
            )
        }
        is PairingSession.Phase.AddedNotSynced -> {
            val sync = syncManager()
            StatusView(
                Icons.Outlined.CheckCircle, stringResource(R.string.pair_done, phase.device), stringResource(R.string.pair_not_synced_body), false,
                Modifier.testTag("devices.added_not_synced"),
            ) {
                Button(onClick = { sync.syncNow() }, modifier = Modifier.testTag("action.pair_sync_now")) {
                    ActionContent(stringResource(R.string.devices_sync_now), Icons.Outlined.Sync)
                }
            }
        }
        is PairingSession.Phase.Failed -> StatusView(
            Icons.Outlined.ErrorOutline, stringResource(R.string.pair_failed), stringResource(phase.reason.text), false,
        ) {
            if (phase.reason == PairingSession.Failure.LocalNetwork) {
                val context = LocalContext.current
                OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.testTag("action.open_settings")) {
                    ActionContent(stringResource(R.string.action_open_settings))
                }
            }
            Button(onClick = retry, modifier = Modifier.testTag("action.pair_retry")) { ActionContent(stringResource(R.string.pair_try_again)) }
        }
    }
}

private val PairingSession.Failure.text: Int
    get() = when (this) {
        PairingSession.Failure.Expired -> R.string.pair_failed_expired
        PairingSession.Failure.NotFound -> R.string.pair_failed_not_found
        PairingSession.Failure.Rejected -> R.string.pair_failed_rejected
        PairingSession.Failure.NotVerified -> R.string.pair_failed_not_verified
        PairingSession.Failure.OtherVersion -> R.string.pair_failed_version
        PairingSession.Failure.ConnectionLost -> R.string.pair_failed_connection
        PairingSession.Failure.CannotListen -> R.string.pair_failed_listen
        PairingSession.Failure.LocalNetwork -> R.string.sync_problem_local_network
    }

/** "Does the other device show this code?" */
@Composable
private fun ConfirmCode(code: String, device: String, answer: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.l, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.pair_confirm_title, device),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            code,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            modifier = Modifier.testTag("devices.confirmationCode")
                .clearAndSetSemantics { contentDescription = code.filter { it.isDigit() }.toList().joinToString(" ") },
        )
        Text(
            stringResource(R.string.pair_confirm_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        ActionButtonPair(
            Modifier.widthIn(max = 460.dp), spacing = Dimens.m,
            first = { width ->
                OutlinedButton(onClick = { answer(false) }, modifier = width.testTag("action.pair_reject")) {
                    ActionContent(stringResource(R.string.pair_no), Icons.Outlined.Close)
                }
            },
            second = { width ->
                Button(onClick = { answer(true) }, modifier = width.testTag("action.pair_accept")) {
                    ActionContent(stringResource(R.string.pair_match), Icons.Outlined.Check)
                }
            },
        )
    }
}

@Composable
private fun StatusView(
    icon: ImageVector,
    title: String,
    message: String,
    working: Boolean,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Dimens.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.m, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 460.dp))
        if (working) LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
        action()
    }
}

/** Full-screen camera that reads one Encave QR code. */
@Composable
private fun QrScanner(onScan: (String) -> Unit, onCancel: () -> Unit) {
    // Back closes the camera, not the screen.
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CameraQrReader(onScan, Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(Dimens.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            Text(
                stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                    .padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)).testTag("sheet.cancel"),
            ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
        }
    }
}
