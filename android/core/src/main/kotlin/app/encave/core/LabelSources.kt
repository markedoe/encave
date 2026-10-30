package app.encave.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where each detail of an AI label answer comes from ("sources"): the details the model
 * gave from its own knowledge rather than read on the label, which the form marks
 * "Estimated by AI" until they are edited. Mirrors CaveKit's `LabelSources`
 * (`fixtures/ai-label-answer-vectors.json`).
 */
object AiLabelSources {
    const val LABEL = "label"
    const val KNOWLEDGE = "knowledge"

    /** The details whose source the answer gives: the drinking window as one. */
    val keys = listOf("name", "producer", "vintage", "color", "appellation", "region", "country", "grapes", "drinkWindow", "abv", "classification")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The [keys] given as "knowledge" in the answer (a JSON object, or text holding one). The
     * drinking window is never printed on a label: always an estimate. Anything else (no
     * sources, another value, a label source) is not marked.
     */
    fun estimated(answer: String): Set<String> {
        val estimated = mutableSetOf("drinkWindow")
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return estimated
        val root = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return estimated
        val sources = root["sources"] as? JsonObject ?: return estimated
        for (key in keys) {
            val value = (sources[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (value.trim().lowercase() == KNOWLEDGE) estimated += key
        }
        return estimated
    }
}
