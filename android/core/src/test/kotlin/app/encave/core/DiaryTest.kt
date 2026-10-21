package app.encave.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Same cases as CaveKit's DiaryTests. */
class DiaryTest {
    private val day = 86_400L
    private val start: Instant = InstantSerializer.fromReferenceSeconds(800_000_000.0)

    /** Two opened bottles (days 1 and 4), two wines tasted elsewhere (days 2 and 3, one to buy). */
    private fun cellar(): Cellar {
        val cellar = Cellar()
        val red = Wine(name = "Clos Rouge", producer = "Domaine A", vintage = 2015, color = WineColor.Red)
        val bottles = cellar.addBottles(red, quantity = 3)
        cellar.drink(bottles[0].id, Tasting(date = start.plusSeconds(day), rating = 4, note = "Cherry and smoke"))
        cellar.drink(bottles[1].id, Tasting(date = start.plusSeconds(4 * day), occasion = "Birthday"))
        val white = Wine(name = "Alvarinho", vintage = 2023, color = WineColor.White)
        cellar.addDiscovery(
            Discovery(wineId = white.id, date = start.plusSeconds(2 * day), setting = Discovery.Setting.Restaurant,
                place = "Lisbon", tags = listOf("Summer"), wantsToBuy = true),
            white,
        )
        val rose = Wine(name = "Tavel", color = WineColor.Rose)
        cellar.addDiscovery(
            Discovery(wineId = rose.id, date = start.plusSeconds(3 * day), setting = Discovery.Setting.Friends, note = "With paella"),
            rose,
        )
        return cellar
    }

    private fun Cellar.names(entries: List<DiaryEntry>) = entries.map { wines[it.wineId]?.name ?: "?" }

    @Test
    fun mixesBothKindsNewestFirst() {
        val cellar = cellar()
        val entries = cellar.diary()
        assertEquals(listOf("Clos Rouge", "Tavel", "Alvarinho", "Clos Rouge"), cellar.names(entries))
        assertEquals(entries.map { it.date }.sortedDescending(), entries.map { it.date })
    }

    @Test
    fun eachFilterShowsItsKind() {
        val cellar = cellar()
        assertTrue(cellar.diary(DiaryFilter.Cellar).all { it is DiaryEntry.Opened })
        assertEquals(2, cellar.diary(DiaryFilter.Cellar).size)
        assertEquals(listOf("Tavel", "Alvarinho"), cellar.names(cellar.diary(DiaryFilter.Elsewhere)))
        assertEquals(listOf("Alvarinho"), cellar.names(cellar.diary(DiaryFilter.ToBuy)))
    }

    @Test
    fun searchLooksInTastingsAndPlaces() {
        val cellar = cellar()
        assertEquals(listOf("Clos Rouge"), cellar.names(cellar.diary(query = "smoke")))
        assertEquals(listOf("Clos Rouge"), cellar.names(cellar.diary(query = "birthday")))
        assertEquals(listOf("Alvarinho"), cellar.names(cellar.diary(query = "lisbon")))
        assertEquals(listOf("Tavel"), cellar.names(cellar.diary(query = "paëlla")))
        assertEquals(listOf("Clos Rouge", "Clos Rouge"), cellar.names(cellar.diary(query = "domaine a")))
        assertTrue(cellar.diary(DiaryFilter.Cellar, query = "lisbon").isEmpty())
    }

    @Test
    fun aTagKeepsOnlyWinesTastedElsewhereWithIt() {
        val cellar = cellar()
        assertEquals(listOf("Alvarinho"), cellar.names(cellar.diary(tag = "summer")))
        assertTrue(cellar.diary(DiaryFilter.Cellar, tag = "summer").isEmpty())
    }

    @Test
    fun sameDateIsOrderedById() {
        val cellar = Cellar()
        val wine = Wine(name = "Gamay")
        val bottle = cellar.addBottles(wine, quantity = 1).first()
        cellar.drink(bottle.id, Tasting(date = start))
        val discovery = cellar.addDiscovery(Discovery(wineId = wine.id, date = start), wine)
        val expected = listOf(bottle.id, discovery.id).sortedBy { it.toString().uppercase() }
        assertEquals(expected, cellar.diary().map { it.id })
    }

    @Test
    fun anOpenedBottleWithoutTastingUsesTheDayItWasAdded() {
        val wine = Wine(name = "Gamay")
        val cellar = Cellar(wines = listOf(wine), bottles = listOf(Bottle(wineId = wine.id, status = Bottle.Status.Consumed, addedAt = start)))
        assertEquals(listOf(start), cellar.diary().map { it.date })
    }
}
