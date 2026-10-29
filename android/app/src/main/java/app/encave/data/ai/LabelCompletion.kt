package app.encave.data.ai

import app.encave.core.ReferenceDates
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.core.WindowSource

/**
 * [wine] completed with what the AI read, for a wine already saved: only empty fields are
 * filled, and the drinking window only if it was an estimate (or missing). [wine] itself,
 * without the AI's date, when the AI had nothing to add.
 */
fun LabelAnalysis.completing(wine: Wine): Wine {
    fun fill(current: String?, value: String): String? = current?.takeIf { it.isNotBlank() } ?: value.trim().ifEmpty { null }
    var result = wine.copy(
        name = wine.name.ifBlank { name.trim() },
        producer = fill(wine.producer, producer),
        vintage = wine.vintage ?: vintage.takeIf { it in 1900..2100 },
        color = wine.color ?: WineColor.fromRaw(color),
        appellation = fill(wine.appellation, appellation),
        region = fill(wine.region, region),
        country = fill(wine.country, country),
        grapes = wine.grapes.ifEmpty { grapes.map { it.trim() }.filter { it.isNotEmpty() } },
        abv = wine.abv ?: abv?.let { java.math.BigDecimal(it.toString()).stripTrailingZeros() },
        classification = fill(wine.classification, classification),
        // Where the label is, when the photo read is the wine's label photo.
        labelFrame = labelFrame?.takeIf { it.photoId == wine.photoId } ?: wine.labelFrame,
    )
    val wasEstimate = wine.drinkFrom == null || wine.drinkUntil == null || wine.windowSource is WindowSource.Estimated
    if (wasEstimate) {
        val window = if (drinkFrom > 1900 && drinkUntil >= drinkFrom) drinkFrom..drinkUntil else result.estimatedWindow()
        if (window != null) result = result.copy(drinkFrom = window.first, drinkUntil = window.last)
    }
    // Nothing else new: not "completed", but where the label is still counts (the studio picture).
    return if (result.sameDetails(wine)) wine.copy(labelFrame = result.labelFrame) else result.copy(aiCompletedAt = ReferenceDates.now())
}

/** The same details as [other], whatever the AI's date and label frame: nothing was completed. */
fun Wine.sameDetails(other: Wine): Boolean = copy(aiCompletedAt = other.aiCompletedAt, labelFrame = other.labelFrame) == other

/**
 * This wine with the fields of [to] where it still holds the value of [from]: what was
 * changed since (here, on another device) is kept. Also the undo, with [from] and [to] swapped.
 */
fun Wine.completed(from: Wine, to: Wine): Wine = copy(
    name = if (name == from.name) to.name else name,
    producer = if (producer == from.producer) to.producer else producer,
    vintage = if (vintage == from.vintage) to.vintage else vintage,
    color = if (color == from.color) to.color else color,
    appellation = if (appellation == from.appellation) to.appellation else appellation,
    region = if (region == from.region) to.region else region,
    country = if (country == from.country) to.country else country,
    grapes = if (grapes == from.grapes) to.grapes else grapes,
    abv = if (abv == from.abv) to.abv else abv,
    classification = if (classification == from.classification) to.classification else classification,
    drinkFrom = if (drinkFrom == from.drinkFrom) to.drinkFrom else drinkFrom,
    drinkUntil = if (drinkUntil == from.drinkUntil) to.drinkUntil else drinkUntil,
    aiCompletedAt = if (aiCompletedAt == from.aiCompletedAt) to.aiCompletedAt else aiCompletedAt,
    labelFrame = if (labelFrame == from.labelFrame) to.labelFrame else labelFrame,
)
