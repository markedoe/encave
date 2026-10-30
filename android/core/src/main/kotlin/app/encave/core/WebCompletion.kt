package app.encave.core

import java.math.BigDecimal
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
 * "Complete from the web": a model whose connection allows web search looks up a saved wine
 * from its identity alone and answers with its details and what to expect. Only empty
 * details are filled; the expected profile is replaced; the pages it cited are kept in
 * [Wine.webSources]. As CaveKit's `WebCompletion` (`fixtures/web-completion-vectors.json`).
 */
data class WebCompletion(
    val producer: String = "",
    val appellation: String = "",
    val region: String = "",
    val country: String = "",
    val grapes: List<String> = emptyList(),
    val drinkFrom: Int = 0,
    val drinkUntil: Int = 0,
    val abv: Double = 0.0,
    val classification: String = "",
    val expected: ExpectedProfile? = null,
) {
    /**
     * The wine with its empty details filled, its drinking window when it had none or only the
     * estimate, the expected profile from the web and the pages cited; [Wine.aiCompletedAt] set
     * when anything changed. The wine itself when nothing did.
     */
    fun completing(wine: Wine, sources: List<WineWebSource>, date: Instant): Wine {
        fun fill(current: String?, value: String): String? = if (current.isNullOrBlank() && value.isNotEmpty()) value else current
        var result = wine.copy(
            producer = fill(wine.producer, producer),
            appellation = fill(wine.appellation, appellation),
            region = fill(wine.region, region),
            country = fill(wine.country, country),
            classification = fill(wine.classification, classification),
            grapes = if (wine.grapes.isEmpty() && grapes.isNotEmpty()) grapes else wine.grapes,
            abv = wine.abv ?: abv.takeIf { it in 0.5..25.0 }?.let { BigDecimal(String.format(java.util.Locale.ROOT, "%.2f", it)).stripTrailingZeros() },
            expectedProfile = expected ?: wine.expectedProfile,
        )
        // None, or only the estimate from the style and vintage.
        if (wine.windowSource != WindowSource.Entered && drinkFrom > 1900 && drinkUntil >= drinkFrom && drinkUntil < 2200) {
            result = result.copy(drinkFrom = drinkFrom, drinkUntil = drinkUntil)
        }
        val cited = AiWebSearch.unique(sources)
        if (cited.isNotEmpty()) result = result.copy(webSources = cited)
        return if (result == wine) wine else result.copy(aiCompletedAt = date)
    }

    companion object {
        private val detailKeys = listOf("producer", "appellation", "region", "country", "grapes", "drinkFrom", "drinkUntil", "abv", "classification")

        /** The question, from the identity alone. */
        fun prompt(identity: WineIdentity, language: String): String = (
            listOf(
                "Search the web for this wine and complete its details from reliable pages (the producer's own site, the " +
                    "appellation's, merchants, guides). Answer with an object holding the details and \"expected\".",
            ) + identity.lines + listOf(
                "- producer: the estate, domaine or house; appellation, region, country (in English).",
                "- grapes: the grape varieties of this wine.",
                "- drinkFrom and drinkUntil: its drinking window in years, or 0.",
                "- abv: its alcohol by volume in percent, or 0; classification: its classification (Grand Cru…), or \"\".",
            ) + ExpectedRequest.lines(language) + "Leave a detail empty (\"\", 0 or []) when the pages do not say it. Do not invent."
            ).joinToString("\n")

        val schema: JsonObject
            get() = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { (detailKeys + "expected").forEach { add(JsonPrimitive(it)) } }
                putJsonObject("properties") {
                    listOf("producer", "appellation", "region", "country", "classification").forEach { putJsonObject(it) { put("type", "string") } }
                    putJsonObject("grapes") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("drinkFrom") { put("type", "integer") }
                    putJsonObject("drinkUntil") { put("type", "integer") }
                    putJsonObject("abv") { put("type", "number") }
                    put("expected", ExpectedRequest.schema)
                }
            }

        private val json = Json { ignoreUnknownKeys = true }

        /** The answer (JSON text, or text holding it); null when it holds no object. */
        fun parse(answer: String, model: String?, language: String, date: Instant): WebCompletion? {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val root = runCatching { json.parseToJsonElement(answer.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
            fun string(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            fun number(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            fun int(key: String) = number(key)?.takeIf { kotlin.math.abs(it) < 100_000 }?.let { Math.round(it).toInt() } ?: 0
            return WebCompletion(
                producer = string("producer"), appellation = string("appellation"), region = string("region"), country = string("country"),
                grapes = (root["grapes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                    .filter { it.isNotEmpty() },
                drinkFrom = int("drinkFrom"), drinkUntil = int("drinkUntil"), abv = number("abv") ?: 0.0,
                classification = string("classification"),
                expected = (root["expected"] as? JsonObject)?.let { ExpectedRequest.profile(it, ExpectedProfile.WEB, model, language, date) },
            )
        }
    }
}
