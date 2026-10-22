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
