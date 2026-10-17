package app.encave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.data.ai.AiSettings
import app.encave.ui.AppViewModel
import app.encave.ui.components.RadioRow
import app.encave.ui.components.SectionHeader
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional AI label recognition: provider, server, model and API key (Keystore-encrypted). */
@Composable
fun AiSettingsSection(viewModel: AppViewModel) {
    val settings by viewModel.ai.settings.collectAsStateWithLifecycle()
    var keyStored by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { keyStored = withContext(Dispatchers.IO) { !viewModel.ai.apiKey.isNullOrEmpty() } }

    SectionHeader(stringResource(R.string.settings_ai))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.ai_provider)) },
        supportingContent = {
            Text(stringResource(if (settings.isEnabled) R.string.settings_ai_on_body else R.string.settings_ai_off_body))
        },
    )
    listOf(
        AiSettings.Provider.Off to stringResource(R.string.ai_off),
        AiSettings.Provider.Claude to "Claude (Anthropic)",
        AiSettings.Provider.OpenAiCompatible to stringResource(R.string.ai_openai),
    ).forEach { (provider, label) ->
        RadioRow(provider, settings.provider == provider, label) { viewModel.ai.update(settings.copy(provider = it)) }
    }
    if (!settings.isEnabled) return
    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        if (settings.provider == AiSettings.Provider.OpenAiCompatible) {
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { viewModel.ai.update(settings.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.ai_url)) },
                placeholder = { Text(AiSettings.DEFAULT_OPENAI_BASE_URL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
        OutlinedTextField(
            value = settings.model,
            onValueChange = { viewModel.ai.update(settings.copy(model = it)) },
            label = { Text(stringResource(R.string.ai_model)) },
            placeholder = {
                Text(if (settings.provider == AiSettings.Provider.Claude) AiSettings.DEFAULT_CLAUDE_MODEL else AiSettings.OPENAI_MODEL_HINT)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        if (keyStored) {
            Row {
                Text(
                    stringResource(R.string.ai_key_saved), Modifier.weight(1f).padding(top = Dimens.m),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    viewModel.ai.apiKey = null
                    keyStored = false
                }) { Text(stringResource(R.string.ai_key_remove), color = MaterialTheme.colorScheme.error) }
            }
        } else {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(stringResource(R.string.ai_key)) },
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            OutlinedButton(
                onClick = {
                    viewModel.ai.apiKey = keyInput
                    keyInput = ""
                    keyStored = true
                },
                enabled = keyInput.isNotBlank(),
            ) { Text(stringResource(R.string.ai_key_save)) }
        }
    }
}
