package app.encave.core

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The aroma game (`fixtures/aroma-game-vectors.json`), as CaveKit's AromaGameTests, and where its games are kept. */
class AromaGameTest {
    private val vectors = sharedFixture("aroma-game-vectors.json")
    private fun rows(key: String) = vectors.getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), key) }
    private fun strings(row: JsonObject, key: String) = row.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun levels(row: JsonObject, key: String) = row.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.intOrNull!! }
    private fun int(row: JsonObject, key: String) = (row[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull
    private fun text(row: JsonObject, key: String) = (row[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    @Test
    fun scoreVectors() {
        for (row in rows("scores")) {
            val note = text(row, "note")
            val score = AromaGame.score(strings(row, "guesses"), levels(row, "structure"), strings(row, "expected"), levels(row, "expectedStructure"))
            assertEquals(int(row, "points"), score.points, note)
            assertEquals(int(row, "maxPoints"), score.maxPoints, note)
            assertEquals(int(row, "accuracy"), score.accuracy, note)
            assertEquals(strings(row, "exact"), score.exact, note)
            assertEquals(row.getValue("family").jsonArray.map { pair -> pair.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } }, score.family, note)
            assertEquals(strings(row, "missed"), score.missed, note)
            assertEquals(strings(row, "wrong"), score.wrong, note)
        }
    }

    @Test
    fun levelVectors() {
        for (pair in vectors.getValue("levels").jsonArray.map { it.jsonArray }) {
            assertEquals(pair[1].jsonPrimitive.content, Palate.Level.of(pair[0].jsonPrimitive.intOrNull!!).raw)
        }
    }

    @Test
    fun palateVectors() {
        fun noon(day: String) = Instant.parse("${day}T12:00:00Z")
        for (row in rows("palate")) {
            val note = text(row, "note")
            val records = (row.getValue("games") as JsonArray).map { it.jsonObject }.map { game ->
                AromaGameRecord(
                    wineId = UUID.randomUUID(), date = noon(text(game, "day")!!), guesses = strings(game, "guesses"),
                    structure = levels(game, "structure"), expected = strings(game, "expected"), expectedStructure = levels(game, "expectedStructure"),
                )
            }
            val palate = Palate.of(records, noon(text(row, "today")!!), ZoneOffset.UTC)
            val result = row.getValue("result").jsonObject
            assertEquals(int(result, "points"), palate.points, note)
            assertEquals(text(result, "level"), palate.level.raw, note)
            assertEquals(int(result, "accuracy"), palate.accuracy, note)
            assertEquals(int(result, "streak"), palate.streak, note)
            assertEquals(text(result, "best"), palate.bestFamily, note)
            assertEquals(text(result, "weakest"), palate.weakestFamily, note)
            assertEquals(int(result, "games"), palate.games, note)
        }
    }

    /** Games stay in a file of this device: never in the cellar, its sync payload, backups or exports. */
    @Test
    fun gamesStayOnTheDevice() {
        val folder = Files.createTempDirectory("aroma-game").toFile()
        val store = AromaGameStore(java.io.File(folder, "aroma-game/games.json"))
        val wine = Wine(name = "Clos", color = WineColor.Red)
        val record = AromaGameRecord(wineId = wine.id, date = Instant.now(), guesses = listOf("fruit.redCherry"), expected = listOf("fruit.redCherry"))
        store.add(record)
        assertEquals(listOf(record.copy(date = store.load().single().date)), store.load())
        val cellar = Cellar(wines = listOf(wine))
        for (text in listOf(CellarJson.encode(cellar), CellarJson.backup(cellar), CellarExport.csv(cellar))) {
            assertFalse(record.id.toString() in text || "guesses" in text || "aromaGame" in text)
        }
        store.removeAll()
        assertTrue(store.load().isEmpty())
        folder.deleteRecursively()
    }
}
