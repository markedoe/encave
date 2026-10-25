package app.encave.core

import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EveningRequestTest {
    private fun JsonObject.string(key: String): String? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull

    /** The vectors shared with CaveKit, in `fixtures/evening-request-vectors.json`. */
    @Test
    fun textMatchesTheSharedVectors() {
        val root = Json.parseToJsonElement(File("../../fixtures/evening-request-vectors.json").readText()).jsonObject
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 5)
        for (case in cases) {
            val wines = case.getValue("wines").jsonArray.map { it.jsonObject }.map { wine ->
                EveningRequest.Line(
                    name = wine.string("name")!!, vintage = wine.int("vintage"), producer = wine.string("producer"),
                    style = wine.string("style"), appellation = wine.string("appellation"),
                    drinkFrom = wine.int("drinkFrom"), drinkUntil = wine.int("drinkUntil"),
                    maturity = wine.string("maturity")!!, bottles = wine.getValue("bottles").jsonPrimitive.int,
                    places = wine.getValue("places").jsonArray.map { it.jsonPrimitive.content },
                )
            }
            val request = EveningRequest(
                occasion = case.string("occasion")!!, bottlesWanted = case.int("bottlesWanted"),
                onlyReady = case.getValue("onlyReady").jsonPrimitive.boolean,
                language = EveningRequest.Language.of(case.string("language")), year = case.int("year")!!,
                wines = wines, omitted = case.int("omitted")!!,
            )
            assertEquals(case.string("text"), request.text, case.string("about"))
        }
    }

    @Test
    fun languageFollowsTheCode() {
        assertEquals(EveningRequest.Language.Fr, EveningRequest.Language.of("fr"))
        assertEquals(EveningRequest.Language.Fr, EveningRequest.Language.of("fr-CA"))
        assertEquals(EveningRequest.Language.En, EveningRequest.Language.of("de"))
        assertEquals(EveningRequest.Language.En, EveningRequest.Language.of(null))
    }

    private fun cellar(): Cellar {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Young", vintage = 2024, color = WineColor.Red, drinkFrom = 2030, drinkUntil = 2040), 2)
        cellar.addBottles(
            Wine(name = "Ready", producer = "Dom. B", vintage = 2020, color = WineColor.Red, region = "Loire", drinkFrom = 2022, drinkUntil = 2030),
            1, purchase = PurchaseInfo(price = BigDecimal(42), place = "Wine shop", storageNote = "Kitchen"),
        )
        val soon = cellar.addBottles(
            Wine(name = "Soon", producer = "Dom. A", vintage = 2018, color = WineColor.White, appellation = "Vouvray",
                drinkFrom = 2020, drinkUntil = 2026, notes = "Secret note"),
            2,
        )
        cellar.reserve(listOf(soon[0].id), "Christmas")
        return cellar
    }

    @Test
    fun readyBottlesComeMostUrgentFirstWithoutThoseKeptForAnOccasion() {
        val request = cellar().eveningRequest(occasion = "Roast chicken", bottlesWanted = 2, language = EveningRequest.Language.En, year = 2026)
        assertEquals(listOf("Soon", "Ready"), request.wines.map { it.name })
        assertEquals(listOf(1, 1), request.wines.map { it.bottles })
        assertEquals("Loire", request.wines[1].appellation)
        val text = request.text
        assertTrue(text.contains("Occasion: Roast chicken"))
        assertTrue(text.contains("2. Ready 2020 | Dom. B | red | Loire | 2022-2030 | ready | 1 | Kitchen"), text)
        for (secret in listOf("42", "Wine shop", "Secret note", "Christmas")) assertFalse(text.contains(secret), secret)
    }

    @Test
    fun everyBottleIsListedWhenNotOnlyReady() {
        val request = cellar().eveningRequest(onlyReady = false, language = EveningRequest.Language.Fr, year = 2026)
        assertEquals(listOf("Ready", "Soon", "Young"), request.wines.map { it.name })
        assertEquals(listOf(1, 2, 2), request.wines.map { it.bottles })
        assertFalse(request.text.contains("ne contient que"))
        assertTrue(request.text.contains("Ma cave en 2026 : 3 vins, 5 bouteilles."))
    }

    @Test
    fun aLimitLeavesWinesOut() {
        val request = cellar().eveningRequest(onlyReady = false, language = EveningRequest.Language.En, year = 2026, limit = 1)
        assertEquals(1, request.wines.size)
        assertEquals(2, request.omitted)
        assertTrue(request.text.contains("My cellar in 2026: 1 wine, 1 bottle (2 more wines left out)."))
    }
}
