package app.encave.data.ai

import app.encave.core.AiWebSearch
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A way to reach an AI service: its provider, an optional name ("OpenRouter perso") and
 * server address. Several per provider are allowed. Its key is not here: it stays in
 * [SecretStore] on this device ([keyName]), never synced, exported or backed up.
 */
@Serializable
data class AiConnection(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("provider") val providerRaw: String,
    val name: String = "",
    /** Empty: the provider's usual address. */
    val baseUrl: String = "",
    /** OpenRouter only. False (the default): requests refuse providers that keep or train on data. */
    val allowsDataCollection: Boolean = false,
    /**
     * Off by default: "Allow web search", for a provider whose API searches the web itself
     * ([webSearch]). Used only to complete a saved wine from the web, never for a label.
     */
    val allowsWebSearch: Boolean = false,
) {
    constructor(provider: AiProvider, name: String = "", baseUrl: String = "", allowsDataCollection: Boolean = false) :
        this(UUID.randomUUID().toString(), provider.raw, name, baseUrl, allowsDataCollection)

    /** How this connection's API searches the web, null when it cannot: by its provider, at the provider's own address. */
    val webSearch: app.encave.core.AiWebSearch.Kind?
        get() {
            val kind = provider.webSearch ?: return null
            fun host(url: String) = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
            return if (host(effectiveBaseUrl) == host(provider.defaultBaseUrl)) kind else null
        }

    /** Web search is allowed and possible. */
    val searchesWeb: Boolean get() = allowsWebSearch && webSearch != null

    /** A provider of a newer version is left out when the configuration is read. */
    val provider: AiProvider get() = AiProvider.fromRaw(providerRaw) ?: AiProvider.Local

    val effectiveBaseUrl: String get() = baseUrl.trim().ifEmpty { provider.defaultBaseUrl }.removeSuffix("/")

    /** Where its key is in [SecretStore]. */
    val keyName: String get() = "ai.connection.$id"

    /** Every request through OpenRouter refuses providers that keep or train on data, unless allowed here. */
    val deniesDataCollection: Boolean
        get() {
            // A connection of another kind pointed at OpenRouter's address is treated the same.
            val host = runCatching { java.net.URI(effectiveBaseUrl).host?.lowercase() }.getOrNull().orEmpty()
            val isOpenRouter = provider == AiProvider.OpenRouter || host == "openrouter.ai" || host.endsWith(".openrouter.ai")
            return isOpenRouter && !allowsDataCollection
        }

    fun withProvider(provider: AiProvider) = copy(providerRaw = provider.raw)
}

/** A model of a connection, in the user's order of preference. */
@Serializable
data class AiModelChoice(
    val id: String = UUID.randomUUID().toString(),
    val connectionId: String,
    val model: String,
)

/** The connections and the ordered models that read label photos; on when the list has a model. Never any key. */
@Serializable
data class AiConfiguration(
    val connections: List<AiConnection> = emptyList(),
    val models: List<AiModelChoice> = emptyList(),
) {
    val isEnabled: Boolean get() = usableModels.isNotEmpty()

    val usableModels: List<AiModelChoice> get() = models.filter { connection(it) != null && it.model.isNotBlank() }

    fun connection(model: AiModelChoice): AiConnection? = connections.firstOrNull { it.id == model.connectionId }

    fun connection(id: String): AiConnection? = connections.firstOrNull { it.id == id }

    fun modelsOf(connectionId: String): List<AiModelChoice> = models.filter { it.connectionId == connectionId }

    /** Without the connection and its models; its key is removed by the caller. */
    fun removingConnection(id: String) = copy(connections = connections.filter { it.id != id }, models = models.filter { it.connectionId != id })

    fun upserting(connection: AiConnection): AiConfiguration =
        if (connections.any { it.id == connection.id }) {
            copy(connections = connections.map { if (it.id == connection.id) connection else it })
        } else {
            copy(connections = connections + connection)
        }

    /** With the model at the end of the list, unless the same one is in it already. */
    fun addingModel(model: String, connectionId: String): AiConfiguration {
        val name = model.trim()
        if (name.isEmpty() || connection(connectionId) == null || models.any { it.connectionId == connectionId && it.model == name }) return this
        return copy(models = models + AiModelChoice(connectionId = connectionId, model = name))
    }

    /** With the model at [from] moved to [to]. */
    fun movingModel(from: Int, to: Int): AiConfiguration {
        if (from !in models.indices || to !in models.indices || from == to) return this
        val list = models.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(models = list)
    }

    fun removingModel(id: String) = copy(models = models.filter { it.id != id })

    /** The models to try for a reading: all in order, or the one the user picked. */
    fun readingOrder(only: String? = null): List<AiModelChoice> = usableModels.filter { only == null || it.id == only }
}

