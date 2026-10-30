package app.encave.data.ai

import app.encave.core.AiWebSearch
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.core.WineIdentity
import java.math.BigDecimal
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What is sent to AI providers for "What to expect" and for web search: only the wine's identity, and the search only where it is allowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiRequestPrivacyTest {
    private val realTransport = AiService.transport

    @After
    fun restore() {
        AiService.transport = realTransport
    }

    private val wine = Wine(
        name = "Les Galets", producer = "Domaine Martin", vintage = 2021, color = WineColor.White, region = "Bourgogne",
        country = "France", appellation = "Chablis", grapes = listOf("Chardonnay"), notes = "Secret note", photoId = "sha256-photo",
        currentPrice = BigDecimal(42), currentPriceCurrency = "EUR", tags = listOf("Gift from Anna"),
    )

    @Test
    fun whatToExpectSendsOnlyTheIdentity() = runBlocking {
        val connection = AiConnection(AiProvider.OpenRouter)
        val configuration = AiConfiguration().upserting(connection).addingModel("vendor/model", connection.id)
        val bodies = mutableListOf<JsonObject>()
        AiService.transport = { _: URL, _, body, _ ->
            bodies += body!!
            Json.parseToJsonElement(
                """{"choices":[{"message":{"content":"{\"expected\":{\"aromas\":[\"fruit.lemon\"],\"structure\":{\"acidity\":5},""" +
                    """\"servingMin\":10,\"servingMax\":12,\"decantMinutes\":0,\"pairings\":[\"shellfish\"],\"text\":\"Vif.\"}}"}}]}""",
            ).jsonObject
        }
        val (profile, model) = AiService.expectedProfile(WineIdentity(wine), "fr", configuration, { "test-key" })
        assertEquals("vendor/model", model.model)
        assertEquals(listOf("fruit.lemon"), profile?.aromas)
        assertEquals("ai", profile?.source)
        val sent = bodies.single().toString()
        for (part in listOf("Domaine Martin", "Les Galets", "Chablis", "2021", "white", "Chardonnay")) assertTrue(part, part in sent)
        for (part in listOf("image_url", "base64", "Secret note", "sha256-photo", "EUR", "Gift", "Bourgogne", "France")) assertFalse(part, part in sent)
        // No web search unless the connection allows it.
        assertNull(bodies.single()["plugins"])
    }

    @Test
    fun webSearchIsOfferedOnlyWhereTheProviderSearches() {
        assertEquals(AiWebSearch.Kind.AnthropicTool, AiConnection(AiProvider.Anthropic).webSearch)
        assertEquals(AiWebSearch.Kind.OpenRouterPlugin, AiConnection(AiProvider.OpenRouter).webSearch)
        assertEquals(AiWebSearch.Kind.ResponsesTool, AiConnection(AiProvider.OpenAi).webSearch)
        assertEquals(AiWebSearch.Kind.ResponsesTool, AiConnection(AiProvider.Xai).webSearch)
        for (provider in listOf(AiProvider.Google, AiProvider.Mistral, AiProvider.DeepSeek, AiProvider.Ollama, AiProvider.LmStudio, AiProvider.Local)) {
            assertNull(provider.raw, AiConnection(provider).webSearch)
        }
        // The same API at another address may not search.
        assertNull(AiConnection(AiProvider.OpenAi, baseUrl = "https://llm.example.org/v1").webSearch)
        // Off by default.
        assertFalse(AiConnection(AiProvider.Anthropic).searchesWeb)
        assertTrue(AiConnection(AiProvider.Anthropic).copy(allowsWebSearch = true).searchesWeb)
        assertFalse(AiConnection(AiProvider.Ollama).copy(allowsWebSearch = true).searchesWeb)
    }

    @Test
    fun requestBodiesAddTheSearchOnlyWhenAsked() {
        val schema = buildJsonObject { }
        val plain = AiRequestBody.claude("claude-x", "p", schema, null, webSearch = false, effort = false, fallbacks = false)
        assertNull(plain["tools"])
        assertNotNull(plain["output_config"]?.jsonObject?.get("format"))
        val searching = AiRequestBody.claude("claude-x", "p", schema, null, webSearch = true, effort = false, fallbacks = false)
        assertEquals("web_search_20250305", (searching["tools"] as JsonArray).single().jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(searching["output_config"])
        // Never a photo with web search.
        assertFalse("image" in searching.toString())
        val openRouter = AiConnection(AiProvider.OpenRouter)
        assertNull(AiRequestBody.chatCompletions(openRouter, "m", "p", null, null, webSearch = false)["plugins"])
        assertNotNull(AiRequestBody.chatCompletions(openRouter, "m", "p", null, null, webSearch = true)["plugins"])
        // Another provider never gets OpenRouter's plugin.
        assertNull(AiRequestBody.chatCompletions(AiConnection(AiProvider.Mistral), "m", "p", null, null, webSearch = true)["plugins"])
        val responses = AiRequestBody.responses("gpt-x", "p", schema)
        assertEquals("web_search", (responses["tools"] as JsonArray).single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("false", responses["store"]?.jsonPrimitive?.content)
    }
}
