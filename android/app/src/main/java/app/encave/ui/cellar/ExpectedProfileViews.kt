package app.encave.ui.cellar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.AromaComparison
import app.encave.core.DishCategory
import app.encave.core.ExpectedProfile
import app.encave.core.ExpectedProfiles
import app.encave.core.Vocabulary
import app.encave.core.Wine
import app.encave.core.expectedOrBundledProfile
import app.encave.ui.components.Formats
import app.encave.ui.components.Hint
import app.encave.ui.components.SectionHeader
import app.encave.ui.components.VocabularyLabels
import app.encave.ui.components.labelRes
import app.encave.ui.components.vocabLabel
import app.encave.ui.theme.Dimens
import androidx.compose.ui.platform.LocalResources

/**
 * "What to expect" on a wine's page: the profile kept with the wine (from the AI), else the
 * typical one bundled with Encave for its appellation, grapes or style. With AI set up,
 * "What to expect" asks the models, sending only the wine's identity.
 */
@Composable
fun ExpectedProfileSection(
    wine: Wine,
    aiEnabled: Boolean,
    asking: Boolean,
    language: String,
    onAsk: () -> Unit,
    onUseTypical: () -> Unit,
    onPlay: (() -> Unit)? = null,
) {
    val profile = wine.expectedOrBundledProfile
    if (profile == null && !aiEnabled) return
    SectionHeader(stringResource(R.string.expected_title), Modifier.testTag("detail.expected"))
    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (profile != null) {
            ExpectedProfileContent(profile, language)
            ExpectedSourceCaption(profile)
        } else {
            Hint(stringResource(R.string.expected_none))
        }
        if (onPlay != null && !profile?.aromas.isNullOrEmpty()) {
            OutlinedButton(onClick = onPlay, modifier = Modifier.testTag("action.aroma_game")) {
                Icon(Icons.Outlined.Quiz, contentDescription = null)
                Text(stringResource(R.string.game_title), Modifier.padding(start = Dimens.s))
            }
        }
        if (aiEnabled) {
            OutlinedButton(onClick = onAsk, enabled = !asking, modifier = Modifier.testTag("action.what_to_expect")) {
                if (asking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                }
                val label = when {
                    asking -> R.string.expected_asking
                    wine.expectedProfile?.source == ExpectedProfile.AI -> R.string.expected_ask_again
                    else -> R.string.expected_ask
                }
                Text(stringResource(label), Modifier.padding(start = Dimens.s))
            }
        }
        if (wine.expectedProfile != null && ExpectedProfiles.profile(wine) != null) {
            TextButton(onClick = onUseTypical, modifier = Modifier.testTag("action.typical_profile")) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                Text(stringResource(R.string.expected_use_typical), Modifier.padding(start = Dimens.s))
            }
        }
        Hint(stringResource(if (aiEnabled) R.string.expected_footer_ai else R.string.expected_footer_bundled))
    }
}

/** Aromas, structure, serving, pairings and the short text of a profile; [compact] in the tasting sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpectedProfileContent(profile: ExpectedProfile, language: String, compact: Boolean = false) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        val aromas = profile.aromas.orEmpty()
        if (aromas.isNotEmpty()) {
            Text(stringResource(R.string.tasting_aromas), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.xs), modifier = Modifier.testTag("expected.aromas")) {
                aromas.forEach { TagPill(vocabLabel(Vocabulary.Kind.Aroma, it)) }
            }
        }
        Vocabulary.structure.forEach { attribute ->
            val value = profile.level(attribute.id) ?: return@forEach
            val name = vocabLabel(Vocabulary.Kind.Structure, attribute.id)
            val word = VocabularyLabels.level(resources, attribute, value).orEmpty()
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$name: $word" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(word, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(Dimens.s))
                LevelBar(value)
            }
        }
        if (!compact) {
            val low = profile.servingMin
            val high = profile.servingMax
            if (low != null && high != null) {
                IconLine(Icons.Outlined.Thermostat, stringResource(R.string.serving_temperature), stringResource(R.string.serving_temperature_value, low, high))
            }
            profile.decantMinutes?.let { minutes ->
                IconLine(
                    Icons.Outlined.WineBar, stringResource(R.string.serving_decanting),
                    if (minutes > 0) stringResource(R.string.serving_decant_value, minutes) else stringResource(R.string.serving_no_decant),
                )
            }
            val dishes = profile.pairings.orEmpty().mapNotNull { id -> DishCategory.entries.firstOrNull { it.raw == id } }
            if (dishes.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Restaurant, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.expected_goes_with), Modifier.padding(start = Dimens.s), style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                    dishes.forEach { TagPill(stringResource(it.labelRes)) }
                }
            }
            val text = profile.text ?: profile.bundledId?.let { ExpectedProfiles.note(it, language) }
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("expected.text"))
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label: $value" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(label, Modifier.weight(1f).padding(start = Dimens.s), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A word in a pill, not a button. */
@Composable
fun TagPill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Five small segments filled up to a level; the word beside it says the same. */
@Composable
fun LevelBar(value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..5).forEach { level ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (level <= value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.width(10.dp).height(6.dp),
            ) {}
        }
    }
}

