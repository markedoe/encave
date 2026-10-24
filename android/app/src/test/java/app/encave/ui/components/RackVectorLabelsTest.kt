package app.encave.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.Slot
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Positions as people read them, from the shared vectors (`fixtures/rack-vectors.json`, also
 * read by the core's RackVectorTest and CaveKit's RackVectorTests), in English and French.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RackVectorLabelsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** Each place of the vectors: its rack, its slot and the vector. Tests run in android/app. */
    private fun places(): List<Triple<Rack, Slot, JsonObject>> {
        val root = Json.parseToJsonElement(File("../../fixtures/rack-vectors.json").readText()).jsonObject
        return root.getValue("racks").jsonArray.map { it.jsonObject }.flatMap { layout ->
            fun ints(key: String) = layout[key]?.jsonArray?.map { it.jsonPrimitive.int }
            val raw = layout.getValue("kind").jsonPrimitive.content
            val rack = Rack(
                name = "Rack", kind = RackKind.entries.first { it.raw == raw },
                rows = layout.getValue("rows").jsonPrimitive.int, columns = layout.getValue("columns").jsonPrimitive.int,
                depth = layout["depth"]?.jsonPrimitive?.int ?: 1, binCapacity = layout["binCapacity"]?.jsonPrimitive?.int ?: 12,
                rowColumns = ints("rowColumns"), shelfLayers = ints("shelfLayers"),
                backReversed = layout["backReversed"]?.jsonPrimitive?.boolean,
            ).normalized()
            layout.getValue("places").jsonArray.map { it.jsonObject }.map { place ->
                val slot = Slot(rack.id, place.getValue("row").jsonPrimitive.int, place.getValue("column").jsonPrimitive.int,
                    place.getValue("depth").jsonPrimitive.int)
                Triple(rack, slot, place)
            }
        }
    }

    private fun check(language: String) {
        val places = places()
        assertTrue(places.isNotEmpty())
        for ((rack, slot, place) in places) {
            assertEquals(place.toString(), place.getValue(language).jsonPrimitive.content, rack.displayLabel(slot, resources))
        }
    }

    @Test
    fun inEnglish() = check("en")

    @Test
    @Config(qualifiers = "fr")
    fun inFrench() = check("fr")
}
