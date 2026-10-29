package app.encave.ui.cellar

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Grade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.encave.R
import app.encave.core.CriticScore
import app.encave.ui.components.SectionHeader
import app.encave.ui.theme.Dimens
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Words and figures of a critic's score, numbers as written in the person's language. */
object CriticScoreTexts {
    private fun number(value: BigDecimal, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }.format(value)

    /** "93/100", "17,5/20", or the award: [CriticScore.value] with the locale's numbers. */
    fun value(score: CriticScore, locale: Locale = Locale.getDefault()): String? {
        val figure = score.score ?: return score.value
        return if (score.scale != null) "${number(figure, locale)}/${score.scale}" else number(figure, locale)
    }

    /** "93/100 · Tasted from barrel": what the row shows under the source. */
    fun line(score: CriticScore, locale: Locale = Locale.getDefault()): String =
        listOfNotNull(value(score, locale), score.note?.trim()?.ifEmpty { null }).joinToString(" · ")

    /** "Cellar Review, 93 out of 100, Tasted from barrel", as TalkBack reads the row. */
    fun spoken(resources: Resources, score: CriticScore, locale: Locale = Locale.getDefault()): String {
        val figure = score.score
        val value = when {
            figure != null && score.scale == 100 -> resources.getString(R.string.rating_spoken_100, number(figure, locale))
            figure != null && score.scale == 20 -> resources.getString(R.string.rating_spoken_20, number(figure, locale))
            else -> value(score, locale)
        }
        return listOfNotNull(score.source.trim().ifEmpty { null }, value, score.note?.trim()?.ifEmpty { null }).joinToString(", ")
    }
}

/**
 * The critics' and guides' scores and awards the person typed for this wine: each opens
 * to be edited or deleted, and "Add a score or award" adds one. Encave looks nothing up.
 */
@Composable
internal fun CriticScoresSection(scores: List<CriticScore>, onChange: (List<CriticScore>) -> Unit) {
    /** The score being edited: its index, -1 for a new one. */
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val resources = LocalResources.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.detail_critics), Modifier.testTag("detail.critics"))
        val editLabel = stringResource(R.string.critic_action_edit)
        val deleteLabel = stringResource(R.string.action_delete)
        scores.forEachIndexed { index, score ->
            val description = CriticScoreTexts.spoken(resources, score)
            ListItem(
                // The click before clearAndSetSemantics, so TalkBack still finds the row's actions.
                modifier = Modifier
                    .testTag("critic.row")
                    .clickable(onClickLabel = editLabel) { editing = index }
                    .clearAndSetSemantics {
                        contentDescription = description
                        customActions = listOf(CustomAccessibilityAction(deleteLabel) { onChange(scores.filterIndexed { i, _ -> i != index }); true })
                    },
                leadingContent = {
                    Icon(if (score.score == null) Icons.Outlined.EmojiEvents else Icons.Outlined.Grade, contentDescription = null)
                },
                headlineContent = { Text(score.source, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = CriticScoreTexts.line(score).ifEmpty { null }?.let { line -> { Text(line) } },
                trailingContent = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
        TextButton(onClick = { editing = -1 }, modifier = Modifier.padding(horizontal = Dimens.s).testTag("critic.add")) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.critic_add), Modifier.padding(start = Dimens.s))
        }
        Text(
            stringResource(R.string.critic_footer),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
        )
    }
    editing?.let { index ->
        val initial = scores.getOrNull(index)
        CriticScoreDialog(
            initial = initial,
            onSave = { score ->
                onChange(if (initial == null) scores + score else scores.mapIndexed { i, old -> if (i == index) score else old })
                editing = null
            },
            onDelete = if (initial != null) ({ onChange(scores.filterIndexed { i, _ -> i != index }); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
}

/** What a critic gave: a score out of 100 or 20, or an award in words. */
internal enum class CriticKind(val scale: Int?, val label: Int) {
    Hundred(100, R.string.critic_kind_100),
    Twenty(20, R.string.critic_kind_20),
    Award(null, R.string.critic_kind_award),
}

/**
 * The score typed in the dialog, or null when it cannot be saved yet (no source, no value,
 * a score outside 0…scale). Starts from [initial], so what a newer version added is kept.
 * A score on another scale (from a newer version) keeps it while [kind] is null.
 */
internal fun criticScore(initial: CriticScore?, source: String, kind: CriticKind?, value: String, note: String): CriticScore? {
    val base = initial ?: CriticScore(source = "")
    val cleanSource = source.trim().ifEmpty { return null }
    val cleanNote = note.trim().ifEmpty { null }
    if (kind == CriticKind.Award) {
        val award = value.trim().ifEmpty { return null }
        return base.copy(source = cleanSource, score = null, scale = null, award = award, note = cleanNote)
    }
    val scale = kind?.scale ?: base.scale ?: return null
    val number = value.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (number < BigDecimal.ZERO || number > BigDecimal(scale)) return null
    return base.copy(source = cleanSource, score = number.stripTrailingZeros(), scale = scale, award = null, note = cleanNote)
}

@Composable
private fun CriticScoreDialog(initial: CriticScore?, onSave: (CriticScore) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var source by rememberSaveable { mutableStateOf(initial?.source.orEmpty()) }
    var kind by rememberSaveable {
        mutableStateOf(
            when {
                initial == null -> CriticKind.Hundred
                initial.score == null -> CriticKind.Award
                else -> CriticKind.entries.firstOrNull { it.scale == initial.scale }
            },
        )
    }
    var value by rememberSaveable {
        mutableStateOf(initial?.let { it.score?.stripTrailingZeros()?.toPlainString() ?: it.award }.orEmpty())
    }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    val scale = kind?.scale ?: initial?.scale
    val result = criticScore(initial, source, kind, value, note)
    val outOfRange = kind != CriticKind.Award && value.isNotBlank() && result == null && source.isNotBlank()
    // Full width with margins: a text field in a default-width dialog never settles in tests.
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = Dimens.xl).widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.critic_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                OutlinedTextField(
                    value = source, onValueChange = { source = it },
                    label = { Text(stringResource(R.string.critic_source)) },
                    supportingText = { Text(stringResource(R.string.critic_source_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("critic.source"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("critic.kind")) {
                    CriticKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = {
                                // A score typed stays when only the scale changes; an award does not become a score.
                                if ((kind == CriticKind.Award) != (option == CriticKind.Award)) value = ""
                                kind = option
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, CriticKind.entries.size),
                        ) { Text(stringResource(option.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (kind == CriticKind.Award) {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it },
                        label = { Text(stringResource(R.string.critic_award)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("critic.value"),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                } else {
                    val range = stringResource(R.string.critic_score_range, scale ?: 100)
                    OutlinedTextField(
                        value = value,
                        onValueChange = { text -> value = text.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        label = { Text(stringResource(R.string.critic_score)) },
                        suffix = scale?.let { { Text("/$it") } },
                        isError = outOfRange,
                        supportingText = { Text(range) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("critic.value").semantics { if (outOfRange) error(range) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text(stringResource(R.string.critic_note)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("critic.note"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { result?.let(onSave) }, enabled = result != null, modifier = Modifier.testTag("critic.save")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("critic.delete")) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
