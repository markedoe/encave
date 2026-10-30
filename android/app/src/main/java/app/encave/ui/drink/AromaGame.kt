package app.encave.ui.drink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.AromaGame
import app.encave.core.AromaGameRecord
import app.encave.core.AromaGameStore
import app.encave.core.Cellar
import app.encave.core.Palate
import app.encave.core.Tasting
import app.encave.core.Vocabulary
import app.encave.core.Wine
import app.encave.core.expectedOrBundledProfile
import app.encave.ui.components.Formats
import app.encave.ui.components.Hint
import app.encave.ui.components.VocabularyLabels
import app.encave.ui.components.vocabLabel
import app.encave.ui.theme.Dimens
import java.io.File
import java.time.Instant
import java.util.UUID

/** Where this device keeps its games: a file left out of backups and device transfer. */
fun aromaGameFile(context: android.content.Context): File = File(context.filesDir, "aroma-game/games.json")

@Composable
fun rememberAromaGameStore(): AromaGameStore {
    val context = LocalContext.current
    return remember { AromaGameStore(aromaGameFile(context)) }
}

/**
 * "Guess the aromas": pick up to six aromas (and, if you like, the structure) before seeing
 * what to expect, then reveal and get points. [onCopy], from the Drink sheet, puts the
 * guesses into the tasting being written.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AromaGameSheet(wine: Wine, bottleId: UUID?, onDismiss: () -> Unit, onCopy: ((List<String>, Map<String, Int>) -> Unit)? = null) {
    val profile = wine.expectedOrBundledProfile ?: return onDismiss()
    val store = rememberAromaGameStore()
    var picks by rememberSaveable { mutableStateOf(listOf<String>()) }
    var guessesStructure by rememberSaveable { mutableStateOf(false) }
    val structure = remember { TastingFormState(Tasting()) }
    var result by remember { mutableStateOf<AromaGameRecord?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.testTag("game.sheet").verticalScroll(rememberScrollState()).padding(horizontal = Dimens.xl).padding(bottom = Dimens.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            Text(
                stringResource(R.string.game_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(wine.displayName, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif))
            val shown = result
            if (shown != null) {
                AromaGameResult(shown)
                if (onCopy != null) {
                    OutlinedButton(onClick = { onCopy(shown.guesses, shown.structure); onDismiss() }, modifier = Modifier.fillMaxWidth().testTag("game.copy")) {
                        Text(stringResource(R.string.game_copy))
                    }
                    Hint(stringResource(R.string.game_copy_footer))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("game.done")) { Text(stringResource(R.string.action_done)) }
                return@Column
            }
            Hint(stringResource(R.string.game_intro, AromaGame.MAX_PICKS))
            Vocabulary.aromaFamilies.forEach { family ->
                Text(vocabLabel(Vocabulary.Kind.AromaFamily, family.id), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    family.aromas.forEach { aroma ->
                        val selected = aroma.id in picks
                        FilterChip(
                            selected = selected,
                            enabled = selected || picks.size < AromaGame.MAX_PICKS,
                            onClick = { picks = if (selected) picks - aroma.id else picks + aroma.id },
                            label = { Text(vocabLabel(Vocabulary.Kind.Aroma, aroma.id)) },
                            // Chosen: a check as well as the fill, not the color alone.
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag("game.aroma.${aroma.id}"),
                        )
                    }
                }
            }
            Hint(stringResource(R.string.game_picked, picks.size, AromaGame.MAX_PICKS))
            Row(
                Modifier.fillMaxWidth().toggleable(guessesStructure, role = Role.Switch) { guessesStructure = it }.testTag("game.structure"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.game_structure), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = guessesStructure, onCheckedChange = null)
            }
            if (guessesStructure) StructureFields(structure, wine.color)
            Hint(stringResource(R.string.game_structure_footer))
            Button(
                onClick = {
                    val record = AromaGameRecord(
                        wineId = wine.id, bottleId = bottleId, date = Instant.now(), guesses = picks,
                        structure = if (guessesStructure) structure.levels else emptyMap(),
                        expected = profile.aromas.orEmpty(), expectedStructure = profile.structure.orEmpty(),
                    )
                    store.add(record)
                    result = record
                },
                enabled = picks.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("game.reveal"),
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
                Text(stringResource(R.string.game_reveal), Modifier.padding(start = Dimens.s))
            }
            Hint(stringResource(R.string.game_rules))
        }
    }
}

/** The score of a game: points, accuracy, and each aroma and level. */
@Composable
fun AromaGameResult(record: AromaGameRecord) {
    val score = record.score
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(
            pluralStringResource(R.plurals.game_points, score.points, score.points),
            style = MaterialTheme.typography.displaySmall, modifier = Modifier.testTag("game.points"),
        )
        Text(stringResource(R.string.game_accuracy, percent(score.accuracy)), style = MaterialTheme.typography.titleMedium)
        @Composable
        fun line(icon: ImageVector, text: String, points: Int?) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text, Modifier.weight(1f).padding(start = Dimens.s), style = MaterialTheme.typography.bodyLarge)
                if (points != null) Text("+$points", style = MaterialTheme.typography.bodyLarge)
            }
        }
        @Composable
        fun group(title: Int, content: @Composable () -> Unit) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Dimens.s).semantics { heading() })
            content()
        }
        if (score.exact.isNotEmpty()) group(R.string.comparison_matched) {
            score.exact.forEach { line(Icons.Outlined.CheckCircle, vocabLabel(Vocabulary.Kind.Aroma, it), AromaGame.EXACT_POINTS) }
        }
        if (score.family.isNotEmpty()) group(R.string.game_same_family) {
            score.family.forEach { (guess, expected) ->
                line(
                    Icons.Outlined.Contrast,
                    stringResource(R.string.game_family_match, vocabLabel(Vocabulary.Kind.Aroma, guess), vocabLabel(Vocabulary.Kind.Aroma, expected)),
                    AromaGame.FAMILY_POINTS,
                )
            }
        }
        if (score.missed.isNotEmpty()) group(R.string.game_missed) {
            score.missed.forEach { line(Icons.Outlined.RadioButtonUnchecked, vocabLabel(Vocabulary.Kind.Aroma, it), null) }
        }
        if (score.wrong.isNotEmpty()) group(R.string.game_wrong) {
            score.wrong.forEach { line(Icons.Outlined.HighlightOff, vocabLabel(Vocabulary.Kind.Aroma, it), null) }
        }
        if (score.levels.isNotEmpty()) group(R.string.game_structure_title) {
            score.levels.forEach { level ->
                val attribute = Vocabulary.structure.firstOrNull { it.id == level.attribute } ?: return@forEach
                val guessed = VocabularyLabels.level(resources, attribute, level.guess) ?: level.guess.toString()
                val expected = VocabularyLabels.level(resources, attribute, level.expected) ?: level.expected.toString()
                line(
                    if (level.isClose) Icons.Outlined.CheckCircle else Icons.Outlined.HighlightOff,
                    stringResource(R.string.game_level, vocabLabel(Vocabulary.Kind.Structure, attribute.id), guessed, expected),
                    if (level.isClose) AromaGame.STRUCTURE_POINTS else 0,
                )
            }
        }
    }
}

