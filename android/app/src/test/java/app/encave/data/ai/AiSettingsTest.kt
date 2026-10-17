package app.encave.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
    fun eachProviderKeepsItsOwnModelAndAddress() {
        val prefs = AiPreferences(context)
        prefs.update(AiSettings(AiProvider.OpenAi, model = "gpt-x", baseUrl = "https://example.test/v1"))
        prefs.select(AiProvider.Mistral)
        assertEquals("", prefs.settings.value.model)
        assertEquals("", prefs.settings.value.baseUrl)
        prefs.select(AiProvider.OpenAi)
        assertEquals("gpt-x", prefs.settings.value.model)
        assertEquals("https://example.test/v1", prefs.settings.value.baseUrl)
        assertEquals(AiProvider.entries.filter { it != AiProvider.Off }, AiProvider.entries.filter { it.keyPage != null })
    }

    @Test
    fun answersInCodeFencesAreRead() {
        val analysis = AiService.decode("```json\n{\"name\":\"Clos X\",\"vintage\":2019,\"color\":\"red\"}\n```")
        assertEquals("Clos X", analysis.name)
        assertEquals(2019, analysis.vintage)
    }
}
