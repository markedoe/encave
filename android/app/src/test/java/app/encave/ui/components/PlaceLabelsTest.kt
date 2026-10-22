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
}
