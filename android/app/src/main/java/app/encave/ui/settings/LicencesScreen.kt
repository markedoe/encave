package app.encave.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import app.encave.R
import app.encave.ui.AppNavigator
import app.encave.ui.ProjectLinks
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.readableWidth
import app.encave.ui.theme.Dimens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The libraries in the app and their licences, from res/raw/licences.json (android/tools/licences.py). */
@Serializable
data class Acknowledgements(val dependencies: List<Dependency>, val licences: Map<String, Licence>) {
    @Serializable
    data class Dependency(val name: String, val url: String, val licence: String, val copyright: String, val artifacts: List<String>)

    @Serializable
    data class Licence(val name: String, val text: String)

    companion object {
        fun load(context: Context): Acknowledgements? = runCatching {
            val text = context.resources.openRawResource(R.raw.licences).use { it.readBytes().toString(Charsets.UTF_8) }
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
        }.getOrNull()
    }
}

/** Settings › About › Licences: Encave's licence, then each library with its licence. */
@Composable
fun LicencesScreen(navigator: AppNavigator) {
    val context = LocalContext.current
    val acknowledgements = remember { Acknowledgements.load(context) }
    // The licence shown in full: "FSL-1.1-ALv2", or a library's name.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val dependency = acknowledgements?.dependencies?.firstOrNull { it.name == open }
    val licence = acknowledgements?.licences?.get(dependency?.licence ?: open)
    BackHandler(enabled = open != null) { open = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(dependency?.name ?: licence?.name?.takeIf { open != null } ?: stringResource(R.string.settings_licences)) },
                navigationIcon = {
                    IconButton(onClick = { if (open != null) open = null else navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) {
            if (open != null && licence != null) {
                Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                    dependency?.let { Text(it.copyright, style = MaterialTheme.typography.titleSmall) }
                    SelectionContainer {
                        Text(licence.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                return@Column
            }
            SectionHeader("Encave")
            Text(
                stringResource(R.string.licences_encave_notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
            Text(
                stringResource(R.string.licences_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
            )
            acknowledgements?.licences?.get("FSL-1.1-ALv2")?.let { fsl ->
                ListItem(
                    modifier = Modifier.clickable { open = "FSL-1.1-ALv2" },
                    headlineContent = { Text(fsl.name) },
                )
            }
            ListItem(
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, ProjectLinks.SOURCE.toUri())) }
                },
                headlineContent = { Text(stringResource(R.string.settings_source)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            )
            HorizontalDivider()
            SectionHeader(stringResource(R.string.licences_libraries))
            Text(
                stringResource(R.string.licences_libraries_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
            )
            acknowledgements?.dependencies?.forEach { item ->
                ListItem(
                    modifier = Modifier.clickable { open = item.name },
                    headlineContent = { Text(item.name) },
                    supportingContent = {
                        val modules = pluralStringResource(R.plurals.licences_modules, item.artifacts.size, item.artifacts.size)
                        Text(listOfNotNull(acknowledgements.licences[item.licence]?.name, modules).joinToString(" · "))
                    },
                )
            }
        }
    }
}
