package app.encave.data.ai

import android.content.Context
import androidx.core.content.edit
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

/** Optional AI features with a model and key the user chooses. Off by default. */
data class AiSettings(
    val provider: AiProvider = AiProvider.Off,
    val model: String = "",
    /** A local server's address, or a different address for a provider (empty: the default). */
    val baseUrl: String = "",
) {
    val isEnabled: Boolean get() = provider != AiProvider.Off

    val effectiveModel: String get() = model.trim().ifEmpty { provider.suggestedModel }

    val effectiveBaseUrl: String
        get() {
            val trimmed = baseUrl.trim()
            val base = trimmed.ifEmpty { provider.defaultBaseUrl }
            return base.removeSuffix("/")
        }
}

/**
 * AI settings in preferences: the provider, and a model and address saved per provider
 * ("model.<provider>", "baseURL.<provider>"), so switching never copies one provider's
 * model to another. Keys are in [SecretStore], one per provider.
 */
class AiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AiSettings> = state.asStateFlow()

    private fun read(): AiSettings {
        val raw = prefs.getString("provider", "off")
        val legacyUrl = prefs.getString("baseURL", "").orEmpty()
        val provider = when (raw) {
            // Names used by the first test builds.
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
            // Keep the address of a server set up with the first builds, without the "/v1".
            val old = (prefs.getString("baseURL.local", null) ?: legacyUrl).removeSuffix("/v1")
            baseUrl = if (old == AiProvider.Ollama.defaultBaseUrl) "" else old
        }
        return AiSettings(provider, model, baseUrl)
    }

    fun update(settings: AiSettings) {
        prefs.edit {
            putString("provider", settings.provider.raw)
            putString("model.${settings.provider.raw}", settings.model)
            putString("baseURL.${settings.provider.raw}", settings.baseUrl)
        }
        state.value = settings
    }

    /** Switching back to a provider keeps the model chosen for it. */
    fun select(provider: AiProvider) = update(
        AiSettings(
            provider = provider,
            model = prefs.getString("model.${provider.raw}", "").orEmpty(),
            baseUrl = prefs.getString("baseURL.${provider.raw}", "").orEmpty(),
        ),
    )

    fun apiKey(provider: AiProvider): String? =
        (secrets.get("ai.apiKey.${provider.raw}") ?: if (provider == AiProvider.Anthropic) secrets.get("ai.apiKey") else null)
            ?.let(::cleanKey)

    fun setApiKey(provider: AiProvider, key: String?) = secrets.set("ai.apiKey.${provider.raw}", key?.let(::cleanKey))

    companion object {
        /** Pasted keys often carry spaces, line breaks or quotes, which providers reject as invalid. */
        fun cleanKey(key: String): String = key.trim { it.isWhitespace() || it in "\"'“”‘’" }
    }
}