/** What a key read found. A failure (the Keystore) is not "missing": such a key is never replaced or lost. */
sealed interface KeyRead {
    data class Found(val key: String) : KeyRead
    data object Missing : KeyRead
    data object Failed : KeyRead
}

/** [SecretStore] in the app; a map in tests. */
interface AiKeyStore {
    fun readKey(name: String): KeyRead

    /** Null or empty deletes. Returns whether it was stored (or deleted). */
    fun writeKey(name: String, value: String?): Boolean

    fun key(connection: AiConnection): String? = (readKey(connection.keyName) as? KeyRead.Found)?.key?.let(AiPreferences::cleanKey)

    fun setKey(connection: AiConnection, key: String?): Boolean = writeKey(connection.keyName, key?.let(AiPreferences::cleanKey))
}

/** The Keystore-encrypted [SecretStore] as an [AiKeyStore]. */
class SecretKeys(private val secrets: SecretStore) : AiKeyStore {
    override fun readKey(name: String): KeyRead = runCatching { secrets.read(name) }
        .fold({ it?.let(KeyRead::Found) ?: KeyRead.Missing }, { KeyRead.Failed })

    /** Written to disk before returning, so a key is never removed from its old place before it is in its new one. */
    override fun writeKey(name: String, value: String?): Boolean = runCatching { secrets.set(name, value, commit = true) }.isSuccess
}

/** The configuration in preferences ("configuration" in "ai"), and the settings of versions with one provider. */
object AiConfigurationStore {
    const val KEY = "configuration"

    /** Set once the settings of earlier versions are moved ([migrate]). */
    const val MIGRATED = "configuration.migrated"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The saved configuration; the first time, the earlier provider, model, address and keys
     * moved into it. If that could not be done yet (a key could not be read), it is tried
     * again at the next load, keeping what the user set up meanwhile.
     */
    fun load(prefs: SharedPreferences, keys: AiKeyStore): AiConfiguration {
        val saved = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
        if (prefs.getBoolean(MIGRATED, false)) return saved ?: AiConfiguration()
        return migrate(prefs, keys, saved ?: AiConfiguration()) ?: saved ?: AiConfiguration()
    }

    /** [commit]: written before returning (the migration removes the old keys only then). */
    fun save(prefs: SharedPreferences, configuration: AiConfiguration, commit: Boolean = false) =
        prefs.edit(commit = commit) { putString(KEY, json.encodeToString(AiConfiguration.serializer(), configuration)) }

    /** A connection of a newer version (an unknown provider) is left out, with its models. */
    internal fun decode(text: String): AiConfiguration {
        val root = json.parseToJsonElement(text).jsonObject
        fun <T> list(key: String, read: (JsonElement) -> T): List<T> =
            (root[key] as? JsonArray).orEmpty().mapNotNull { runCatching { read(it) }.getOrNull() }
        val connections = list("connections") { json.decodeFromJsonElement(AiConnection.serializer(), it) }
            .filter { AiProvider.fromRaw(it.providerRaw) != null && AiProvider.fromRaw(it.providerRaw) != AiProvider.Off }
        val ids = connections.map { it.id }.toSet()
        val models = list("models") { json.decodeFromJsonElement(AiModelChoice.serializer(), it) }.filter { it.connectionId in ids }
        return AiConfiguration(connections, models)
    }

