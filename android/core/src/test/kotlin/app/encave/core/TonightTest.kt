package app.encave.core

import java.time.LocalDate
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

    @Test
    fun startIndexChangesDailyAndStaysInRange() {
        assertEquals(0, tonightStartIndex(1, 3))
        assertEquals(1, tonightStartIndex(2, 3))
        assertEquals(0, tonightStartIndex(4, 3))
        assertEquals(0, tonightStartIndex(200, 0))
    }

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
