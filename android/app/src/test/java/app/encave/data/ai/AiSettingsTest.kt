package app.encave.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun claudeOptionsFollowEachModel() {
        fun options(model: String) = AiService.claudeOptions(model).let { it.effort to it.fallbacks }
        assertEquals(true to true, options("claude-opus-5-5"))
        assertEquals(true to true, options("claude-opus-5"))
        assertEquals(true to true, options("claude-fable-5-1"))
        assertEquals(true to true, options("claude-sonnet-5-5"))
        assertEquals(true to false, options("claude-sonnet-5"))
        assertEquals(true to false, options("claude-haiku-5-5"))
        assertEquals(true to false, options("claude-opus-4-8"))
        assertEquals(true to false, options("claude-opus-4-5-20251101"))
        assertEquals(false to false, options("claude-sonnet-4-5-20250929"))
        assertEquals(false to false, options("claude-haiku-4-5"))
        assertEquals(false to false, options("claude-3-7-sonnet-latest"))
    }

    @Test
    fun emptyBaseUrlMeansTheDefault() {
        assertEquals("https://api.mistral.ai/v1", AiSettings(AiProvider.Mistral).effectiveBaseUrl)
        assertEquals("https://example.test/v1", AiSettings(AiProvider.Mistral, baseUrl = "https://example.test/v1").effectiveBaseUrl)
        assertEquals("http://example.test:11434/v1", AiSettings(AiProvider.Local, baseUrl = "http://example.test:11434/v1/").effectiveBaseUrl)
        assertEquals(AiProvider.Local.defaultBaseUrl, AiSettings(AiProvider.Local).effectiveBaseUrl)
        assertEquals("claude-opus-5-5", AiSettings(AiProvider.Anthropic).effectiveModel)
    }

    @Test
    fun firstBuildNamesAreMigrated() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "openAICompatible").putString("baseURL", "http://example.test:11434/v1").putString("model", "llava").commit()
        val settings = AiPreferences(context).settings.value
        assertEquals(AiProvider.Local, settings.provider)
        assertEquals("llava", settings.model)
    }

    @Test
    fun theFirstLocalOptionMovesToOllama() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "local").putString("baseURL.local", "http://example.test:11434/v1").commit()
        val settings = AiPreferences(context).settings.value
        assertEquals(AiProvider.Ollama, settings.provider)
        assertEquals("http://example.test:11434", settings.baseUrl)
    }

    @Test
    fun anotherLocalServerStaysOpenAiCompatible() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "local").putString("baseURL.local", "http://example.test:8080/v1").commit()
        assertEquals(AiProvider.Local, AiPreferences(context).settings.value.provider)
        assertTrue(AiProvider.Ollama.isLocal && !AiProvider.Ollama.requiresKey && AiProvider.Mistral.requiresKey)
    }

    @Test
    fun errorsAndThinkingAreRead() {
        val parse = { text: String -> kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject }
        assertEquals("a", AiService.errorMessage(parse("""{"error":{"message":"a"}}""")))
        assertEquals("b", AiService.errorMessage(parse("""{"error":"b"}""")))
        assertEquals("c", AiService.errorMessage(parse("""{"detail":"c"}""")))
        assertEquals("d", AiService.errorMessage(parse("""{"message":"d"}""")))
        assertEquals("W", AiService.decode("<think>{\"name\":\"no\"}</think>{\"name\":\"W\"}").name)
    }

    @Test
    fun eachProviderKeepsItsOwnModelAndAddress() {
        val prefs = AiPreferences(context)
        prefs.update(AiSettings(AiProvider.OpenAi, model = "gpt-x", baseUrl = "https://example.test/v1"))
        prefs.select(AiProvider.Mistral)
        assertEquals("", prefs.settings.value.model)
        assertEquals("", prefs.settings.value.baseUrl)
        prefs.select(AiProvider.OpenAi)
        assertEquals("gpt-x", prefs.settings.value.model)
        assertEquals("https://example.test/v1", prefs.settings.value.baseUrl)
        assertEquals(AiProvider.entries.filter { it != AiProvider.Off && it != AiProvider.Local }, AiProvider.entries.filter { it.keyPage != null })
    }

    @Test
    fun answersInCodeFencesAreRead() {
        val analysis = AiService.decode("```json\n{\"name\":\"Clos X\",\"vintage\":2019,\"color\":\"red\"}\n```")
        assertEquals("Clos X", analysis.name)
        assertEquals(2019, analysis.vintage)
    }

    @Test
    fun pastedKeysAreCleaned() {
        assertEquals("sk-abc", AiPreferences.cleanKey(" “sk-abc”\n"))
        assertEquals("sk-abc", AiPreferences.cleanKey("'sk-abc'\r\n"))
        assertEquals("key-1", AiPreferences.cleanKey("  \"key-1\"\n"))
    }
}
