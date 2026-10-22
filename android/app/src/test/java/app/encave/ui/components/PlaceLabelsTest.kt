package app.encave.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.Slot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Places as people read them: shelves are numbered from the top in every fridge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaceLabelsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun shelvesAreNumbered() {
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6)
        assertEquals("Shelf 2, place 3", fridge.displayLabel(Slot(fridge.id, 1, 2), resources))
        val grid = Rack(name = "Grid", rows = 4, columns = 6)
        assertEquals("B3", grid.displayLabel(Slot(grid.id, 1, 2), resources))
    }

    @Test
    fun deepShelvesNameTheSideLast() {
        val fridge = Rack(name = "Fridge", kind = RackKind.Shelves, rows = 4, columns = 6, depth = 3)
        assertEquals("Shelf 1, place 6, front", fridge.displayLabel(Slot(fridge.id, 0, 5, 0), resources))
        assertEquals("Shelf 1, place 6, middle", fridge.displayLabel(Slot(fridge.id, 0, 5, 1), resources))
        assertEquals("Shelf 1, place 6, back", fridge.displayLabel(Slot(fridge.id, 0, 5, 2), resources))
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
    fun placesInFrench() {
        val fridge = Rack(name = "Cave", kind = RackKind.Shelves, rows = 4, columns = 6)
        assertEquals("Étagère 1, place 6", fridge.displayLabel(Slot(fridge.id, 0, 5), resources))
        val bands = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 6, depth = 2, shelfLayers = listOf(1, 1))
        assertEquals("Étagère 1, fond, place 3", bands.displayLabel(Slot(bands.id, 0, 2, 1), resources))
        assertEquals("Cave à vin, étagères en couches", resources.getString(bands.kindLabelRes))
    }
}