    /** The provider, model and address of versions with one provider, including the names of the first test builds. */
    internal fun legacySettings(prefs: SharedPreferences): AiSettings {
        val raw = prefs.getString("provider", "off")
        val legacyUrl = prefs.getString("baseURL", "").orEmpty()
        val provider = when (raw) {
            "claude" -> AiProvider.Anthropic
            "openAICompatible" -> if (legacyUrl.isEmpty() || legacyUrl.contains("api.openai.com")) AiProvider.OpenAi else AiProvider.Local
            else -> AiProvider.fromRaw(raw) ?: AiProvider.Off
        }.let { chosen ->
            // The first "local" option pointed at Ollama by default.
            val localUrl = prefs.getString("baseURL.local", null) ?: legacyUrl
            if (raw == "local" && (localUrl.isEmpty() || localUrl.contains(":11434"))) AiProvider.Ollama else chosen
        }
        val model = prefs.getString("model.${provider.raw}", null) ?: prefs.getString("model", "").orEmpty()
        var baseUrl = prefs.getString("baseURL.${provider.raw}", null) ?: if (provider == AiProvider.Local) legacyUrl else ""
        if (raw == "local" && provider == AiProvider.Ollama && baseUrl.isEmpty()) {
            // The address of a server set up with the first builds, without the "/v1".
            val old = (prefs.getString("baseURL.local", null) ?: legacyUrl).removeSuffix("/v1")
            baseUrl = if (old == AiProvider.Ollama.defaultBaseUrl) "" else old
        }
        return AiSettings(provider, model, baseUrl)
    }

    private fun legacyKeyNames(provider: AiProvider) =
        if (provider == AiProvider.Anthropic) listOf("ai.apiKey.anthropic", "ai.apiKey") else listOf("ai.apiKey.${provider.raw}")

    /**
     * Moves the settings of versions with one provider: the provider in use becomes the first
     * connection, with its model first in the list; every other provider that had a key gets
     * a connection too, so no key is lost. Keys are copied, the configuration saved, and only
     * then the old keys removed. Null when a key could not be read or written: nothing
     * changes, it is tried again next time.
     */
    fun migrate(prefs: SharedPreferences, keys: AiKeyStore, existing: AiConfiguration = AiConfiguration()): AiConfiguration? {
        val legacy = legacySettings(prefs)
        val found = mutableMapOf<AiProvider, String>()
        val oldNames = mutableListOf<String>()
        for (provider in AiProvider.entries.filter { it != AiProvider.Off }) {
            for (name in legacyKeyNames(provider)) {
                when (val read = keys.readKey(name)) {
                    is KeyRead.Found -> {
                        oldNames += name
                        val clean = AiPreferences.cleanKey(read.key)
                        if (clean.isNotEmpty()) found.putIfAbsent(provider, clean)
                    }
                    KeyRead.Missing -> Unit
                    KeyRead.Failed -> return null
                }
            }
        }
        var configuration = AiConfiguration()
        if (legacy.isEnabled) {
            val connection = AiConnection(migratedId(legacy.provider), legacy.provider.raw, baseUrl = legacy.baseUrl)
            configuration = configuration.upserting(connection).addingModel(legacy.effectiveModel, connection.id)
        }
        for (provider in AiProvider.entries) {
            if (provider == legacy.provider || provider !in found) continue
            val baseUrl = prefs.getString("baseURL.${provider.raw}", "").orEmpty()
            configuration = configuration.upserting(AiConnection(migratedId(provider), provider.raw, baseUrl = baseUrl))
        }
        val written = mutableListOf<AiConnection>()
        for (connection in configuration.connections) {
            val key = found[connection.provider] ?: continue
            if (!keys.setKey(connection, key)) {
                written.forEach { keys.setKey(it, null) }
                return null
            }
            written += connection
        }
        // What was set up before the move could be made stays, after the earlier settings.
        val earlier = configuration.connections.map { it.id }.toSet()
        configuration = configuration.copy(
            connections = configuration.connections + existing.connections.filter { it.id !in earlier },
            models = configuration.models + existing.models.filter { model ->
                configuration.models.none { it.connectionId == model.connectionId && it.model == model.model }
            },
        )
        save(prefs, configuration, commit = true)
        prefs.edit(commit = true) { putBoolean(MIGRATED, true) }
        oldNames.forEach { keys.writeKey(it, null) }
        return configuration
    }

    /** The same id for a provider's moved connection each time the move is tried: an interrupted move writes its key at the same place. */
    internal fun migratedId(provider: AiProvider): String = UUID.nameUUIDFromBytes("encave.ai.migrated.${provider.raw}".toByteArray()).toString()
}

/** One model that could not read the label, and why. */
data class AiModelFailure(val model: AiModelChoice, val error: Throwable)

/** Every model was tried and none could read the label: what failed, per model; [untried] when the time ran out. */
data class AiAllModelsFailed(val failures: List<AiModelFailure>, val untried: List<AiModelChoice>) : Exception()

object AiFallback {
    /** A whole reading, every model included: long enough for a local model that loads. */
    const val TOTAL_BUDGET_MS = 360_000L

    /** A model is not started with less time than this left. */
    const val MINIMUM_ATTEMPT_MS = 15_000L

