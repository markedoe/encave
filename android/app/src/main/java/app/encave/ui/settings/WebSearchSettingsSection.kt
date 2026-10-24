package app.encave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.R
import app.encave.core.search.WebSearchService
import app.encave.data.search.WebSearchClient
import app.encave.ui.components.SectionHeader
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.search.openLink
import app.encave.ui.search.webSearchErrorText
import app.encave.ui.search.webSearchPreferences
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Worked(val count: Int) : TestState
    data class Failed(val error: Throwable) : TestState
}

@Composable
private fun WebSearchService.title(): String = brand ?: stringResource(R.string.ai_off)

/**
 * Optional web search: the service, its key (Keystore-encrypted, one per service) or a
 * SearXNG server address, and a test search.
 */
@Composable
fun WebSearchSettingsSection() {
    val preferences = webSearchPreferences()
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val service = settings.service
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember(service) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var test by remember(service) { mutableStateOf<TestState>(TestState.Idle) }
    val testQuery = stringResource(R.string.web_search_test_query)
    LaunchedEffect(service) { apiKey = withContext(Dispatchers.IO) { preferences.apiKey(service).orEmpty() } }

    fun runTest() {
        test = TestState.Testing
        val current = settings
        val key = apiKey
        scope.launch {
            test = runCatching { WebSearchClient.search(testQuery, current, key, count = 5) }
                .fold({ TestState.Worked(it.size) }, { TestState.Failed(it) })
        }
    }

    SectionHeader(stringResource(R.string.settings_web_search))
    ServiceField(service) { preferences.select(it) }
    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        if (settings.isEnabled) {
            if (service.requiresServerUrl) {
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = { preferences.setServerUrl(it); if (test != TestState.Testing) test = TestState.Idle },
                    label = { Text(stringResource(R.string.ai_server_url)) },
                    placeholder = { Text("http://nas.local:8080") },
                    supportingText = { Text(stringResource(R.string.help_field_searxng_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
            if (service.requiresKey) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { key ->
                        apiKey = key
                        if (test != TestState.Testing) test = TestState.Idle
                        preferences.saveApiKey(service, key)
                    },
                    label = { Text(stringResource(R.string.ai_key)) },
                    supportingText = { Text(stringResource(R.string.help_field_web_search_key)) },
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
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OutlinedButton(
                    onClick = ::runTest,
                    enabled = test != TestState.Testing && (!service.requiresKey || apiKey.isNotBlank()) &&
                        (!service.requiresServerUrl || settings.serverUrl.isNotBlank()),
                    modifier = Modifier.testTag("settings.webSearchTest"),
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Text(stringResource(R.string.web_search_test), Modifier.padding(start = Dimens.s))
                }
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    when (val state = test) {
                        TestState.Testing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        is TestState.Worked -> {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(pluralStringResource(R.plurals.web_search_results_count, state.count, state.count), Modifier.padding(start = Dimens.xs))
                        }
                        is TestState.Failed -> {
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.web_search_not_working), Modifier.padding(start = Dimens.xs), color = MaterialTheme.colorScheme.error)
                        }
                        TestState.Idle -> Unit
                    }
                }
            }
            (test as? TestState.Failed)?.let {
                Text(webSearchErrorText(it.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            service.setupPage?.let { page ->
                TextButton(onClick = { openLink(context, page) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                    Text(
                        if (service.requiresServerUrl) stringResource(R.string.web_search_install_searxng)
                        else stringResource(R.string.ai_create_key_at, service.brand.orEmpty()),
                        Modifier.padding(start = Dimens.s),
                    )
                }
            }
        }
        val footer = when {
            !settings.isEnabled -> listOf(R.string.settings_web_search_off_body)
            service.requiresServerUrl -> listOf(R.string.web_search_searxng_json, R.string.settings_web_search_on_body)
            else -> listOf(R.string.settings_web_search_on_body)
        }
        // Under the service field's text, past its icon, not at the icon's column.
        footer.forEach {
            Text(
                stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = SettingsFieldInset),
            )
        }
    }
    if (settings.isEnabled) HelpLink(HelpTopic.WebSearch, R.string.help_link_web_search)
}

/** Service menu: Off, Brave Search, Tavily and SearXNG. */
@Composable
private fun ServiceField(selected: WebSearchService, onSelect: (WebSearchService) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
        headlineContent = {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected.title(),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.web_search_service)) },
                    supportingText = { Text(stringResource(R.string.help_field_web_search_service)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .testTag("settings.webSearchService"),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    WebSearchService.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.title()) }, onClick = { onSelect(option); expanded = false })
                    }
                }
            }
        },
    )
}

/** Where the text of a settings field with a leading icon starts, inside a section's 16 dp padding: the icon and its gap. */
internal val SettingsFieldInset = 24.dp + Dimens.l
