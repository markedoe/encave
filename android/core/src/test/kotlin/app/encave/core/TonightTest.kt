package app.encave.core

import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TonightTest {
    @Test
    fun candidatesRankDrinkSoonThenReadyByEndOfWindowBottlesAndName() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Ready late", drinkFrom = 2020, drinkUntil = 2035), 1)
        cellar.addBottles(Wine(name = "Ready early B", drinkFrom = 2020, drinkUntil = 2028), 1)
        cellar.addBottles(Wine(name = "Ready early A", drinkFrom = 2020, drinkUntil = 2028), 1)
        cellar.addBottles(Wine(name = "Ready early C", drinkFrom = 2020, drinkUntil = 2028), 3)
        cellar.addBottles(Wine(name = "Soon", drinkFrom = 2020, drinkUntil = 2026), 1)
        cellar.addBottles(Wine(name = "Young", drinkFrom = 2030, drinkUntil = 2040), 1)
        cellar.addBottles(Wine(name = "Past", drinkFrom = 2010, drinkUntil = 2015), 1)
        val kept = cellar.addBottles(Wine(name = "Kept", drinkFrom = 2020, drinkUntil = 2026), 1)
        cellar.reserve(kept.map { it.id }, "Birthday")
        assertEquals(
            listOf("Soon", "Ready early C", "Ready early A", "Ready early B", "Ready late"),
            cellar.tonightCandidates(2026).map { it.wine.name },
        )
        assertEquals(1, cellar.reservedBottleCount)
    }

    /** The vectors shared with CaveKit, in `fixtures/tonight-vectors.json`. */
    @Test
    fun ranksAndOrdersMatchTheSharedVectors() {
        val root = Json.parseToJsonElement(File("../../fixtures/tonight-vectors.json").readText()).jsonObject
        val ranks = root.getValue("ranks").jsonArray
        val orders = root.getValue("orders").jsonArray
        assertTrue(ranks.isNotEmpty() && orders.isNotEmpty())
        for (vector in ranks.map { it.jsonObject }) {
            val rank = tonightRank(UUID.fromString(vector.string("wineId")), LocalDate.parse(vector.string("day")))
            assertEquals(vector.string("rank"), rank.toString(16).padStart(16, '0'))
        }
        for (vector in orders.map { it.jsonObject }) {
            val day = LocalDate.parse(vector.string("day"))
            val ids = vector.getValue("wineIds").jsonArray.map { it.jsonPrimitive.content }
            val expected = vector.getValue("order").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, ids.sortedBy { tonightRank(UUID.fromString(it), day) })
        }
    }

    /** Tonight's whole order in a cellar, from `picks` in the shared vectors (CaveKit reads them too). */
    @Test
    fun picksMatchTheSharedVectors() {
        val root = Json.parseToJsonElement(File("../../fixtures/tonight-vectors.json").readText()).jsonObject
        val picks = root.getValue("picks").jsonArray.map { it.jsonObject }
        assertTrue(picks.isNotEmpty())
        for (vector in picks) {
            val cellar = Cellar()
            val wines = vector.getValue("wines").jsonArray.map { it.jsonObject }
            assertTrue(wines.isNotEmpty())
            for (item in wines) {
                val wine = Wine(
                    id = UUID.fromString(item.string("id")), name = item.string("id"),
                    drinkFrom = item.int("from") ?: 2020, drinkUntil = item.int("until"),
                )
                val free = item.int("free") ?: 1
                val reserved = item.int("reserved") ?: 0
                val added = cellar.addBottles(wine, free + reserved)
                if (reserved > 0) cellar.reserve(added.takeLast(reserved).map { it.id }, "Birthday")
            }
            val kept = vector["kept"]?.jsonPrimitive?.content?.let(UUID::fromString)
            val expected = vector.getValue("order").jsonArray.map { it.jsonPrimitive.content.lowercase() }
            val day = LocalDate.parse(vector.string("day"))
            assertEquals(expected, cellar.tonightPicks(2026, day, kept).map { it.wine.id.toString() }, vector.string("about"))
        }
    }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.content?.toInt()

    @Test
    fun todaysPickStaysWhenOtherWinesComeAndGo() {
        val cellar = Cellar()
        for (index in 1..6) cellar.addBottles(Wine(name = "Wine $index", drinkFrom = 2020, drinkUntil = 2030), 1)
        val day = LocalDate.of(2026, 10, 22)
        val picks = cellar.tonightPicks(2026, day)
        assertEquals(6, picks.size)
        val first = picks.first().wine.id
        // Another wine drunk: the same first pick.
        cellar.drink(picks.last().bottles.first().id, Tasting(rating = 7))
        assertEquals(first, cellar.tonightPicks(2026, day).first().wine.id)
        // Wines added: the pick kept by the app stays first, whatever their rank.
        for (index in 1..6) cellar.addBottles(Wine(name = "New $index", drinkFrom = 2020, drinkUntil = 2030), 1)
        assertEquals(first, cellar.tonightPicks(2026, day, kept = first).first().wine.id)
        assertEquals(11, cellar.tonightPicks(2026, day, kept = first).size)
        // Kept but drunk: the best ranked candidate instead.
        cellar.drink(picks.first().bottles.first().id, Tasting(rating = 7))
        assertEquals(cellar.tonightPicks(2026, day).first().wine.id, cellar.tonightPicks(2026, day, kept = first).first().wine.id)
        // Another day, another order; the same set of wines.
        assertEquals(
            cellar.tonightPicks(2026, day).map { it.wine.id }.toSet(),
            cellar.tonightPicks(2026, day.plusDays(1)).map { it.wine.id }.toSet(),
        )
    }

    @Test
    fun picksPutDrinkSoonFirstThenReadyEachInTheDaysOrder() {
        val cellar = Cellar()
        for (index in 1..4) {
            cellar.addBottles(Wine(name = "Soon $index", drinkFrom = 2020, drinkUntil = 2026), 1)
            cellar.addBottles(Wine(name = "Ready $index", drinkFrom = 2020, drinkUntil = 2030), 1)
        }
        for (day in listOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3))) {
            val picks = cellar.tonightPicks(2026, day)
            assertTrue(picks.take(4).all { it.wine.name.startsWith("Soon") }, "$day")
            assertTrue(picks.takeLast(4).all { it.wine.name.startsWith("Ready") }, "$day")
            for (half in listOf(picks.take(4), picks.takeLast(4))) {
                val ranks = half.map { tonightRank(it.wine.id, day) }
                assertEquals(ranks.sorted(), ranks, "$day")
            }
            // The pick kept by the app stays first, even a ready one.
            val ready = picks.last()
            assertEquals(
                listOf(ready.wine.id) + picks.dropLast(1).map { it.wine.id },
                cellar.tonightPicks(2026, day, kept = ready.wine.id).map { it.wine.id },
            )
        }
    }

    @Test
    fun picksEdgeCases() {
        val cellar = Cellar()
        val day = LocalDate.of(2026, 1, 1)
        assertTrue(cellar.tonightPicks(2026, day).isEmpty())
        // A kept id that is not a candidate (young, unknown) changes nothing.
        cellar.addBottles(Wine(name = "Ready", drinkFrom = 2020, drinkUntil = 2030), 1)
        val young = cellar.addBottles(Wine(name = "Young", drinkFrom = 2030, drinkUntil = 2040), 1).first()
        assertEquals(listOf("Ready"), cellar.tonightPicks(2026, day, kept = young.wineId).map { it.wine.name })
        assertEquals(listOf("Ready"), cellar.tonightPicks(2026, day, kept = UUID.randomUUID()).map { it.wine.name })
        // Kept for an occasion: never suggested.
        val kept = cellar.addBottles(Wine(name = "Kept", drinkFrom = 2020, drinkUntil = 2030), 1)
        cellar.reserve(kept.map { it.id }, "Birthday")
        assertEquals(listOf("Ready"), cellar.tonightPicks(2026, day, kept = kept.first().wineId).map { it.wine.name })
    }

    /** The day key is ISO yyyy-MM-dd, as on Apple, also early in the year and on 29 February. */
    @Test
    fun dayKeyIsIsoDate() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        assertEquals(tonightRank(id, LocalDate.of(2028, 2, 29)), tonightRank(id, LocalDate.parse("2028-02-29")))
        assertTrue(tonightRank(id, LocalDate.of(2026, 1, 1)) != tonightRank(id, LocalDate.of(2026, 1, 2)))
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun nextToBeReadyIsTheYoungWineWhoseWindowOpensFirst() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "Later", drinkFrom = 2032, drinkUntil = 2040), 1)
        cellar.addBottles(Wine(name = "Sooner", drinkFrom = 2029, drinkUntil = 2040), 1)
        assertTrue(cellar.tonightCandidates(2026).isEmpty())
        assertEquals("Sooner", cellar.nextToBeReady(2026)?.wine?.name)
    }

    @Test
    fun drinkChoicesListEachPlaceOnceFreeFirst() {
        val cellar = Cellar()
        val wine = Wine(name = "Same place")
        val bottles = cellar.addBottles(wine, 3, purchase = PurchaseInfo(storageNote = "Cave"))
        cellar.reserve(listOf(bottles[0].id), "Wedding")
        val choices = cellar.bottlesOf(wine.id).drinkChoices
        assertEquals(2, choices.size)
        assertNull(choices.first().reservedFor)
        assertEquals("Wedding", choices.last().reservedFor)
        assertNull(cellar.bottlesOf(wine.id).bottleToOpen?.reservedFor)
    }

    @Test
    fun occasionsMatchInAnyLanguageIgnoringCaseAndAccents() {
        assertEquals(Occasion.DinnerAtHome, Occasion.matching("dîner à la maison"))
        assertEquals(Occasion.DinnerAtHome, Occasion.matching(" DINER A LA MAISON "))
        assertEquals(Occasion.WithFriends, Occasion.matching("With Friends"))
        assertEquals(Occasion.Celebration, Occasion.matching("fete"))
        assertNull(Occasion.matching("Paul's 40th"))
        assertNull(Occasion.matching(""))
    }

    @Test
    fun yearInWineCountsAChipTheSameInEveryLanguage() {
        val cellar = Cellar()
        val bottles = cellar.addBottles(Wine(name = "A"), 3)
        val date = LocalDate.of(2026, 3, 1).atTime(12, 0).toInstant(ZoneOffset.UTC)
        cellar.drink(bottles[0].id, Tasting(date = date, occasion = "Entre amis"))
        cellar.drink(bottles[1].id, Tasting(date = date, occasion = "with friends"))
        cellar.drink(bottles[2].id, Tasting(date = date, occasion = "Celebration"))
        assertEquals("With friends", cellar.yearInWine(2026, ZoneOffset.UTC).topOccasion)
    }
}
