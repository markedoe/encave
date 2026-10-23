package app.encave.data.ai

import android.util.Base64
import app.encave.core.AiLabelPlacement
import app.encave.core.WineLabelFrame
import app.encave.core.sync.LocalAddress
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
) {
    /** The label's frame for the studio picture, for the photo read. */
    val labelFrame: WineLabelFrame? get() = photoId?.let { id -> placement?.frame(id) }

    companion object {
        private val colors = listOf("red", "white", "rose", "sparkling", "dessert", "fortified", "orange")

        private val detailKeys = listOf("name", "producer", "vintage", "color", "appellation", "region", "country", "grapes", "drinkFrom", "drinkUntil")

        /**
         * The JSON schema of the answer, the same for every provider. With [findsLabel] (the
         * studio look on), it also asks where the label is: "label_box" and "label_angle".
         */
        fun schema(findsLabel: Boolean): JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            // Strict schemas want every key listed: the label's place is null when not found.
            putJsonArray("required") {
                (detailKeys + if (findsLabel) listOf("label_box", "label_angle") else emptyList()).forEach { add(JsonPrimitive(it)) }
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
        fun prompt(findsLabel: Boolean): String = buildList {
            add("This is a photo of a wine label. Read it and return the wine's details.")
            add("- name: the wine or cuvée name as printed (for example the château or domaine and cuvée), without the vintage.")
            add("- producer: the estate, domaine or house, if different from the name; otherwise \"\".")
            add("- vintage: the year on the label, or 0 for a non-vintage wine.")
            add("- color: one of red, white, rose, sparkling, dessert, fortified, orange; \"\" if you cannot tell.")
            add("- appellation, region, country: from the label, or from your knowledge of the appellation. Country in English.")
            add("- grapes: the grape varieties if printed or typical of the appellation; [] if unsure.")
            add("- drinkFrom and drinkUntil: a realistic drinking window in years for this wine and vintage, or 0 if unknown.")
            if (findsLabel) {
                add(
                    "- label_box: where the whole paper front label is in the photo, including its borders and any logo or " +
                        "picture printed on it, as {\"x\", \"y\", \"w\", \"h\"}: fractions from 0 to 1 of the photo's width " +
                        "and height, from its top-left corner; null if you cannot see it.",
                )
                add("- label_angle: the label's slant in degrees, positive when its text runs uphill to the right, 0 when level; null if unknown.")
            }
            add("Use \"\" or 0 for anything you cannot determine. Do not invent a producer or vintage that is not visible.")
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

/** Sends the label photo (a resized JPEG), and nothing else, to the configured provider. */
object AiService {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Sends the label photo to the provider set up, in one request. With [findsLabel] (the
     * studio look on), the same request also asks where the label is in the photo.
     */
    suspend fun analyzeLabel(jpeg: ByteArray, settings: AiSettings, apiKey: String?, findsLabel: Boolean = true): LabelAnalysis {
        if (!settings.isEnabled) throw AiError.NotConfigured
        if (settings.provider.requiresKey && apiKey.isNullOrEmpty()) throw AiError.MissingKey
        if (settings.effectiveModel.isEmpty()) throw AiError.NoModel
        val analysis = when {
            settings.provider.usesAnthropicApi -> claude(jpeg, settings.effectiveModel, apiKey.orEmpty(), settings.effectiveBaseUrl, findsLabel)
            settings.provider.usesOllamaApi -> ollama(jpeg, settings, findsLabel)
            else -> openAiCompatible(jpeg, settings, apiKey, findsLabel)
        }
        // Not asked for: an answer that gives it anyway is not used.
        return if (findsLabel) analysis else analysis.copy(placement = null)
    }

    /** Models that cannot chat: embeddings, speech, image generation, moderation. */
    private val excludedModels = listOf(
        "embed", "tts", "whisper", "dall-e", "audio", "realtime", "moderation", "transcribe",
        "gpt-image", "imagen", "rerank", "speech", "sora", "veo",
    )

    /** Checks the key by listing the models it can use; returns their identifiers. */
    suspend fun models(settings: AiSettings, apiKey: String?): List<String> {
        if (!settings.isEnabled) throw AiError.NotConfigured
        if (settings.provider.requiresKey && apiKey.isNullOrEmpty()) throw AiError.MissingKey
        if (settings.provider.usesOllamaApi) return ollamaModels(settings)
        val url = runCatching {
            URL(settings.effectiveBaseUrl + "/models" + if (settings.provider.usesAnthropicApi) "?limit=100" else "")
        }.getOrElse { throw AiError.NotConfigured }
        val headers = buildMap {
            if (settings.provider.usesAnthropicApi) {
                put("x-api-key", apiKey.orEmpty())
                put("anthropic-version", "2023-06-01")
            } else if (!apiKey.isNullOrEmpty()) {
                put("Authorization", "Bearer $apiKey")
            }
        }
        val json = send(url, headers, body = null, timeout = LIST_TIMEOUT)
        val items = (json["data"] as? JsonArray) ?: (json["models"] as? JsonArray) ?: JsonArray(emptyList())
        // Gemini lists "models/gemini-…"; chat requests take the bare name.
        val ids = items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            ((obj["id"] ?: obj["name"]) as? JsonPrimitive)?.contentOrNull?.replace("models/", "")
        }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
        return ids.filter { id -> excludedModels.none { id.lowercase().contains(it) } }.distinct().sortedWith(collator).reversed()
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
    private suspend fun claude(jpeg: ByteArray, model: String, apiKey: String, baseUrl: String, findsLabel: Boolean): LabelAnalysis {
        val options = claudeOptions(model)
        return try {
            claude(jpeg, model, apiKey, baseUrl, findsLabel, options)
        } catch (error: AiError.Server) {
            // A model or service that does not take these options: once more without them.
            if (error.status != 400 || options == ClaudeOptions(effort = false, fallbacks = false)) throw error
            claude(jpeg, model, apiKey, baseUrl, findsLabel, ClaudeOptions(effort = false, fallbacks = false))
        }
    }

    private suspend fun claude(
        jpeg: ByteArray,
        model: String,
        apiKey: String,
        baseUrl: String,
        findsLabel: Boolean,
        options: ClaudeOptions,
    ): LabelAnalysis {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 8000)
            if (options.fallbacks) put("fallbacks", "default")
            putJsonObject("output_config") {
                if (options.effort) put("effort", "low")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", LabelAnalysis.schema(findsLabel))
                }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64(jpeg))
                            }
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", LabelAnalysis.prompt(findsLabel))
                        })
                    }
                })
            }
        }
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", "2023-06-01")
            // Re-runs a declined request on another model instead of returning a refusal.
            if (options.fallbacks) put("anthropic-beta", "server-side-fallback-2026-07-01")
            put("content-type", "application/json")
        }
        val response = send(URL("$baseUrl/messages"), headers, body, timeout = REQUEST_TIMEOUT)
        if (response["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") throw AiError.Refused
        val text = (response["content"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull ?: throw AiError.InvalidResponse
        return decode(text)
    }

    /** OpenAI-compatible (Chat Completions). Not every service supports a JSON schema: falls back to plain JSON, then to no format. */
    private suspend fun openAiCompatible(jpeg: ByteArray, settings: AiSettings, apiKey: String?, findsLabel: Boolean): LabelAnalysis {
        val formats: List<JsonObject?> = listOf(
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "wine_label")
                    put("strict", true)
                    put("schema", LabelAnalysis.schema(findsLabel))
                }
            },
            buildJsonObject { put("type", "json_object") },
            null,
        )
        var lastError: AiError = AiError.InvalidResponse
        for (format in formats) {
            try {
                return openAiCompatible(jpeg, settings, apiKey, findsLabel, format)
            } catch (error: AiError.Server) {
                if (error.status != 400 && error.status != 422) throw error
                lastError = error
            }
        }
        throw lastError
    }

    private suspend fun openAiCompatible(jpeg: ByteArray, settings: AiSettings, apiKey: String?, findsLabel: Boolean, format: JsonObject?): LabelAnalysis {
        val url = runCatching { URL(settings.effectiveBaseUrl + "/chat/completions") }.getOrElse { throw AiError.NotConfigured }
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            if (format != null) put("response_format", format)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", LabelAnalysis.prompt(findsLabel))
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64,${base64(jpeg)}") }
                        })
                    }
                })
            }
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (!apiKey.isNullOrEmpty()) put("Authorization", "Bearer $apiKey")
        }
        val response = send(url, headers, body, timeout(settings))
        val message = (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
        val refusal = message?.get("refusal")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (!refusal.isNullOrEmpty()) throw AiError.Refused
        val text = (message?.get("content") as? JsonPrimitive)?.contentOrNull ?: throw AiError.InvalidResponse
        return decode(text)
    }

    private const val REQUEST_TIMEOUT = 120_000
    private const val LOCAL_TIMEOUT = 300_000
    private const val LIST_TIMEOUT = 30_000

    /** Local models can take a while, especially the first time they load. */
    private fun timeout(settings: AiSettings) = if (settings.provider.isLocal) LOCAL_TIMEOUT else REQUEST_TIMEOUT

    /** "http://host:11434", whether the user typed it with "/v1" or "/api" or not. */
    private fun ollamaBase(settings: AiSettings): String {
        var base = settings.effectiveBaseUrl
        for (suffix in listOf("/v1", "/api")) base = base.removeSuffix(suffix)
        return base
    }

    /** Ollama's native API: structured output with the label schema, and the photo in "images". */
    private suspend fun ollama(jpeg: ByteArray, settings: AiSettings, findsLabel: Boolean): LabelAnalysis {
        val url = runCatching { URL(ollamaBase(settings) + "/api/chat") }.getOrElse { throw AiError.NotConfigured }
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            put("stream", false)
            put("format", LabelAnalysis.schema(findsLabel))
            // A photo needs more room than Ollama's default context.
            putJsonObject("options") {
                put("temperature", 0)
                put("num_ctx", 8192)
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", LabelAnalysis.prompt(findsLabel))
                    putJsonArray("images") { add(JsonPrimitive(base64(jpeg))) }
                })
            }
        }
        val response = send(url, mapOf("Content-Type" to "application/json"), body, timeout(settings))
        val text = ((response["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
            ?: throw AiError.InvalidResponse
        return decode(text)
    }

    /** Installed models that read images (Ollama reports "vision"); all of them if none reports capabilities. */
    private suspend fun ollamaModels(settings: AiSettings): List<String> = coroutineScope {
        val base = ollamaBase(settings)
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
     */
    internal fun isAllowed(url: URL): Boolean = url.host.isNotEmpty() &&
        (url.protocol == "https" || (url.protocol == "http" && LocalAddress.isLocal(url.host)))

    /** POST when [body] is set, GET otherwise. Every request goes through here. */
    private suspend fun send(url: URL, headers: Map<String, String>, body: JsonObject?, timeout: Int): JsonObject = withContext(Dispatchers.IO) {
        if (!isAllowed(url)) throw AiError.InsecureUrl
        val connection = url.openConnection() as HttpURLConnection
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
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (status !in 200..299) throw AiError.Server(status, errorMessage(parsed))
            parsed ?: throw AiError.InvalidResponse
        } catch (error: IOException) {
            throw AiError.Server(0, error.localizedMessage.orEmpty())
        } finally {
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
        return analysis.copy(placement = AiLabelPlacement.parse(trimmed))
    }
}
