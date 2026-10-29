package app.encave.data.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A key store in memory; [failing] names cannot be read or written. */
class MemoryKeys : AiKeyStore {
    val items = mutableMapOf<String, String>()
    val failing = mutableSetOf<String>()

    override fun readKey(name: String): KeyRead = when {
        name in failing -> KeyRead.Failed
        else -> items[name]?.let(KeyRead::Found) ?: KeyRead.Missing
    }

    override fun writeKey(name: String, value: String?): Boolean {
        if (name in failing) return false
        if (value.isNullOrEmpty()) items.remove(name) else items[name] = value
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiConnectionsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val realTransport = AiService.transport

    @After
    fun restoreTransport() {
        AiService.transport = realTransport
    }

    @Test
    fun theProviderInUseBecomesTheFirstConnectionAndModel() {
        prefs.edit().putString("provider", "mistral").putString("model.mistral", "pixtral-large-latest")
            .putString("baseURL.mistral", "https://example.test/v1").commit()
        val keys = MemoryKeys()
        keys.items["ai.apiKey.mistral"] = "test-key-mistral"
        keys.items["ai.apiKey.openRouter"] = "test-key-openrouter"

        val configuration = AiPreferences(context, keys).configuration.value
        assertEquals(listOf(AiProvider.Mistral, AiProvider.OpenRouter), configuration.connections.map { it.provider })
        val first = configuration.connections.first()
        assertEquals("https://example.test/v1", first.baseUrl)
        assertEquals(listOf("pixtral-large-latest"), configuration.models.map { it.model })
        assertEquals(first.id, configuration.models.single().connectionId)
        assertTrue(configuration.isEnabled)
        // Keys moved, not lost: at their connection, and gone from the old places.
        assertEquals("test-key-mistral", keys.key(first))
        assertEquals("test-key-openrouter", keys.key(configuration.connections[1]))
        assertNull(keys.items["ai.apiKey.mistral"])
        assertNull(keys.items["ai.apiKey.openRouter"])
        // Done once: read again, the same configuration.
        assertEquals(configuration, AiPreferences(context, keys).configuration.value)
    }

    @Test
    fun theFirstClaudeKeyAndSuggestedModelAreKept() {
        prefs.edit().putString("provider", "claude").commit()
        val keys = MemoryKeys()
        keys.items["ai.apiKey"] = " \"test-key-claude\"\n"
        val configuration = AiPreferences(context, keys).configuration.value
        assertEquals(AiProvider.Anthropic, configuration.connections.single().provider)
        assertEquals(listOf("claude-opus-5-5"), configuration.models.map { it.model })
        assertEquals("test-key-claude", keys.key(configuration.connections.single()))
        assertNull(keys.items["ai.apiKey"])
    }

    @Test
    fun aKeyThatCannotBeReadLeavesEverythingForNextTime() {
        prefs.edit().putString("provider", "openAI").putString("model.openAI", "gpt-test").commit()
        val keys = MemoryKeys()
        keys.items["ai.apiKey.openAI"] = "test-key-openai"
        keys.failing += "ai.apiKey.xai"
        assertEquals(AiConfiguration(), AiPreferences(context, keys).configuration.value)
        assertNull(prefs.getString(AiConfigurationStore.KEY, null))
        assertEquals("test-key-openai", keys.items["ai.apiKey.openAI"])
        keys.failing.clear()
        val configuration = AiPreferences(context, keys).configuration.value
        assertEquals(listOf("gpt-test"), configuration.models.map { it.model })
        assertEquals("test-key-openai", keys.key(configuration.connections.single()))
    }

    @Test
    fun nothingSetUpStaysOff() {
        assertFalse(AiPreferences(context, MemoryKeys()).configuration.value.isEnabled)
    }

    @Test
    fun modelsKeepTheirOrderAndGoWithTheirConnection() {
        val a = AiConnection(AiProvider.OpenRouter, name = "OpenRouter perso")
        val b = AiConnection(AiProvider.Ollama)
        var configuration = AiConfiguration().upserting(a).upserting(b)
            .addingModel("vendor/one", a.id).addingModel("gemma3", b.id).addingModel("vendor/three", a.id)
        assertEquals(configuration, configuration.addingModel("vendor/one", a.id).addingModel(" ", a.id))
        configuration = configuration.movingModel(2, 0)
        assertEquals(listOf("vendor/three", "vendor/one", "gemma3"), configuration.models.map { it.model })
        configuration = configuration.movingModel(0, 2)
        assertEquals(listOf("vendor/one", "gemma3", "vendor/three"), configuration.models.map { it.model })
        val gemma = configuration.models[1]
        assertEquals(listOf(gemma), configuration.readingOrder(gemma.id))
        configuration = configuration.removingConnection(a.id)
        assertEquals(listOf(gemma), configuration.models)
        assertEquals(listOf(b), configuration.connections)
        // Saved and read back the same, in order.
        AiConfigurationStore.save(prefs, configuration, commit = true)
        assertEquals(configuration, AiConfigurationStore.load(prefs, MemoryKeys()))
    }

    @Test
    fun aConnectionOfANewerVersionIsLeftOut() {
        val json = """{"connections":[{"id":"a","provider":"mistral"},{"id":"b","provider":"future"}],
            "models":[{"id":"1","connectionId":"b","model":"x"},{"id":"2","connectionId":"a","model":"y"}]}"""
        val configuration = AiConfigurationStore.decode(json)
        assertEquals(listOf("a"), configuration.connections.map { it.id })
        assertEquals(listOf("y"), configuration.models.map { it.model })
    }

    private fun answer(name: String) = Json.parseToJsonElement(
        """{"choices":[{"message":{"content":"{\"name\":\"$name\",\"vintage\":2019}"}}]}""",
    ).jsonObject

    private data class Call(val url: URL, val headers: Map<String, String>, val body: JsonObject?)

    private fun twoOpenRouterModels(): Triple<AiConfiguration, AiConnection, AiConnection> {
        val first = AiConnection(AiProvider.OpenRouter, name = "Perso")
        val second = AiConnection(AiProvider.OpenRouter, name = "Work")
        val configuration = AiConfiguration().upserting(first).upserting(second)
            .addingModel("vendor/first", first.id).addingModel("vendor/second", second.id)
        return Triple(configuration, first, second)
    }

    @Test
    fun aModelThatAnswers429HandsOverToTheNextOne() = runBlocking {
        val (configuration, first, second) = twoOpenRouterModels()
        val calls = mutableListOf<Call>()
        var running = 0
        var mostAtOnce = 0
        AiService.transport = { url, headers, body, _ ->
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            try {
                calls += Call(url, headers, body)
                if (body?.get("model")?.jsonPrimitive?.contentOrNull == "vendor/first") throw AiError.Server(429, "Too many requests")
                answer("Clos Test")
            } finally {
                running--
            }
        }
        val keys = mapOf(first.id to "test-key-first", second.id to "test-key-second")
        val reading = AiService.analyzeLabel(ByteArray(8), configuration, { keys[it.id] }, findsLabel = false)
        assertEquals("vendor/second", reading.model.model)
        assertEquals(second, reading.connection)
        assertEquals("Clos Test", reading.analysis.name)
        assertEquals(listOf("vendor/first", "vendor/second"), calls.map { it.body?.get("model")?.jsonPrimitive?.content })
        assertEquals(listOf("Bearer test-key-first", "Bearer test-key-second"), calls.map { it.headers["Authorization"] })
        assertEquals(1, mostAtOnce)
        // OpenRouter is asked, by default, to skip providers that keep or train on the data.
        calls.forEach { assertEquals("deny", it.body?.get("provider")?.jsonObject?.get("data_collection")?.jsonPrimitive?.content) }
    }

    @Test
    fun whenEveryModelFailsEachFailureIsListed() = runBlocking {
        val (configuration, _, _) = twoOpenRouterModels()
        AiService.transport = { _, _, body, _ ->
            when (body?.get("model")?.jsonPrimitive?.contentOrNull) {
                "vendor/first" -> throw AiError.Server(503, "")
                else -> Json.parseToJsonElement("""{"choices":[{"message":{"refusal":"no"}}]}""").jsonObject
            }
        }
        try {
            AiService.analyzeLabel(ByteArray(8), configuration, { "test-key" }, findsLabel = false)
            fail("Expected every model to fail")
        } catch (error: AiAllModelsFailed) {
            assertEquals(configuration.models, error.failures.map { it.model })
            assertEquals(AiError.Server(503, ""), error.failures[0].error)
            assertEquals(AiError.Refused, error.failures[1].error)
            assertTrue(error.untried.isEmpty())
        }
    }

    @Test
    fun aSingleModelGivesItsOwnError() = runBlocking {
        val connection = AiConnection(AiProvider.OpenAi)
        val configuration = AiConfiguration().upserting(connection).addingModel("gpt-test", connection.id)
        AiService.transport = { _, _, _, _ -> throw AiError.Server(401, "Invalid key") }
        val error = runCatching { AiService.analyzeLabel(ByteArray(8), configuration, { "test-key" }) }.exceptionOrNull()
        assertEquals(AiError.Server(401, "Invalid key"), error)
    }

    @Test
    fun theTimeBudgetStopsBeforeTheNextModel() = runBlocking {
        val connection = AiConnection(AiProvider.Ollama)
        val models = listOf("slow", "next").map { AiModelChoice(connectionId = connection.id, model = it) }
        var clock = 0L
        val asked = mutableListOf<String>()
        try {
            AiFallback.read(models, budgetMs = 100_000, now = { clock }) { model, left ->
                asked += model.model
                assertEquals(100_000L, left)
                clock += 95_000
                throw AiError.Server(0, "")
            }
            fail("Expected a failure")
        } catch (error: AiAllModelsFailed) {
            assertEquals(listOf("slow"), asked)
            assertEquals(listOf("next"), error.untried.map { it.model })
        }
    }

    @Test
    fun turningDataCollectionOnLeavesOutTheProviderField() {
        val denied = AiConnection(AiProvider.OpenRouter)
        val body = AiService.chatCompletionsBody(AiEndpoint(denied, "vendor/vision", "test-key"), false, "AAAA", null)
        assertEquals("deny", body["provider"]?.jsonObject?.get("data_collection")?.jsonPrimitive?.content)
        val allowed = denied.copy(allowsDataCollection = true)
        assertNull(AiService.chatCompletionsBody(AiEndpoint(allowed, "vendor/vision", "test-key"), false, "AAAA", null)["provider"])
        // Other services never get OpenRouter's field.
        assertNull(AiService.chatCompletionsBody(AiEndpoint(AiConnection(AiProvider.OpenAi), "m", "test-key"), false, "AAAA", null)["provider"])
    }

    @Test
    fun openRouterListsOnlyModelsThatReadImages() = runBlocking {
        val connection = AiConnection(AiProvider.OpenRouter)
        AiService.transport = { _, _, _, _ ->
            Json.parseToJsonElement(
                """{"data":[{"id":"vendor/vision","architecture":{"input_modalities":["text","image"]}},
                    {"id":"vendor/text","architecture":{"input_modalities":["text"]}}]}""",
            ).jsonObject
        }
        assertEquals(listOf("vendor/vision"), AiService.models(connection, "test-key"))
    }

    @Test
    fun keysAreNeverInPreferencesExportsOrLogs() {
        prefs.edit().putString("provider", "openRouter").putString("model.openRouter", "vendor/vision").commit()
        val keys = MemoryKeys()
        keys.items["ai.apiKey.openRouter"] = "test-key-secret-value"
        val configuration = AiPreferences(context, keys).configuration.value
        val connection = configuration.connections.single()
        // Not in any preferences file of the app (those may move to a new phone).
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles().orEmpty().forEach { file -> assertFalse(file.name, file.readText().contains("test-key-secret-value")) }
        assertFalse(prefs.all.values.joinToString().contains("test-key-secret-value"))
        // Nor in what an endpoint prints.
        assertFalse(AiEndpoint(connection, "vendor/vision", "test-key-secret-value").toString().contains("secret"))
        // The encrypted keys stay out of cloud backups and device transfers.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertFalse(Regex("""<include[^>]*secrets\.xml""").containsMatchIn(rules))
        assertTrue(rules.contains("""<exclude domain="sharedpref" path="." />"""))
    }
}
