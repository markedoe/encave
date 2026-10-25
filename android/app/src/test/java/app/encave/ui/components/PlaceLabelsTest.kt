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
        assertEquals("Shelf\u00A02, position\u00A03", fridge.displayLabel(Slot(fridge.id, 1, 2), resources))
        val grid = Rack(name = "Grid", rows = 4, columns = 6)
        assertEquals("B3", grid.displayLabel(Slot(grid.id, 1, 2), resources))
    }

    @Test
    fun deepShelvesNameTheSideBeforeThePosition() {
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 3)
        assertEquals("Shelf\u00A01, front, position\u00A06", fridge.displayLabel(Slot(fridge.id, 0, 5, 0), resources))
        assertEquals("Shelf\u00A01, middle, position\u00A06", fridge.displayLabel(Slot(fridge.id, 0, 5, 1), resources))
        assertEquals("Shelf\u00A01, back, position\u00A06", fridge.displayLabel(Slot(fridge.id, 0, 5, 2), resources))
    }

    @Test
    fun shelvesTwoDeepReadShelfSideThenPosition() {
        // Not "Shelf 2, place 3, back": the side comes before the position, as in layered fridges.
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        val name = fridge.displayLabel(Slot(fridge.id, 1, 2, 1), resources)
        assertEquals("Shelf\u00A02, back, position\u00A03", name)
        assertFalse(name.endsWith(", back"))
    }

    @Test
    fun layeredFridgesReadShelfSideLayerThenPosition() {
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 6, depth = 2, shelfLayers = listOf(2))
        assertEquals("Shelf\u00A01, back, bottom layer, position\u00A03", fridge.displayLabel(Slot(fridge.id, 1, 2, 1), resources))
        assertEquals("Shelf\u00A01, top layer, position\u00A03", fridge.copy(depth = 1).displayLabel(Slot(fridge.id, 0, 2), resources))
    }

    @Test
    fun binsAndHoneycombsKeepTheirCodes() {
        val bins = Rack(name = "Bins", kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 6)
        assertEquals("Bin B3", bins.displayLabel(Slot(bins.id, 1, 2, 4), resources))
        val honeycomb = Rack(name = "H", kind = RackKind.Staggered, rows = 3, columns = 5, depth = 2)
        assertEquals("C1, back", honeycomb.displayLabel(Slot(honeycomb.id, 2, 0, 1), resources))
    }

    @Test
    fun rowsOfDifferentDepthsNameTheSideOnlyWhereThereIsOne() {
        // The shared vectors' grid: rows two, one and two deep.
        val grid = Rack(name = "G", rows = 3, columns = 4, depth = 2, rowDepths = listOf(2, 1, 2)).normalized()
        assertEquals("A1, front", grid.displayLabel(Slot(grid.id, 0, 0, 0), resources))
        assertEquals("B3", grid.displayLabel(Slot(grid.id, 1, 2, 0), resources))
        // A bottle an older version put behind that single row is named as the back.
        assertEquals("B3, back", grid.displayLabel(Slot(grid.id, 1, 2, 1), resources))
        assertEquals("C4, back", grid.displayLabel(Slot(grid.id, 2, 3, 1), resources))
        // Shelves three deep above a shelf two deep: the second one's back is its second bottle.
        val shelves = Rack(name = "S", kind = RackKind.Shelves, rows = 2, columns = 4, depth = 3, rowDepths = listOf(3, 2)).normalized()
        assertEquals("Shelf\u00A01, middle, position\u00A01", shelves.displayLabel(Slot(shelves.id, 0, 0, 1), resources))
        assertEquals("Shelf\u00A02, back, position\u00A01", shelves.displayLabel(Slot(shelves.id, 1, 0, 1), resources))
    }

    @Test
    @Config(qualifiers = "fr")
    fun positionsInFrench() {
        val fridge = Rack(name = "Armoire", kind = RackKind.Shelves, rows = 4, columns = 6)
        assertEquals("Étagère\u00A01, position\u00A06", fridge.displayLabel(Slot(fridge.id, 0, 5), resources))
        val bands = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 6, depth = 2, shelfLayers = listOf(1, 1))
        assertEquals("Étagère\u00A01, fond, position\u00A03", bands.displayLabel(Slot(bands.id, 0, 2, 1), resources))
        val deep = Rack(name = "Armoire", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 2)
        assertEquals("Étagère\u00A02, fond, position\u00A03", deep.displayLabel(Slot(deep.id, 1, 2, 1), resources))
        val bins = Rack(name = "Losanges", kind = RackKind.Bins, rows = 2, columns = 3, binCapacity = 6)
        assertEquals("Case B3", bins.displayLabel(Slot(bins.id, 1, 2, 4), resources))
        assertEquals("Cave à vin réfrigérée", resources.getString(RackKind.Shelves.labelRes))
        assertEquals("Rangements", resources.getString(app.encave.R.string.nav_racks))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aCountOfOneReadsWellInFrench() {
        assertEquals("1 sur 40 bouteilles", resources.getString(app.encave.R.string.rack_occupancy, 1, 40))
        assertEquals("1 sur 12 bouteilles avec un prix", resources.getString(app.encave.R.string.insights_estimated_count, 1, 12))
    }
}
