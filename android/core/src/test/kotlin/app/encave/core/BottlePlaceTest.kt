package app.encave.core

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The order bottles are listed in and the bottle Put away places, from `fixtures/bottle-place-vectors.json` (CaveKit reads them too). */
class BottlePlaceTest {
    private fun JsonObject.string(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = string(key)?.toInt()

    @Test
    fun orderAndPutAwayMatchTheSharedVectors() {
        val root = Json.parseToJsonElement(File("../../fixtures/bottle-place-vectors.json").readText()).jsonObject
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (vector in cases) {
            val about = vector.string("about")
            val wine = Wine(name = "Wine")
            val racks = vector.getValue("racks").jsonArray.map { it.jsonObject }.map { item ->
                Rack(
                    id = UUID.fromString(item.string("id")), name = item.string("id")!!,
                    kind = RackKind.entries.first { it.raw == item.string("kind") },
                    rows = item.int("rows")!!, columns = item.int("columns")!!, depth = item.int("depth") ?: 1,
                    shelfLayers = item["shelfLayers"]?.jsonArray?.map { it.jsonPrimitive.content.toInt() },
                )
            }
            val bottles = vector.getValue("bottles").jsonArray.map { it.jsonObject }.map { item ->
                Bottle(
                    id = UUID.fromString(item.string("id")), wineId = wine.id,
                    slot = item.string("rack")?.let {
                        Slot(UUID.fromString(it), item.int("row") ?: 0, item.int("column") ?: 0, item.int("depth") ?: 0)
                    },
                    addedAt = Instant.parse(item.string("addedAt")),
                    reservedFor = item.string("reservedFor"),
                )
            }
            val expectedOrder = vector.getValue("placeOrder").jsonArray.map { it.jsonPrimitive.content.lowercase() }
            val expectedPutAway = vector.string("putAway")?.lowercase()
            // Whatever the order the bottles come in.
            for (order in listOf(bottles, bottles.reversed())) {
                val cellar = Cellar(wines = listOf(wine), bottles = order, racks = racks)
                assertEquals(expectedOrder, cellar.inPlaceOrder(cellar.bottlesOf(wine.id)).map { it.id.toString() }, about)
                assertEquals(expectedPutAway, cellar.bottlesOf(wine.id).bottleToPutAway?.id?.toString(), about)
                assertEquals(expectedPutAway != null, cellar.bottlesOf(wine.id).hasBottleToPutAway, about)
            }
        }
    }

    @Test
    fun bottlesNotInStorageAreTheOnesWithoutAPosition() {
        val rack = Rack(name = "Rack", rows = 2, columns = 2)
        val wine = Wine(name = "Wine")
        val placed = Bottle(wineId = wine.id, slot = Slot(rack.id, 0, 0))
        val loose = Bottle(wineId = wine.id)
        val cellar = Cellar(wines = listOf(wine), bottles = listOf(placed, loose), racks = listOf(rack))
        assertEquals(listOf(loose.id), cellar.bottlesNotInStorage.map { it.id })
    }
}
