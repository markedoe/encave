package app.encave.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Shared with the Swift RackViewportTests: the same cases and numbers. */
class RackViewportTest {
    @Test
    fun everyStyleHasItsOwnSymbol() {
        val marks = WineColor.entries.map { it.styleMark }
        assertEquals(WineColor.entries.size, marks.toSet().size, "two styles share a mark")
        // The same choice as on Apple platforms.
        assertEquals(WineStyleMark.Glass, WineColor.Red.styleMark)
        assertEquals(WineStyleMark.Sun, WineColor.White.styleMark)
        assertEquals(WineStyleMark.Heart, WineColor.Rose.styleMark)
        assertEquals(WineStyleMark.Leaf, WineColor.Orange.styleMark)
        assertEquals(WineStyleMark.Bubbles, WineColor.Sparkling.styleMark)
        assertEquals(WineStyleMark.Drop, WineColor.Dessert.styleMark)
        assertEquals(WineStyleMark.Shield, WineColor.Fortified.styleMark)
    }

    @Test
    fun placesAreNeverSmallerThanATouchTarget() {
        assertEquals(48.0, RackViewport.minimumCellSize(Rack(name = "G", rows = 4, columns = 6), 48.0))
        // A fridge drawn front and back: its back places are 0.78 of a cell tall.
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 2, columns = 4, depth = 2, shelfLayers = listOf(2))
        assertEquals(48 / 0.78, RackViewport.minimumCellSize(fridge, 48.0), 0.001)
        assertEquals(44 / 0.78, RackViewport.minimumCellSize(fridge, 44.0), 0.001)
    }

    @Test
    fun fitsWhenEveryPlaceReachesTheMinimumElseScrolls() {
        assertFalse(RackViewport.scrolls(60.0, 44.0))
        assertEquals(60.0, RackViewport.cellSize(60.0, 44.0))
        assertFalse(RackViewport.scrolls(44.0, 44.0))
        assertTrue(RackViewport.scrolls(30.0, 44.0))
        assertEquals(44.0, RackViewport.cellSize(30.0, 44.0))
    }

    @Test
    fun visibleAreaIsAFractionOfTheDrawing() {
        assertEquals(ViewportArea(0.5, 0.0, 0.25, 1.0), RackViewport.visibleArea(1000.0, 500.0, 250.0, 500.0, ViewportPoint(500.0, 0.0)))
        // Past the end (bouncing): clipped to the drawing.
        val past = RackViewport.visibleArea(1000.0, 500.0, 250.0, 250.0, ViewportPoint(900.0, -50.0))
        assertEquals(0.9, past.x, 1e-9)
        assertEquals(0.1, past.width, 1e-9)
        assertEquals(0.0, past.y)
        assertEquals(0.4, past.height, 1e-9)
        // Smaller than the view: all of it.
        assertEquals(ViewportArea(0.0, 0.0, 1.0, 1.0), RackViewport.visibleArea(100.0, 100.0, 250.0, 250.0, ViewportPoint(0.0, 0.0)))
        assertEquals(ViewportArea(0.0, 0.0, 1.0, 1.0), RackViewport.visibleArea(0.0, 0.0, 250.0, 250.0, ViewportPoint(0.0, 0.0)))
    }

    @Test
    fun tappingTheOverviewCentersThatSpot() {
        assertEquals(ViewportPoint(375.0, 125.0), RackViewport.offsetCentering(ViewportPoint(0.5, 0.5), 1000.0, 500.0, 250.0, 250.0))
        // Near the edges: as far as the drawing goes.
        assertEquals(ViewportPoint(0.0, 0.0), RackViewport.offsetCentering(ViewportPoint(0.0, 0.0), 1000.0, 500.0, 250.0, 250.0))
        assertEquals(ViewportPoint(750.0, 250.0), RackViewport.offsetCentering(ViewportPoint(1.0, 1.0), 1000.0, 500.0, 250.0, 250.0))
        // An axis that fits does not move.
        assertEquals(ViewportPoint(575.0, 0.0), RackViewport.offsetCentering(ViewportPoint(0.7, 0.7), 1000.0, 200.0, 250.0, 250.0))
    }

    @Test
    fun centerOfAPlace() {
        val grid = Rack(name = "G", rows = 4, columns = 6)
        assertEquals(ViewportPoint(2.5 / 6, 1.5 / 4), RackViewport.center(grid, RackCell(grid.id, 1, 2)))
        // Head-to-tail fridge: shelf 1 (layers 0, 1) then shelf 2 (layer 2), each front then back, the back half a place right.
        val fridge = Rack(
            name = "F", kind = RackKind.Staggered, rows = 3, columns = 6, depth = 2, shelfLayers = listOf(2, 1), backReversed = true,
        )
        val back = RackViewport.center(fridge, RackCell(fridge.id, 1, 2), 1)
        assertEquals(3.0 / 7, back.x, 0.0001)
        assertEquals(3.5 / 6, back.y, 0.0001)
        val front = RackViewport.center(fridge, RackCell(fridge.id, 2, 0), 0)
        assertEquals(0.5 / 7, front.x, 0.0001)
        assertEquals(4.5 / 6, front.y, 0.0001)
    }
}
