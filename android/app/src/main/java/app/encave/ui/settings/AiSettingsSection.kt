package app.encave.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.data.ai.AiError
import app.encave.data.ai.AiProvider
import app.encave.ui.AppViewModel
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.resolve
import app.encave.ui.theme.Dimens
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val count: Int) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

@Composable
private fun AiProvider.title(): String = when (this) {
    AiProvider.Off -> stringResource(R.string.ai_off)
    AiProvider.Local -> stringResource(R.string.ai_local)
    else -> brand.orEmpty()
}

/**
 * Optional AI features: provider, key (checked live, one per provider, Keystore-encrypted)
 * and model (from the provider's list, or typed).
 */
@Composable
fun AiSettingsSection(viewModel: AppViewModel) {
    val settings by viewModel.ai.settings.collectAsStateWithLifecycle()
    val provider = settings.provider
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var apiKey by remember(provider) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var models by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var check by remember(provider) { mutableStateOf<KeyCheck>(KeyCheck.Idle) }
    var customModel by remember(provider) { mutableStateOf(true) }
    LaunchedEffect(provider) { apiKey = withContext(Dispatchers.IO) { viewModel.ai.apiKey(provider).orEmpty() } }

    fun checkKey() {
        check = KeyCheck.Checking
        val key = apiKey
        scope.launch {
            viewModel.loadModels(key)
                .onSuccess { list ->
                    models = list
                    check = KeyCheck.Valid(list.size)
                    val current = viewModel.ai.settings.value
                    if (current.model.isEmpty() || current.model !in list) {
                        val suggested = provider.suggestedModel
                        if (suggested in list) viewModel.ai.update(current.copy(model = suggested))
                    }
                    customModel = list.isEmpty()
                }
                .onFailure { error ->
                    val message = (error as? AiError.Server)?.message?.takeIf { it.isNotBlank() }
                        ?: viewModel.aiErrorText(error).resolve(resources)
                    check = KeyCheck.Failed(message)
                }
        }
    }

    SectionHeader(stringResource(R.string.settings_ai))
    ProviderField(provider) { viewModel.ai.select(it) }
    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        if (provider.isEnabledProvider()) {
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { viewModel.ai.update(settings.copy(baseUrl = it)) },
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
                    viewModel.ai.saveApiKey(provider, key)
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
                            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.ai_models_count, state.count, state.count), Modifier.padding(start = Dimens.xs))
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
            if (models.isNotEmpty() && !customModel) {
                ModelMenu(settings.model, models) { viewModel.ai.update(settings.copy(model = it)) }
                Text(stringResource(R.string.help_field_ai_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { customModel = true }) { Text(stringResource(R.string.ai_enter_model)) }
            } else {
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { viewModel.ai.update(settings.copy(model = it)) },
                    label = { Text(stringResource(R.string.ai_model)) },
                    placeholder = { Text(provider.suggestedModel.ifEmpty { "model-name" }) },
                    supportingText = { Text(stringResource(R.string.help_field_ai_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                if (models.isNotEmpty()) TextButton(onClick = { customModel = false }) { Text(stringResource(R.string.ai_choose_from_list)) }
            }
        }
        val footer = when {
            !settings.isEnabled -> listOf(R.string.settings_ai_off_body)
            provider == AiProvider.Ollama -> listOf(R.string.ai_tip_ollama, R.string.ai_local_sent)
            provider == AiProvider.Vllm -> listOf(R.string.ai_tip_vllm, R.string.ai_local_sent)
            provider == AiProvider.LmStudio -> listOf(R.string.ai_tip_lmstudio, R.string.ai_local_sent)
            provider == AiProvider.Local -> listOf(R.string.ai_tip_other, R.string.ai_local_sent)
            else -> listOf(R.string.settings_ai_on_body)
        }
        // Under the service field's text, past its icon, not at the icon's column.
        footer.forEach {
            Text(
                stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SettingsFieldInset),
            )
        }
    }
    HelpLink(HelpTopic.Ai, R.string.help_link_ai)
}

private fun AiProvider.isEnabledProvider() = this != AiProvider.Off

/** "Create an API key at …", or how to get a local server. */
@Composable
fun keyLinkLabel(provider: AiProvider): String = when (provider) {
    AiProvider.Ollama -> stringResource(R.string.ai_install_ollama)
    AiProvider.Vllm -> stringResource(R.string.ai_vllm_start)
    AiProvider.LmStudio -> stringResource(R.string.ai_download_lmstudio)
    else -> stringResource(R.string.ai_create_key_at, provider.brand.orEmpty())
}

/** Provider menu: Off, then global services, Chinese services, OpenRouter and a local server. */
@Composable
private fun ProviderField(selected: AiProvider, onSelect: (AiProvider) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        headlineContent = {
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
                    listOf(listOf(AiProvider.Off), AiProvider.global, AiProvider.chinese, AiProvider.others, AiProvider.localServers).forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider()
                        group.forEach { option ->
                            DropdownMenuItem(text = { Text(option.title()) }, onClick = { onSelect(option); expanded = false })
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ModelMenu(model: String, models: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = model.takeIf { it in models } ?: stringResource(R.string.ai_choose_model),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.ai_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}
