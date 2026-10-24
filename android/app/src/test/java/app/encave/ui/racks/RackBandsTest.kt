package app.encave.ui.racks

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.ui.components.displayLabel
import app.encave.ui.theme.EncaveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** A fridge two deep drawn front and back: its places, their names, and the drawing's size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RackBandsTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    /** Shelves that differ: 4 and 3 on top, a single layer of 7, then 6 and 5 on top; head-to-tail. */
    private val fridge = Rack(
        name = "F", kind = RackKind.Staggered, rows = 5, columns = 7, depth = 2,
        rowColumns = listOf(3, 4, 7, 5, 6), shelfLayers = listOf(2, 1, 2), backReversed = true,
    ).normalized()

    private fun slot(row: Int, column: Int, depth: Int) = Slot(fridge.id, row, column, depth)

    @Test
    fun placesAreNamedByShelfBandLayerAndNumber() {
        assertTrue(fridge.hasDepthBands && fridge.isBackReversed)
        assertEquals("Shelf\u00A01, front, top layer, position\u00A02", fridge.displayLabel(slot(0, 1, 0), resources))
        assertEquals("Shelf\u00A01, back, bottom layer, position\u00A04", fridge.displayLabel(slot(1, 3, 1), resources))
        assertEquals("Shelf\u00A02, front, position\u00A03", fridge.displayLabel(slot(2, 2, 0), resources))
        assertEquals("Shelf\u00A02, back, position\u00A07", fridge.displayLabel(slot(2, 6, 1), resources))
        assertEquals("Shelf\u00A03, front, bottom layer, position\u00A06", fridge.displayLabel(slot(4, 5, 0), resources))
        // One deep: the usual names.
        val single = fridge.copy(depth = 1).normalized()
        assertEquals("Shelf\u00A02, position\u00A03", single.displayLabel(Slot(single.id, 2, 2), resources))
    }

    @Test
    fun aPlaceOfABandHoldsOneBottle() {
        val wine = Wine(name = "W")
        val cellar = Cellar(racks = listOf(fridge))
        cellar.addBottles(wine, 1, listOf(slot(1, 0, 0)))
        val front = RackPlace.of(slot(1, 0, 0), fridge)
        val back = RackPlace.of(slot(1, 0, 1), fridge)
        assertEquals(0, front.depth)
        assertTrue(front.holdsOne(fridge) && back.holdsOne(fridge))
        assertTrue(slot(1, 0, 0) in front && slot(1, 0, 0) !in back)
        assertEquals(1, cellar.bottlesAt(front).size)
        assertTrue(cellar.bottlesAt(back).isEmpty())
        assertNull(cellar.freeSlotAt(front))
        assertEquals(slot(1, 0, 1), cellar.freeSlotAt(back))
        assertEquals(back, RackPlace.of(back.key(), fridge))
        // Elsewhere a place is a whole cell, as before.
        val grid = Rack(name = "G", rows = 2, columns = 2, depth = 2)
        val cell = RackPlace.of(Slot(grid.id, 1, 1, 1), grid)
        assertNull(cell.depth)
        assertFalse(cell.holdsOne(grid))
        assertEquals(cell, RackPlace.of(cell.key(), grid))
        assertNull(RackPlace.of("9:9:1", fridge))
    }

    @Test
    fun theDrawingAddsABackBandUnderEachShelf() {
        val cell = 40.dp
        val label = 20.dp
        val (width, height) = rackGridSize(fridge, cell, showHeaders = true, labelHeight = label)
        val (flatWidth, flatHeight) = rackGridSize(fridge.copy(depth = 1).normalized(), cell, showHeaders = true, labelHeight = label)
        // Head-to-tail, the back band is drawn half a place (and a gap) to the right.
        assertEquals((flatWidth + (cell + cell * 0.04f) / 2).value, width.value, 0.01f)
        val (sideBySide, _) = rackGridSize(fridge.copy(backReversed = null), cell, showHeaders = true, labelHeight = label)
        assertEquals(flatWidth.value, sideBySide.value, 0.01f)
        // Per shelf: two captions, a gap, the back layers (0.78 high, nesting) on a padded tray.
        val back = cell * 0.78f
        val nest = (back + cell * 0.04f) * 0.134f
        val bands = fridge.shelfRows.fold(0.dp) { total, rows ->
            val n = rows.count()
            total + label * 2 + cell * 0.06f + back * n - nest * (n - 1) + cell * 0.08f
        }
        assertEquals((flatHeight + bands).value, height.value, 0.01f)
    }

    @Test
    fun eachBackNeckSitsBetweenTwoFrontBasesAndFitsTheDrawingSize() = backPlacesAreDrawn(fridge, shift = 0.5f)

    @Test
    fun eachBackBaseSitsUnderItsFrontOneWhenNotHeadToTail() = backPlacesAreDrawn(fridge.copy(backReversed = null), shift = 0f)

    /** Each back place of [rack] is drawn [shift] places right of its front one, below it, and within the drawing's size. */
    private fun backPlacesAreDrawn(rack: Rack, shift: Float) {
        val cellar = Cellar(racks = listOf(rack))
        val cell = 40.dp
        var size: Pair<Dp, Dp>? = null
        compose.setContent {
            EncaveTheme {
                RackGrid(rack, cellar, cellSize = cell, onCellClick = { _, _ -> })
            }
            size = rackGridSize(rack, cell, showHeaders = true, labelHeight = 20.dp)
        }
        compose.waitForIdle()
        var right = 0f
        for (row in 0 until rack.rows) {
            for (column in 0 until rack.columnsInRow(row)) {
                val front = compose.onNodeWithTag("cell.$row.$column", useUnmergedTree = true).getBoundsInRoot()
                val back = compose.onNodeWithTag("cell.$row.$column.back", useUnmergedTree = true).getBoundsInRoot()
                right = maxOf(right, back.right.value)
                assertEquals("row $row, place $column", front.left.value + cell.value * 1.04f * shift, back.left.value, 0.5f)
                assertEquals((front.right - front.left).value, (back.right - back.left).value, 0.5f)
                assertTrue(abs((back.bottom - back.top).value - cell.value * 0.78f) < 0.5f)
                assertTrue("back under front", back.top > front.bottom)
            }
        }
        assertEquals(2 * rack.shelfRows.size, compose.onAllNodesWithTag("rack.band.front").fetchSemanticsNodes().size +
            compose.onAllNodesWithTag("rack.band.back").fetchSemanticsNodes().size)
        val bottom = (0 until rack.columnsInRow(4)).maxOf {
            compose.onNodeWithTag("cell.4.$it.back", useUnmergedTree = true).getBoundsInRoot().bottom.value
        }
        assertTrue("drawn within its computed height ($bottom of ${size!!.second})", bottom <= size!!.second.value + 1f)
        assertTrue("drawn within its computed width ($right of ${size!!.first})", right <= size!!.first.value + 1f)
    }
}
