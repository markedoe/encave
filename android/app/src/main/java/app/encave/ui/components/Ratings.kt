package app.encave.ui.components

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.R
import app.encave.core.RatingScale
import app.encave.core.Score
import app.encave.ui.theme.Dimens
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** How ratings are shown and entered on this device (Settings › Ratings), provided by the app. */
val LocalRatingScale = staticCompositionLocalOf { RatingScale.Stars }

/** A rating as words and figures on a scale, with the shared conversion ([Score]). */
object RatingTexts {
    /** The language the texts are in: numbers are written as in it. */
    private val Resources.locale: Locale get() = configuration.locales.get(0) ?: Locale.getDefault()

    /** "17.5" or "17,5", "93": at most one decimal, as written in [locale]. */
    fun number(value: Double, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }.format(value)

    /** "17.5/20", "93/100", "≈ 90/100" for a rating given in stars; null in stars or without a rating. */
    fun score(resources: Resources, rating: Int?, score100: Double?, scale: RatingScale): String? {
        if (scale == RatingScale.Stars) return null
        val value = Score.shown(rating, score100, scale) ?: return null
        val text = resources.getString(if (scale == RatingScale.Twenty) R.string.rating_score_20 else R.string.rating_score_100, number(value, resources.locale))
        return if (Score.isFromStars(rating, score100)) resources.getString(R.string.rating_approx, text) else text
    }

    /** As TalkBack reads it: "4 stars", "17.5 out of 20", "about 90 out of 100"; null without a rating. */
    fun spoken(resources: Resources, rating: Int?, score100: Double?, scale: RatingScale): String? {
        val value = Score.shown(rating, score100, scale) ?: return null
        if (scale == RatingScale.Stars) {
            val stars = value.roundToInt()
            return resources.getQuantityString(R.plurals.journal_rating_stars, stars, stars)
        }
        val text = resources.getString(if (scale == RatingScale.Twenty) R.string.rating_spoken_20 else R.string.rating_spoken_100, number(value, resources.locale))
        return if (Score.isFromStars(rating, score100)) resources.getString(R.string.rating_spoken_approx, text) else text
    }
}

/** [RatingTexts.spoken] on the device's scale. */
@Composable
fun spokenRating(rating: Int?, score100: Double?): String? =
    RatingTexts.spoken(LocalResources.current, rating, score100, LocalRatingScale.current)

/**
 * A rating on the device's scale: stars, or "17.5/20" and "≈ 90/100" (a rating given in
 * stars). Read by TalkBack as "Rated 4 out of 5" or "Rated 17.5 out of 20".
 */
@Composable
fun RatingDisplay(rating: Int?, score100: Double?, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val scale = LocalRatingScale.current
    if (scale == RatingScale.Stars) {
        RatingStars(rating, modifier, size)
        return
    }
    val resources = LocalResources.current
    val text = RatingTexts.score(resources, rating, score100, scale)
    val description = RatingTexts.spoken(resources, rating, score100, scale)?.let { stringResource(R.string.rating_value_score, it) }
        ?: stringResource(R.string.rating_none)
    Text(
        text ?: stringResource(R.string.rating_none),
        style = MaterialTheme.typography.labelLarge,
        color = if (text != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("rating.score").clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * Rating entry on the device's scale: five stars, or a slider out of 20 (half points) or
 * out of 100 (50 to 100). [onChange] gets the rating and the precise score to store
 * (`Tasting.withRating`); null clears both. TalkBack reads the slider as "Rating, 17.5 out of 20".
 */
@Composable
fun RatingField(rating: Int?, score100: Double?, onChange: (Int?, Double?) -> Unit, modifier: Modifier = Modifier) {
    val scale = LocalRatingScale.current
    if (scale == RatingScale.Stars) {
        RatingInput(rating, onChange = { onChange(it, null) }, modifier)
        return
    }
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val range = Score.range(scale)
    val step = Score.step(scale)
    val shown = Score.shown(rating, score100, scale)
    // Without a rating, the thumb waits at a usual score; the words say there is none.
    val value = shown ?: if (scale == RatingScale.Twenty) 15.0 else 88.0
    val label = stringResource(R.string.rating_label)
    val state = RatingTexts.spoken(resources, rating, score100, scale) ?: stringResource(R.string.rating_input_none)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                RatingTexts.score(resources, rating, score100, scale) ?: stringResource(R.string.rating_input_none),
                style = MaterialTheme.typography.titleLarge,
                color = if (shown != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                // The slider below says the same to TalkBack.
                modifier = Modifier.weight(1f).clearAndSetSemantics { },
            )
            if (rating != null) {
                IconButton(onClick = { onChange(null, null) }, modifier = Modifier.testTag("rating.clear")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rating_clear))
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = (Math.round(raw / step) * step).coerceIn(range.start, range.endInclusive)
                if (snapped == shown && !Score.isFromStars(rating, score100)) return@Slider
                val entered = Score.entered(snapped, scale) ?: return@Slider
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onChange(entered.rating, entered.score100)
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth().testTag("rating.slider").semantics {
                contentDescription = label
                stateDescription = state
            },
        )
    }
}
