package app.encave.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.encave.core.Discovery
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.encave.R
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.data.ai.LabelAnalysis
import app.encave.ui.components.Hint
import app.encave.ui.components.WineColorDot
import app.encave.ui.components.labelRes
import app.encave.ui.theme.Dimens
import app.encave.core.search.WebSearchQuery
import app.encave.ui.search.WebSearchSheet
import app.encave.ui.search.webSearchPreferences
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.OutlinedButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import kotlin.math.roundToInt

/** Editable copy of a wine. The drinking window follows the estimate until the user changes it. */
class WineFormState(private val initial: Wine?, private val year: Int) {
    val id: UUID = initial?.id ?: UUID.randomUUID()
    var name by mutableStateOf(initial?.name.orEmpty())
    var producer by mutableStateOf(initial?.producer.orEmpty())
    var vintageText by mutableStateOf(initial?.vintage?.toString().orEmpty())
    var color by mutableStateOf(initial?.color)
    var region by mutableStateOf(initial?.region.orEmpty())
    var country by mutableStateOf(initial?.country.orEmpty())
    var appellation by mutableStateOf(initial?.appellation.orEmpty())
    val grapes = mutableStateListOf<String>().apply { initial?.grapes?.let { addAll(it) } }
    var grapeInput by mutableStateOf("")
    var notes by mutableStateOf(initial?.notes.orEmpty())
    val tags = mutableStateListOf<String>().apply { initial?.tags?.let { addAll(it) } }
    /** Tag being typed; added on save so nothing is lost. */
    var tagInput by mutableStateOf("")
    var showErrors by mutableStateOf(false)

    /** Photo already stored for this wine, a new prepared JPEG, or removal. */
    val storedPhotoId: String? = initial?.photoId
    var newPhoto by mutableStateOf<ByteArray?>(null)
    var photoRemoved by mutableStateOf(false)
    val hasPhoto: Boolean get() = newPhoto != null || (storedPhotoId != null && !photoRemoved)

    /** When the AI last completed the details: set by [apply], kept on save. */
    var aiCompletedAt by mutableStateOf(initial?.aiCompletedAt)

    /** Values filled automatically from the label; those may be replaced again, typed ones never. */
    private val autoFilled = mutableMapOf<String, Any>()

    /** Null while following the estimate. */
    var manualWindow by mutableStateOf(
        if (initial?.drinkFrom != null && initial.drinkUntil != null) initial.drinkFrom!!..initial.drinkUntil!! else null,
    )

    /** Off when the user removed the window, or an existing wine had none. */
    var windowEnabled by mutableStateOf(initial == null || manualWindow != null)

    val vintage: Int? get() = vintageText.trim().toIntOrNull()?.takeIf { it in 1800..2200 }
    val vintageInvalid: Boolean get() = vintageText.isNotBlank() && vintage == null
    val nameMissing: Boolean get() = name.isBlank()

    val estimate: IntRange? get() = Wine(name = "", vintage = vintage, color = color).estimatedWindow()
    val window: IntRange? get() = if (!windowEnabled) null else manualWindow ?: estimate
    val isEstimated: Boolean get() = windowEnabled && manualWindow == null && estimate != null

    /** Slider bounds: from the vintage (or a few years back) to 40 years later. */
    val sliderBounds: IntRange
        get() {
            val low = minOf(vintage ?: (year - 5), window?.first ?: year)
            return low..maxOf(low + 40, window?.last ?: 0)
        }

