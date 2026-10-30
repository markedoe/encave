package app.encave.ui.drink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import app.encave.R
import app.encave.core.SearchText
import app.encave.core.Vocabulary
import app.encave.core.WineColor
import app.encave.ui.components.Hint
import app.encave.ui.components.VocabularyLabels
import app.encave.ui.components.vocabLabel
import app.encave.ui.theme.Dimens
import kotlin.math.roundToInt

/** A part of the guided sheet: Appearance, Nose, Palate. */
@Composable
internal fun GuidedHeader(title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Dimens.s).semantics { heading() },
    )
}

/** A label above a group of choices. */
@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { heading() },
    )
}

/** A choice that shows it is chosen with a check, not only a tint; TalkBack reads it as selected. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = modifier,
    )
}

/** Intensity (pale, medium, deep: a second tap clears it) and hue, those of the wine's style first. */
@Composable
internal fun AppearanceFields(state: TastingFormState, color: WineColor?) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        GroupLabel(stringResource(R.string.tasting_intensity))
        val intensities = Vocabulary.intensities
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("tasting.intensity")) {
            intensities.forEachIndexed { index, id ->
                SegmentedButton(
                    selected = state.intensity == id,
                    onClick = { state.intensity = if (state.intensity == id) null else id },
                    shape = SegmentedButtonDefaults.itemShape(index, intensities.size),
                ) { Text(vocabLabel(Vocabulary.Kind.Intensity, id), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        // One from a newer version: kept, and shown so it can be cleared.
        state.intensity?.takeIf { it !in intensities }?.let { id ->
            ChoiceChip(vocabLabel(Vocabulary.Kind.Intensity, id), selected = true, onClick = { state.intensity = null })
        }
        GroupLabel(stringResource(R.string.tasting_hue))
        var allHues by rememberSaveable { mutableStateOf(false) }
        val suiting = Vocabulary.hues.filter { it.suits(color) || it.id == state.hue }
        val shown = if (allHues) Vocabulary.hues else suiting
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            shown.forEach { hue ->
                ChoiceChip(
                    vocabLabel(Vocabulary.Kind.Hue, hue.id), selected = state.hue == hue.id,
                    onClick = { state.hue = if (state.hue == hue.id) null else hue.id },
                    modifier = Modifier.testTag("hue.${hue.id}"),
                )
            }
            state.hue?.takeIf { id -> Vocabulary.hues.none { it.id == id } }?.let { id ->
                ChoiceChip(vocabLabel(Vocabulary.Kind.Hue, id), selected = true, onClick = { state.hue = null })
            }
        }
        if (suiting.size < Vocabulary.hues.size) {
            ShowMoreButton(allHues, R.string.tasting_show_all, R.string.tasting_show_fewer, Modifier.testTag("tasting.hues.all")) { allHues = !allHues }
        }
    }
}

@Composable
private fun ShowMoreButton(expanded: Boolean, more: Int, fewer: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        Text(stringResource(if (expanded) fewer else more), Modifier.padding(start = Dimens.s))
    }
}

/**
 * Aromas by family: those that suit the wine's style (and those chosen) first, the others
 * under "More aromas"; a search finds any by its label in the person's language.
 */
@Composable
internal fun AromaFields(state: TastingFormState, color: WineColor?, expected: app.encave.core.ExpectedProfile? = null) {
    val resources = LocalResources.current
    var query by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }
    var showsExpected by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        GroupLabel(stringResource(R.string.tasting_aromas))
        // What to expect, behind one tap so it does not lead the taster.
        if (expected != null && (!expected.aromas.isNullOrEmpty() || expected.structure != null)) {
            ShowMoreButton(showsExpected, R.string.expected_show, R.string.expected_hide, Modifier.testTag("tasting.show_expected")) {
                showsExpected = !showsExpected
            }
            if (showsExpected) {
                app.encave.ui.cellar.ExpectedProfileContent(expected, language = "", compact = true)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.tasting_aroma_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search)) } }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("tasting.aroma_search"),
        )
        @Composable
        fun family(family: Vocabulary.AromaFamily, aromas: List<Vocabulary.Entry>) {
            if (aromas.isEmpty()) return
            GroupLabel(vocabLabel(Vocabulary.Kind.AromaFamily, family.id), Modifier.padding(top = Dimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                aromas.forEach { aroma ->
                    ChoiceChip(
                        vocabLabel(Vocabulary.Kind.Aroma, aroma.id), selected = aroma.id in state.aromas,
                        onClick = { state.toggleAroma(aroma.id) },
                        modifier = Modifier.testTag("aroma.${aroma.id}"),
                    )
                }
            }
        }
        if (query.isNotBlank()) {
            val matching = Vocabulary.aromaFamilies.map { family ->
                family to family.aromas.filter { SearchText.matches(query, listOf(VocabularyLabels.label(resources, Vocabulary.Kind.Aroma, it.id))) }
            }.filter { it.second.isNotEmpty() }
            if (matching.isEmpty()) {
                Text(stringResource(R.string.tasting_aromas_none_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            matching.forEach { (family, aromas) -> family(family, aromas) }
        } else {
            fun first(entry: Vocabulary.Entry) = entry.suits(color) || entry.id in state.aromas
            Vocabulary.aromaFamilies.forEach { family -> family(family, family.aromas.filter(::first)) }
            val hasOthers = Vocabulary.aromaFamilies.any { family -> family.aromas.any { !first(it) } }
            if (hasOthers) {
                ShowMoreButton(more, R.string.tasting_more_aromas, R.string.tasting_fewer_aromas, Modifier.testTag("tasting.aromas.more")) { more = !more }
            }
            if (more) Vocabulary.aromaFamilies.forEach { family -> family(family, family.aromas.filterNot(::first)) }
        }
        // Aromas of a newer version: kept, and shown so they can be removed.
        val unknown = state.aromas.filter { Vocabulary.familyOfAroma(it) == null }
        if (unknown.isNotEmpty()) {
            GroupLabel(stringResource(R.string.tasting_aromas_other), Modifier.padding(top = Dimens.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                unknown.forEach { id -> ChoiceChip(vocabLabel(Vocabulary.Kind.Aroma, id), selected = true, onClick = { state.toggleAroma(id) }) }
            }
        }
    }
}

/**
 * One slider per structure attribute offered for the wine's style (tannin for reds…, or
 * when already noted): 0 leaves it out, 1 to 5 go from low to high. The level's word is
 * written next to it and read by TalkBack.
 */
@Composable
internal fun StructureFields(state: TastingFormState, color: WineColor?) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Vocabulary.structure.filter { it.isOffered(color, state.levels[it.id]) }.forEach { attribute ->
            val value = state.levels[attribute.id]
            val name = vocabLabel(Vocabulary.Kind.Structure, attribute.id)
            val word = value?.let { VocabularyLabels.level(resources, attribute, it) } ?: stringResource(R.string.tasting_level_none)
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    word, style = MaterialTheme.typography.labelLarge,
                    color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = (value ?: 0).toFloat(),
                onValueChange = { raw -> state.setLevel(attribute.id, raw.roundToInt().takeIf { it > 0 }) },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier.fillMaxWidth().testTag("structure.${attribute.id}").semantics {
                    contentDescription = name
                    stateDescription = word
                },
            )
        }
        Hint(stringResource(R.string.tasting_structure_footer))
    }
}
