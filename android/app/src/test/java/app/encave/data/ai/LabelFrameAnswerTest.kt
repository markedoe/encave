package app.encave.data.ai

import app.encave.core.AiLabelPlacement
import app.encave.core.Wine
import app.encave.core.WineLabelFrame
import app.encave.ui.add.WineFormState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The label's place in the photo, asked in the same request as the details. */
class LabelFrameAnswerTest {
    private val details = """"name":"Clos","producer":"","vintage":2019,"color":"red","appellation":"","region":"","country":"","grapes":[],"drinkFrom":0,"drinkUntil":0"""

    private fun required(schema: JsonObject) = (schema["required"] as JsonArray).map { it.jsonPrimitive.content }

    @Test
    fun withTheStudioLookTheRequestAsksForTheLabelsPlace() {
        val schema = LabelAnalysis.schema(findsLabel = true)
        assertTrue("label_box" in required(schema) && "label_angle" in required(schema))
        val properties = schema["properties"] as JsonObject
        assertNotNull(properties["label_box"])
        assertNotNull(properties["label_angle"])
        // Every property is required, as strict schemas want.
        assertEquals(properties.keys, required(schema).toSet())
        val prompt = LabelAnalysis.prompt(findsLabel = true)
        assertTrue(prompt.contains("label_box") && prompt.contains("uphill to the right"))
    }

    @Test
    fun withoutItTheRequestIsAsBefore() {
        val schema = LabelAnalysis.schema(findsLabel = false)
        assertFalse("label_box" in required(schema) || "label_angle" in required(schema))
        val properties = schema["properties"] as JsonObject
        assertNull(properties["label_box"])
        assertEquals(properties.keys, required(schema).toSet())
        val prompt = LabelAnalysis.prompt(findsLabel = false)
        assertFalse(prompt.contains("label_"))
        assertTrue(prompt.startsWith("This is a photo of a wine label."))
        assertTrue(prompt.endsWith("Do not invent a producer or vintage that is not visible."))
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
    fun anAnswerWithNothingNewStillGivesWhereTheLabelIs() {
        val saved = Wine(name = "Clos", photoId = "sha256-a")
        val analysis = LabelAnalysis(name = "Clos", photoId = "sha256-a", placement = AiLabelPlacement(0.1, 0.2, 0.6, 0.5, 0.0))
        val completed = analysis.completing(saved)
        // Not "completed by AI", but the studio picture gets the label's place.
        assertNull(completed.aiCompletedAt)
        assertEquals(WineLabelFrame("sha256-a", 0.1, 0.2, 0.6, 0.5, 0.0), completed.labelFrame)
        assertTrue(completed.sameDetails(saved))
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

    @Test
    fun theRequestAsksForTheAlcoholAndClassificationAsCaveKitDoes() {
        for (findsLabel in listOf(true, false)) {
            val schema = LabelAnalysis.schema(findsLabel)
            assertTrue("abv" in required(schema) && "classification" in required(schema))
            val properties = schema["properties"] as JsonObject
            assertEquals("number", (properties["abv"] as JsonObject)["type"]!!.jsonPrimitive.content)
            assertEquals("string", (properties["classification"] as JsonObject)["type"]!!.jsonPrimitive.content)
            val lines = LabelAnalysis.prompt(findsLabel).lines()
            // Right after the drinking window, in CaveKit's words.
            val window = lines.indexOfFirst { it.startsWith("- drinkFrom and drinkUntil") }
            assertEquals("- abv: the alcohol by volume printed on the label, in percent (for example 13.5), or 0 if not printed.", lines[window + 1])
            assertEquals(
                "- classification: a classification printed on the label (for example Grand Cru, Premier Cru, Cru Bourgeois), or \"\" if none is printed.",
                lines[window + 2],
            )
        }
    }

    @Test
    fun theAlcoholAndClassificationAreReadTolerantly() {
        val read = AiService.decode("""{$details,"abv":13.5,"classification":" Grand Cru "}""")
        assertEquals(13.5, read.abv!!, 0.0)
        assertEquals("Grand Cru", read.classification)
        // Not printed, absent, as text, or not a number: unknown, never a failed answer.
        assertNull(AiService.decode("""{$details,"abv":0,"classification":""}""").abv)
        assertNull(AiService.decode("""{$details}""").abv)
        assertEquals("", AiService.decode("""{$details}""").classification)
        assertEquals(12.5, AiService.decode("""{$details,"abv":"12,5 % vol"}""").abv!!, 0.0)
        assertNull(AiService.decode("""{$details,"abv":"strong","classification":3}""").abv)
        assertEquals("", AiService.decode("""{$details,"classification":3}""").classification)
        assertNull(AiService.decode("""{$details,"abv":135}""").abv)
    }
}
