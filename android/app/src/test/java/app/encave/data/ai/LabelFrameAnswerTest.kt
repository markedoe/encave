package app.encave.data.ai

import app.encave.core.AiLabelPlacement
import app.encave.core.Wine
import app.encave.core.WineLabelFrame
import app.encave.ui.add.WineFormState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The label's place in the photo, asked in the same request as the details. */
class LabelFrameAnswerTest {
    private val details = """"name":"Clos","producer":"","vintage":2019,"color":"red","appellation":"","region":"","country":"","grapes":[],"drinkFrom":0,"drinkUntil":0"""

    @Test
    fun theRequestAsksForTheLabelsPlace() {
        val required = (LabelAnalysis.schema["required"] as JsonArray).map { it.jsonPrimitive.content }
        assertTrue("label_box" in required && "label_angle" in required)
        val properties = LabelAnalysis.schema["properties"] as JsonObject
        assertNotNull(properties["label_box"])
        assertNotNull(properties["label_angle"])
        assertTrue(LabelAnalysis.prompt.contains("label_box") && LabelAnalysis.prompt.contains("uphill"))
    }

    @Test
    fun theAnswerGivesTheBoxAndTheAngle() {
        val analysis = AiService.decode("""{$details,"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":-4}""")
        assertEquals("Clos", analysis.name)
        assertEquals(AiLabelPlacement(0.2, 0.3, 0.5, 0.4, -4.0), analysis.placement)
        // The frame is for the photo read, once known.
        assertNull(analysis.labelFrame)
        assertEquals(WineLabelFrame("sha256-a", 0.2, 0.3, 0.5, 0.4, -4.0), analysis.copy(photoId = "sha256-a").labelFrame)
    }

    @Test
    fun anInvalidBoxNeverFailsTheAnswer() {
        for (place in listOf(
            """"label_box":null,"label_angle":null""",
            """"label_box":"somewhere"""",
            """"label_box":{"x":0.8,"y":0.3,"w":0.5,"h":0.4}""",
            """"label_box":{"x":0.2,"y":0.3,"w":0.05,"h":0.4}""",
            """"label_box":{"x":0.2,"y":0.3,"w":0.5,"h":0.4},"label_angle":80""",
        )) {
            val analysis = AiService.decode("{$details,$place}")
            assertEquals("Clos", analysis.name)
            assertNull(place, analysis.placement)
        }
        // An answer without the fields, from a server without strict schemas.
        assertNull(AiService.decode("{$details}").placement)
    }

    @Test
    fun aCompletionKeepsTheFrameOnlyForTheWinesPhoto() {
        val saved = Wine(name = "Clos", photoId = "sha256-a")
        val analysis = LabelAnalysis(region = "Bourgogne", placement = AiLabelPlacement(0.1, 0.2, 0.6, 0.5, 3.0))
        val completed = analysis.copy(photoId = "sha256-a").completing(saved)
        assertEquals(WineLabelFrame("sha256-a", 0.1, 0.2, 0.6, 0.5, 3.0), completed.labelFrame)
        // Another photo was read: no frame for this one.
        assertNull(analysis.copy(photoId = "sha256-b").completing(saved).labelFrame)
        // Taken into the wine as it is now, and undone with the rest.
        val updated = saved.completed(from = saved, to = completed)
        assertEquals(completed.labelFrame, updated.labelFrame)
        assertNull(updated.completed(from = updated, to = saved).labelFrame)
    }

    @Test
    fun theFormKeepsTheFrameForTheSave() {
        val form = WineFormState(Wine(name = "Clos", photoId = "sha256-a"), 2026)
        form.apply(LabelAnalysis(placement = AiLabelPlacement(0.1, 0.2, 0.6, 0.5), photoId = "sha256-a"))
        assertEquals(WineLabelFrame("sha256-a", 0.1, 0.2, 0.6, 0.5, 0.0), form.build().labelFrame)
    }
}