/** Where a profile comes from, in words: bundled, by the AI, or from the web. */
@Composable
fun ExpectedSourceCaption(profile: ExpectedProfile) {
    val day = profile.date?.let { Formats.recentDay(it) }.orEmpty()
    val (icon, text) = when (profile.source) {
        ExpectedProfile.BUNDLED -> Icons.AutoMirrored.Outlined.MenuBook to stringResource(R.string.expected_source_bundled)
        ExpectedProfile.WEB -> Icons.Outlined.Public to stringResource(R.string.expected_source_web, profile.model.orEmpty(), day)
        else -> Icons.Outlined.AutoAwesome to stringResource(R.string.expected_source_ai, profile.model.orEmpty(), day)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("expected.source")) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.xs))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** After a tasting: the aromas found compared with those expected, with Done. */
@Composable
fun AromaComparisonContent(wineName: String, comparison: AromaComparison, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.m), modifier = Modifier.testTag("comparison")) {
        Text(
            stringResource(R.string.comparison_title), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() },
        )
        Text(wineName, style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
        Text(
            stringResource(R.string.comparison_found, comparison.matched.size, comparison.expectedCount),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("comparison.summary"),
        )
        @Composable
        fun group(title: Int, ids: List<String>, icon: ImageVector) {
            if (ids.isEmpty()) return
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            ids.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(vocabLabel(Vocabulary.Kind.Aroma, id), Modifier.padding(start = Dimens.s), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        group(R.string.comparison_matched, comparison.matched, Icons.Outlined.CheckCircle)
        group(R.string.comparison_missed, comparison.missed, Icons.Outlined.RadioButtonUnchecked)
        group(R.string.comparison_extra, comparison.extra, Icons.Outlined.AddCircleOutline)
        if (comparison.extra.isNotEmpty()) Hint(stringResource(R.string.comparison_extra_footer))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("comparison.done")) { Text(stringResource(R.string.action_done)) }
    }
}

/**
 * "Complete from the web" and the pages the AI cited, when a connection allows web search or
 * the wine has pages from an earlier completion. Only http and https pages are opened.
 */
@Composable
fun WebCompletionSection(wine: Wine, available: Boolean, running: Boolean, onComplete: () -> Unit) {
    val sources = wine.webSources.orEmpty().filter { it.isWebAddress }
    if (!available && sources.isEmpty()) return
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    SectionHeader(stringResource(R.string.web_section), Modifier.testTag("detail.web"))
    Column(Modifier.padding(horizontal = Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (available) {
            OutlinedButton(onClick = onComplete, enabled = !running, modifier = Modifier.testTag("action.complete_from_web")) {
                if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Public, contentDescription = null)
                Text(stringResource(if (running) R.string.web_searching else R.string.web_complete), Modifier.padding(start = Dimens.s))
            }
        }
        sources.forEach { source ->
            val host = runCatching { java.net.URI(source.url).host }.getOrNull() ?: source.url
            val openLabel = stringResource(R.string.web_open_page)
            Column(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = openLabel) { runCatching { uriHandler.openUri(source.url) } }
                    .padding(vertical = Dimens.xs),
            ) {
                Text(source.title ?: host, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Hint(stringResource(if (available) R.string.web_footer else R.string.web_footer_sources))
    }
}
