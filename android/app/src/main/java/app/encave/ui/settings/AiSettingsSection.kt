package app.encave.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.data.ai.AiConnection
import app.encave.data.ai.AiError
import app.encave.data.ai.AiModelChoice
import app.encave.data.ai.AiProvider
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.readableWidth
import app.encave.ui.components.resolve
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.navigation.AiConnectionRoute
import app.encave.ui.navigation.AiConnectionsRoute
import app.encave.ui.navigation.AiModelsRoute
import app.encave.ui.theme.Dimens
import app.encave.ui.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AiProvider.title(): String = when (this) {
    AiProvider.Off -> stringResource(R.string.ai_off)
    AiProvider.Local -> stringResource(R.string.ai_local)
    else -> brand.orEmpty()
}

/**
 * Settings › AI: the connections (provider, key encrypted with the Keystore, address)
 * and the models that read label photos, in order.
 */
@Composable
fun AiSettingsSection(viewModel: AppViewModel, navigator: AppNavigator) {
    val configuration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_ai))
    ListItem(
        modifier = Modifier.testTag("settings.ai.connections").clickable { navigator.open(AiConnectionsRoute) },
        leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_ai_connections)) },
        trailingContent = { Text(configuration.connections.size.toString()) },
    )
    ListItem(
        modifier = Modifier.testTag("settings.ai.models").clickable { navigator.open(AiModelsRoute) },
        leadingContent = { Icon(Icons.Outlined.Memory, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_ai_models)) },
        trailingContent = { Text(configuration.usableModels.size.toString()) },
    )
    Text(
        stringResource(if (configuration.isEnabled) R.string.settings_ai_on_body else R.string.settings_ai_off_body),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SettingsFieldInset, end = Dimens.l),
    )
    HelpLink(HelpTopic.Ai, R.string.help_link_ai)
}

@Composable
private fun AiScaffold(title: String, navigator: AppNavigator, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) { content() }
    }
}

/** Settings › AI › Connections: each with its provider, name, address and key. */
@Composable
fun AiConnectionsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val configuration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    AiScaffold(
        stringResource(R.string.settings_ai_connections), navigator,
        actions = {
            IconButton(onClick = { navigator.open(AiConnectionRoute(null)) }, modifier = Modifier.testTag("ai.connections.add")) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.ai_connection_add))
            }
        },
    ) {
        if (configuration.connections.isEmpty()) {
            Text(stringResource(R.string.ai_connections_empty), Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val resources = LocalResources.current
        configuration.connections.forEach { connection ->
            val count = configuration.modelsOf(connection.id).size
            val models = pluralStringResource(R.plurals.ai_models_count, count, count)
            val title = connection.title(resources)
            ListItem(
                modifier = Modifier.clickable { navigator.open(AiConnectionRoute(connection.id)) },
                headlineContent = { Text(title) },
                supportingContent = {
                    Text(if (title == connection.provider.title()) models else "${connection.provider.title()} · $models")
                },
            )
        }
        Text(
            stringResource(R.string.ai_connections_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l),
        )
    }
}

private sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val count: Int) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/**
 * A connection's provider, name, address and key, the key checked live. A new one
 * ([connectionId] null) is added with Add; an existing one is saved as it changes.
 */
