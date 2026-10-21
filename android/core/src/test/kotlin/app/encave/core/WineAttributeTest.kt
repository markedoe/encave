package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WineAttributeTest {
    private val a = Wine(
        name = "Les Clos", producer = "Domaine X", vintage = 2015, color = WineColor.Red, region = "Bourgogne",
        country = "France", appellation = "Chablis", grapes = listOf("Pinot Noir", " Gamay "), tags = listOf("Gift"),
    )
    private val b = Wine(
        name = "Aubade", producer = "  domaine x ", vintage = 2015, color = WineColor.White, region = "bourgogne",
        country = "FRANCE", appellation = "Meursault", grapes = listOf("gamay"), tags = listOf("gift "),
    )
    private val c = Wine(name = "Zéphyr", producer = "Domaine Y", vintage = 2018, color = WineColor.Red, grapes = listOf("Syrah"))
    private val ordered = Wine(name = "En primeur", producer = "Domaine X", vintage = 2022, color = WineColor.Red)
    private val drunk = Wine(name = "Bue", producer = "Domaine X", vintage = 2015, color = WineColor.Red)

    private val cellar = Cellar(
        listOf(a, b, c, ordered, drunk),
        listOf(
            Bottle(wineId = a.id), Bottle(wineId = a.id), Bottle(wineId = a.id, status = Bottle.Status.Ordered),
            Bottle(wineId = b.id),
            Bottle(wineId = c.id), Bottle(wineId = c.id),
            Bottle(wineId = ordered.id, status = Bottle.Status.Ordered), Bottle(wineId = ordered.id, status = Bottle.Status.Ordered),
            Bottle(wineId = drunk.id, status = Bottle.Status.Consumed), Bottle(wineId = drunk.id, status = Bottle.Status.Gone),
        ),
    )

    private fun names(attribute: WineAttribute) = cellar.winesSharing(attribute).map { it.wine.name }

    @Test
    fun producerIgnoresCaseAndSpaces() {
        assertEquals(listOf("Aubade", "En primeur", "Les Clos"), names(WineAttribute.Producer("domaine X")))
        assertEquals(listOf("Zéphyr"), names(WineAttribute.Producer(" Domaine Y")))
    }

    @Test
    fun regionCountryAppellation() {
        assertEquals(listOf("Aubade", "Les Clos"), names(WineAttribute.Region("BOURGOGNE")))
        assertEquals(listOf("Aubade", "Les Clos"), names(WineAttribute.Country("france ")))
        assertEquals(listOf("Les Clos"), names(WineAttribute.Appellation("chablis")))
    }

    @Test
    fun eachGrapeOnItsOwn() {
        assertEquals(listOf("Aubade", "Les Clos"), names(WineAttribute.Grape("Gamay")))
        assertEquals(listOf("Les Clos"), names(WineAttribute.Grape("pinot noir")))
        assertTrue(names(WineAttribute.Grape("Pinot")).isEmpty())
    }

    @Test
    fun vintageColorAndTag() {
        assertEquals(listOf("Aubade", "Les Clos"), names(WineAttribute.Vintage(2015)))
        assertEquals(listOf("En primeur", "Les Clos", "Zéphyr"), names(WineAttribute.Color(WineColor.Red)))
        assertEquals(listOf("Aubade", "Les Clos"), names(WineAttribute.Tag("GIFT")))
    }

    @Test
    fun blankValueMatchesNothing() {
        assertTrue(names(WineAttribute.Producer("  ")).isEmpty())
        assertTrue(names(WineAttribute.Tag("")).isEmpty())
    }

    @Test
    fun countsBottlesInTheCellarOrOnOrderOnly() {
        val groups = cellar.winesSharing(WineAttribute.Producer("Domaine X"))
        // Les Clos: its 2 bottles in the cellar (the one on order is left out of the row);
        // En primeur: its 2 bottles on order; Bue: no bottle left, not listed.
        assertEquals(listOf(1, 2, 2), groups.map { it.count })
        assertEquals(5, groups.sumOf { it.count })
        assertTrue(groups.first { it.wine.id == ordered.id }.bottles.all { it.status == Bottle.Status.Ordered })
        assertTrue(groups.first { it.wine.id == a.id }.bottles.all { it.status == Bottle.Status.InCellar })
    }

    @Test
    fun wineHasAttribute() {
        assertTrue(a.has(WineAttribute.Grape(" gamay")))
        assertFalse(a.has(WineAttribute.Vintage(2016)))
        assertFalse(c.has(WineAttribute.Region("Bourgogne")))
    }
}
