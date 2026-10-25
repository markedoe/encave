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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rows of their own (`rack.rowDepths`, `rack.rowBackReversed`) from shared vectors. CaveKit
 * (StorageRowVectorTests.swift) reads the same file, `fixtures/storage-row-vectors.json`, so
 * both platforms read, edit and make such racks alike.
 */
class StorageRowVectorTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/storage-row-vectors.json").readText()).jsonObject

    private fun cases(key: String) = root.getValue(key).jsonArray.map { it.jsonObject }

    private fun readRack(element: JsonElement?): Rack =
        CellarJson.json.decodeFromJsonElement(Rack.serializer(), element!!).normalized()

    private fun JsonElement?.ints(): List<Int>? = (this as? JsonArray)?.map { it.jsonPrimitive.int }

    private fun JsonElement?.booleans(): List<Boolean>? = (this as? JsonArray)?.map { it.jsonPrimitive.boolean }

    private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.boolean

    private fun slots(element: JsonElement?, rack: Rack): List<Slot> =
        element!!.jsonArray.map { place -> place.jsonArray.map { it.jsonPrimitive.int }.let { Slot(rack.id, it[0], it[1], it[2]) } }

    @Test
    fun racksAreReadTheSameEverywhere() {
        val cases = cases("wire")
        assertTrue(cases.isNotEmpty())
        for (vector in cases) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            assertEquals(vector.getValue("depth").jsonPrimitive.int, rack.depth, about)
            assertEquals(vector["rowDepths"].ints(), rack.rowDepths, about)
            assertEquals(vector["rowBackReversed"].booleans(), rack.rowBackReversed, about)
            assertEquals(vector["backReversed"].flag(), rack.backReversed, about)
            assertEquals(vector["depthOfRow"].ints(), (0 until rack.rows).map { rack.depthOfRow(it) }, about)
            assertEquals(vector["headToTailOfRow"].booleans(), (0 until rack.rows).map { rack.isBackReversedRow(it) }, about)
            assertEquals(vector.getValue("capacity").jsonPrimitive.int, rack.capacity, about)
            assertEquals(rack.capacity, rack.allSlots.size, about)
            assertTrue(rack.allSlots.all { rack.contains(it) }, about)
            assertTrue(rack.allSlots.all { rack.hasPosition(it) }, about)

            // Written back as read, and read again the same.
            val written = CellarJson.json.encodeToJsonElement(Rack.serializer(), rack).jsonObject
            assertEquals(rack.rowDepths != null, "rowDepths" in written, about)
            assertEquals(rack.rowBackReversed != null, "rowBackReversed" in written, about)
            assertEquals(rack, readRack(written), about)
        }
    }

    /**
     * A version that does not know the per-row fields keeps them as unknown keys and reads
     * `depth` and `backReversed`: it still shows every place there is.
     */
    @Test
    fun olderVersionsStillShowEveryPlace() {
        for (vector in cases("wire")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            val written = CellarJson.json.encodeToJsonElement(Rack.serializer(), rack).jsonObject
            val older = readRack(JsonObject(written - "rowDepths" - "rowBackReversed"))
            assertTrue(rack.allSlots.all { older.contains(it) }, about)
            assertEquals((0 until rack.rows).any { rack.isBackReversedRow(it) }, older.isBackReversed, about)
        }
        // Fields this version does not know are kept next to the per-row ones.
        val future = Json.parseToJsonElement(
            """{"id":"7a0f1c2e-5b3d-4e6f-8a9b-0c1d2e3f4a5b","name":"R","kind":"grid","rows":2,"columns":2,"depth":2,"rowDepths":[2,1],"rowFuture":[1,2]}""",
        )
        val again = CellarJson.json.encodeToJsonElement(Rack.serializer(), readRack(future)).jsonObject
        assertEquals(listOf(1, 2), again["rowFuture"].ints())
        assertEquals(listOf(2, 1), again["rowDepths"].ints())
    }

    /** Places every version keeps (sync repair, imports) and positions the apps offer. */
    @Test
    fun placesBehindASingleRowAreKept() {
        val cases = cases("kept")
        assertTrue(cases.isNotEmpty())
        for (vector in cases) {
            val about = vector.getValue("about").jsonPrimitive.content
            val rack = readRack(vector["rack"])
            val place = vector["slot"].ints()!!
            val slot = Slot(rack.id, place[0], place[1], place[2])
            assertEquals(vector.getValue("contains").jsonPrimitive.boolean, rack.contains(slot), about)
            assertEquals(vector.getValue("hasPosition").jsonPrimitive.boolean, rack.hasPosition(slot), about)
            assertEquals(vector.getValue("label").jsonPrimitive.content, rack.label(slot), about)
            assertEquals(vector.getValue("en").jsonPrimitive.content, rack.placeName(slot), about)
        }
    }

    /** A version that does not know rowDepths puts a bottle behind a single row; a CSV from it imports it in place. */
    @Test
    fun aBottleAnOlderVersionPutBehindASingleRowStaysPlaced() {
        val rack = Rack(name = "Main", rows = 3, columns = 4, depth = 2, rowDepths = listOf(2, 1, 2)).normalized()
        val behind = Slot(rack.id, 1, 2, 1)
        assertEquals(behind, rack.slotForLabel("B3-2"))
        assertEquals("B3-2", rack.label(behind))
        val cellar = Cellar()
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "Clos"), slots = listOf(behind))
        assertEquals(behind, cellar.bottlesInCellar.single().slot)
        // Counted among the positions, so the count never reads more bottles than places.
        assertEquals(rack.capacity + 1, cellar.capacity(rack))
        assertTrue(cellar.displacedBottles(rack).isEmpty())
    }

    @Test
    fun bottlesLeftWithoutAPlaceWhenARowShrinks() {
        for (vector in cases("displaced")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val before = readRack(vector["before"])
            val after = readRack(vector["after"])
            val cellar = Cellar()
            cellar.addRack(before)
            val slots = slots(vector["slots"], before)
            assertTrue(slots.all { before.contains(it) }, about)
            cellar.addBottles(Wine(name = "W"), quantity = slots.size, slots = slots)
            val displaced = cellar.displacedBottles(after).mapNotNull { it.slot }
            assertEquals(slots(vector["displaced"], before), displaced, about)
            // Saving asks first; agreed, the displaced bottles are taken out, the others stay.
            if (displaced.isNotEmpty()) assertThrows(CellarException::class.java) { cellar.updateRack(after) }
            cellar.updateRack(after, takingOut = true)
            val kept = cellar.bottlesInCellar.mapNotNull { it.slot }
            assertEquals(slots.size - displaced.size, kept.size, about)
            assertTrue(kept.all { after.contains(it) }, about)
        }
    }

    @Test
    fun theWizardMakesRowsOfTheirOwn() {
        val types = mapOf("rack" to StorageType.Rack, "wineFridge" to StorageType.WineFridge, "crates" to StorageType.Crates)
        val layouts = mapOf("grid" to RackLayout.Grid, "honeycomb" to RackLayout.Honeycomb, "diamond" to RackLayout.Diamond)
        for (vector in cases("wizard")) {
            val about = vector.getValue("about").jsonPrimitive.content
            val settings = vector.getValue("settings").jsonObject
            var wizard = StorageSettings(types.getValue(settings.getValue("type").jsonPrimitive.content))
            settings["layout"]?.let { wizard = wizard.copy(layout = layouts.getValue(it.jsonPrimitive.content)) }
            settings["bottlesPerBin"]?.let { wizard = wizard.copy(bottlesPerBin = it.jsonPrimitive.int) }
            settings["bins"]?.let { wizard = wizard.copy(bins = it.jsonPrimitive.int) }
            wizard = wizard.copy(
                rowSettings = settings.getValue("rows").jsonArray.map { it.jsonObject }.map { row ->
                    StorageRowSettings(
                        positions = row["positions"]?.jsonPrimitive?.int ?: 1, depth = row["depth"]?.jsonPrimitive?.int ?: 1,
                        headToTail = row["headToTail"]?.jsonPrimitive?.boolean ?: false, layers = row["layers"]?.jsonPrimitive?.int ?: 1,
                    )
                },
            )
            val made = wizard.rack(name = "Rack")
            val expected = vector.getValue("rack").jsonObject
            assertEquals(expected.getValue("kind").jsonPrimitive.content, made.kind.raw, about)
            assertEquals(expected.getValue("rows").jsonPrimitive.int, made.rows, about)
            assertEquals(expected.getValue("columns").jsonPrimitive.int, made.columns, about)
            assertEquals(expected.getValue("depth").jsonPrimitive.int, made.depth, about)
            assertEquals(expected["rowColumns"].ints(), made.rowColumns, about)
            assertEquals(expected["rowDepths"].ints(), made.rowDepths, about)
            assertEquals(expected["shelfLayers"].ints(), made.shelfLayers, about)
            assertEquals(expected["backReversed"].flag(), made.backReversed, about)
            assertEquals(expected["rowBackReversed"].booleans(), made.rowBackReversed, about)
            assertEquals((expected["binStyle"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, made.binStyleValue, about)
            assertEquals(vector.getValue("capacity").jsonPrimitive.int, made.capacity, about)
            // Described again, the same rack comes back.
            assertTrue(StorageSettings.sameShape(StorageSettings.of(made).rack(name = "Rack"), made), about)
        }
    }

    @Test
    fun rowsStartAsTheUniformSettingsAndFollowTheirNumber() {
        var fridge = StorageSettings(StorageType.WineFridge)
        val uniform = fridge.rack(name = "F")
        fridge = fridge.withRowsDiffer(true)
        assertEquals(fridge.shelves, fridge.rowSettings?.size)
        assertTrue(StorageSettings.sameShape(fridge.rack(name = "F"), uniform))
        assertEquals(StorageRowSettings(positions = 6, depth = 2, headToTail = true, layers = 2), fridge.rowSettings?.first())
        fridge = fridge.withRow(1, fridge.rowSettings!![1].copy(depth = 1)).withRowCount(7)
        assertEquals(7, fridge.rowSettings?.size)
        assertEquals(2, fridge.rowSettings?.last()?.depth)
        fridge = fridge.withRowCount(2)
        assertEquals(2, fridge.rowSettings?.size)
        assertEquals(1, fridge.rowSettings?.last()?.depth)
        assertNull(fridge.withRowsDiffer(false).rowSettings)

        val honeycomb = StorageSettings(StorageType.Rack, layout = RackLayout.Honeycomb, rows = 3, columns = 5).withRowsDiffer(true)
        assertEquals(listOf(5, 4, 5), honeycomb.rowSettings?.map { it.positions })

        val crates = StorageSettings(StorageType.Crates).withRowsDiffer(true)
        assertNull(crates.rowSettings)
        assertTrue(!crates.offersRowSettings)
    }
}