    /**
     * Tries the models in order, one at a time and each at most once, until one answers. Any
     * failure moves on to the next (network, timeout, a server that cannot be reached,
     * 401/402/403/429/5xx, a refusal, an answer that cannot be read); cancelling stops.
     * [attempt] gets the time left for its request, in milliseconds.
     */
    suspend fun <T> read(
        models: List<AiModelChoice>,
        budgetMs: Long = TOTAL_BUDGET_MS,
        now: () -> Long = System::currentTimeMillis,
        attempt: suspend (AiModelChoice, Long) -> T,
    ): Pair<AiModelChoice, T> {
        val deadline = now() + budgetMs
        val failures = mutableListOf<AiModelFailure>()
        models.forEachIndexed { index, model ->
            currentCoroutineContext().ensureActive()
            val left = deadline - now()
            if (left < MINIMUM_ATTEMPT_MS && failures.isNotEmpty()) throw AiAllModelsFailed(failures, models.drop(index))
            try {
                return model to attempt(model, maxOf(left, MINIMUM_ATTEMPT_MS))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failures += AiModelFailure(model, error)
            }
        }
        throw AiAllModelsFailed(failures, emptyList())
    }
}

object AiRequestBody {
    /** OpenRouter's provider preferences: `data_collection: "deny"` (openrouter.ai/docs, provider routing). */
    fun openRouterProvider(connection: AiConnection): JsonObject? =
        if (connection.deniesDataCollection) buildJsonObject { put("data_collection", "deny") } else null

    /** Of OpenRouter's model list, the models that read images (`architecture.input_modalities` holds "image"). */
    fun openRouterVisionModels(items: JsonArray): List<String> = items.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val modalities = ((obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { "image" in modalities }
    }

    internal fun hasArchitecture(items: JsonArray) = items.any { (it as? JsonObject)?.get("architecture") != null }

    /**
     * A Chat Completions request with the prompt and the photo (none for a question about the
     * wine's identity); [format] is the response_format, null to send none. With [webSearch],
     * through OpenRouter only, its web plugin. As CaveKit's `AIRequestBody.chatCompletions`.
     */
    fun chatCompletions(connection: AiConnection, model: String, prompt: String, jpegBase64: String?, format: JsonObject?, webSearch: Boolean = false): JsonObject =
        buildJsonObject {
            put("model", model)
            if (format != null) put("response_format", format)
            openRouterProvider(connection)?.let { put("provider", it) }
            if (webSearch && connection.webSearch == AiWebSearch.Kind.OpenRouterPlugin) {
                putJsonArray("plugins") { add(buildJsonObject { put("id", "web"); put("max_results", 5) }) }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        if (jpegBase64 != null) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$jpegBase64") }
                            })
                        }
                    }
                })
            }
        }

    /**
     * The Anthropic Messages request: the photo when there is one, then the prompt. With
     * [webSearch], the web search tool (at most 5 searches) and no output format, the JSON
     * object asked in the prompt instead. As CaveKit's `AIRequestBody.claude`.
     */
    fun claude(model: String, prompt: String, schema: JsonObject, jpegBase64: String?, webSearch: Boolean, effort: Boolean, fallbacks: Boolean): JsonObject =
        buildJsonObject {
            put("model", model)
            put("max_tokens", 8000)
            if (fallbacks) put("fallbacks", "default")
            if (!webSearch || effort) {
                putJsonObject("output_config") {
                    // Reading a label is a simple task.
                    if (effort) put("effort", "low")
                    if (!webSearch) {
                        putJsonObject("format") {
                            put("type", "json_schema")
                            put("schema", schema)
                        }
                    }
                }
            }
            if (webSearch) {
                putJsonArray("tools") {
                    add(buildJsonObject { put("type", "web_search_20250305"); put("name", "web_search"); put("max_uses", 5) })
                }
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (jpegBase64 != null) {
                            add(buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", jpegBase64)
                                }
                            })
                        }
                        add(buildJsonObject { put("type", "text"); put("text", if (webSearch) AiWebSearch.prompt(prompt, schema) else prompt) })
                    }
                })
            }
        }

    /** The Responses request of OpenAI and xAI with their web search tool: text only (never a photo). */
    fun responses(model: String, prompt: String, schema: JsonObject): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("input") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", AiWebSearch.prompt(prompt, schema)) }) }
            })
        }
        putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
    }
}