    /**
     * Fills a field only if it is empty or still holds the value filled automatically before.
     * False when the AI had nothing to add.
     */
    fun apply(analysis: LabelAnalysis): Boolean {
        val before = snapshot()
        fun fill(key: String, current: String, value: String, set: (String) -> Unit) {
            val clean = value.trim()
            if (clean.isNotEmpty() && (current.trim().isEmpty() || current.trim() == autoFilled[key])) {
                set(clean)
                autoFilled[key] = clean
            }
        }
        fill("name", name, analysis.name) { name = it }
        fill("producer", producer, analysis.producer) { producer = it }
        fill("appellation", appellation, analysis.appellation) { appellation = it }
        fill("region", region, analysis.region) { region = it }
        fill("country", country, analysis.country) { country = it }
        fill("grapes", grapes.joinToString(", "), analysis.grapes.joinToString(", ")) { text ->
            grapes.clear()
            grapes.addAll(text.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (analysis.vintage in 1900..2100 && (vintage == null || vintage == autoFilled["vintage"])) {
            vintageText = analysis.vintage.toString()
            autoFilled["vintage"] = analysis.vintage
        }
        val color = WineColor.fromRaw(analysis.color)
        if (color != null && (this.color == null || this.color == autoFilled["color"])) {
            this.color = color
            autoFilled["color"] = color
        }
        if (manualWindow == null && analysis.drinkFrom > 1900 && analysis.drinkUntil >= analysis.drinkFrom) {
            manualWindow = analysis.drinkFrom..analysis.drinkUntil
            windowEnabled = true
        }
        if (snapshot() == before) return false
        aiCompletedAt = app.encave.core.ReferenceDates.now()
        return true
    }

    /** Another vintage of [wine] (one barcode for several vintages): its details, the vintage left to fill. */
    fun fillFrom(wine: Wine) {
        name = wine.name
        producer = wine.producer.orEmpty()
        vintageText = ""
        color = wine.color
        region = wine.region.orEmpty()
        country = wine.country.orEmpty()
        appellation = wine.appellation.orEmpty()
        grapes.clear()
        grapes.addAll(wine.grapes)
        autoFilled.clear()
    }

    /** What the user can edit, to tell whether anything changed since it was taken (Discard changes?). */
    fun snapshot(): List<Any?> = listOf(
        name, producer, vintageText, color, region, country, appellation, grapes.toList(), grapeInput, notes,
        tags.toList(), tagInput, newPhoto != null, photoRemoved, manualWindow, windowEnabled, aiCompletedAt,
    )

    fun addGrape() {
        grapeInput.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() && it !in grapes }.forEach { grapes += it }
        grapeInput = ""
    }

    fun addTags() {
        val all = Discovery.normalized(tags + tagInput.split(","))
        tags.clear()
        tags.addAll(all)
        tagInput = ""
    }

    fun build(): Wine {
        addGrape()
        addTags()
        fun String.orNull() = trim().ifEmpty { null }
        // Starts from the stored record so fields the form does not show (barcodes) are kept.
        return (initial ?: Wine(name = "")).copy(
            id = id, name = name.trim(), producer = producer.orNull(), vintage = vintage, color = color,
            region = region.orNull(), country = country.orNull(), appellation = appellation.orNull(),
            grapes = grapes.toList(), drinkFrom = window?.first, drinkUntil = window?.last, notes = notes.orNull(),
            photoId = if (photoRemoved) null else storedPhotoId,
            tags = tags.toList().ifEmpty { null },
            aiCompletedAt = aiCompletedAt,
        )
    }
}

/** A field of the wine form, to open "Edit wine" scrolled to it (a detail tapped on the wine's page). */
enum class WineFormField {
    Name, Producer, Vintage, Style, Appellation, Region, Country, Grapes, Window, Tags, Notes;

