package app.encave.core

import java.math.BigDecimal
import kotlin.math.floor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** How ratings are shown and entered on this device: 5 stars, out of 20 or out of 100. A device preference, never synced. */
enum class RatingScale(val raw: String) {
    Stars("stars"), Twenty("twenty"), Hundred("hundred");

    companion object {
        fun fromRaw(raw: String?): RatingScale? = entries.firstOrNull { it.raw == raw }
    }
}

/** A rating (1 to 5) and the precise score out of 100 kept with it, if any. */
data class EnteredRating(val rating: Int, val score100: Double?)

/**
 * The one conversion between stars, scores out of 20 and scores out of 100, as CaveKit's
 * `Score`, pinned by `fixtures/rating-scale-vectors.json` (docs/SYNC.md §1).
 *
 * A precise score is kept as `score100`, from 50 to 100 in quarter points, next to the
 * 1–5 `rating`, which is always written so that older versions show stars:
 * - out of 20: `score100 = 50 + 2.5 × score20` (16/20 is 90, 17.5/20 is 93.75), and back;
 * - stars from a score: below 70 is 1, below 80 is 2, below 87 is 3, below 94 is 4, else 5;
 * - a rating in stars only is shown on the other scales as 65, 75, 83, 90 or 96 out of 100.
 */
object Score {
    const val LOWEST = 50.0
    const val HIGHEST = 100.0

    /** Rounds half away from zero, as Swift's `.toNearestOrAwayFromZero` (values here are positive). */
    private fun roundHalfUp(value: Double): Double = if (value >= 0) floor(value + 0.5) else -floor(-value + 0.5)

    /** Brought into 50…100 and rounded to the nearest quarter point; null for null or a value that is not a finite number. */
    fun normalized(score100: Double?): Double? {
        if (score100 == null || !score100.isFinite()) return null
        return roundHalfUp(score100.coerceIn(LOWEST, HIGHEST) * 4) / 4
    }

    /** The 1–5 stars written with a score. */
    fun stars(score100: Double): Int = when {
        score100 < 70 -> 1
        score100 < 80 -> 2
        score100 < 87 -> 3
        score100 < 94 -> 4
        else -> 5
    }

    /** What a rating in stars (1 to 5) stands for out of 100, to show it on another scale. */
    fun score100(stars: Int): Double = listOf(65.0, 75.0, 83.0, 90.0, 96.0)[stars.coerceIn(1, 5) - 1]

    /** A score out of 20 as a score out of 100 (normalized). */
    fun score100FromTwenty(twenty: Double): Double? = normalized(LOWEST + 2.5 * twenty)

    /** A score out of 100 as a score out of 20. */
    fun twenty(score100: Double): Double = (score100 - LOWEST) / 2.5

    /**
     * The score kept with a rating: only while the rating is the stars of that score. An
     * older version that changed or removed the rating left the score behind: it is stale
     * and read as left out, the same way on every device.
     */
    fun kept(score100: Double?, rating: Int?): Double? {
        if (rating == null) return null
        val score = normalized(score100) ?: return null
        return if (stars(score) == rating) score else null
    }

    /**
     * The rating and score to store for a value entered on a scale: stars give no score; out
     * of 20 (0…20) and out of 100 (50…100) give both. Null when the value is not a number.
     */
    fun entered(value: Double, scale: RatingScale): EnteredRating? {
        if (!value.isFinite()) return null
        return when (scale) {
            RatingScale.Stars -> EnteredRating(roundHalfUp(value).coerceIn(1.0, 5.0).toInt(), null)
            RatingScale.Twenty -> score100FromTwenty(value)?.let { EnteredRating(stars(it), it) }
            RatingScale.Hundred -> normalized(value)?.let { EnteredRating(stars(it), it) }
        }
    }

    /**
     * The value to show on a scale for a rating and its score: stars (1 to 5), out of 20 to
     * the nearest half point, out of 100 to the nearest point. Null without a rating.
     */
    fun shown(rating: Int?, score100: Double?, scale: RatingScale): Double? {
        if (rating == null) return null
        val stars = rating.coerceIn(1, 5)
        val score = kept(score100, stars) ?: score100(stars)
        return when (scale) {
            RatingScale.Stars -> stars.toDouble()
            RatingScale.Twenty -> roundHalfUp(twenty(score) * 2) / 2
            RatingScale.Hundred -> roundHalfUp(score)
        }
    }

    /** True when the value shown comes from the stars alone, not from a score entered on that scale. */
    fun isFromStars(rating: Int?, score100: Double?): Boolean = rating != null && kept(score100, rating) == null

    /** The range of a value entered on a scale. */
    fun range(scale: RatingScale): ClosedFloatingPointRange<Double> = when (scale) {
        RatingScale.Stars -> 1.0..5.0
        RatingScale.Twenty -> 0.0..20.0
        RatingScale.Hundred -> 50.0..100.0
    }

    fun step(scale: RatingScale): Double = if (scale == RatingScale.Twenty) 0.5 else 1.0
}

/** A score as a bare JSON number written like Swift writes a `Double`: "90", "93.75" (not "90.0"). */
object ScoreSerializer : KSerializer<Double> {
    override val descriptor = PrimitiveSerialDescriptor("app.encave.Score", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Double) {
        if (encoder is JsonEncoder && value.isFinite()) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(BigDecimal(value.toString()).stripTrailingZeros().toPlainString()))
        } else {
            encoder.encodeDouble(value)
        }
    }

    override fun deserialize(decoder: Decoder): Double {
        if (decoder !is JsonDecoder) return decoder.decodeDouble()
        val element = decoder.decodeJsonElement().jsonPrimitive
        // A number, as Swift reads a `Double`; not a string.
        if (element.isString) throw SerializationException("Invalid score: $element")
        return element.doubleOrNull ?: throw SerializationException("Invalid score: $element")
    }
}
