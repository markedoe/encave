package app.encave.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.encave.R
import app.encave.core.Vocabulary
import app.encave.core.WineColor
import app.encave.ui.components.Hint
import app.encave.ui.components.vocabLabel
import app.encave.ui.theme.Dimens
import kotlinx.coroutines.delay

/**
 * The wine's details beyond the label: alcohol, classification, lieu-dit, dosage (sparkling)
 * or sweetness (still), the share of each grape, ageing, farming and closure. All optional.
 * [collapsible]: behind a "More details" disclosure, open when the wine has some of them or
 * the form was opened for them ([target]); in the Add form they are already behind one.
 */
@Composable
fun WineDetailsSection(form: WineFormState, collapsible: Boolean, initiallyExpanded: Boolean = false, target: WineFormField? = null) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded || target == WineFormField.Details) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(target) {
        if (target != WineFormField.Details) return@LaunchedEffect
        delay(FIELD_TARGET_DELAY_MS)
        requester.bringIntoView()
    }
    Column(Modifier.bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        if (collapsible) {
            val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClickLabel = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand)) { expanded = !expanded }
                    .semantics { stateDescription = state }
                    .testTag("form.more_details")
                    .padding(vertical = Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.add_more_details), Modifier.weight(1f).padding(end = Dimens.l),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                )
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
        } else {
            Text(
                stringResource(R.string.detail_details), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Dimens.s).semantics { heading() },
            )
        }
        if (!collapsible || expanded) WineDetailsFields(form)
        if (collapsible) Hint(stringResource(R.string.help_wine_details_footer))
    }
}

@Composable
private fun WineDetailsFields(form: WineFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        val abvError = stringResource(R.string.help_field_abv)
        OutlinedTextField(
            value = form.abvText,
            onValueChange = { text -> form.abvText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
            label = { Text(stringResource(R.string.field_abv)) },
            suffix = { Text("%") },
            isError = form.abvInvalid,
            supportingText = { Text(if (form.abvInvalid) abvError else aiSupporting(form, "abv", abvError)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("field.abv").semantics { if (form.abvInvalid) error(abvError) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        )
        ClassificationField(form.classification, estimated = form.isEstimatedByAi("classification")) { form.classification = it }
        OutlinedTextField(
            value = form.lieuDit, onValueChange = { form.lieuDit = it },
            label = { Text(stringResource(R.string.field_lieu_dit)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("field.lieu_dit"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        // Dosage says how dry a sparkling wine is; a still wine has a sweetness.
        if (form.color == WineColor.Sparkling) {
            VocabularyDropdown(R.string.field_dosage, Vocabulary.Kind.Dosage, Vocabulary.dosages, form.dosage, "field.dosage") { form.dosage = it }
        } else {
            VocabularyDropdown(R.string.field_sweetness, Vocabulary.Kind.Sweetness, Vocabulary.sweetnesses, form.sweetness, "field.sweetness") {
                form.sweetness = it
            }
        }
        if (form.grapes.isNotEmpty()) {
            Text(stringResource(R.string.field_grape_shares), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            form.grapes.toList().forEach { grape ->
                OutlinedTextField(
                    value = form.grapeShares[grape].orEmpty(),
                    onValueChange = { text ->
                        val digits = text.filter { it.isDigit() }.take(3)
                        form.grapeShares = if (digits.isEmpty()) form.grapeShares - grape else form.grapeShares + (grape to digits)
                    },
                    label = { Text(grape) },
                    suffix = { Text("%") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("field.grape_share"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                )
            }
            Hint(stringResource(R.string.help_field_grape_shares))
        }
        Text(stringResource(R.string.field_ageing), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            // Ids of a newer version are kept and shown, so they can be removed.
            (Vocabulary.ageings + form.ageing.filter { it !in Vocabulary.ageings }).forEach { id ->
                val selected = id in form.ageing
                FilterChip(
                    selected = selected,
                    onClick = { form.ageing = if (selected) form.ageing - id else form.ageing + id },
                    label = { Text(vocabLabel(Vocabulary.Kind.Ageing, id)) },
                    // Chosen: a check, not only a tint.
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("ageing.$id"),
                )
            }
        }
        VocabularyDropdown(R.string.field_farming, Vocabulary.Kind.Farming, Vocabulary.farmings, form.farming, "field.farming") { form.farming = it }
        if (form.farming != null) {
            Row(
                Modifier.fillMaxWidth().toggleable(form.farmingCertified, role = Role.Switch) { form.farmingCertified = it }
                    .testTag("field.farming_certified").padding(vertical = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.field_farming_certified), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = form.farmingCertified, onCheckedChange = null)
            }
        }
        VocabularyDropdown(R.string.field_closure, Vocabulary.Kind.Closure, Vocabulary.closures, form.closure, "field.closure") { form.closure = it }
    }
}

/** A read-only dropdown of vocabulary ids, "Not set" first; an id of a newer version is kept and shown as it is. */
@Composable
private fun VocabularyDropdown(label: Int, kind: Vocabulary.Kind, ids: List<String>, value: String?, tag: String, onChange: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value?.let { vocabLabel(kind, it) } ?: stringResource(R.string.option_not_set),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf<String?>(null) + ids + listOfNotNull(value?.takeIf { it !in ids })).forEach { id ->
                DropdownMenuItem(
                    text = { Text(id?.let { vocabLabel(kind, it) } ?: stringResource(R.string.option_not_set)) },
                    leadingIcon = if (id == value) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                    onClick = { onChange(id); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Free text with the usual classifications offered, all of them while it is empty. */
@Composable
private fun ClassificationField(value: String, estimated: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val needle = value.trim()
    val matches = Vocabulary.classificationSuggestions.filter { it.contains(needle, ignoreCase = true) && !it.equals(needle, ignoreCase = true) }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); expanded = true },
            label = { Text(stringResource(R.string.field_classification)) },
            supportingText = if (estimated) ({ Text(stringResource(R.string.ai_estimated)) }) else null,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && matches.isNotEmpty()) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).testTag("field.classification"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onChange(option); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