    companion object {
        fun from(name: String?): WineFormField? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Name, producer, vintage and style first, with Search online; the rest behind "More details".
 * [nameFocus] and [vintageFocus] let the screen move to the name when it is missing, or to
 * the vintage of another vintage.
 */
@Composable
fun WineEssentials(
    form: WineFormState,
    known: KnownValues,
    modifier: Modifier = Modifier,
    nameFocus: FocusRequester? = null,
    vintageFocus: FocusRequester? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        val nameError = form.showErrors && form.nameMissing
        val nameRequired = stringResource(R.string.add_name_required)
        OutlinedTextField(
            value = form.name,
            onValueChange = { form.name = it },
            label = { Text(stringResource(R.string.field_name)) },
            isError = nameError,
            supportingText = {
                Text(stringResource(if (nameError) R.string.add_name_required else R.string.help_field_name))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("field.name")
                .then(if (nameFocus != null) Modifier.focusRequester(nameFocus) else Modifier)
                // TalkBack says why the field is in error, not only that it is.
                .semantics { if (nameError) error(nameRequired) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            AutoCompleteField(
                value = form.producer, onValueChange = { form.producer = it }, label = stringResource(R.string.field_producer),
                options = known.producers, modifier = Modifier.weight(1.6f),
                supporting = stringResource(R.string.help_field_producer),
            )
            OutlinedTextField(
                value = form.vintageText,
                onValueChange = { text -> form.vintageText = text.filter { it.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.field_vintage)) },
                isError = form.vintageInvalid,
                supportingText = {
                    Text(stringResource(if (form.vintageInvalid) R.string.add_vintage_invalid else R.string.help_field_vintage))
                },
                singleLine = true,
                modifier = Modifier.weight(1f).then(if (vintageFocus != null) Modifier.focusRequester(vintageFocus) else Modifier),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
        }
        // Right under the name: the web often has the details the label lacks.
        val webSearch by webSearchPreferences().settings.collectAsStateWithLifecycle()
        var searching by remember { mutableStateOf(false) }
        if (webSearch.isEnabled) {
            OutlinedButton(onClick = { searching = true }, enabled = !form.nameMissing, modifier = Modifier.testTag("form.search_online")) {
                Icon(Icons.Outlined.Public, contentDescription = null)
                Text(stringResource(R.string.search_online), Modifier.padding(start = Dimens.s))
            }
            Hint(stringResource(R.string.search_online_footer, webSearch.service.brand.orEmpty()))
        }
        if (searching) {
            WebSearchSheet(
                initialQuery = WebSearchQuery.terms(form.producer, form.name, form.appellation, form.vintage, stringResource(R.string.search_wine_word)),
                onDismiss = { searching = false },
            )
        }
        Text(stringResource(R.string.field_style), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            WineColor.entries.forEach { color ->
                FilterChip(
                    selected = form.color == color,
                    onClick = { form.color = if (form.color == color) null else color },
                    label = { Text(stringResource(color.labelRes)) },
                    leadingIcon = { WineColorDot(color) },
                )
            }
        }
        Hint(stringResource(R.string.help_style_footer))
    }
}

@Composable
fun WineMoreDetails(
    form: WineFormState,
    known: KnownValues,
    modifier: Modifier = Modifier,
    showsTags: Boolean = true,
    /** Tags are scrolled to and notes focused when the form opens for them. */
    target: WineFormField? = null,
) {
    val tagsRequester = remember { BringIntoViewRequester() }
    val notesFocus = remember { FocusRequester() }
    LaunchedEffect(target) {
        if (target != WineFormField.Tags && target != WineFormField.Notes) return@LaunchedEffect
        delay(FIELD_TARGET_DELAY_MS)
        if (target == WineFormField.Tags) tagsRequester.bringIntoView() else runCatching { notesFocus.requestFocus() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
            AutoCompleteField(
                form.region, { form.region = it }, stringResource(R.string.field_region), known.regions, Modifier.weight(1f),
                supporting = stringResource(R.string.help_field_region),
            )
            AutoCompleteField(
                form.country, { form.country = it }, stringResource(R.string.field_country), known.countries, Modifier.weight(1f),
            )
        }
        AutoCompleteField(
            form.appellation, { form.appellation = it }, stringResource(R.string.field_appellation), known.appellations, Modifier.fillMaxWidth(),
            supporting = stringResource(R.string.help_field_appellation),
        )
        GrapesField(form, known.grapes)
        Hint(stringResource(R.string.help_origin_footer))
        // Discoveries have tags of their own.
        if (showsTags) {
            Box(Modifier.bringIntoViewRequester(tagsRequester)) { WineTagsField(form, known.tags) }
        }
        OutlinedTextField(
            value = form.notes, onValueChange = { form.notes = it },
            label = { Text(stringResource(R.string.field_notes)) }, minLines = 2,
            modifier = Modifier.fillMaxWidth().focusRequester(notesFocus).testTag("form.notes"),
            supportingText = { Text(stringResource(R.string.help_notes_footer)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/** Tags chosen, then tags used in the cellar, plus a field to add one. */
@Composable
fun WineTagsField(form: WineFormState, suggestions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(stringResource(R.string.field_tags), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        val chosen = form.tags.map { it.lowercase() }.toSet()
        val shown = form.tags.toList() + suggestions.filter { it.lowercase() !in chosen }.take(12)
        if (shown.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                shown.forEach { tag ->
                    val selected = tag in form.tags
                    FilterChip(
                        selected = selected,
                        onClick = { if (selected) form.tags.remove(tag) else form.tags.add(tag) },
                        label = { Text(tag) },
                        leadingIcon = {
                            Icon(if (selected) Icons.Filled.Check else Icons.Outlined.Tag, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = form.tagInput,
            onValueChange = { text -> form.tagInput = text; if (text.contains(",")) form.addTags() },
            label = { Text(stringResource(R.string.discovery_add_tag)) },
            placeholder = { Text(stringResource(R.string.tag_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field.tags"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { form.addTags() }),
            supportingText = { Text(stringResource(R.string.help_wine_tags_footer)) },
        )
    }
}

@Composable
private fun GrapesField(form: WineFormState, suggestions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        if (form.grapes.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                form.grapes.toList().forEach { grape ->
                    InputChip(
                        selected = false,
                        onClick = { form.grapes.remove(grape) },
                        label = { Text(grape) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.add_grape_remove, grape)) },
                    )
                }
            }
        }
        AutoCompleteField(
            value = form.grapeInput,
            onValueChange = { form.grapeInput = it },
            label = stringResource(R.string.add_grape_hint),
            options = suggestions.filter { it !in form.grapes },
            modifier = Modifier.fillMaxWidth(),
            onPick = { form.grapeInput = it; form.addGrape() },
            imeAction = ImeAction.Done,
            onImeAction = { form.addGrape() },
            supporting = stringResource(R.string.help_field_grapes),
            trailing = {
                if (form.grapeInput.isNotBlank()) {
                    IconButton(onClick = { form.addGrape() }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_grape_hint))
                    }
                }
            },
        )
    }
}

/** Drinking window: estimated from style and vintage, adjustable with a range slider. */
@Composable
fun DrinkingWindowField(form: WineFormState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(form.windowEnabled, role = Role.Switch) { enabled ->
                    form.windowEnabled = enabled
                    if (!enabled) form.manualWindow = null
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = Dimens.l)) {
                Text(stringResource(R.string.add_window_set), style = MaterialTheme.typography.bodyLarge)
                form.window?.let {
                    Text(
                        stringResource(R.string.detail_window_range, it.first, it.last),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(checked = form.windowEnabled, onCheckedChange = null)
        }
        if (form.windowEnabled) {
            val bounds = form.sliderBounds
            val current = form.window ?: (bounds.first + 2)..(bounds.first + 10)
            RangeSlider(
                value = current.first.toFloat()..current.last.toFloat(),
                onValueChange = { range ->
                    val from = range.start.roundToInt()
                    form.manualWindow = from..maxOf(from, range.endInclusive.roundToInt())
                },
                valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
                steps = (bounds.last - bounds.first - 1).coerceAtLeast(0),
            )
            if (form.isEstimated) Hint(stringResource(R.string.add_window_auto))
        }
        Hint(stringResource(R.string.help_window_footer))
    }
}

/** Values already used in the cellar, for autocompletion. */
data class KnownValues(
    val producers: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val appellations: List<String> = emptyList(),
    val grapes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** Text field with a menu of matching known values (exposed dropdown). */
@Composable
fun AutoCompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = onValueChange,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(value, options) {
        val needle = value.trim()
        if (needle.isEmpty()) emptyList()
        else options.filter { it.contains(needle, ignoreCase = true) && !it.equals(needle, ignoreCase = true) }.take(6)
    }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = trailing,
            supportingText = supporting?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        )
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onPick(option); expanded = false },
                    contentPadding = androidx.compose.material3.ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Lets the form be laid out before it scrolls to a field. */
internal const val FIELD_TARGET_DELAY_MS = 250L
