package app.encave.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The storage type of bins from shared vectors. CaveKit (StorageTypeVectorTests.swift) reads
 * the same file, `fixtures/storage-type-vectors.json`.
 */
class StorageTypeVectorTest {
    /** Tests run in android/core. */
    private val cases: List<JsonObject> = Json.parseToJsonElement(File("../../fixtures/storage-type-vectors.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private val types = mapOf("rack" to StorageType.Rack, "wineFridge" to StorageType.WineFridge, "crates" to StorageType.Crates)

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int

    private fun rack(vector: JsonObject): Rack {
        val wizard = vector["wizard"]?.jsonObject
        var rack = if (wizard != null) {
            var settings = StorageSettings(types.getValue(wizard.getValue("type").jsonPrimitive.content))
            wizard["layout"]?.jsonPrimitive?.content?.let { raw ->
                settings = settings.copy(layout = RackLayout.entries.first { it.name.lowercase() == raw })
            }
            wizard.int("rows")?.let { settings = settings.copy(rows = it) }
            wizard.int("columns")?.let { settings = settings.copy(columns = it) }
            wizard.int("bins")?.let { settings = settings.copy(bins = it) }
            wizard.int("bottlesPerBin")?.let { settings = settings.copy(bottlesPerBin = it) }
            settings.rack(name = "Bins")
        } else {
            CellarJson.json.decodeFromString(Rack.serializer(), vector.getValue("record").toString())
        }
        vector["edit"]?.jsonObject?.int("columns")?.let { rack = rack.copy(columns = it).normalized() }
        return rack
    }

    @Test
    fun storageTypeCountAndBinStyle() {
        assertTrue(cases.size >= 5)
        for (vector in cases) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = rack(vector)
            assertEquals(types.getValue(vector.getValue("storageType").jsonPrimitive.content), rack.storageType, about)
            assertEquals(vector.int("storageCount"), rack.storageCount, about)
            val text = CellarJson.json.encodeToString(Rack.serializer(), rack)
            val encoded = Json.parseToJsonElement(text).jsonObject
            val expected = vector.getValue("binStyle").takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            assertEquals(expected, encoded["binStyle"]?.jsonPrimitive?.contentOrNull, about)
            // Read back, the rack is the same.
            val again = CellarJson.json.decodeFromString(Rack.serializer(), text)
            assertEquals(rack, again, about)
            assertTrue(again.unknownKeys.isEmpty(), about)
        }
    }

    @Test
    fun editorSettingsKeepTheType() {
        for (vector in cases) {
            val rack = rack(vector)
            assertEquals(rack.storageType, StorageSettings.of(rack).type, vector.getValue("about").jsonPrimitive.content)
        }
    }
}
