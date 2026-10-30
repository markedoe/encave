package app.encave.data.ai

import android.util.Base64
import app.encave.core.AiLabelPlacement
import app.encave.core.AiLabelSources
import app.encave.core.AiWebSearch
import app.encave.core.ExpectedProfile
import app.encave.core.ExpectedRequest
import app.encave.core.WebCompletion
import app.encave.core.WineIdentity
import app.encave.core.WineWebSource
import app.encave.core.CappedRead
import app.encave.core.Http
import app.encave.core.WineLabelFrame
import app.encave.core.sync.LocalName
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the model read on the label. Empty strings and zeros mean "unknown". */
@Serializable
data class LabelAnalysis(
    val name: String = "",
    val producer: String = "",
    val vintage: Int = 0,
    val color: String = "",
    val appellation: String = "",
    val region: String = "",
    val country: String = "",
    val grapes: List<String> = emptyList(),
    val drinkFrom: Int = 0,
    val drinkUntil: Int = 0,
    /**
     * Where the whole front label is in the photo ("label_box" and "label_angle"), checked
     * when the answer is read; null when absent or invalid, which never fails the answer.
     */
    @Transient val placement: AiLabelPlacement? = null,
    /** The ID of the photo read (its content's hash, as [app.encave.data.PhotoStore] names it). */
    @Transient val photoId: String? = null,
    /**
     * The alcohol by volume printed on the label, in percent; null when not printed or not
     * understood. Read when the answer is read ([AiService.decode]), never failing it.
     */
    @Transient val abv: Double? = null,
    /** A classification printed on the label ("Grand Cru"), "" when none; read as [abv]. */
    @Transient val classification: String = "",
    /**
     * The details the model gave from its own knowledge rather than read on the label
     * ([AiLabelSources] keys), which the form marks "Estimated by AI". Read as [abv].
     */
    @Transient val estimated: Set<String> = setOf("drinkWindow"),
    /** What to expect from the wine, when the request asked it ([schema] with a language). */
    @Transient val expected: ExpectedProfile? = null,
) {
    /** The label's frame for the studio picture, for the photo read. */
    val labelFrame: WineLabelFrame? get() = photoId?.let { id -> placement?.frame(id) }

    companion object {
        private val colors = listOf("red", "white", "rose", "sparkling", "dessert", "fortified", "orange")

        private val detailKeys = listOf(
            "name", "producer", "vintage", "color", "appellation", "region", "country", "grapes", "drinkFrom", "drinkUntil", "abv", "classification",
        )

        /**
         * The JSON schema of the answer, the same for every provider. With [findsLabel] (the
         * studio look on), it also asks where the label is: "label_box" and "label_angle".
         */
        fun schema(findsLabel: Boolean, expectedLanguage: String? = null): JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            // Strict schemas want every key listed: the label's place is null when not found.
            putJsonArray("required") {
                (detailKeys + "sources" + (if (findsLabel) listOf("label_box", "label_angle") else emptyList()) +
                    (if (expectedLanguage != null) listOf("expected") else emptyList())).forEach { add(JsonPrimitive(it)) }
            }
            putJsonObject("properties") {
                listOf("name", "producer").forEach { putJsonObject(it) { put("type", "string") } }
                putJsonObject("vintage") { put("type", "integer") }
                putJsonObject("color") {
                    put("type", "string")
                    putJsonArray("enum") { (listOf("") + colors).forEach { add(JsonPrimitive(it)) } }
                }
                listOf("appellation", "region", "country").forEach { putJsonObject(it) { put("type", "string") } }
                putJsonObject("grapes") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("drinkFrom") { put("type", "integer") }
                putJsonObject("drinkUntil") { put("type", "integer") }
                putJsonObject("abv") { put("type", "number") }
                putJsonObject("classification") { put("type", "string") }
                if (expectedLanguage != null) put("expected", ExpectedRequest.schema)
                // "label", "knowledge" or "" for each detail.
                putJsonObject("sources") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { AiLabelSources.keys.forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("properties") {
                        AiLabelSources.keys.forEach { key ->
                            putJsonObject(key) {
                                put("type", "string")
                                putJsonArray("enum") { listOf("", AiLabelSources.LABEL, AiLabelSources.KNOWLEDGE).forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }
                }
                if (findsLabel) {
                    putJsonObject("label_box") {
                        putJsonArray("anyOf") {
                            add(
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    putJsonArray("required") { listOf("x", "y", "w", "h").forEach { add(JsonPrimitive(it)) } }
                                    putJsonObject("properties") {
                                        listOf("x", "y", "w", "h").forEach { putJsonObject(it) { put("type", "number") } }
                                    }
                                },
                            )
                            add(buildJsonObject { put("type", "null") })
                        }
                    }
                    putJsonObject("label_angle") {
                        putJsonArray("anyOf") {
                            add(buildJsonObject { put("type", "number") })
                            add(buildJsonObject { put("type", "null") })
                        }
                    }
                }
            }
        }

        /** The text sent with the photo; with [findsLabel], it also asks where the label is. */
        fun prompt(findsLabel: Boolean, expectedLanguage: String? = null): String = buildList {
            add("This is a photo of a wine label. Read it and return the wine's details.")
            add("- name: the wine or cuvée name as printed (for example the château or domaine and cuvée), without the vintage.")
            add("- producer: the estate, domaine or house, if different from the name; otherwise \"\".")
            add("- vintage: the year on the label, or 0 for a non-vintage wine.")
            add("- color: one of red, white, rose, sparkling, dessert, fortified, orange; \"\" if you cannot tell.")
            add("- appellation, region, country: from the label, or from your knowledge of the appellation. Country in English.")
            add("- grapes: the grape varieties if printed, or if you know them for this wine or appellation; [] if unsure.")
            add("- drinkFrom and drinkUntil: a realistic drinking window in years for this wine and vintage, or 0 if unknown.")
            add("- abv: the alcohol by volume printed on the label, in percent (for example 13.5), or 0 if not printed.")
            add(
                "- classification: a classification printed on the label (for example Grand Cru, Premier Cru, Cru Bourgeois), " +
                    "or \"\" if none is printed.",
            )
            if (findsLabel) {
                add(
                    "- label_box: where the whole paper front label is in the photo, including its borders and any logo or " +
                        "picture printed on it, as {\"x\", \"y\", \"w\", \"h\"}: fractions from 0 to 1 of the photo's width " +
                        "and height, from its top-left corner; null if you cannot see it.",
                )
                add("- label_angle: the label's slant in degrees, positive when its text runs uphill to the right, 0 when level; null if unknown.")
            }
            if (expectedLanguage != null) addAll(ExpectedRequest.lines(expectedLanguage))
            add(
                "- sources: for each detail, \"label\" if you read it on the label, \"knowledge\" if it comes from what you " +
                    "know rather than from the label (an estimate), \"\" if you left it empty. drinkWindow is the source of " +
                    "drinkFrom and drinkUntil.",
            )
            add(
                "Leave a detail empty (\"\", 0 or []) when you are not sure of it rather than guessing. " +
                    "Do not invent a producer or vintage that is not visible.",
            )
        }.joinToString("\n")
    }
}

sealed class AiError : Exception() {
    data object NotConfigured : AiError()
    data object MissingKey : AiError()
    data object NoModel : AiError()
    data object Refused : AiError()
    data class Server(val status: Int, override val message: String) : AiError()
    data object InvalidResponse : AiError()
    /** Not an https:// address, or plain http:// to a server outside the local network. */
    data object InsecureUrl : AiError()
}

/** One model of one connection, ready to be asked: its key, and the time left for the reading. */
data class AiEndpoint(
    val connection: AiConnection,
    val model: String,
    val apiKey: String?,
    /** When this model's turn ends (System.currentTimeMillis), whatever number of requests it takes. */
    val deadline: Long = Long.MAX_VALUE,
) {
    val provider: AiProvider get() = connection.provider
    val baseUrl: String get() = connection.effectiveBaseUrl

    /** Local models can take a while, especially the first time they load; never past [deadline]. */
    val requestTimeout: Int
        get() = minOf(if (provider.isLocal) 300_000L else 120_000L, deadline - System.currentTimeMillis()).coerceAtLeast(1_000L).toInt()

    override fun toString() = "AiEndpoint(${connection.id}, $model)"
}

/**
 * One question for a model: the text, the schema of the answer, and the label photo when
 * there is one (a question about the wine's identity sends none). With [webSearch], the
 * model may search the web where its connection allows it.
 */
class AiQuery(val prompt: String, val schema: JsonObject, val schemaName: String, val jpeg: ByteArray?, val webSearch: Boolean = false)

/** A model's answer: its text, and the pages it cited when it searched the web. */
data class AiAnswer(val text: String, val sources: List<WineWebSource> = emptyList())

/** A label read: what it says, and which model of which connection read it. */
data class AiReading(val analysis: LabelAnalysis, val model: AiModelChoice, val connection: AiConnection)

/** Sends a request (POST with a body, GET without) and reads its JSON answer; replaced in tests. */
typealias AiTransport = suspend (url: URL, headers: Map<String, String>, body: JsonObject?, timeout: Int) -> JsonObject

/** Sends the label photo (a resized JPEG), and nothing else, to the configured provider. */
object AiService {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Every request goes through here; tests replace it. */
    @androidx.annotation.VisibleForTesting
    internal var transport: AiTransport = ::httpSend

    /**
     * Sends the label photo to the first model of [configuration]; if it fails, to the next,
     * one at a time, within [AiFallback.TOTAL_BUDGET_MS]. With [only], to that model. With
     * [findsLabel] (the studio look on), the same request also asks where the label is.
     */
    suspend fun analyzeLabel(
        jpeg: ByteArray,
        configuration: AiConfiguration,
        keys: (AiConnection) -> String?,
        only: String? = null,
        findsLabel: Boolean = true,
        expectedLanguage: String? = null,
    ): AiReading {
        val order = configuration.readingOrder(only)
        if (order.isEmpty()) throw AiError.NotConfigured
        val (model, analysis) = try {
            AiFallback.read(order) { model, left ->
                val connection = configuration.connection(model) ?: throw AiError.NotConfigured
                val key = withContext(Dispatchers.IO) { keys(connection) }
                // The whole turn of a model, every request included, ends with its time.
                withTimeoutOrNull(left) {
                    analyze(jpeg, AiEndpoint(connection, model.model, key, System.currentTimeMillis() + left), findsLabel, expectedLanguage)
                } ?: throw AiError.Server(0, "")
            }
        } catch (error: AiAllModelsFailed) {
            // One model: its own error, as before.
            if (error.failures.size == 1 && error.untried.isEmpty()) throw error.failures.first().error
            throw error
        }
        return AiReading(analysis, model, configuration.connection(model) ?: throw AiError.NotConfigured)
    }

    /** One model, one request (two or three for a service that does not take every format). */
    suspend fun analyze(jpeg: ByteArray, endpoint: AiEndpoint, findsLabel: Boolean, expectedLanguage: String? = null): LabelAnalysis {
        val query = AiQuery(LabelAnalysis.prompt(findsLabel, expectedLanguage), LabelAnalysis.schema(findsLabel, expectedLanguage), "wine_label", jpeg)
        val answer = ask(query, endpoint)
        val analysis = decode(answer.text)
        val expected = expectedLanguage?.let {
            ExpectedRequest.profileFromAnswer(answer.text, ExpectedProfile.AI, endpoint.model, it, java.time.Instant.now())
        }
        // Not asked for: an answer that gives it anyway is not used.
        return analysis.copy(placement = if (findsLabel) analysis.placement else null, expected = expected)
    }

    /** Sends a query to one model: the photo when there is one, the schema of the answer, and nothing else. */
    suspend fun ask(query: AiQuery, endpoint: AiEndpoint): AiAnswer {
        if (endpoint.provider.requiresKey && endpoint.apiKey.isNullOrEmpty()) throw AiError.MissingKey
        if (endpoint.model.isBlank()) throw AiError.NoModel
        return when {
            query.webSearch && query.jpeg == null && endpoint.connection.webSearch == AiWebSearch.Kind.ResponsesTool -> responses(query, endpoint)
            endpoint.provider.usesAnthropicApi -> claude(query, endpoint)
            endpoint.provider.usesOllamaApi -> ollama(query, endpoint)
            else -> openAiCompatible(query, endpoint)
        }
    }

    /**
     * What to expect from a wine, from its identity alone (producer, name, appellation,
     * vintage, colour, grapes): never the photo, the cellar, notes, prices or places. The
     * models in order, as for a label. A null profile when the model did not know the wine.
     */
    suspend fun expectedProfile(
        identity: WineIdentity,
        language: String,
        configuration: AiConfiguration,
        keys: (AiConnection) -> String?,
        only: String? = null,
    ): Pair<ExpectedProfile?, AiModelChoice> {
        val query = AiQuery(ExpectedRequest.prompt(identity, language), ExpectedRequest.answerSchema, "wine_expected", null)
        val (model, answer) = askInOrder(query, configuration, keys, only)
        return ExpectedRequest.profileFromAnswer(answer.text, ExpectedProfile.AI, model.model, language, java.time.Instant.now()) to model
    }

    /**
     * Completes a saved wine from the web: the models whose connection allows web search, in
     * order, are sent only the wine's identity and may search the web with it. Never a photo,
     * the cellar, notes, prices or places. Null when the answer could not be read.
     */
    suspend fun completeFromWeb(
        identity: WineIdentity,
        language: String,
        configuration: AiConfiguration,
        keys: (AiConnection) -> String?,
    ): Pair<WebCompletion, List<WineWebSource>>? {
        val order = configuration.webSearchOrder
        if (order.isEmpty()) throw AiError.NotConfigured
        val webOnly = configuration.copy(models = order)
        val query = AiQuery(WebCompletion.prompt(identity, language), WebCompletion.schema, "wine_web", null, webSearch = true)
        val (model, answer) = askInOrder(query, webOnly, keys, null)
        val completion = WebCompletion.parse(answer.text, model.model, language, java.time.Instant.now()) ?: return null
        return completion to answer.sources
    }

    /** The models in order, each within its time, until one answers. */
    internal suspend fun askInOrder(query: AiQuery, configuration: AiConfiguration, keys: (AiConnection) -> String?, only: String?): Pair<AiModelChoice, AiAnswer> {
        val order = configuration.readingOrder(only)
        if (order.isEmpty()) throw AiError.NotConfigured
        return try {
            AiFallback.read(order) { model, left ->
                val connection = configuration.connection(model) ?: throw AiError.NotConfigured
                val key = withContext(Dispatchers.IO) { keys(connection) }
                withTimeoutOrNull(left) {
                    ask(query, AiEndpoint(connection, model.model, key, System.currentTimeMillis() + left))
                } ?: throw AiError.Server(0, "")
            }
        } catch (error: AiAllModelsFailed) {
            if (error.failures.size == 1 && error.untried.isEmpty()) throw error.failures.first().error
            throw error
        }
    }

    /**
     * The "Test" of a model: its key works and it answers on a picture of a made-up label
     * bundled with the app ([testImage]). No photo or data of the user is sent.
     */
    suspend fun test(connection: AiConnection, model: String, apiKey: String?, testImage: ByteArray) {
        analyze(testImage, AiEndpoint(connection, model, apiKey), findsLabel = false)
    }

    /** Models that cannot chat: embeddings, speech, image generation, moderation. */
    private val excludedModels = listOf(
        "embed", "tts", "whisper", "dall-e", "audio", "realtime", "moderation", "transcribe",
        "gpt-image", "imagen", "rerank", "speech", "sora", "veo",
    )

    /** Checks the key by listing the models it can use; returns their identifiers. */
    suspend fun models(connection: AiConnection, apiKey: String?): List<String> {
        val provider = connection.provider
        if (provider.requiresKey && apiKey.isNullOrEmpty()) throw AiError.MissingKey
        if (provider.usesOllamaApi) return ollamaModels(connection.effectiveBaseUrl)
        val url = runCatching {
            URL(connection.effectiveBaseUrl + "/models" + if (provider.usesAnthropicApi) "?limit=100" else "")
        }.getOrElse { throw AiError.NotConfigured }
        val headers = buildMap {
            if (provider.usesAnthropicApi) {
                put("x-api-key", apiKey.orEmpty())
                put("anthropic-version", "2023-06-01")
            } else if (!apiKey.isNullOrEmpty()) {
                put("Authorization", "Bearer $apiKey")
            }
        }
        val json = send(url, headers, body = null, timeout = LIST_TIMEOUT)
        val items = (json["data"] as? JsonArray) ?: (json["models"] as? JsonArray) ?: JsonArray(emptyList())
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        // Through OpenRouter, only the models that read images (a server at another address
        // that does not describe its models: all of them).
        if (provider == AiProvider.OpenRouter && AiRequestBody.hasArchitecture(items)) {
            return AiRequestBody.openRouterVisionModels(items).distinct().sortedWith(collator)
        }
        // Models that can chat (no embeddings, speech, image generation…) and read a label
        // photo; Gemini's "models/…" prefix is dropped.
        val chat = items.mapNotNull { it as? JsonObject }.filter { obj ->
            val id = ((obj["id"] ?: obj["name"]) as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            excludedModels.none { id.contains(it) }
        }
        return AiRequestBody.imageModels(chat, provider).distinct().sortedWith(collator).reversed()
    }

    private fun base64(jpeg: ByteArray) = Base64.encodeToString(jpeg, Base64.NO_WRAP)

    /** Request options a Claude model takes, from Anthropic's model documentation. */
    internal data class ClaudeOptions(val effort: Boolean, val fallbacks: Boolean)

    /** Models (and their dated versions) that take `output_config.effort`. */
    private val effortModels = listOf(
        "claude-opus-4-5", "claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5",
        "claude-sonnet-4-6", "claude-sonnet-5", "claude-haiku-5", "claude-fable-5", "claude-mythos-5",
    )

    /** Models that re-run a declined request on another model (`fallbacks: "default"`). */
    private val fallbackModels = listOf("claude-fable-5", "claude-mythos-5-1", "claude-opus-5", "claude-sonnet-5-5")

    internal fun claudeOptions(model: String): ClaudeOptions {
        val id = model.trim().lowercase(java.util.Locale.ROOT)
        fun among(list: List<String>) = list.any { id == it || id.startsWith("$it-") }
        return ClaudeOptions(effort = among(effortModels), fallbacks = among(fallbackModels))
    }

    /** Claude (Anthropic Messages API). */
    private suspend fun claude(query: AiQuery, endpoint: AiEndpoint): AiAnswer {
        val options = claudeOptions(endpoint.model)
        return try {
            claude(query, endpoint, options)
        } catch (error: AiError.Server) {
            // A model or service that does not take these options: once more without them.
            if (error.status != 400 || options == ClaudeOptions(effort = false, fallbacks = false)) throw error
            claude(query, endpoint, ClaudeOptions(effort = false, fallbacks = false))
        }
    }

    private suspend fun claude(query: AiQuery, endpoint: AiEndpoint, options: ClaudeOptions): AiAnswer {
        val body = AiRequestBody.claude(endpoint.model, query.prompt, query.schema, query.jpeg?.let(::base64), query.webSearch, options.effort, options.fallbacks)
        val headers = buildMap {
            put("x-api-key", endpoint.apiKey.orEmpty())
            put("anthropic-version", "2023-06-01")
            // Re-runs a declined request on another model instead of returning a refusal.
            if (options.fallbacks) put("anthropic-beta", "server-side-fallback-2026-07-01")
            put("content-type", "application/json")
        }
        val response = send(URL("${endpoint.baseUrl}/messages"), headers, body, timeout = endpoint.requestTimeout)
        if (response["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw AiError.Refused
        val blocks = (response["content"] as? JsonArray) ?: JsonArray(emptyList())
        val texts = blocks.mapNotNull { it as? JsonObject }.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        // With web search, the answer is the last text, after the searches.
        val text = (if (query.webSearch) texts.lastOrNull { it.contains('{') } ?: texts.lastOrNull() else texts.firstOrNull())
            ?: throw AiError.InvalidResponse
        return AiAnswer(text, AiWebSearch.claude(blocks))
    }

    /** OpenAI and xAI with their web search tool (Responses API): text only. */
    private suspend fun responses(query: AiQuery, endpoint: AiEndpoint): AiAnswer {
        val url = runCatching { URL(endpoint.baseUrl + "/responses") }.getOrElse { throw AiError.NotConfigured }
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (!endpoint.apiKey.isNullOrEmpty()) put("Authorization", "Bearer ${endpoint.apiKey}")
        }
        val response = send(url, headers, AiRequestBody.responses(endpoint.model, query.prompt, query.schema), endpoint.requestTimeout)
        val (text, sources) = AiWebSearch.responses(response)
        return AiAnswer(text ?: throw AiError.InvalidResponse, sources)
    }

    /** OpenAI-compatible (Chat Completions). Not every service supports a JSON schema: falls back to plain JSON, then to no format. */
    private suspend fun openAiCompatible(query: AiQuery, endpoint: AiEndpoint): AiAnswer {
        val formats: List<JsonObject?> = listOf(
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", query.schemaName)
                    put("strict", true)
                    put("schema", query.schema)
                }
            },
            buildJsonObject { put("type", "json_object") },
            null,
        )
        var lastError: AiError = AiError.InvalidResponse
        for (format in formats) {
            try {
                return openAiCompatible(query, endpoint, format)
            } catch (error: AiError.Server) {
                if (error.status != 400 && error.status != 422) throw error
                lastError = error
            }
        }
        throw lastError
    }

    private suspend fun openAiCompatible(query: AiQuery, endpoint: AiEndpoint, format: JsonObject?): AiAnswer {
        val url = runCatching { URL(endpoint.baseUrl + "/chat/completions") }.getOrElse { throw AiError.NotConfigured }
        val body = AiRequestBody.chatCompletions(endpoint.connection, endpoint.model, query.prompt, query.jpeg?.let(::base64), format, query.webSearch)
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (!endpoint.apiKey.isNullOrEmpty()) put("Authorization", "Bearer ${endpoint.apiKey}")
        }
        val response = send(url, headers, body, endpoint.requestTimeout)
        val message = (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
        val refusal = message?.get("refusal")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (!refusal.isNullOrEmpty()) throw AiError.Refused
        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull ?: throw AiError.InvalidResponse
        return AiAnswer(text, AiWebSearch.chatCompletions(message))
    }

    /** A Chat Completions request for a label; through OpenRouter, with the provider preferences that refuse providers keeping data. */
    internal fun chatCompletionsBody(endpoint: AiEndpoint, findsLabel: Boolean, jpegBase64: String, format: JsonObject?): JsonObject =
        AiRequestBody.chatCompletions(endpoint.connection, endpoint.model, LabelAnalysis.prompt(findsLabel), jpegBase64, format, webSearch = false)

    private const val LIST_TIMEOUT = 30_000

    /** "http://host:11434", whether the user typed it with "/v1" or "/api" or not. */
    private fun ollamaBase(baseUrl: String): String {
        var base = baseUrl
        for (suffix in listOf("/v1", "/api")) base = base.removeSuffix(suffix)
        return base
    }

    /** Ollama's native API: structured output with the answer's schema, and the photo in "images". */
    private suspend fun ollama(query: AiQuery, endpoint: AiEndpoint): AiAnswer {
        val url = runCatching { URL(ollamaBase(endpoint.baseUrl) + "/api/chat") }.getOrElse { throw AiError.NotConfigured }
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("stream", false)
            put("format", query.schema)
            // A photo needs more room than Ollama's default context.
            putJsonObject("options") {
                put("temperature", 0)
                put("num_ctx", 8192)
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", query.prompt)
                    query.jpeg?.let { jpeg -> putJsonArray("images") { add(JsonPrimitive(base64(jpeg))) } }
                })
            }
        }
        val response = send(url, mapOf("Content-Type" to "application/json"), body, endpoint.requestTimeout)
        val text = ((response["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
            ?: throw AiError.InvalidResponse
        return AiAnswer(text)
    }

    /** Installed models that read images (Ollama reports "vision"); all of them if none reports capabilities. */
    private suspend fun ollamaModels(baseUrl: String): List<String> = coroutineScope {
        val base = ollamaBase(baseUrl)
        val tags = send(runCatching { URL("$base/api/tags") }.getOrElse { throw AiError.NotConfigured }, emptyMap(), null, LIST_TIMEOUT)
        val names = (tags["models"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ((obj["name"] ?: obj["model"]) as? JsonPrimitive)?.contentOrNull
        }
        val capabilities = names.map { name ->
            async {
                name to runCatching {
                    val show = send(
                        URL("$base/api/show"), mapOf("Content-Type" to "application/json"),
                        buildJsonObject { put("model", name) }, LIST_TIMEOUT,
                    )
                    (show["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                }.getOrNull()
            }
        }.awaitAll().toMap()
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        val sorted = names.sortedWith(collator)
        val vision = sorted.filter { capabilities[it]?.contains("vision") == true }
        // Older Ollama versions do not report capabilities: then every model is offered.
        vision.ifEmpty { sorted }
    }

    /**
     * HTTPS, or plain HTTP only for a server on this device or the local network, as for
     * web search and WebDAV: elsewhere anyone on the way would read the key and the photo.
     * A name over http is checked and replaced by its local address in [send] ([LocalName]).
     */
    internal fun isAllowed(url: URL): Boolean = url.host.isNotEmpty() &&
        (url.protocol == "https" || (url.protocol == "http" && LocalName.mayBeLocal(url.host)))

    /** Every request goes through [transport]. */
    private suspend fun send(url: URL, headers: Map<String, String>, body: JsonObject?, timeout: Int): JsonObject {
        // Checked before any transport: a key or a photo never goes over plain HTTP to the internet.
        if (!isAllowed(url)) throw AiError.InsecureUrl
        return transport(url, headers, body, timeout)
    }

    /** POST when [body] is set, GET otherwise. */
    private suspend fun httpSend(url: URL, headers: Map<String, String>, body: JsonObject?, timeout: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!isAllowed(url)) throw AiError.InsecureUrl
        val connection = Http.openChecked(LocalName.resolve(url))
        // Cancelled (another photo, the form closed, the model's time up): the connection is
        // closed at once, so a request never goes on while the next model is asked.
        val closeOnCancel = launch { try { awaitCancellation() } finally { connection.disconnect() } }
        try {
            // A redirect could take the key to another server.
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body != null) "POST" else "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = timeout
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { CappedRead.bytes(it, connection.contentLengthLong, CappedRead.JSON_LIMIT).toString(Charsets.UTF_8) }.orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (status !in 200..299) throw AiError.Server(status, errorMessage(parsed))
            parsed ?: throw AiError.InvalidResponse
        } catch (error: IOException) {
            throw AiError.Server(0, error.localizedMessage.orEmpty())
        } finally {
            closeOnCancel.cancel()
            connection.disconnect()
        }
    }

    /** {"error":{"message":…}} (OpenAI, Anthropic), {"error":"…"} (Ollama), {"detail":…} (vLLM) or {"message":…}. */
    internal fun errorMessage(json: JsonObject?): String {
        fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        val error = json?.get("error")
        return (error as? JsonObject)?.get("message").text() ?: error.text() ?: json?.get("detail").text() ?: json?.get("message").text() ?: ""
    }

    /** Parses the JSON answer, tolerating a Markdown code fence and a reasoning model's "<think>…</think>". */
    fun decode(text: String): LabelAnalysis {
        var trimmed = text.substringAfter("</think>").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) trimmed = trimmed.substring(start, end + 1)
        val analysis = runCatching { json.decodeFromString(LabelAnalysis.serializer(), trimmed) }.getOrElse { throw AiError.InvalidResponse }
        val fields = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        return analysis.copy(
            placement = AiLabelPlacement.parse(trimmed),
            abv = abv(fields?.get("abv")),
            classification = (fields?.get("classification") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty(),
            estimated = AiLabelSources.estimated(trimmed),
        )
    }

    /**
     * The alcohol from the answer: a number, or text such as "13,5 %" from a model that did
     * not follow the schema; null for 0 (not printed), anything outside 0.5…25, or no number.
     */
    internal fun abv(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        val text = primitive.content.replace("%", "").replace("vol", "", ignoreCase = true).replace(',', '.').trim()
        val value = text.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it in 0.5..25.0 }
    }
}
