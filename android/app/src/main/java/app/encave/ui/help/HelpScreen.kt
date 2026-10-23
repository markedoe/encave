package app.encave.ui.help

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.encave.R
import app.encave.ui.ProjectLinks
import app.encave.ui.components.readableWidth
import app.encave.data.ai.AiProvider
import app.encave.ui.AppNavigator
import app.encave.ui.AppViewModel
import app.encave.ui.components.sampleLabels
import app.encave.core.containsSample
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.encave.ui.settings.keyLinkLabel
import app.encave.ui.theme.Dimens

/** Renders the small markdown used in help: **bold** and `code`. */
fun helpText(raw: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < raw.length) {
        when {
            raw.startsWith("**", index) -> {
                val end = raw.indexOf("**", index + 2)
                if (end < 0) { append(raw.substring(index)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(raw.substring(index + 2, end)) }
                index = end + 2
            }
            raw[index] == '`' -> {
                val end = raw.indexOf('`', index + 1)
                if (end < 0) { append(raw.substring(index)); break }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw.substring(index + 1, end)) }
                index = end + 1
            }
            else -> {
                append(raw[index])
                index++
            }
        }
    }
}

private fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/**
 * The sections of one article; the AI article ends with a key link per provider. With [viewModel],
 * Getting started ends with Try with a sample cellar.
 */
@Composable
fun HelpArticleContent(article: HelpArticle, modifier: Modifier = Modifier, viewModel: AppViewModel? = null) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.l)) {
        Text(stringResource(article.summary), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        article.sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                section.title?.let { title ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                        section.icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                }
                Text(helpText(stringResource(section.body)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (article.topic == HelpTopic.Start && viewModel != null) {
            SampleCellarHelp(viewModel)
        }
        if (article.topic == HelpTopic.Privacy) {
            LinkRow(stringResource(R.string.settings_privacy_policy)) { openLink(context, ProjectLinks.PRIVACY_POLICY) }
        }
        if (article.topic == HelpTopic.Ai) {
            Text(stringResource(R.string.help_create_key), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Column {
                (AiProvider.global + AiProvider.chinese + AiProvider.OpenRouter).forEach { provider ->
                    val page = provider.keyPage ?: return@forEach
                    LinkRow(provider.brand.orEmpty()) { openLink(context, page) }
                }
                listOf(AiProvider.Ollama, AiProvider.Vllm, AiProvider.LmStudio).forEach { provider ->
                    val page = provider.keyPage ?: return@forEach
                    LinkRow(keyLinkLabel(provider)) { openLink(context, page) }
                }
            }
        }
    }
}

/**
 * Getting started › Try with a sample cellar: loads the sample cellar, in every build (store
 * builds offer it nowhere else), only into an empty cellar.
 */
@Composable
private fun SampleCellarHelp(viewModel: AppViewModel) {
    val cellar by viewModel.cellar.collectAsStateWithLifecycle()
    val shared by viewModel.sharedWithDevices.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val allowed = viewModel.sampleAllowed(cellar, shared)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.help_sample_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        val body = when {
            cellar.containsSample -> R.string.help_sample_loaded
            allowed -> R.string.help_sample_body
            else -> R.string.help_sample_unavailable
        }
        Text(helpText(stringResource(body)), style = MaterialTheme.typography.bodyMedium)
        if (allowed) {
            FilledTonalButton(onClick = { viewModel.loadSample(sampleLabels(resources)) }, modifier = Modifier.testTag("help.load_sample")) {
                Text(stringResource(R.string.action_load_sample))
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Settings → Help: the list of topics, then one article. */
@Composable
fun HelpScreen(navigator: AppNavigator, initialTopic: String? = null, viewModel: AppViewModel? = null) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(initialTopic) }
    val article = open?.let { name -> HelpArticle.all.firstOrNull { it.topic.name == name } }
    BackHandler(enabled = article != null && initialTopic == null) { open = null }
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(app.encave.ui.components.LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(article?.title ?: R.string.help_encave)) },
                navigationIcon = {
                    IconButton(onClick = { if (article != null && initialTopic == null) open = null else navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).readableWidth()) {
            if (article != null) {
                HelpArticleContent(article, Modifier.padding(Dimens.l), viewModel)
            } else {
                HelpArticle.all.forEach { item ->
                    ListItem(
                        leadingContent = { Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(stringResource(item.title)) },
                        supportingContent = { Text(stringResource(item.summary)) },
                        modifier = Modifier.clickable { open = item.topic.name },
                    )
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.help_open_source),
                    Modifier.padding(Dimens.l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkRow(stringResource(R.string.help_project_page)) { openLink(context, ProjectLinks.SOURCE) }
            }
        }
    }
}

/** A "Help with …" row at the end of a form; opens the topic in a sheet. */
@Composable
fun HelpLink(topic: HelpTopic, title: Int, modifier: Modifier = Modifier) {
    var showing by rememberSaveable { mutableStateOf(false) }
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(title), color = MaterialTheme.colorScheme.primary) },
        modifier = modifier.fillMaxWidth().clickable { showing = true },
    )
    if (showing) {
        val article = HelpArticle.of(topic)
        ModalBottomSheet(onDismissRequest = { showing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl).padding(bottom = Dimens.xl).navigationBarsPadding()) {
                Text(
                    stringResource(article.title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = Dimens.m).semantics { heading() },
                )
                HelpArticleContent(article)
            }
        }
    }
}