@Composable
fun AiConnectionScreen(viewModel: AppViewModel, navigator: AppNavigator, connectionId: String?) {
    val configuration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    val existing = connectionId?.let { configuration.connection(it) }
    val isNew = connectionId == null
    var draft by remember(connectionId) { mutableStateOf(existing ?: AiConnection(AiProvider.OpenRouter)) }
    // Deleted meanwhile (another window): nothing to edit.
    if (!isNew && existing == null) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var apiKey by remember(connectionId) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var check by remember(connectionId) { mutableStateOf<KeyCheck>(KeyCheck.Idle) }
    var confirmingDelete by remember { mutableStateOf(false) }
    if (!isNew) LaunchedEffect(connectionId) { apiKey = withContext(Dispatchers.IO) { viewModel.ai.apiKey(draft).orEmpty() } }

    fun change(connection: AiConnection) {
        if (connection.provider != draft.provider || connection.baseUrl != draft.baseUrl) check = KeyCheck.Idle
        draft = connection
        if (!isNew) viewModel.ai.update { it.upserting(connection) }
    }

    fun checkKey() {
        check = KeyCheck.Checking
        val checked = draft
        val key = AiPreferencesKey.clean(apiKey)
        scope.launch {
            viewModel.loadModels(checked, key)
                .onSuccess { list ->
                    if (checked.provider != draft.provider || checked.baseUrl != draft.baseUrl) return@onSuccess
                    AiLoadedModels.lists[checked.id] = list
                    check = KeyCheck.Valid(list.size)
                }
                .onFailure { error ->
                    val message = (error as? AiError.Server)?.message?.takeIf { it.isNotBlank() } ?: viewModel.aiErrorText(error).resolve(resources)
                    check = KeyCheck.Failed(message)
                }
        }
    }

    val provider = draft.provider
    AiScaffold(
        if (isNew) stringResource(R.string.ai_connection_new) else draft.title(resources), navigator,
        actions = {
            if (isNew) {
                TextButton(
                    onClick = {
                        viewModel.ai.update { it.upserting(draft) }
                        viewModel.ai.saveApiKey(draft, AiPreferencesKey.clean(apiKey))
                        navigator.back()
                    },
                    enabled = !provider.requiresKey || AiPreferencesKey.clean(apiKey).isNotEmpty(),
                    modifier = Modifier.testTag("ai.connection.save"),
                ) { Text(stringResource(R.string.action_add)) }
            }
        },
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            ProviderField(provider) { change(draft.withProvider(it)) }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { change(draft.copy(name = it)) },
                label = { Text(stringResource(R.string.ai_connection_name)) },
                placeholder = { Text(provider.title()) },
                supportingText = { Text(stringResource(R.string.help_field_ai_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { change(draft.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.ai_server_url)) },
                placeholder = { Text(provider.defaultBaseUrl) },
                supportingText = { Text(stringResource(R.string.help_field_ai_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { key ->
                    apiKey = key
                    if (check != KeyCheck.Checking) check = KeyCheck.Idle
                    if (!isNew) viewModel.ai.saveApiKey(draft, key)
                },
                label = { Text(stringResource(if (provider.requiresKey) R.string.ai_key else R.string.ai_key_optional)) },
                supportingText = { Text(stringResource(R.string.help_field_ai_key)) },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (showKey) R.string.ai_hide_key else R.string.ai_show_key),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { checkKey() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OutlinedButton(
                    onClick = ::checkKey,
                    enabled = check != KeyCheck.Checking && (!provider.requiresKey || apiKey.isNotEmpty()),
                ) {
                    Icon(if (provider.isLocal) Icons.Outlined.Lan else Icons.Outlined.VerifiedUser, contentDescription = null)
                    Text(stringResource(if (provider.isLocal) R.string.ai_connect else R.string.ai_check), Modifier.padding(start = Dimens.s))
                }
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    when (val state = check) {
                        KeyCheck.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        is KeyCheck.Valid -> {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(pluralStringResource(R.plurals.ai_models_count, state.count, state.count), Modifier.padding(start = Dimens.xs))
                        }
                        is KeyCheck.Failed -> {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.ai_not_valid), Modifier.padding(start = Dimens.xs), color = MaterialTheme.colorScheme.error)
                        }
                        KeyCheck.Idle -> Unit
                    }
                }
            }
            (check as? KeyCheck.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            provider.keyPage?.let { page ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri())) } }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                    Text(keyLinkLabel(provider), Modifier.padding(start = Dimens.s))
                }
            }
            val tips = when (provider) {
                AiProvider.Ollama -> listOf(R.string.ai_tip_ollama, R.string.ai_local_sent)
                AiProvider.Vllm -> listOf(R.string.ai_tip_vllm, R.string.ai_local_sent)
                AiProvider.LmStudio -> listOf(R.string.ai_tip_lmstudio, R.string.ai_local_sent)
                AiProvider.Local -> listOf(R.string.ai_tip_other, R.string.ai_local_sent)
                else -> emptyList()
            }
            tips.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (provider == AiProvider.OpenRouter) {
            HorizontalDivider()
            ListItem(
                modifier = Modifier.testTag("ai.connection.data_collection")
                    .clickable { change(draft.copy(allowsDataCollection = !draft.allowsDataCollection)) },
                headlineContent = { Text(stringResource(R.string.ai_allow_data)) },
                supportingContent = { Text(stringResource(R.string.ai_allow_data_body)) },
                trailingContent = { Switch(checked = draft.allowsDataCollection, onCheckedChange = null) },
            )
        }
        if (!isNew) {
            HorizontalDivider()
            TextButton(
                onClick = {
                    if (configuration.modelsOf(draft.id).isEmpty()) {
                        viewModel.ai.deleteConnection(draft.id)
                        navigator.back()
                    } else {
                        confirmingDelete = true
                    }
                },
                modifier = Modifier.padding(horizontal = Dimens.s),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.ai_connection_delete), Modifier.padding(start = Dimens.s), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmingDelete) {
        val count = configuration.modelsOf(draft.id).size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.ai_connection_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.ai_connection_delete_body, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.ai.deleteConnection(draft.id)
                    navigator.back()
                }) { Text(stringResource(R.string.ai_connection_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Pasted keys are cleaned the same way everywhere. */
private object AiPreferencesKey {
    fun clean(key: String) = app.encave.data.ai.AiPreferences.cleanKey(key)
}

/** Models listed by "Check key and load models", by connection, while the app runs (to add them). */
private object AiLoadedModels {
    val lists = mutableStateMapOf<String, List<String>>()
}

private sealed interface ModelTest {
    data object Testing : ModelTest
    data object Passed : ModelTest
    data class Failed(val message: String) : ModelTest
}

/**
 * Settings › AI › Models: the models that read label photos, in order. The first one reads
 * the photo; the next only when it fails. Dragged by the handle (TalkBack: Move up, Move down).
 */
@Composable
fun AiModelsScreen(viewModel: AppViewModel, navigator: AppNavigator) {
    val configuration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(configuration)
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val tests = remember { mutableStateMapOf<String, ModelTest>() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    fun move(id: String, by: Int) {
        val from = latest.models.indexOfFirst { it.id == id }
        if (from >= 0) viewModel.ai.update { it.movingModel(from, from + by) }
    }

    fun test(model: AiModelChoice) {
        tests[model.id] = ModelTest.Testing
        scope.launch {
            tests[model.id] = viewModel.testModel(model, resources).fold(
                { ModelTest.Passed },
                { error -> ModelTest.Failed((error as? AiError.Server)?.message?.takeIf { it.isNotBlank() } ?: viewModel.aiErrorText(error).resolve(resources)) },
            )
        }
    }

    AiScaffold(
        stringResource(R.string.settings_ai_models), navigator,
        actions = {
            IconButton(
                onClick = { adding = true }, enabled = configuration.connections.isNotEmpty(),
                modifier = Modifier.testTag("ai.models.add"),
            ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.ai_model_add)) }
        },
    ) {
        if (configuration.connections.isEmpty()) {
            Text(stringResource(R.string.ai_models_no_connection), Modifier.padding(Dimens.l), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { navigator.open(AiConnectionsRoute) }, modifier = Modifier.padding(horizontal = Dimens.s)) {
                Text(stringResource(R.string.settings_ai_connections))
            }
        }
        SectionHeader(stringResource(R.string.ai_models_header))
        val moveUp = stringResource(R.string.ai_model_move_up)
        val moveDown = stringResource(R.string.ai_model_move_down)
        val delete = stringResource(R.string.ai_model_delete)
        configuration.models.forEachIndexed { index, model ->
            val isDragged = dragging == model.id
            val connection = configuration.connection(model)
            val test = tests[model.id]
            ListItem(
                modifier = Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .onSizeChanged { heights[model.id] = it.height }
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction(moveUp) { move(model.id, -1); true } else null,
                            if (index < configuration.models.lastIndex) CustomAccessibilityAction(moveDown) { move(model.id, 1); true } else null,
                            CustomAccessibilityAction(delete) { viewModel.ai.update { it.removingModel(model.id) }; true },
                        )
                    }
                    .testTag("ai.model.$index"),
                tonalElevation = if (isDragged) 4.dp else 0.dp,
                leadingContent = {
                    Icon(
                        Icons.Outlined.DragHandle, contentDescription = null,
                        modifier = Modifier.pointerInput(model.id) {
                            detectDragGestures(
                                onDragStart = { dragging = model.id; dragOffset = 0f },
                                onDragEnd = { dragging = null; dragOffset = 0f },
                                onDragCancel = { dragging = null; dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val list = latest.models
                                val at = list.indexOfFirst { it.id == model.id }
                                val next = list.getOrNull(at + 1)?.let { heights[it.id] }
                                val previous = list.getOrNull(at - 1)?.let { heights[it.id] }
                                if (next != null && dragOffset > next / 2f) {
                                    move(model.id, 1)
                                    dragOffset -= next
                                } else if (previous != null && dragOffset < -previous / 2f) {
                                    move(model.id, -1)
                                    dragOffset += previous
                                }
                            }
                        },
                    )
                },
                headlineContent = { Text(model.model) },
                supportingContent = {
                    Column {
                        Text(connection?.title(resources).orEmpty())
                        when (test) {
                            ModelTest.Passed -> Text(stringResource(R.string.ai_model_works), color = MaterialTheme.colorScheme.primary)
                            is ModelTest.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (test == ModelTest.Testing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { test(model) }) { Text(stringResource(R.string.ai_model_test)) }
                        }
                        IconButton(onClick = { viewModel.ai.update { it.removingModel(model.id) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = delete)
                        }
                    }
                },
            )
        }
        Text(
            stringResource(R.string.ai_models_footer), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.l),
        )
    }
    if (adding) AddModelDialog(viewModel) { adding = false }
}

/** Adds a model of a connection: typed, or picked from its list (loaded with its key). */
@Composable
private fun AddModelDialog(viewModel: AppViewModel, close: () -> Unit) {
    val configuration by viewModel.ai.configuration.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var connectionId by rememberSaveable { mutableStateOf(configuration.connections.firstOrNull()?.id) }
    val connection = connectionId?.let { configuration.connection(it) }
    var model by rememberSaveable(connectionId) { mutableStateOf(connection?.provider?.suggestedModel.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember(connectionId) { mutableStateOf<String?>(null) }
    val list = connectionId?.let { AiLoadedModels.lists[it] }.orEmpty()

    fun load() {
        val chosen = connection ?: return
        loading = true
        problem = null
        scope.launch {
            val key = withContext(Dispatchers.IO) { viewModel.ai.apiKey(chosen) }
            viewModel.loadModels(chosen, key)
                .onSuccess { AiLoadedModels.lists[chosen.id] = it }
                .onFailure { error ->
                    if (chosen.id == connectionId) {
                        problem = (error as? AiError.Server)?.message?.takeIf { it.isNotBlank() } ?: viewModel.aiErrorText(error).resolve(resources)
                    }
                }
            loading = false
        }
    }
    LaunchedEffect(connectionId) { if (list.isEmpty()) load() }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ai_model_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                ConnectionMenu(configuration.connections, connection) { connectionId = it.id }
                ModelField(model, list) { model = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::load, enabled = connection != null && !loading) { Text(stringResource(R.string.ai_model_load)) }
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    stringResource(if (connection?.provider == AiProvider.OpenRouter) R.string.ai_model_openrouter_vision else R.string.help_field_ai_model),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = connectionId ?: return@TextButton
                    viewModel.ai.update { it.addingModel(model, id) }
                    close()
                },
                enabled = connectionId != null && model.isNotBlank(),
                modifier = Modifier.testTag("ai.model.add.confirm"),
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConnectionMenu(connections: List<AiConnection>, selected: AiConnection?, onSelect: (AiConnection) -> Unit) {
    val resources = LocalResources.current
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.title(resources).orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.ai_model_connection)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            connections.forEach { option ->
                DropdownMenuItem(text = { Text(option.title(resources)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** The model's name, typed or picked from the list, which narrows as it is typed. */
@Composable
private fun ModelField(model: String, models: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = models.filter { model.isBlank() || model in models || it.contains(model.trim(), ignoreCase = true) }.take(100)
    ExposedDropdownMenuBox(expanded = expanded && shown.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = model,
            onValueChange = { onChange(it); expanded = true },
            singleLine = true,
            label = { Text(stringResource(R.string.ai_model)) },
            placeholder = { Text("model-name") },
            trailingIcon = { if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        ExposedDropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = { expanded = false }) {
            shown.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

/** "Create an API key at …", or how to get a local server. */
@Composable
fun keyLinkLabel(provider: AiProvider): String = when (provider) {
    AiProvider.Ollama -> stringResource(R.string.ai_install_ollama)
    AiProvider.Vllm -> stringResource(R.string.ai_vllm_start)
    AiProvider.LmStudio -> stringResource(R.string.ai_download_lmstudio)
    else -> stringResource(R.string.ai_create_key_at, provider.brand.orEmpty())
}

/** Provider menu: global services, Chinese services, OpenRouter and a local server. */
@Composable
private fun ProviderField(selected: AiProvider, onSelect: (AiProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.title(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.ai_provider)) },
            supportingText = { Text(stringResource(R.string.help_field_ai_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(AiProvider.global, AiProvider.chinese, AiProvider.others, AiProvider.localServers).forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                group.forEach { option ->
                    DropdownMenuItem(text = { Text(option.title()) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}
