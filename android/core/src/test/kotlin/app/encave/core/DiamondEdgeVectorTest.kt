package app.encave.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Diamond racks with edge triangles (`rack.edgeTriangles`, `rack.triangleCapacity`) from shared
 * vectors. CaveKit (DiamondEdgeVectorTests.swift) reads the same file,
 * `fixtures/diamond-edge-vectors.json`, so both platforms read, name and edit them alike.
 */
class DiamondEdgeVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/diamond-edge-vectors.json").readText()).jsonObject

    private fun cases(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }

    private fun readRack(element: JsonElement?): Rack =
        CellarJson.json.decodeFromJsonElement(Rack.serializer(), element!!).normalized()

    private fun written(rack: Rack): JsonObject = CellarJson.json.encodeToJsonElement(Rack.serializer(), rack).jsonObject

    private fun JsonElement?.ints(): List<Int>? = (this as? JsonArray)?.map { it.jsonPrimitive.int }

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    /** The rack as a version that does not know edge triangles reads it: the keys among its unknown ones. */
    private fun older(rack: Rack): Rack {
        val keys = written(rack)
        val unknown = keys.filterKeys { it == "edgeTriangles" || it == "triangleCapacity" }
        return readRack(JsonObject(keys - "edgeTriangles" - "triangleCapacity")).copy(unknownKeys = JsonObject(unknown))
    }

    /** "B3" for a diamond, "top 2" for a triangle. */
    private fun name(place: DiamondPlace?, row: Int, column: Int): String = when (place) {
        is DiamondPlace.Diamond -> "${Rack.rowLetters(place.row)}${place.column + 1}"
        is DiamondPlace.Triangle -> "${place.edge.raw} ${place.index + 1}"
        null -> "${Rack.rowLetters(row)}${column + 1}"
    }

    @Test
    fun racksAreReadTheSameEverywhere() {
        val cases = cases("wire")
        assertTrue(cases.size >= 5)
        for (vector in cases) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            val layout = rack.diamondLayout
            assertEquals(vector["edges"]!!.jsonArray.map { it.jsonPrimitive.content },
                DiamondEdge.entries.filter { layout?.edges?.contains(it) == true }.map { it.raw }, about)
            assertEquals(vector["diamondRows"].int(), layout?.rows, about)
            assertEquals(vector["diamondColumns"].int(), layout?.columns, about)
            assertEquals(vector["columnsInRow"].ints(), (0 until rack.rows).map { rack.columnsInRow(it) }, about)
            assertEquals(vector["offsetRows"]!!.jsonArray.map { it.jsonPrimitive.boolean }, (0 until rack.rows).map { rack.isOffset(it) }, about)
            if (layout != null) assertEquals(vector["triangleCapacity"].int(), rack.triangleCapacity, about)
            assertEquals(vector["capacity"].int(), rack.capacity, about)
            assertEquals(rack.capacity, rack.allSlots.size, about)
            assertTrue(rack.allSlots.all { rack.hasPosition(it) }, about)
            assertEquals(vector["fillOrderStart"]!!.jsonArray.map { it.ints() }, rack.allSlots.take(6).map { listOf(it.row, it.column, it.depth) }, about)
            for (place in vector["places"]!!.jsonArray.map { it.jsonObject }) {
                val row = place["row"].int()!!
                val column = place["column"].int()!!
                val cell = RackCell(rack.id, row, column)
                assertTrue(rack.contains(cell), about)
                assertEquals(place["place"]!!.jsonPrimitive.content, name(rack.diamondPlace(cell), row, column), about)
                assertEquals(place["positions"].int(), rack.positions(cell), about)
                val slot = Slot(rack.id, row, column, 0)
                assertEquals(place["label"]!!.jsonPrimitive.content, rack.label(slot), about)
                assertEquals(place["en"]!!.jsonPrimitive.content, rack.placeName(slot), about)
                rack.diamondPlace(cell)?.let { assertEquals(row to column, layout!!.cellOf(it), about) }
            }
            // Written back as read, and read again the same.
            val keys = written(rack)
            val raw = vector["rack"]!!.jsonObject
            assertEquals(raw["edgeTriangles"], keys["edgeTriangles"], about)
            assertEquals(raw["triangleCapacity"], keys["triangleCapacity"], about)
            assertEquals(rack, readRack(keys), about)
        }
    }

    /** A version that does not know the triangles keeps both keys and judges the same places. */
    @Test
    fun olderVersionsKeepTheKeysAndTheSamePlaces() {
        for (vector in cases("wire")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            val older = older(rack).copy(edgeTrianglesValue = null, triangleCapacityValue = null)
            assertNull(older.diamondLayout, about)
            assertEquals(rack.cells, older.cells, about)
            assertTrue(rack.allSlots.all { older.contains(it) }, about)
            assertTrue(older.allSlots.all { rack.contains(it) }, about)
            // It writes them back unchanged after its own edit (a rename): read again, the same triangles.
            val back = readRack(JsonObject(written(older.copy(name = "Renamed")) + older.unknownKeys))
            assertEquals(rack.diamondLayout, back.diamondLayout, about)
            assertEquals(rack.triangleCapacityValue, back.triangleCapacityValue, about)
        }
    }

    @Test
    fun placesEveryVersionKeeps() {
        for (vector in cases("kept")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            val place = vector["slot"].ints()!!
            val slot = Slot(rack.id, place[0], place[1], place[2])
            assertEquals(vector.getValue("contains").jsonPrimitive.boolean, rack.contains(slot), about)
            assertEquals(vector.getValue("contains").jsonPrimitive.boolean, older(rack).copy(edgeTrianglesValue = null).contains(slot), about)
            assertEquals(vector.getValue("hasPosition").jsonPrimitive.boolean, rack.hasPosition(slot), about)
            assertEquals(vector.getValue("label").jsonPrimitive.content, rack.label(slot), about)
            assertEquals(vector.getValue("en").jsonPrimitive.content, rack.placeName(slot), about)
        }
    }

    @Test
    fun editsMoveBottlesWithTheirDiamond() {
        for (vector in cases("moves")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val before = readRack(vector["before"])
            val after = readRack(vector["after"])
            val map = Rack.diamondCellMap(before, after)
            for (move in vector["cells"]!!.jsonArray.map { it.jsonObject }) {
                val from = move["from"].ints()!!
                val to = move["to"].ints()?.let { RackCell(before.id, it[0], it[1]) }
                assertEquals(to, Rack.moved(Slot(before.id, from[0], from[1], 0), emptyMap(), map)?.cell, "$about $from")
                if (to != null) assertTrue(after.contains(to), about)
            }
        }
    }

    @Test
    fun theWizardMakesTriangles() {
        for (vector in cases("wizard")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val settings = vector.getValue("settings").jsonObject
            val wizard = StorageSettings(StorageType.Rack).copy(
                layout = RackLayout.Diamond,
                rows = settings["rows"].int()!!,
                columns = settings["columns"].int()!!,
                bottlesPerBin = settings["bottlesPerBin"].int()!!,
                diamondEdges = settings["edges"]!!.jsonArray.mapNotNull { DiamondEdge.of(it.jsonPrimitive.content) }.toSet(),
                bottlesPerTriangle = settings["bottlesPerTriangle"].int(),
            )
            val made = wizard.rack(name = "Rack")
            assertEquals(vector.getValue("rack").jsonObject, JsonObject(written(made) - "id" - "name"), about)
            val again = StorageSettings.of(made)
            assertEquals(wizard.diamondEdges, again.diamondEdges, about)
            assertNull(again.rowSettings, about)
            assertTrue(StorageSettings.sameShape(again.rack(name = "Rack"), made), about)
        }
    }
}
