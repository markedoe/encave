package app.encave.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The pages an AI cited (`fixtures/ai-web-source-vectors.json`), as CaveKit's AIWebSearchTests. */
class AiWebSearchTest {
    private val vectors = sharedFixture("ai-web-source-vectors.json")
    private fun rows(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }
    private fun sources(element: Any?) = (element as JsonArray).map { item ->
        val obj = item.jsonObject
        WineWebSource(obj.getValue("url").jsonPrimitive.content, (obj["title"] as? JsonPrimitive)?.content?.ifEmpty { null })
    }

    @Test
    fun sourceVectors() {
        for (row in rows("claude")) assertEquals(sources(row["sources"]), AiWebSearch.claude(row.getValue("blocks").jsonArray), row["note"].toString())
        for (row in rows("chatCompletions")) {
            assertEquals(sources(row["sources"]), AiWebSearch.chatCompletions(row.getValue("message").jsonObject), row["note"].toString())
        }
        for (row in rows("responses")) {
            val (text, found) = AiWebSearch.responses(row.getValue("json").jsonObject)
            assertEquals((row["text"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content, text, row["note"].toString())
            assertEquals(sources(row["sources"]), found, row["note"].toString())
        }
    }
}
