package app.encave.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.CellarJson
import app.encave.core.Rack
import app.encave.core.RackCell
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * The places of diamond racks with edge triangles as people read them ("Top triangle 3", "Bin A3"),
 * from the shared vectors (`fixtures/diamond-edge-vectors.json`, also read by the core's
 * DiamondEdgeVectorTest and CaveKit's DiamondEdgeVectorTests), in English and French.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DiamondEdgeLabelsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** Each place of the vectors: its rack, its cell and the vector. Tests run in android/app. */
    private fun places(): List<Triple<Rack, RackCell, JsonObject>> {
        val root = Json.parseToJsonElement(File("../../fixtures/diamond-edge-vectors.json").readText()).jsonObject
        return root.getValue("wire").jsonArray.map { it.jsonObject }.flatMap { vector ->
            val rack = CellarJson.json.decodeFromJsonElement(Rack.serializer(), vector.getValue("rack")).normalized()
            vector.getValue("places").jsonArray.map { it.jsonObject }.map { place ->
                Triple(rack, RackCell(rack.id, place.getValue("row").jsonPrimitive.int, place.getValue("column").jsonPrimitive.int), place)
            }
        }
    }

    private fun check(language: String) {
        val places = places()
        assertTrue(places.isNotEmpty())
        for ((rack, cell, place) in places) {
            val label = rack.displayLabel(cell, resources)
            // On screen a no-break space keeps each number with its word; the vectors use plain spaces.
            assertEquals(place.toString(), place.getValue(language).jsonPrimitive.content, label.replace(' ', ' '))
        }
    }

    @Test
    fun inEnglish() = check("en")

    @Test
    @Config(qualifiers = "fr")
    fun inFrench() = check("fr")
}
