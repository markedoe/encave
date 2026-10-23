package app.encave.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Positions as people read them, in one order everywhere: shelf, side, layer, position.
 * Shelves are numbered from the top in every fridge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaceLabelsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun shelvesAreNumbered() {
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6)
        assertEquals("Shelf 2, position 3", fridge.displayLabel(Slot(fridge.id, 1, 2), resources))
        val grid = Rack(name = "Grid", rows = 4, columns = 6)
        assertEquals("B3", grid.displayLabel(Slot(grid.id, 1, 2), resources))
    }

    @Test
    fun deepShelvesNameTheSideBeforeThePosition() {
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 3)
        assertEquals("Shelf 1, front, position 6", fridge.displayLabel(Slot(fridge.id, 0, 5, 0), resources))
        assertEquals("Shelf 1, middle, position 6", fridge.displayLabel(Slot(fridge.id, 0, 5, 1), resources))
        assertEquals("Shelf 1, back, position 6", fridge.displayLabel(Slot(fridge.id, 0, 5, 2), resources))
    }

    @Test
    fun shelvesTwoDeepReadShelfSideThenPosition() {
        // Not "Shelf 2, place 3, back": the side comes before the position, as in layered fridges.
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        val name = fridge.displayLabel(Slot(fridge.id, 1, 2, 1), resources)
        assertEquals("Shelf 2, back, position 3", name)
        assertFalse(name.endsWith(", back"))
    }

    @Test
    fun layeredFridgesReadShelfSideLayerThenPosition() {
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 6, depth = 2, shelfLayers = listOf(2))
        assertEquals("Shelf 1, back, bottom layer, position 3", fridge.displayLabel(Slot(fridge.id, 1, 2, 1), resources))
        assertEquals("Shelf 1, top layer, position 3", fridge.copy(depth = 1).displayLabel(Slot(fridge.id, 0, 2), resources))
    }

    @Test
    fun binsAndHoneycombsKeepTheirCodes() {
        val bins = Rack(name = "Bins", kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 6)
        assertEquals("Bin B3", bins.displayLabel(Slot(bins.id, 1, 2, 4), resources))
        val honeycomb = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 5, depth = 2)
        assertEquals("C1, back", honeycomb.displayLabel(Slot(honeycomb.id, 2, 0, 1), resources))
    }

    @Test
    @Config(qualifiers = "fr")
    fun positionsInFrench() {
        val fridge = Rack(name = "Armoire", kind = RackKind.Shelves, rows = 4, columns = 6)
        assertEquals("Étagère 1, position 6", fridge.displayLabel(Slot(fridge.id, 0, 5), resources))
        val bands = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 6, depth = 2, shelfLayers = listOf(1, 1))
        assertEquals("Étagère 1, fond, position 3", bands.displayLabel(Slot(bands.id, 0, 2, 1), resources))
        assertEquals("Cave à vin réfrigérée, étagères en couches", resources.getString(bands.kindLabelRes))
        val deep = Rack(name = "Armoire", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        assertEquals("Étagère 2, fond, position 3", deep.displayLabel(Slot(deep.id, 1, 2, 1), resources))
        val bins = Rack(name = "Losanges", kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 6)
        assertEquals("Case B3", bins.displayLabel(Slot(bins.id, 1, 2, 4), resources))
        assertEquals("Cave à vin réfrigérée", resources.getString(RackKind.Shelves.labelRes))
        assertEquals("Rangements", resources.getString(app.encave.R.string.nav_racks))
    }
}
