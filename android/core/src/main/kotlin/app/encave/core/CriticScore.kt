package app.encave.core

import java.math.BigDecimal
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * A critic's or guide's score or award for a wine, as the user typed it from a guide, a
 * magazine or a sticker on the bottle. No database: Encave looks nothing up. Written as
 * "criticScores" on the wine (docs/SYNC.md §1), as CaveKit's `CriticScore`.
 */
@KeepGeneratedSerializer
@Serializable(with = CriticScoreSerializer::class)
data class CriticScore(
    /** Who gave it ("Guide Hachette", "Decanter", "Concours de Mâcon"). */
    val source: String,
    /** The score, with its [scale]; null for an award. */
    @Serializable(with = DecimalSerializer::class) val score: BigDecimal? = null,
    /** 100 or 20 for a score; null for an award given as text. Other values (from a newer version) are kept and shown as "score/scale". */
    val scale: Int? = null,
    /** A medal or award in words ("Gold medal", "Coup de cœur"), when there is no score. */
    val award: String? = null,
    /** A note such as the vintage it was given for ("for the 2015"). */
    val note: String? = null,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** "94/100", "17.5/20" or the award, without the source; null when there is neither. Numbers with a dot. */
    val value: String?
        get() {
            if (score != null) {
                val number = DecimalSerializer.plain(score)
                return if (scale != null) "$number/$scale" else number
            }
            return award?.trim()?.ifEmpty { null }
        }

    /** The score out of 100 by the shared conversion ([Score]), for a score out of 100 or 20; null otherwise. */
    val score100: Double?
        get() {
            val number = score?.toDouble() ?: return null
            return when (scale) {
                100 -> Score.normalized(number)
                20 -> Score.score100FromTwenty(number)
                else -> null
            }
        }
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object CriticScoreSerializer : KeepingUnknownKeys<CriticScore>(CriticScore.generatedSerializer()) {
    override fun unknownKeys(value: CriticScore) = value.unknownKeys
    override fun withUnknownKeys(value: CriticScore, keys: JsonObject) = value.copy(unknownKeys = keys)
}
