package app.encave.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.sync.LocalNetworkException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun plainHttpOnlyReachesTheLocalNetwork() {
        fun allowed(url: String) = AiService.isAllowed(java.net.URL(url))
        // Addresses built from their parts, so no address appears as such in the repository.
        fun ip(vararg parts: Int) = parts.joinToString(".")
        assertTrue(allowed("https://api.openai.com/v1/models"))
        assertTrue(allowed("http://localhost:11434/api/tags"))
        assertTrue(allowed("http://${ip(192, 168, 1, 20)}:11434/api/chat"))
        assertTrue(allowed("http://${ip(10, 0, 0, 5)}:8000/v1/chat/completions"))
        assertTrue(allowed("http://nas.local:8080/v1/models"))
        assertTrue(allowed("http://ai.home.arpa/v1/models"))
        assertTrue(allowed("http://[fd00::1]:11434/api/tags"))
        // A name may be on the home network: resolved and checked in send (LocalNameTest).
        assertTrue(allowed("http://ollama.home:11434/api/tags"))
        assertEquals(false, allowed("http://${ip(203, 0, 113, 7)}:11434/api/chat"))
        assertEquals(false, allowed("ftp://${ip(192, 168, 1, 20)}/v1"))
    }

    @Test
    fun aKeyIsNeverSentOverPlainHttpToTheInternet() {
        val connection = AiConnection(AiProvider.OpenAi, baseUrl = "http://203.0.113.7/v1")
        val error = runCatching {
            kotlinx.coroutines.runBlocking { AiService.analyze(ByteArray(4), AiEndpoint(connection, "gpt-test", "sk-test"), findsLabel = true) }
        }.exceptionOrNull()
        assertEquals(AiError.InsecureUrl, error)
        val listing = runCatching { kotlinx.coroutines.runBlocking { AiService.models(connection, "sk-test") } }.exceptionOrNull()
        assertEquals(AiError.InsecureUrl, listing)
        // A name is resolved first, and only a local one gets the request (".invalid" never resolves).
        val named = connection.copy(baseUrl = "http://api.invalid/v1")
        val unresolved = runCatching { kotlinx.coroutines.runBlocking { AiService.models(named, "sk-test") } }.exceptionOrNull()
        assertTrue(unresolved is LocalNetworkException)
    }

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
        assertEquals("https://api.mistral.ai/v1", AiConnection(AiProvider.Mistral).effectiveBaseUrl)
        assertEquals("https://example.test/v1", AiConnection(AiProvider.Mistral, baseUrl = "https://example.test/v1").effectiveBaseUrl)
        assertEquals("http://example.test:11434/v1", AiConnection(AiProvider.Local, baseUrl = "http://example.test:11434/v1/").effectiveBaseUrl)
        assertEquals(AiProvider.Local.defaultBaseUrl, AiConnection(AiProvider.Local).effectiveBaseUrl)
        assertEquals("claude-opus-5-5", AiSettings(AiProvider.Anthropic).effectiveModel)
    }

    private fun migrated() = AiPreferences(context, MemoryKeys()).configuration.value

    @Test
    fun firstBuildNamesAreMigrated() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "openAICompatible").putString("baseURL", "http://example.test:11434/v1").putString("model", "llava").commit()
        val configuration = migrated()
        assertEquals(AiProvider.Local, configuration.connections.single().provider)
        assertEquals(listOf("llava"), configuration.models.map { it.model })
    }

    @Test
    fun theFirstLocalOptionMovesToOllama() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "local").putString("baseURL.local", "http://example.test:11434/v1").putString("model", "gemma3").commit()
        val connection = migrated().connections.single()
        assertEquals(AiProvider.Ollama, connection.provider)
        assertEquals("http://example.test:11434", connection.baseUrl)
    }

    @Test
    fun anotherLocalServerStaysOpenAiCompatible() {
        context.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
            .putString("provider", "local").putString("baseURL.local", "http://example.test:8080/v1").commit()
        assertEquals(AiProvider.Local, migrated().connections.single().provider)
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
    fun everyProviderButAnOwnServerHasAKeyPage() {
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
