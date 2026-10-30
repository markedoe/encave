package app.encave.core

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Completing a saved wine from the web (`fixtures/web-completion-vectors.json`), as CaveKit's WebCompletionTests. */
class WebCompletionTest {
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String) = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }

    @Test
    fun vectors() {
        val date = Instant.parse("2026-05-10T12:00:00Z")
        val cases = sharedFixture("web-completion-vectors.json").getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (row in cases) {
            val note = row.text("note")
            val wine = CellarJson.json.decodeFromJsonElement(
                Wine.serializer(), JsonObject(row.getValue("wine").jsonObject + ("id" to JsonPrimitive(UUID.randomUUID().toString()))),
            )
            val completion = WebCompletion.parse(row.text("answer")!!, "m", "en", date)
            val result = row["result"] as? JsonObject
            if (result == null) {
                assertNull(completion, note)
                continue
            }
            val sources = row.getValue("sources").jsonArray.map { it.jsonObject }.map { WineWebSource(it.text("url")!!, it.text("title")) }
            val completed = completion!!.completing(wine, sources, date)
            val changed = row.getValue("changed").jsonPrimitive.booleanOrNull!!
            assertEquals(changed, completed != wine, note)
            assertEquals(changed, completed.aiCompletedAt == date, note)
            assertEquals(result.text("producer"), completed.producer, note)
            assertEquals(result.text("appellation"), completed.appellation, note)
            assertEquals(result.text("region"), completed.region, note)
            assertEquals(result.text("country"), completed.country, note)
            assertEquals(result.strings("grapes"), completed.grapes, note)
            assertEquals(result.text("classification"), completed.classification, note)
            assertEquals((result["abv"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull, completed.abv?.toDouble(), note)
            assertEquals((result["drinkFrom"] as? JsonPrimitive)?.intOrNull, completed.drinkFrom, note)
            assertEquals((result["drinkUntil"] as? JsonPrimitive)?.intOrNull, completed.drinkUntil, note)
            assertEquals(result.strings("expectedAromas"), completed.expectedProfile?.aromas, note)
            assertEquals(result.text("expectedSource"), completed.expectedProfile?.source, note)
            assertEquals(result.strings("webSources"), completed.webSources?.map { it.url }, note)
        }
    }

    @Test
    fun promptSendsOnlyTheIdentity() {
        val wine = Wine(name = "Les Galets", producer = "Domaine Martin", vintage = 2021, region = "Bourgogne", appellation = "Chablis", notes = "Secret note")
        val prompt = WebCompletion.prompt(WineIdentity(wine), "en")
        assertTrue("Name: Les Galets" in prompt && "- expected:" in prompt)
        assertFalse("Secret" in prompt || "Bourgogne" in prompt)
    }

    @Test
    fun webSourcesRoundTrip() {
        val wine = Wine(name = "X", webSources = listOf(WineWebSource("https://a.example", "A")))
        assertEquals(wine, CellarJson.json.decodeFromString(Wine.serializer(), CellarJson.json.encodeToString(Wine.serializer(), wine)))
        assertNull(Wine(name = "X", webSources = emptyList()).normalized().webSources)
    }
}
