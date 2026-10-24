package app.encave.core

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Search by words and a stable cellar order. CaveKit (SearchTests.swift) reads the same
 * vectors, in `fixtures/search` at the top of the repository.
 */
class SearchTest {
    private class Search(val query: String, val matches: List<String>)

    /** Tests run in android/core. */
    private fun vectors(): Triple<List<Wine>, List<Search>, List<Search>> {
        val root = Json.parseToJsonElement(File("../../fixtures/search/wine-matching.json").readText()).jsonObject
        fun strings(element: kotlinx.serialization.json.JsonElement?) = element?.jsonArray?.map { it.jsonPrimitive.content }
        val wines = root.getValue("wines").jsonArray.map { item ->
            val o = item.jsonObject
            fun text(key: String) = o[key]?.jsonPrimitive?.content
            Wine(
                name = text("name")!!, producer = text("producer"), vintage = o["vintage"]?.jsonPrimitive?.int,
                appellation = text("appellation"), region = text("region"), country = text("country"),
                grapes = strings(o["grapes"]).orEmpty(), barcodes = strings(o["barcodes"]), tags = strings(o["tags"]),
            )
        }
        val searches = root.getValue("searches").jsonArray.map { item ->
            val o = item.jsonObject
            Search(o.getValue("query").jsonPrimitive.content, strings(o["matches"]).orEmpty())
        }
        val suggestions = root.getValue("suggestions").jsonArray.map { item ->
            val o = item.jsonObject
            Search(o.getValue("query").jsonPrimitive.content, strings(o["names"]).orEmpty())
        }
        return Triple(wines, searches, suggestions)
    }

    @Test
    fun everyWordIsFoundInSomeField() {
        val (wines, searches) = vectors()
        assertTrue(searches.isNotEmpty())
        searches.forEach { search ->
            val found = wines.filter { it.matches(search.query) }.sortedWith { a, b -> Cellar.nameOrder(a, b) }.map { it.name }
            assertEquals(search.matches, found, search.query)
        }
    }

    @Test
    fun suggestionsComeInNameOrder() {
        val (wines, _, suggestions) = vectors()
        val cellar = Cellar()
        wines.forEach { cellar.addBottles(it) }
        assertTrue(suggestions.isNotEmpty())
        suggestions.forEach { search ->
            assertEquals(search.matches, cellar.wineSuggestions(search.query).map { it.name }, search.query)
        }
    }

    @Test
    fun cellarListJournalAndDiscoveriesSearchByWords() {
        val cellar = Cellar()
        val palmer = Wine(name = "Château Palmer", vintage = 2015, appellation = "Margaux")
        val guigal = Wine(name = "La Landonne", producer = "E. Guigal", grapes = listOf("Syrah"))
        cellar.addBottles(palmer, quantity = 2)
        cellar.addBottles(guigal)
        assertEquals(listOf(palmer.id), cellar.groups("margaux 2015").map { it.wine.id })
        assertTrue(cellar.groups("margaux 2016").isEmpty())
        assertEquals(listOf(guigal.id), cellar.wineSuggestions("guigal syrah").map { it.id })

        // A tasting: the words may be in the wine or in the note.
        val bottle = cellar.bottlesOf(palmer.id).first()
        cellar.drink(bottle.id, Tasting(note = "Silky, long finish", occasion = "Birthday"))
        assertEquals(listOf(bottle.id), cellar.tastings("palmer silky").map { it.id })
        assertEquals(listOf(bottle.id), cellar.tastings("birthday finish").map { it.id })
        assertTrue(cellar.tastings("palmer tannic").isEmpty())

        // A wine tasted elsewhere: the wine, the place, the note and the tags.
        val found = Wine(name = "Rully", producer = "Domaine Jacqueson")
        val discovery = cellar.addDiscovery(Discovery(wineId = found.id, place = "Bistro, Lisbon", note = "Mineral"), found)
        assertEquals(listOf(discovery.id), cellar.discoveries(DiscoveryFilter(query = "rully lisbon")).map { it.id })
        assertEquals(listOf(discovery.id), cellar.discoveries(DiscoveryFilter(query = "jacqueson mineral")).map { it.id })
        assertTrue(cellar.discoveries(DiscoveryFilter(query = "rully paris")).isEmpty())
    }

    @Test
    fun suggestionsCanKeepOnlyWinesInTheCellar() {
        val cellar = Cellar()
        // Drunk wines first by name: without the flag they would fill every suggestion.
        val drunk = (1..8).map { Wine(name = "Bourgogne A$it") }
        drunk.forEach { wine -> cellar.addBottles(wine).forEach { cellar.drink(it.id, Tasting()) } }
        val kept = Wine(name = "Bourgogne Z")
        cellar.addBottles(kept)
        assertEquals(8, cellar.wineSuggestions("bourgogne").size)
        assertTrue(kept.id !in cellar.wineSuggestions("bourgogne").map { it.id })
        assertEquals(listOf(kept.id), cellar.wineSuggestions("bourgogne", inCellarOnly = true).map { it.id })
    }

    /**
     * Wines alike (same name, vintage and window) always come in the same order, whatever
     * order the records are stored in: every sort is a total order.
     */
    @Test
    fun everySortIsATotalOrder() {
        val added = Instant.ofEpochSecond(1_700_000_000)
        val cellar = Cellar()
        repeat(6) { index ->
            val producer = if (index < 2) null else if (index % 2 == 0) "Domaine B" else "Domaine A"
            cellar.addBottles(Wine(name = "Bourgogne", producer = producer, vintage = 2020, color = WineColor.Red), addedAt = added)
        }
        CellarSort.entries.forEach { sort ->
            val groups = cellar.groups(CellarFilter(), sort, 2026)
            assertEquals(6, groups.size)
            val reversed = Cellar()
            groups.reversed().forEach { reversed.addBottles(it.wine, addedAt = added) }
            assertEquals(groups.map { it.wine.id }, reversed.groups(CellarFilter(), sort, 2026).map { it.wine.id }, sort.name)
        }
        val producers = cellar.groups(CellarFilter(), CellarSort.Readiness, 2026).map { it.wine.producer.orEmpty() }
        assertEquals(listOf("", "", "Domaine A", "Domaine A", "Domaine B", "Domaine B"), producers)
    }
}
