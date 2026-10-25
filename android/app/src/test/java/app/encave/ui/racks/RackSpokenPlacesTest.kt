package app.encave.ui.racks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.core.WineColor
import app.encave.core.storageType
import app.encave.ui.components.labelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack reads on a rack: maturity, the contents of a bin, and the state while checking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RackSpokenPlacesTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun placesSayTheMaturity() {
        val cellar = Cellar()
        val rack = Rack(name = "Grid", rows = 2, columns = 2)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "Clos", vintage = 2018, color = WineColor.Red, drinkFrom = 2020, drinkUntil = 2030), slots = listOf(Slot(rack.id, 0, 0)))
        val cell = RackCell(rack.id, 0, 0)
        val text = cellDescription(rack, cell, cellar.bottlesIn(cell), cellar, resources, year = 2025)
        assertEquals("A1, Clos 2018, Red, Ready", text)
    }

    @Test
    fun binsListTheirWines() {
        val cellar = Cellar()
        val rack = Rack(name = "Bins", kind = RackKind.Bins, rows = 1, columns = 1, binCapacity = 10)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "X", vintage = 2015), quantity = 2, slots = listOf(Slot(rack.id, 0, 0, 0), Slot(rack.id, 0, 0, 1)))
        cellar.addBottles(Wine(name = "Y"), slots = listOf(Slot(rack.id, 0, 0, 2)))
        val cell = RackCell(rack.id, 0, 0)
        val text = cellDescription(rack, cell, cellar.bottlesIn(cell), cellar, resources)
        assertEquals("Bin A1, 3 of 10 bottles: 2 X 2015, 1 Y", text)
    }

    @Test
    fun checkingSaysFoundOrNot() {
        val cellar = Cellar()
        val rack = Rack(name = "Grid", rows = 1, columns = 1)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 0, 0)))
        val bottles = cellar.bottlesIn(RackCell(rack.id, 0, 0))
        assertEquals("Found", cellCheckState(bottles, 1, resources))
        assertEquals("Not found yet", cellCheckState(bottles, 0, resources))
        assertNull(cellCheckState(bottles, null, resources))
        assertNull(cellCheckState(emptyList(), 0, resources))
    }

    @Test
    fun binsCapTheirListAtThreeWines() {
        val cellar = Cellar()
        val rack = Rack(name = "Bins", kind = RackKind.Bins, rows = 1, columns = 1, binCapacity = 12)
        cellar.addRack(rack)
        listOf("A", "B", "C", "D", "E").forEachIndexed { i, name ->
            cellar.addBottles(Wine(name = name), slots = listOf(Slot(rack.id, 0, 0, i)))
        }
        val cell = RackCell(rack.id, 0, 0)
        val text = cellDescription(rack, cell, cellar.bottlesIn(cell), cellar, resources)
        assertEquals("Bin A1, 5 of 12 bottles: 1 A, 1 B, 1 C, 2 more", text)
    }

    @Test
    fun partlyFoundPlacesSayHowMany() {
        val cellar = Cellar()
        val rack = Rack(name = "Bins", kind = RackKind.Bins, rows = 1, columns = 1, binCapacity = 4)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "W"), quantity = 2, slots = listOf(Slot(rack.id, 0, 0, 0), Slot(rack.id, 0, 0, 1)))
        val cell = RackCell(rack.id, 0, 0)
        val bottles = cellar.bottlesIn(cell)
        assertNull(cellCheckState(bottles, 1, resources))
        assertEquals("Bin A1, 1 of 2 found", cellDescription(rack, cell, bottles, cellar, resources, found = 1))
    }

    @Test
    @Config(qualifiers = "fr")
    fun checkingInFrench() {
        val cellar = Cellar()
        val rack = Rack(name = "Grille", rows = 1, columns = 1)
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "W"), slots = listOf(Slot(rack.id, 0, 0)))
        val bottles = cellar.bottlesIn(RackCell(rack.id, 0, 0))
        assertEquals("Trouvée", cellCheckState(bottles, 1, resources))
        assertEquals("Pas encore trouvée", cellCheckState(bottles, 0, resources))
    }

    @Test
    fun deepShelvesSayTheSideBeforeThePosition() {
        val cellar = Cellar()
        val rack = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        cellar.addRack(rack)
        val cell = RackCell(rack.id, 1, 2)
        assertEquals("Shelf\u00A02, back, position\u00A03, empty", cellDescription(rack, cell, emptyList(), cellar, resources, depth = 1))
        assertEquals("Shelf\u00A02, position\u00A03, empty", cellDescription(rack, cell, emptyList(), cellar, resources))
    }

    @Test
    fun aRowOneDeepBetweenDeeperOnesSaysNoSide() {
        // Rows two, one and two deep: the single row reads as one position, the others side by side.
        val rack = Rack(name = "Mixed", rows = 3, columns = 4, depth = 2, rowDepths = listOf(2, 1, 2)).normalized()
        val cellar = Cellar()
        cellar.addRack(rack)
        cellar.addBottles(Wine(name = "Clos"), slots = listOf(Slot(rack.id, 1, 2)))
        val single = RackCell(rack.id, 1, 2)
        assertEquals("B3, Clos", cellDescription(rack, single, cellar.bottlesIn(single), cellar, resources))
        val deep = RackCell(rack.id, 0, 0)
        assertEquals("A1: front empty; back empty", cellDescription(rack, deep, emptyList(), cellar, resources))
        // Taps on the single row reach a position of one bottle; on the others, a cell of two.
        assertEquals(true, RackPlace.of(Slot(rack.id, 1, 2), rack).holdsOne(rack))
        assertEquals(false, RackPlace.of(Slot(rack.id, 0, 0, 1), rack).holdsOne(rack))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aShelfOneDeepOfALayeredFridgeIsReadOnceInFrench() {
        val fridge = Rack(
            name = "F", kind = RackKind.Staggered, rows = 5, columns = 6, depth = 2, shelfLayers = listOf(2, 1, 2),
            rowDepths = listOf(2, 2, 1, 2, 2), backReversed = true,
        ).normalized()
        val cellar = Cellar()
        cellar.addRack(fridge)
        val single = RackCell(fridge.id, 2, 1)
        assertEquals("Étagère\u00A02, position\u00A02, vide", cellDescription(fridge, single, emptyList(), cellar, resources))
        assertEquals(
            "Étagère\u00A01, fond, couche du bas, position\u00A03, vide",
            cellDescription(fridge, RackCell(fridge.id, 1, 2), emptyList(), cellar, resources, depth = 1),
        )
    }

    @Test
    fun fridgesOnShelvesAreNotCalledHoneycombs() {
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 4, columns = 6, depth = 2, shelfLayers = listOf(2, 2), backReversed = true)
        assertEquals("Wine fridge", resources.getString(fridge.storageType.labelRes))
        val honeycomb = Rack(name = "H", kind = RackKind.Staggered, rows = 4, columns = 6)
        assertEquals("Rack", resources.getString(honeycomb.storageType.labelRes))
    }
}
