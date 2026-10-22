package app.encave.ui.drink

import app.encave.ui.components.ActionContent
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.encave.core.Occasion
import app.encave.ui.components.labelRes
import app.encave.core.drinkChoices
import app.encave.ui.components.rememberForgetRetained
import app.encave.ui.components.rememberRetained
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.encave.R
import app.encave.ui.components.displayLocation
import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.Tasting
import app.encave.ui.components.Formats
import app.encave.ui.components.Hint
import app.encave.ui.help.HelpLink
import app.encave.ui.help.HelpTopic
import app.encave.ui.components.RatingInput
import app.encave.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Editable copy of a tasting, shared by the drink sheet and the tasting screen. */
class TastingFormState(initial: Tasting) {
    var date by mutableStateOf(initial.date)
    var rating by mutableStateOf(initial.rating)
    var note by mutableStateOf(initial.note.orEmpty())
    var occasion by mutableStateOf(initial.occasion.orEmpty())
    var appearance by mutableStateOf(initial.appearance.orEmpty())
    var nose by mutableStateOf(initial.nose.orEmpty())
    var palate by mutableStateOf(initial.palate.orEmpty())
    var finish by mutableStateOf(initial.finish.orEmpty())
    var food by mutableStateOf(initial.foodPairing.orEmpty())

    /** The full tasting note is shown: a disclosure only, what it holds is always saved. */
    var expanded by mutableStateOf(initial.isDetailed)

    fun toTasting(): Tasting {
        fun String.orNull() = trim().ifEmpty { null }
        return Tasting(
            date = date, rating = rating, note = note.orNull(), occasion = occasion.orNull(),
            appearance = appearance.orNull(), nose = nose.orNull(), palate = palate.orNull(),
            finish = finish.orNull(), foodPairing = food.orNull(),
        )
    }

    /** The tasting as the form opened, to know whether something changed. */
    private val original: Tasting = toTasting()

    val isDirty: Boolean get() = toTasting() != original
}


