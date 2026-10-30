package app.encave.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI services the user can connect with their own key, plus a server of their own.
 * Most speak the OpenAI chat format; Claude has its own API.
 */
enum class AiProvider(
    val raw: String,
    /** Brand names are not translated; null for "Off" and the local server (localized in the UI). */
    val brand: String?,
    val defaultBaseUrl: String,
    val keyPage: String? = null,
) {
    Off("off", null, ""),
    OpenAi("openAI", "ChatGPT (OpenAI)", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
    Anthropic("anthropic", "Claude (Anthropic)", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys"),
    Google("google", "Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey"),
    Xai("xai", "Grok (xAI)", "https://api.x.ai/v1", "https://console.x.ai"),
    Mistral("mistral", "Mistral AI", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys"),
    DeepSeek("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
    Qwen("qwen", "Qwen (Alibaba Cloud)", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "https://help.aliyun.com/en/model-studio/get-api-key"),
    Kimi("kimi", "Kimi (Moonshot AI)", "https://api.moonshot.ai/v1", "https://platform.moonshot.ai/console/api-keys"),
    Zhipu("zhipu", "GLM (Z.ai)", "https://api.z.ai/api/paas/v4", "https://z.ai/manage-apikey/apikey-list"),
    MiniMax("minimax", "MiniMax", "https://api.minimax.io/v1", "https://platform.minimax.io/user-center/basic-information/interface-key"),
    OpenRouter("openRouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),

    /** Servers on the user's own computer or network. */
    Ollama("ollama", "Ollama", "http://localhost:11434", "https://ollama.com/download"),
    Vllm("vllm", "vLLM", "http://localhost:8000/v1", "https://docs.vllm.ai/en/latest/getting_started/quickstart.html"),
    LmStudio("lmStudio", "LM Studio", "http://localhost:1234/v1", "https://lmstudio.ai/download"),

    /** Any other OpenAI-compatible server (OpenWebUI, llama.cpp…). */
    Local("local", null, "http://localhost:8080/v1");

    val usesAnthropicApi: Boolean get() = this == Anthropic

    /**
     * How the provider's API searches the web itself, null when it does not (or not in a way
     * Encave uses): Claude's web search tool, OpenRouter's web plugin, and the web search tool
     * of the Responses API of OpenAI and xAI. As CaveKit's `AIProvider.webSearch`.
     */
    val webSearch: app.encave.core.AiWebSearch.Kind?
        get() = when (this) {
            Anthropic -> app.encave.core.AiWebSearch.Kind.AnthropicTool
            OpenRouter -> app.encave.core.AiWebSearch.Kind.OpenRouterPlugin
            OpenAi, Xai -> app.encave.core.AiWebSearch.Kind.ResponsesTool
            else -> null
        }

    /** Ollama has its own API (structured outputs, list of models that read images). */
    val usesOllamaApi: Boolean get() = this == Ollama

    /** Runs on the user's computer or network: no key needed, slower answers allowed. */
    val isLocal: Boolean get() = this in localServers

    /** A key is optional only for a server of your own. */
    val requiresKey: Boolean get() = !isLocal && this != Off

    /** Model used until the list is loaded, when one is known to read images. */
    val suggestedModel: String get() = if (this == Anthropic) "claude-opus-5-5" else ""

    companion object {
        val global = listOf(OpenAi, Anthropic, Google, Xai, Mistral)
        val chinese = listOf(DeepSeek, Qwen, Kimi, Zhipu, MiniMax)
        val others = listOf(OpenRouter)
        val localServers = listOf(Ollama, Vllm, LmStudio, Local)

        fun fromRaw(raw: String?): AiProvider? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * The single provider, model and address of earlier versions, read once to move them into
 * an [AiConfiguration] ([AiConfigurationStore.migrate]).
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.Off,
    val model: String = "",
    /** A local server's address, or a different address for a provider (empty: the default). */
    val baseUrl: String = "",
) {
    val isEnabled: Boolean get() = provider != AiProvider.Off

    val effectiveModel: String get() = model.trim().ifEmpty { provider.suggestedModel }
}

/**
 * AI settings in preferences ("ai"): the connections and the ordered models
 * ([AiConfigurationStore]). Keys are in [SecretStore], one per connection, never in them.
 */
class AiPreferences(context: Context, private val keys: AiKeyStore = SecretKeys(SecretStore(context))) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(AiConfigurationStore.load(prefs, keys))

    val configuration: StateFlow<AiConfiguration> = state.asStateFlow()

    fun update(transform: (AiConfiguration) -> AiConfiguration) {
        val next = transform(state.value)
        if (next == state.value) return
        state.value = next
        AiConfigurationStore.save(prefs, next)
    }

    fun apiKey(connection: AiConnection): String? = keys.key(connection)

    fun setApiKey(connection: AiConnection, key: String?) = keys.setKey(connection, key)

    /** Saves the key in the background, in the order keys are typed. */
    fun saveApiKey(connection: AiConnection, key: String?) = SecretStore.writes.execute { runCatching { setApiKey(connection, key) } }

    /** Removes the connection, its models and its key. */
    fun deleteConnection(id: String) {
        val connection = state.value.connection(id) ?: return
        update { it.removingConnection(id) }
        saveApiKey(connection, null)
    }

    companion object {
        /** Pasted keys often carry spaces, line breaks or quotes, which providers reject as invalid. */
        fun cleanKey(key: String): String = key.trim { it.isWhitespace() || it in "\"'“”‘’" }
    }
}
