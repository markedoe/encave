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

    /** Ollama, LM Studio, OpenWebUI or any OpenAI-compatible server. */
    Local("local", null, "http://localhost:11434/v1", "https://ollama.com/download");

    val usesAnthropicApi: Boolean get() = this == Anthropic

    /** A key is optional only for a server of your own. */
    val requiresKey: Boolean get() = this != Local && this != Off

    /** Model used until the list is loaded, when one is known to read images. */
    val suggestedModel: String get() = if (this == Anthropic) "claude-opus-5-5" else ""

    companion object {
        val global = listOf(OpenAi, Anthropic, Google, Xai, Mistral)
        val chinese = listOf(DeepSeek, Qwen, Kimi, Zhipu, MiniMax)
        val others = listOf(OpenRouter, Local)

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
        }
        val model = prefs.getString("model.${provider.raw}", null) ?: prefs.getString("model", "").orEmpty()
        val baseUrl = prefs.getString("baseURL.${provider.raw}", null) ?: if (provider == AiProvider.Local) legacyUrl else ""
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
        secrets.get("ai.apiKey.${provider.raw}") ?: if (provider == AiProvider.Anthropic) secrets.get("ai.apiKey") else null

    fun setApiKey(provider: AiProvider, key: String?) = secrets.set("ai.apiKey.${provider.raw}", key?.trim())
}
