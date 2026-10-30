package app.encave.data.ai

import app.encave.core.AiLabelSources
import app.encave.ui.add.WineFormState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The label request asks where each detail comes from; the form marks the estimates until edited. */
class EstimatedFieldsTest {
    private val answer = """{"name":"Clos des Lilas","producer":"","vintage":2019,"color":"red","appellation":"Pauillac",""" +
        """"region":"Bordeaux","country":"France","grapes":["Cabernet Sauvignon","Merlot"],"drinkFrom":2026,"drinkUntil":2040,""" +
        """"abv":13.5,"classification":"","sources":{"name":"label","producer":"","vintage":"label","color":"knowledge",""" +
        """"appellation":"label","region":"knowledge","country":"knowledge","grapes":"knowledge","drinkWindow":"knowledge",""" +
        """"abv":"label","classification":""}}"""

    @Test
    fun theRequestAsksForEverySource() {
        for (findsLabel in listOf(false, true)) {
            val schema = LabelAnalysis.schema(findsLabel)
            assertTrue("sources" in (schema["required"] as JsonArray).map { it.jsonPrimitive.content })
            val sources = (schema["properties"] as JsonObject)["sources"] as JsonObject
            assertEquals(AiLabelSources.keys.toSet(), (sources["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
            assertEquals(AiLabelSources.keys.toSet(), (sources["properties"] as JsonObject).keys)
            val prompt = LabelAnalysis.prompt(findsLabel)
            assertTrue(prompt.contains("\"knowledge\"") && prompt.contains("when you are not sure"))
        }
    }

    @Test
    fun estimatesAreMarkedUntilEdited() {
        val analysis = AiService.decode(answer)
        assertEquals(setOf("color", "region", "country", "grapes", "drinkWindow"), analysis.estimated)
        val form = WineFormState(null, 2026)
        assertTrue(form.apply(analysis))
        for (key in listOf("color", "region", "country", "grapes", "drinkWindow")) assertTrue(key, form.isEstimatedByAi(key))
        // Read on the label, or left empty: not marked.
        for (key in listOf("name", "vintage", "appellation", "abv", "producer", "classification")) assertFalse(key, form.isEstimatedByAi(key))
        // Edited: no longer the AI's estimate.
        form.region = "Médoc"
        assertFalse(form.isEstimatedByAi("region"))
        form.grapes.remove("Merlot")
        assertFalse(form.isEstimatedByAi("grapes"))
        form.manualWindow = 2027..2040
        assertFalse(form.isEstimatedByAi("drinkWindow"))
        assertTrue(form.isEstimatedByAi("country"))
        // Set back to the same text: still the estimate.
        form.region = "Bordeaux"
        assertTrue(form.isEstimatedByAi("region"))
    }

    @Test
    fun aReadingThatFindsItOnTheLabelRemovesTheMark() {
        val form = WineFormState(null, 2026)
        form.apply(AiService.decode(answer))
        assertTrue(form.isEstimatedByAi("country"))
        form.apply(AiService.decode(answer.replace(""""country":"knowledge"""", """"country":"label"""")))
        assertFalse(form.isEstimatedByAi("country"))
    }
}
