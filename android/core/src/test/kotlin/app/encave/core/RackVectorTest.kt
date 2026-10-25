package app.encave.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Storage layouts from shared vectors. CaveKit (RackVectorTests.swift) reads the same file,
 * `fixtures/rack-vectors.json`; the app's RackVectorLabelsTest checks the French names in it.
 */
class RackVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/rack-vectors.json").readText()).jsonObject

    private val layouts = root.getValue("racks").jsonArray.map { it.jsonObject }

    private fun JsonObject.ints(key: String): List<Int>? = this[key]?.jsonArray?.map { it.jsonPrimitive.int }

    private fun rack(layout: JsonObject): Rack {
        val raw = layout.getValue("kind").jsonPrimitive.content
        return Rack(
            name = "Rack", kind = RackKind.entries.first { it.raw == raw },
            rows = layout.getValue("rows").jsonPrimitive.int, columns = layout.getValue("columns").jsonPrimitive.int,
            depth = layout["depth"]?.jsonPrimitive?.int ?: 1, binCapacity = layout["binCapacity"]?.jsonPrimitive?.int ?: 12,
            rowColumns = layout.ints("rowColumns"), shelfLayers = layout.ints("shelfLayers"),
            backReversed = layout["backReversed"]?.jsonPrimitive?.boolean,
            rowDepths = layout.ints("rowDepths"),
            rowBackReversed = layout["rowBackReversed"]?.jsonArray?.map { it.jsonPrimitive.boolean },
        ).normalized()
    }

    @Test
    fun cellsPerRowAndFillOrder() {
        assertTrue(layouts.isNotEmpty())
        for (layout in layouts) {
            val rack = rack(layout)
            val about = layout.getValue("about").jsonPrimitive.content
            assertEquals(layout.ints("columnsInRow"), (0 until rack.rows).map { rack.columnsInRow(it) }, about)
            val start = layout.getValue("fillOrderStart").jsonArray.map { place -> place.jsonArray.map { it.jsonPrimitive.int } }
            assertTrue(start.isNotEmpty(), about)
            assertEquals(start, rack.allSlots.take(start.size).map { listOf(it.row, it.column, it.depth) }, about)
        }
    }

    @Test
    fun storageTypeAndCount() {
        val raw = mapOf("rack" to StorageType.Rack, "wineFridge" to StorageType.WineFridge, "crates" to StorageType.Crates)
        for (layout in layouts) {
            val rack = rack(layout)
            val about = layout.getValue("about").jsonPrimitive.content
            assertEquals(raw.getValue(layout.getValue("storageType").jsonPrimitive.content), rack.storageType, about)
            assertEquals(layout.getValue("storageCount").jsonPrimitive.int, rack.storageCount, about)
        }
    }

    @Test
    fun placesAreNamedTheSame() {
        var count = 0
        for (layout in layouts) {
            val rack = rack(layout)
            for (place in layout.getValue("places").jsonArray.map { it.jsonObject }) {
                val slot = Slot(rack.id, place.getValue("row").jsonPrimitive.int, place.getValue("column").jsonPrimitive.int,
                    place.getValue("depth").jsonPrimitive.int)
                val about = "${layout.getValue("about").jsonPrimitive.content}: $place"
                assertTrue(rack.contains(slot), about)
                assertEquals(place.getValue("label").jsonPrimitive.content, rack.label(slot), about)
                assertEquals(place.getValue("en").jsonPrimitive.content, rack.placeName(slot), about)
                assertEquals(place.getValue("emptyCaption").jsonPrimitive.content, rack.emptyCaption(slot.cell), about)
                assertEquals(place.getValue("bandShift").jsonPrimitive.double, rack.bandShift(slot.row, slot.depth).toDouble(), about)
                count++
            }
        }
        assertTrue(count > 0)
    }
}