/** Rating, note, occasion and date, plus the optional full tasting note. */
@Composable
fun TastingFields(state: TastingFormState, modifier: Modifier = Modifier) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
        Text(stringResource(R.string.drink_rating), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        RatingInput(state.rating, onChange = { state.rating = it })
        Hint(stringResource(R.string.help_field_rating))
        OutlinedTextField(
            value = state.note, onValueChange = { state.note = it },
            label = { Text(stringResource(R.string.drink_note)) },
            supportingText = { Text(stringResource(R.string.help_field_note)) },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Text(stringResource(R.string.field_occasion), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
            // A note written in another language still shows its chip as chosen.
            val chosen = Occasion.matching(state.occasion)
            Occasion.entries.forEach { occasion ->
                val label = stringResource(occasion.labelRes)
                FilterChip(
                    selected = chosen == occasion,
                    onClick = { state.occasion = if (chosen == occasion) "" else label },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = state.occasion, onValueChange = { state.occasion = it },
            label = { Text(stringResource(R.string.field_occasion)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        AssistChip(
            onClick = { pickingDate = true },
            label = { Text(Formats.day(state.date)) },
            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(R.string.field_date)) },
        )
        Hint(stringResource(R.string.help_tasting_footer))
        // Shows or hides the fields; what they hold is kept either way.
        val expandedLabel = stringResource(if (state.expanded) R.string.state_expanded else R.string.state_collapsed)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(if (state.expanded) R.string.action_collapse else R.string.action_expand)) {
                    state.expanded = !state.expanded
                }
                .semantics { stateDescription = expandedLabel }
                .padding(vertical = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.drink_full_note), Modifier.weight(1f).padding(end = Dimens.l), style = MaterialTheme.typography.bodyLarge)
            Icon(if (state.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (state.expanded) {
            Hint(stringResource(R.string.help_full_note_footer))
            listOf(
                Triple(R.string.field_appearance to R.string.help_field_appearance, state.appearance) { v: String -> state.appearance = v },
                Triple(R.string.field_nose to R.string.help_field_nose, state.nose) { v: String -> state.nose = v },
                Triple(R.string.field_palate to R.string.help_field_palate, state.palate) { v: String -> state.palate = v },
                Triple(R.string.field_finish to R.string.help_field_finish, state.finish) { v: String -> state.finish = v },
                Triple(R.string.field_food to R.string.help_field_food, state.food) { v: String -> state.food = v },
            ).forEach { (labels, value, onChange) ->
                OutlinedTextField(
                    value = value, onValueChange = onChange, label = { Text(stringResource(labels.first)) },
                    supportingText = { Text(stringResource(labels.second)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
    if (pickingDate) {
        DateDialog(
            initial = state.date,
            onPick = { picked -> picked?.let { state.date = it } },
            onDismiss = { pickingDate = false },
            allowClear = false,
        )
    }
}

/** Material date picker; the picked day keeps the current time of day (noon for other days). */
@Composable
fun DateDialog(initial: Instant?, onPick: (Instant?) -> Unit, onDismiss: () -> Unit, allowClear: Boolean = true) {
    val zone = ZoneId.systemDefault()
    val initialMillis = initial?.let { it.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    val time = if (day == LocalDate.now(zone)) LocalTime.now(zone) else LocalTime.NOON
                    onPick(day.atTime(time).atZone(zone).toInstant())
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onPick(null); onDismiss() }) { Text(stringResource(R.string.date_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    ) { DatePicker(state) }
}

/** "Discard this note?", before closing a form whose note changed. */
@Composable
fun DiscardNoteDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.discard_note_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("discard.confirm")) {
                Text(stringResource(R.string.action_discard), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.action_keep_editing)) } },
    )
}

/** Which bottle's drink sheet is open, by id: kept when the activity is recreated (rotation…). */
@Composable
fun rememberDrinking() = rememberSaveable { mutableStateOf<String?>(null) }

/**
 * Short sheet to record a bottle being opened: which bottle (one per place, the free ones
 * first), rating, note, occasion and date. The snackbar that follows offers Undo. The form
 * is kept when the activity is recreated, and closing it with a changed note asks first.
 * [bottleId]: the bottle chosen at first, usually the wine's bottleToOpen.
 */
@Composable
fun DrinkSheet(
    cellar: Cellar,
    bottleId: String,
    onDismiss: () -> Unit,
    onDrink: (Bottle, Tasting) -> Unit,
) {
    val opened = cellar.bottles.values.firstOrNull { it.id.toString() == bottleId && it.status == Bottle.Status.InCellar }
    if (opened == null) {
        // Opened or removed meanwhile (on another device).
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val wine = cellar.wines[opened.wineId]
    val formKey = "drink.form.${opened.wineId}"
    val form = rememberRetained(formKey) { TastingFormState(Tasting()) }
    val forget = rememberForgetRetained()
    // One choice per place; the bottle the sheet opened with stands for its own.
    val choices = remember(cellar, opened.id) {
        (listOf(opened) + cellar.bottlesOf(opened.wineId).filter { it.id != opened.id }).drinkChoices
    }
    var chosenId by rememberSaveable(bottleId) { mutableStateOf(bottleId) }
    val chosen = choices.firstOrNull { it.id.toString() == chosenId } ?: opened
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // A swipe down with a changed note asks instead of closing.
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && form.isDirty) {
                confirmingDiscard = true
                false
            } else {
                true
            }
        },
    )
    fun close() {
        forget(formKey)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = { if (form.isDirty) confirmingDiscard = true else close() }, sheetState = sheetState) {
        Column(
            Modifier
                .testTag("drink.sheet")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.xl)
                .padding(bottom = Dimens.l)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            // What happens, then which wine.
            Text(
                stringResource(R.string.drink_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                wine?.displayName ?: stringResource(R.string.unknown_wine),
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
            )
            if (choices.size > 1) {
                Text(stringResource(R.string.drink_which), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    choices.forEach { bottle ->
                        val location = cellar.displayLocation(bottle) ?: stringResource(R.string.location_none)
                        val occasion = bottle.reservedFor
                        FilterChip(
                            selected = bottle.id == chosen.id,
                            onClick = { chosenId = bottle.id.toString() },
                            // A kept bottle says so in words and with a gift, not by color alone.
                            label = { Text(if (occasion != null) stringResource(R.string.drink_choice_kept, location, occasion) else location) },
                            leadingIcon = if (occasion != null) {
                                { Icon(Icons.Outlined.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag(if (occasion != null) "drink.choice.kept" else "drink.choice"),
                        )
                    }
                }
            } else {
                Text(
                    cellar.displayLocation(chosen) ?: stringResource(R.string.location_none),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                )
            }
            TastingFields(form)
            Button(
                onClick = {
                    onDrink(chosen, form.toTasting())
                    close()
                },
                modifier = Modifier.fillMaxWidth().testTag("action.drink_confirm"),
            ) { ActionContent(stringResource(R.string.action_drink), Icons.Outlined.LocalBar) }
            HelpLink(HelpTopic.Drinking, R.string.help_link_tasting)
        }
    }
    if (confirmingDiscard) {
        DiscardNoteDialog(
            onDiscard = {
                confirmingDiscard = false
                close()
            },
            onKeepEditing = {
                confirmingDiscard = false
                // Back or a tap outside may have started to close the sheet.
                scope.launch { sheetState.show() }
            },
        )
    }
}
