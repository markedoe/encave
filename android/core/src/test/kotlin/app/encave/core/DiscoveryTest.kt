package app.encave.core

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoveryTest {
    /** The vectors shared with CaveKit, in `fixtures/tag-vectors.json` (tests run in android/core). */
    @Test
    fun tagsAreTrimmedOfUnicodeWhiteSpaceOnly() {
        val root = Json.parseToJsonElement(File("../../fixtures/tag-vectors.json").readText()).jsonObject
        val vectors = root.getValue("vectors").jsonArray.map { it.jsonObject }
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val tags = vector.getValue("tags").jsonArray.map { it.jsonPrimitive.content }
            val expected = vector.getValue("normalized").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, Discovery.normalized(tags), tags.toString())
            assertEquals(expected.ifEmpty { null }, Wine(name = "W", tags = tags).normalized().tags, tags.toString())
        }
    }

    @Test
    fun discoveredWineIsNotABottleInTheCellar() {
        val cellar = Cellar()
        val wine = Wine(name = "Alvarinho", vintage = 2023, color = WineColor.White)
        cellar.addDiscovery(Discovery(wineId = wine.id, wantsToBuy = true), wine)
        assertNotNull(cellar.wines[wine.id])
        assertTrue(cellar.bottlesInCellar.isEmpty())
        assertTrue(cellar.groups("").isEmpty())
        assertEquals(0, cellar.statistics(2026).wineCount)
        assertEquals(1, cellar.shoppingListCount)
    }

    @Test
    fun sameWineIsLinkedInsteadOfDuplicated() {
        val cellar = Cellar()
        val wine = Wine(name = "Clos X", producer = "Dom Y", vintage = 2018)
        cellar.addBottles(wine, quantity = 1)
        val again = Wine(name = "clos x ", producer = "Dom Y", vintage = 2018, photoId = "photo")
        val stored = cellar.addDiscovery(Discovery(wineId = again.id), again)
        assertEquals(wine.id, stored.wineId)
        assertEquals(1, cellar.wines.size)
        assertEquals("photo", cellar.wines[wine.id]?.photoId)
    }

    @Test
    fun deletingTheLastReferenceRemovesTheWine() {
        val cellar = Cellar()
        val wine = Wine(name = "Gamay")
        val discovery = cellar.addDiscovery(Discovery(wineId = wine.id), wine)
        val bottle = cellar.addBottles(wine, quantity = 1).first()
        cellar.deleteBottle(bottle.id)
        assertNotNull(cellar.wines[wine.id], "still referenced by the discovery")
        cellar.deleteDiscovery(discovery.id)
        assertNull(cellar.wines[wine.id])
    }

    @Test
    fun deletingTheWineRemovesItsDiscoveries() {
        val cellar = Cellar()
        val wine = Wine(name = "Gamay")
        cellar.addDiscovery(Discovery(wineId = wine.id), wine)
        cellar.deleteWine(wine.id)
        assertTrue(cellar.discoveries.isEmpty())
    }

    @Test
    fun filtersByShoppingListTagAndText() {
        val cellar = SampleData.cellar(year = 2026)
        assertEquals(2, cellar.discoveries(DiscoveryFilter(toBuyOnly = true)).size)
        assertEquals(1, cellar.discoveries(DiscoveryFilter(tag = "gift idea")).size)
        assertEquals(1, cellar.discoveries(DiscoveryFilter(query = "lisbon")).size)
        assertEquals(1, cellar.discoveries(DiscoveryFilter(query = "Vinho verde")).size)
        val dates = cellar.discoveries().map { it.date }
        assertEquals(dates.sortedDescending(), dates)
    }

    @Test
    fun tagsAreTrimmedAndDeduplicated() {
        val discovery = Discovery(wineId = UUID.randomUUID(), tags = listOf(" To buy", "to buy", "", "Gift")).normalized()
        assertEquals(listOf("To buy", "Gift"), discovery.tags)
        val cellar = Cellar()
        val wine = Wine(name = "W")
        val stored = cellar.addDiscovery(Discovery(wineId = wine.id, rating = 9, tags = listOf("a", "A ")), wine)
        assertEquals(5, stored.rating)
        assertEquals(listOf("a"), stored.tags)
    }

    @Test
    fun markBoughtLeavesTheShoppingList() {
        val cellar = SampleData.cellar(year = 2026)
        val item = cellar.discoveries(DiscoveryFilter(toBuyOnly = true)).first()
        cellar.markBought(item.id)
        assertFalse(cellar.discoveries.getValue(item.id).wantsToBuy)
        assertNotNull(cellar.discoveries.getValue(item.id).boughtAt)
    }

    @Test
    fun updatingAnUnknownDiscoveryFails() {
        expectError(CellarError.UnknownDiscovery) { Cellar().updateDiscovery(Discovery(wineId = UUID.randomUUID())) }
    }

    @Test
    fun tagsAreSortedByUse() {
        val cellar = Cellar()
        val wine = Wine(name = "W")
        cellar.addDiscovery(Discovery(wineId = wine.id, tags = listOf("Beta", "Alpha")), wine)
        cellar.addDiscovery(Discovery(wineId = wine.id, tags = listOf("beta")), wine)
        assertEquals(listOf("Beta", "Alpha"), cellar.discoveryTags)
    }

    @Test
    fun discoveriesSurviveBackupAndMerge() {
        val cellar = SampleData.cellar(year = 2026)
        val restored = CellarExport.backupFromJson(CellarExport.json(cellar)).cellar
        assertEquals(cellar.discoveries.keys, restored.discoveries.keys)
        assertEquals(cellar.discoveries.values.flatMap { it.tags }.toSet(), restored.discoveries.values.flatMap { it.tags }.toSet())
        assertEquals(cellar.shoppingListCount, restored.shoppingListCount)
        val empty = Cellar()
        empty.merge(cellar)
        assertEquals(cellar.discoveries.size, empty.discoveries.size)
    }

    @Test
    fun olderFilesWithoutDiscoveriesStillLoad() {
        val cellar = CellarJson.decode("""{"schemaVersion":2,"wines":[],"bottles":[],"racks":[]}""")
        assertTrue(cellar.discoveries.isEmpty())
    }
}
