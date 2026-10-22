package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetSnapshotTest {
    private val year = 2026

    @Test
    fun emptyCellarIsEmpty() {
        val snapshot = WidgetSnapshot.of(Cellar(), year)
        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.bottles)
        assertEquals(0, snapshot.wines)
        assertTrue(snapshot.tonight.isEmpty())
    }

    @Test
    fun countsMatchTheCellar() {
        val cellar = SampleData.cellar(year = year)
        val snapshot = WidgetSnapshot.of(cellar, year)
        val digest = cellar.drinkingDigest(year)
        assertEquals(cellar.bottlesInCellar.size, snapshot.bottles)
        assertEquals(cellar.groups().size, snapshot.wines)
        assertEquals(cellar.bottlesOnOrder.size, snapshot.onOrder)
        assertEquals(digest.drinkSoon, snapshot.drinkSoon)
        assertEquals(digest.pastPeak, snapshot.pastPeak)
        assertTrue(snapshot.drinkSoon + snapshot.ready + snapshot.young + snapshot.pastPeak <= snapshot.bottles)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun picksAreUrgentFirstAndLimited() {
        val cellar = SampleData.cellar(year = year)
        val snapshot = WidgetSnapshot.of(cellar, year)
        assertFalse(snapshot.tonight.isEmpty())
        assertTrue(snapshot.tonight.size <= WidgetSnapshot.PICK_LIMIT)
        val maturities = snapshot.tonight.map { it.maturity }
        assertEquals(maturities.sorted(), maturities)
        assertTrue(maturities.all { it in setOf(Maturity.DrinkSoon, Maturity.Ready, Maturity.PastPeak) })
        assertTrue(WidgetSnapshot.of(cellar, year, pickLimit = 2).tonight.size <= 2)
        assertTrue(WidgetSnapshot.of(cellar, year, pickLimit = -1).tonight.isEmpty())
    }

    @Test
    fun theWindowThatClosesFirstComesFirst() {
        val cellar = Cellar()
        cellar.addBottles(Wine(name = "A later", drinkFrom = 2020, drinkUntil = 2030))
        cellar.addBottles(Wine(name = "B sooner", drinkFrom = 2020, drinkUntil = 2028))
        cellar.addBottles(Wine(name = "C last year", drinkFrom = 2020, drinkUntil = year))
        val names = WidgetSnapshot.of(cellar, year).tonight.map { it.name }
        assertEquals(listOf("C last year", "B sooner", "A later"), names)
    }

    @Test
    fun pickCarriesNamePlaceAndCount() {
        val cellar = Cellar()
        val rack = Rack(name = "Main", rows = 2, columns = 2)
        cellar.addRack(rack)
        val wine = Wine(name = "Clos Test", vintage = 2018, color = WineColor.Red, drinkFrom = 2020, drinkUntil = year)
        cellar.addBottles(wine, quantity = 2, slots = listOf(Slot(rack.id, 0, 0)))
        val snapshot = WidgetSnapshot.of(cellar, year)
        val pick = snapshot.tonight.first()
        assertEquals(wine.id.toString(), pick.wineId)
        assertEquals("Clos Test", pick.name)
        assertEquals(2018, pick.vintage)
        assertEquals(WineColor.Red, pick.color)
        assertEquals(Maturity.DrinkSoon, pick.maturity)
        assertEquals(2, pick.bottles)
        // One bottle has a place, the other none: the pick names that place.
        val placed = cellar.bottlesOf(wine.id).first { it.slot != null }
        assertEquals(cellar.locationLabel(placed), pick.place)
        assertNotNull(pick.place)
        assertEquals(2, snapshot.drinkSoon)
        // The app names places as its lists do.
        val named = WidgetSnapshot.of(cellar, year, place = { bottle -> bottle.slot?.let { "Main · Shelf ${it.row + 1}" } })
        assertEquals("Main · Shelf 1", named.tonight.first().place)
    }

    @Test
    fun reservedAndYoungBottlesAreNotSuggested() {
        val cellar = Cellar()
        val ids = cellar.addBottles(Wine(name = "Kept", drinkFrom = 2020, drinkUntil = 2030)).map { it.id }
        cellar.addBottles(Wine(name = "Young", drinkFrom = 2030, drinkUntil = 2040))
        cellar.reserve(ids, "Birthday")
        val snapshot = WidgetSnapshot.of(cellar, year)
        assertTrue(snapshot.tonight.isEmpty())
        assertEquals(2, snapshot.bottles)
        assertEquals(1, snapshot.ready)
        assertEquals(1, snapshot.young)
    }

    @Test
    fun ordersCountButAreNotInTheCellar() {
        val cellar = Cellar()
        cellar.addBottles(
            Wine(name = "Primeur", drinkFrom = 2020, drinkUntil = 2030), quantity = 3,
            purchase = PurchaseInfo(isOrdered = true),
        )
        val snapshot = WidgetSnapshot.of(cellar, year)
        assertEquals(3, snapshot.onOrder)
        assertEquals(0, snapshot.bottles)
        assertTrue(snapshot.tonight.isEmpty())
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun roundTripsAndIsStable() {
        val cellar = SampleData.cellar(year = year)
        val snapshot = WidgetSnapshot.of(cellar, year)
        val text = snapshot.encoded()
        assertEquals(snapshot, WidgetSnapshot.decode(text))
        assertEquals(text, WidgetSnapshot.of(cellar.copy(), year).encoded())
    }

    @Test
    fun readsTheAppleFormat() {
        // Written by CaveKit's WidgetSnapshot (sorted keys, uppercase UUID, no nil values).
        val text = """{"bottles":3,"drinkSoon":1,"onOrder":0,"pastPeak":0,"ready":2,"tonight":[""" +
            """{"bottles":1,"color":"red","maturity":"drinkSoon","name":"Clos","place":"Main · A1",""" +
            """"vintage":2018,"wineID":"0F8E2C4A-1B2C-4D5E-8F90-123456789ABC"}],"version":1,"wines":2,"year":2026,"young":0}"""
        val snapshot = WidgetSnapshot.decode(text)!!
        assertEquals(3, snapshot.bottles)
        assertEquals(Maturity.DrinkSoon, snapshot.tonight.single().maturity)
        assertEquals("Main · A1", snapshot.tonight.single().place)
    }

    @Test
    fun rejectsDamagedOrNewerData() {
        assertNull(WidgetSnapshot.decode("not json"))
        assertNull(WidgetSnapshot.decode(""))
        assertNull(WidgetSnapshot.decode(null))
        val newer = WidgetSnapshot.of(Cellar(), year).copy(version = WidgetSnapshot.CURRENT_VERSION + 1)
        assertNull(WidgetSnapshot.decode(newer.encoded()))
    }
}
