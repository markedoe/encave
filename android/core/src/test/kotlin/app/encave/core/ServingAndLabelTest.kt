package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServingAdviceTest {
    @Test
    fun youngFullRedIsDecanted() {
        val advice = ServingAdvice.advice(Wine(name = "W", vintage = 2022, color = WineColor.Red, region = "Bordeaux"), 2026)!!
        assertEquals(16, advice.minTemperature)
        assertEquals(60, advice.decantMinutes)
    }

    @Test
    fun lightRedIsCooler() {
        val advice = ServingAdvice.advice(Wine(name = "W", color = WineColor.Red, grapes = listOf("Pinot Noir")), 2026)!!
        assertEquals(16, advice.maxTemperature)
        assertNull(advice.decantMinutes)
    }

    @Test
    fun sparklingIsColdAndUnknownStyleHasNoAdvice() {
        assertEquals(8, ServingAdvice.advice(Wine(name = "W", color = WineColor.Sparkling), 2026)!!.maxTemperature)
        assertNull(ServingAdvice.advice(Wine(name = "W"), 2026))
    }
}

class LabelParserTest {
    @Test
    fun readsNameProducerVintageAndAppellation() {
        val lines = listOf(
            LabelLine("DOMAINE ARNAUD", 0.05),
            LabelLine("CLOS DES TILLEULS", 0.12),
            LabelLine("2017", 0.06),
            LabelLine("Pauillac Appellation Pauillac Contrôlée", 0.03),
            LabelLine("Mis en bouteille au domaine", 0.02),
            LabelLine("13,5% vol 75 cl", 0.02),
        )
        val result = LabelTextParser.parse(lines, currentYear = 2026)
        assertEquals(2017, result.vintage)
        assertEquals("Domaine Arnaud", result.producer)
        assertTrue(result.name == "Clos des Tilleuls" || result.name == "Domaine Arnaud")
        assertTrue(result.appellation?.contains("Pauillac") == true)
    }

    @Test
    fun ignoresImplausibleYearsAndNoise() {
        val lines = listOf(LabelLine("Cuvée 1850", 0.1), LabelLine("Since 1789", 0.02), LabelLine("Tel 0123", 0.01))
        val result = LabelTextParser.parse(lines, currentYear = 2026)
        assertNull(result.vintage)
        assertEquals("Cuvée 1850", result.name)
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        assertTrue(LabelTextParser.parse(emptyList()).isEmpty)
    }

    @Test
    fun titleCaseKeepsMixedCase() {
        assertEquals("Les Pierres Blanches", LabelTextParser.titleCase("Les Pierres Blanches"))
        assertEquals("Les Pierres de la Roche", LabelTextParser.titleCase("LES PIERRES DE LA ROCHE"))
    }
}

class PhotoIdTest {
    @Test
    fun photoIdRoundTripsWithTheSwiftKey() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "W", photoId = "ABC-123"))
        val json = CellarExport.json(cellar)
        assertTrue(json.contains("\"photoID\": \"ABC-123\""))
        assertEquals("ABC-123", CellarExport.backupFromJson(json).cellar.wines.values.single().photoId)
    }
}
