package app.encave.ui.drink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
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
    var detailed by mutableStateOf(initial.isDetailed)

    fun toTasting(): Tasting {
        fun String.orNull() = trim().ifEmpty { null }
        return Tasting(
            date = date, rating = rating, note = note.orNull(), occasion = occasion.orNull(),
            appearance = appearance.orNull().takeIf { detailed }, nose = nose.orNull().takeIf { detailed },
            palate = palate.orNull().takeIf { detailed }, finish = finish.orNull().takeIf { detailed },
            foodPairing = food.orNull().takeIf { detailed },
        )
    }
}

private val occasionLabels = listOf(
    R.string.occasion_dinner, R.string.occasion_friends, R.string.occasion_celebration,
    R.string.occasion_weeknight, R.string.occasion_tasting,
)

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
            occasionLabels.forEach { id ->
                val label = stringResource(id)
                FilterChip(
                    selected = state.occasion == label,
                    onClick = { state.occasion = if (state.occasion == label) "" else label },
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
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = state.detailed, role = Role.Switch, onValueChange = { state.detailed = it })
                .padding(vertical = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.drink_full_note), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = state.detailed, onCheckedChange = null)
        }
        if (state.detailed) {
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

/**
 * Short sheet to record a bottle being opened: which bottle, rating, note,
 * occasion and date. The snackbar that follows offers Undo.
 */
@Composable
fun DrinkSheet(
    cellar: Cellar,
    bottles: List<Bottle>,
    initialBottleId: java.util.UUID? = null,
    onDismiss: () -> Unit,
    onDrink: (Bottle, Tasting) -> Unit,
) {
    if (bottles.isEmpty()) return
    val wine = cellar.wines[bottles.first().wineId]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val form = remember { TastingFormState(Tasting()) }
    // One choice per distinct location; bottles in the same place are interchangeable.
    val choices = remember(bottles) {
        bottles.distinctBy { it.slot ?: it.storageNote.orEmpty() }
    }
    var chosenId by rememberSaveable { mutableStateOf((initialBottleId ?: choices.first().id).toString()) }
    val chosen = bottles.firstOrNull { it.id.toString() == chosenId } ?: choices.first()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.xl)
                .padding(bottom = Dimens.l)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Text(
                stringResource(R.string.drink_title, wine?.displayName ?: stringResource(R.string.unknown_wine)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (choices.size > 1) {
                Text(stringResource(R.string.drink_which), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    choices.forEach { bottle ->
                        FilterChip(
                            selected = bottle.id == chosen.id,
                            onClick = { chosenId = bottle.id.toString() },
                            label = { Text(cellar.displayLocation(bottle) ?: stringResource(R.string.location_none)) },
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
                onClick = { onDrink(chosen, form.toTasting()); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.LocalBar, contentDescription = null)
                Text(stringResource(R.string.action_drink), Modifier.padding(start = Dimens.s))
            }
            HelpLink(HelpTopic.Drinking, R.string.help_link_tasting)
        }
    }
}
