package app.encave.core

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What identifies a wine for the AI, and all it is sent when the photo is not: producer,
 * name, appellation, vintage, colour and grapes. Never the cellar, notes, prices or places.
 * As CaveKit's `WineIdentity`.
 */
data class WineIdentity(
    val producer: String = "",
    val name: String,
    val appellation: String = "",
    val vintage: Int? = null,
    val color: String = "",
    val grapes: List<String> = emptyList(),
) {
    constructor(wine: Wine) : this(wine.producer.orEmpty(), wine.name, wine.appellation.orEmpty(), wine.vintage, wine.color?.raw.orEmpty(), wine.grapes)

    /** One line per detail known, as sent. */
    val lines: List<String>
        get() = buildList {
            if (producer.isNotBlank()) add("Producer: ${producer.trim()}")
            if (name.isNotBlank()) add("Name: ${name.trim()}")
            if (appellation.isNotBlank()) add("Appellation: ${appellation.trim()}")
            add("Vintage: ${vintage?.toString() ?: "non-vintage"}")
            if (color.isNotBlank()) add("Colour: ${color.trim()}")
            val clean = grapes.map { it.trim() }.filter { it.isNotEmpty() }
            if (clean.isNotEmpty()) add("Grapes: ${clean.joinToString(", ")}")
        }
}

/**
 * "What to expect" from a wine, asked of the AI: with the label photo (part of the label
 * request, no extra request), or from the wine's identity only ([prompt]). The answer's
 * "expected" object becomes an [ExpectedProfile] ([profileFromAnswer]). As CaveKit's `ExpectedRequest`.
 */
object ExpectedRequest {
    val structureKeys = listOf("sweetness", "acidity", "tannin", "body", "alcohol", "length")

    private fun languageName(code: String) = if (code.lowercase().startsWith("fr")) "French" else "English"

    /** The lines describing "expected", in a label request or on its own. */
    fun lines(language: String): List<String> = listOf(
        "- expected: what to expect when tasting this wine and vintage, from what you know of it, its producer and " +
            "its appellation; leave a detail empty ([], 0 or \"\") when you do not know it.",
        "  - aromas: up to 6 of these ids, the most typical first: " + Vocabulary.aromaIds.joinToString(", ") + ".",
        "  - structure: " + structureKeys.joinToString(", ") + " from 1 (low) to 5 (high); 0 when unknown, and tannin 0 for a white wine.",
        "  - servingMin and servingMax: serving temperature in degrees Celsius; decantMinutes: minutes of decanting, 0 for none.",
        "  - pairings: up to 4 of these ids: " + DishCategory.entries.joinToString(", ") { it.raw } + ".",
        "  - text: one or two short sentences in ${languageName(language)} on what to expect, or \"\".",
    )

    val schema: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") {
                listOf("aromas", "structure", "servingMin", "servingMax", "decantMinutes", "pairings", "text").forEach { add(JsonPrimitive(it)) }
            }
            putJsonObject("properties") {
                putJsonObject("aromas") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { Vocabulary.aromaIds.forEach { add(JsonPrimitive(it)) } }
                    }
                }
                putJsonObject("structure") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { structureKeys.forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("properties") { structureKeys.forEach { putJsonObject(it) { put("type", "integer") } } }
                }
                listOf("servingMin", "servingMax", "decantMinutes").forEach { putJsonObject(it) { put("type", "integer") } }
                putJsonObject("pairings") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "string")
                        putJsonArray("enum") { DishCategory.entries.forEach { add(JsonPrimitive(it.raw)) } }
                    }
                }
                putJsonObject("text") { put("type", "string") }
            }
        }

    /** The text of a request from the wine's identity alone (no photo). */
    fun prompt(identity: WineIdentity, language: String): String =
        (listOf("What can one expect when tasting this wine? Answer with an object holding \"expected\".") + identity.lines +
            lines(language) + "Do not invent: an empty detail is better than a wrong one.").joinToString("\n")

    /** The schema of a request from the identity alone. */
    val answerSchema: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add(JsonPrimitive("expected")) }
            putJsonObject("properties") { put("expected", schema) }
        }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The "expected" object of an answer (JSON text, or text holding it): ids it does not
     * know, levels of 0 and temperatures of 0 or less left out, the text cut at 400
     * characters. Null when nothing is left.
     */
    fun profileFromAnswer(text: String, source: String, model: String?, language: String, date: Instant): ExpectedProfile? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
        val expected = root["expected"] as? JsonObject ?: return null
        return profile(expected, source, model, language, date)
    }

    fun profile(expected: JsonObject, source: String, model: String?, language: String, date: Instant): ExpectedProfile? {
        fun int(element: Any?): Int? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val value = primitive.doubleOrNull ?: return null
            return if (value.isFinite() && kotlin.math.abs(value) < 100_000) Math.round(value).toInt() else null
        }
        fun strings(element: Any?) = (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val known = Vocabulary.aromaIds.toSet()
        val dishes = DishCategory.entries.map { it.raw }.toSet()
        val structure = (expected["structure"] as? JsonObject).orEmpty().filterKeys { it in structureKeys }
            .mapNotNull { (key, value) -> int(value)?.takeIf { it >= 1 }?.let { key to it } }.toMap()
        val text = (expected["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty().take(400)
        val profile = ExpectedProfile(
            aromas = strings(expected["aromas"]).filter { it in known }.take(8),
            structure = structure,
            servingMin = int(expected["servingMin"])?.takeIf { it > 0 },
            servingMax = int(expected["servingMax"])?.takeIf { it > 0 },
            decantMinutes = int(expected["decantMinutes"]),
            pairings = strings(expected["pairings"]).filter { it in dishes }.take(6),
            text = text,
            language = if (text.isEmpty()) null else language.lowercase().take(2),
            source = source,
            model = model,
            date = date,
        ).normalized()
        return if (profile.isEmpty) null else profile
    }
}
