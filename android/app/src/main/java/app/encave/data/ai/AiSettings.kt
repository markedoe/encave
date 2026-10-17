package app.encave.data.ai

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional label recognition by an AI model the user chooses and pays for.
 * Off by default. When on, only the label photo is sent, never the cellar.
 */
data class AiSettings(
    val provider: Provider = Provider.Off,
    val model: String = "",
    val baseUrl: String = "",
) {
    enum class Provider(val raw: String) {
        Off("off"),
        Claude("claude"),

        /** OpenAI, a local OpenWebUI or Ollama server, or any compatible API. */
        OpenAiCompatible("openAICompatible"),
    }

    val isEnabled: Boolean get() = provider != Provider.Off

    val effectiveModel: String
        get() = model.trim().ifEmpty { if (provider == Provider.Claude) DEFAULT_CLAUDE_MODEL else "" }

    val effectiveBaseUrl: String get() = baseUrl.trim().ifEmpty { DEFAULT_OPENAI_BASE_URL }

    companion object {
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val OPENAI_MODEL_HINT = "gpt-4o"
    }
}

/** AI settings in preferences; the API key in [SecretStore]. */
class AiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AiSettings> = state.asStateFlow()

    private fun read() = AiSettings(
        provider = AiSettings.Provider.entries.firstOrNull { it.raw == prefs.getString("provider", null) } ?: AiSettings.Provider.Off,
        model = prefs.getString("model", "").orEmpty(),
        baseUrl = prefs.getString("baseURL", "").orEmpty(),
    )

    fun update(settings: AiSettings) {
        prefs.edit {
            putString("provider", settings.provider.raw)
            putString("model", settings.model)
            putString("baseURL", settings.baseUrl)
        }
        state.value = settings
    }

    var apiKey: String?
        get() = secrets.get(KEY_NAME)
        set(value) = secrets.set(KEY_NAME, value?.trim())

    private companion object {
        const val KEY_NAME = "ai.apiKey"
    }
}