/** "My palate" in Insights: the games played on this device. */
@Composable
fun MyPalateCard(cellar: Cellar, sample: List<AromaGameRecord> = emptyList()) {
    val store = rememberAromaGameStore()
    var records by remember { mutableStateOf(store.load().ifEmpty { sample }) }
    var confirmingReset by remember { mutableStateOf(false) }
    if (records.isEmpty()) return
    val palate = Palate.of(records, Instant.now())
    Card(
        Modifier.fillMaxWidth().testTag("palate.card"),
        shape = RoundedCornerShape(Dimens.radiusCard),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            Text(stringResource(R.string.palate_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(Formats.integer(palate.points), style = MaterialTheme.typography.displaySmall)
                    Text(pluralStringResource(R.plurals.palate_points_caption, palate.points), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(palate.level.labelRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("palate.level"))
            }
            palate.level.nextAt?.let { next ->
                LinearProgressIndicator(progress = { palate.points.toFloat() / next }, modifier = Modifier.fillMaxWidth())
                Hint(pluralStringResource(R.plurals.palate_to_next, next - palate.points, next - palate.points))
            }
            @Composable
            fun fact(label: Int, value: String) {
                Row {
                    Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value)
                }
            }
            fact(R.string.palate_accuracy, palate.accuracy?.let { percent(it) } ?: "–")
            fact(R.string.palate_streak, pluralStringResource(R.plurals.palate_days, palate.streak, palate.streak))
            fact(R.string.palate_games, Formats.integer(palate.games))
            palate.bestFamily?.let { fact(R.string.palate_best, vocabLabel(Vocabulary.Kind.AromaFamily, it)) }
            palate.weakestFamily?.let { fact(R.string.palate_weakest, vocabLabel(Vocabulary.Kind.AromaFamily, it)) }
            if (palate.recent.isNotEmpty()) {
                Text(stringResource(R.string.palate_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                palate.recent.forEach { game ->
                    Row {
                        Text(cellar.wines[game.wineId]?.displayName ?: stringResource(R.string.palate_wine_gone), Modifier.weight(1f), maxLines = 1)
                        Text("+${game.score.points}  ${Formats.recentDay(game.date)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hint(stringResource(R.string.palate_footer))
            TextButton(onClick = { confirmingReset = true }, modifier = Modifier.testTag("palate.reset")) {
                Text(stringResource(R.string.palate_reset), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.palate_reset_title)) },
            text = { Text(stringResource(R.string.palate_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.removeAll()
                    records = emptyList()
                    confirmingReset = false
                }) { Text(stringResource(R.string.palate_reset_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

val Palate.Level.labelRes: Int
    get() = when (this) {
        Palate.Level.Novice -> R.string.palate_level_novice
        Palate.Level.Amateur -> R.string.palate_level_amateur
        Palate.Level.Connoisseur -> R.string.palate_level_connoisseur
        Palate.Level.Sommelier -> R.string.palate_level_sommelier
    }

/** "46 %", in the person's language. */
private fun percent(value: Int): String = java.text.NumberFormat.getPercentInstance().format(value / 100.0)
