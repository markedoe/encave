package app.encave.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Where the whole front label is in a wine's label photo, as the optional AI label
 * recognition answered it, to frame the studio picture on it. Fractions (0 to 1) of
 * the photo's width and height from its top-left corner, and the label's slant in
 * degrees (positive when its text runs uphill to the right). It belongs to the photo
 * named by [photoId]: a new label photo makes it stale, and it is then ignored.
 * Synced with the wine (docs/SYNC.md), as on Apple (CaveKit's `WineLabelFrame`).
 */
@KeepGeneratedSerializer
@Serializable(with = WineLabelFrameSerializer::class)
data class WineLabelFrame(
    @SerialName("photoID") val photoId: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val angle: Double = 0.0,
    /** Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1). */
    @Transient val unknownKeys: JsonObject = NoUnknownKeys,
) {
    /** A part of the photo in pixels, from its top-left corner: its centre and size. */
    data class Crop(val centerX: Double, val centerY: Double, val width: Double, val height: Double)

    /**
     * The part of a photo of [width] × [height] pixels to keep, once turned by [angle]
     * around the label's centre so its text is level: the label and its margin, at
     * most the photo's size.
     */
    fun crop(width: Double, height: Double, margin: Double = MARGIN): Crop = Crop(
        centerX = (x + w / 2) * width,
        centerY = (y + h / 2) * height,
        width = min(width, w * width * (1 + 2 * margin)),
        height = min(height, h * height * (1 + 2 * margin)),
    )

    /** A short, stable tag for caches made from this frame (the studio picture). */
    val cacheTag: String
        get() {
            fun part(value: Double) = (value * 1000).roundToInt().toString()
            return "f${part(x)}-${part(y)}-${part(w)}-${part(h)}-a${(angle * 10).roundToInt()}"
        }

    companion object {
        /** Margin added around the label in the studio picture, as a share of its size. */
        const val MARGIN = 0.06
    }
}

/** Keeps the keys of a newer version (see [KeepingUnknownKeys]). */
object WineLabelFrameSerializer : KeepingUnknownKeys<WineLabelFrame>(WineLabelFrame.generatedSerializer()) {
    override fun unknownKeys(value: WineLabelFrame) = value.unknownKeys
    override fun withUnknownKeys(value: WineLabelFrame, keys: JsonObject) = value.copy(unknownKeys = keys)
}

/** The label frame, only while it belongs to [photoId] (the label photo shown). */
fun Wine.labelFrame(photoId: String?): WineLabelFrame? = labelFrame?.takeIf { photoId != null && it.photoId == photoId }

/**
 * The label's place in the photo as read from an AI answer ("label_box" and
 * "label_angle"), checked: anything invalid is left out, never failing the answer.
 */
data class AiLabelPlacement(val x: Double, val y: Double, val w: Double, val h: Double, val angle: Double = 0.0) {
    fun frame(photoId: String): WineLabelFrame = WineLabelFrame(photoId, x, y, w, h, angle)

    companion object {
        /** The smallest label, as a share of the photo's width and of its height. */
        const val MINIMUM_SIZE = 0.1
        /** The steepest slant believed, in degrees either way. */
        const val MAXIMUM_ANGLE = 45.0

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Null when a value is not finite or outside 0–1, the box leaves the photo, is
         * narrower or lower than [MINIMUM_SIZE], or the angle is steeper than [MAXIMUM_ANGLE].
         */
        fun validated(x: Double, y: Double, w: Double, h: Double, angle: Double = 0.0): AiLabelPlacement? {
            if (listOf(x, y, w, h).any { !it.isFinite() || it < 0 || it > 1 } || !angle.isFinite()) return null
            // A little room for rounding (0.3 + 0.7).
            if (x + w > 1 + 1e-6 || y + h > 1 + 1e-6) return null
            if (w < MINIMUM_SIZE || h < MINIMUM_SIZE || abs(angle) > MAXIMUM_ANGLE) return null
            return AiLabelPlacement(x, y, w, h, angle)
        }

        /**
         * From the JSON answer: null when "label_box" is absent, null or invalid. A
         * missing or null "label_angle" counts as level; an invalid one leaves out the box.
         */
        fun parse(answer: String): AiLabelPlacement? {
            val root = runCatching { json.parseToJsonElement(answer) }.getOrNull() as? JsonObject ?: return null
            val box = root["label_box"] as? JsonObject ?: return null
            fun number(element: JsonElement?): Double? = (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val x = number(box["x"]) ?: return null
            val y = number(box["y"]) ?: return null
            val w = number(box["w"]) ?: return null
            val h = number(box["h"]) ?: return null
            val angle = when (val value = root["label_angle"]) {
                null, JsonNull -> 0.0
                else -> number(value) ?: return null
            }
            return validated(x, y, w, h, angle)
        }
    }
}
