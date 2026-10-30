package app.encave.data.ai

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only models that read images are offered (`fixtures/model-vision-vectors.json`, as CaveKit's AIConnectionsTests). */
class AiVisionModelsTest {
    private val vectors = Json.parseToJsonElement(File("../../fixtures/model-vision-vectors.json").readText()).jsonObject

    private fun provider(raw: String) = AiProvider.entries.first { it.raw == raw }

    @Test
    fun eachModel() {
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (row in cases) {
            val item = row.getValue("item").jsonObject
            val expected = row.getValue("vision").jsonPrimitive.content
            val actual = AiRequestBody.vision(item, provider(row.getValue("provider").jsonPrimitive.content))
            assertEquals("$row", expected, actual.name.lowercase())
        }
    }

    @Test
    fun offeredLists() {
        for (row in vectors.getValue("lists").jsonArray.map { it.jsonObject }) {
            val items = row.getValue("ids").jsonArray.map { buildJsonObject { put("id", it.jsonPrimitive) } }
            val offered = row.getValue("offered").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$row", offered, AiRequestBody.imageModels(items, provider(row.getValue("provider").jsonPrimitive.content)))
        }
    }
}
