package app.encave.ui.racks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.core.RackKind
import app.encave.core.WineStyleMark
import app.encave.ui.components.icon
import app.encave.ui.theme.Dimens
import app.encave.ui.theme.EncaveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Places are never drawn under 48 dp: a larger rack scrolls under its overview; taps reach the drawn shapes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RackTouchTargetTest {
    @get:Rule
    val compose = createComposeRule()

    /** Draws [rack] in a 360 × 480 view; returns the state and the last cell size drawn. */
    private fun show(rack: Rack): Pair<RackZoomState, () -> Dp> {
        var state: RackZoomState? = null
        var cell = 0.dp
        compose.setContent {
            val zoom = rememberRackZoomState(rack)
            state = zoom
            ZoomableRack(rack, zoom, Modifier.size(360.dp, 480.dp)) { size, _ ->
                cell = size
                Box(Modifier.size(size * rack.maxColumns, size * rack.rows).testTag("drawing"))
            }
        }
        compose.waitForIdle()
        return state!! to { cell }
    }

    @Test
    fun everyStyleMarkHasItsOwnIcon() {
        val names = WineStyleMark.entries.map { it.icon.name }
        assertEquals(names.toString(), WineStyleMark.entries.size, names.toSet().size)
    }

    @Test
    fun aRackThatFitsIsDrawnWhole() {
        val (state, cell) = show(Rack(name = "Small", rows = 4, columns = 5))
        assertTrue("fitted, larger than the minimum (${cell()})", cell() > 48.dp)
        compose.onAllNodesWithTag("rack.overview").assertCountEquals(0)
        assertEquals(ViewportAreaAll, state.visibleArea.rounded())
    }

    @Test
    fun aLargeRackScrollsAtTheMinimumUnderItsOverview() {
        val (state, cell) = show(Rack(name = "Wall", rows = 12, columns = 24))
        assertEquals(48f, cell().value, 0.01f)
        compose.onNodeWithTag("rack.overview").assertExists()
        val area = state.visibleArea
        assertTrue("only part of it in view ($area)", area.width < 1 && area.height < 1)
        // It opens at its top-leading corner: the row letters and the first column show.
        assertEquals(0.0, area.x, 1e-6)
        assertEquals(0.0, area.y, 1e-6)

        // A tap on the overview's bottom right corner brings that part into view.
        compose.onNodeWithTag("rack.overview").performTouchInput { click(Offset(right - 2f, bottom - 2f)) }
        compose.waitForIdle()
        val after = state.visibleArea
        assertNotEquals(area, after)
        assertEquals(1.0, after.x + after.width, 0.02)
        assertEquals(1.0, after.y + after.height, 0.02)
    }

    @Test
    fun aRackSlightlyTooWideIsFittedRatherThanScrolled() {
        // A rack whose places fit at 44 to 47 dp (under 10 % too large at 48): fitted, no overview.
        val rack = (1..12).flatMap { rows -> (3..30).map { Rack(name = "Near", rows = rows, columns = it) } }.first {
            fittingCellSize(it, 360.dp - Dimens.l * 2, 480.dp - Dimens.l * 2).value in 44f..47f
        }
        val (state, cell) = show(rack)
        assertTrue("fitted near the minimum (${cell()})", cell().value in 40f..48f)
        compose.onAllNodesWithTag("rack.overview").assertCountEquals(0)
        assertEquals(ViewportAreaAll, state.visibleArea.rounded())
    }

    @Test
    fun aRackThatScrollsSitsRightUnderItsOverview() {
        // Too wide, but not tall: drawn at the top of the space left, not centered in it.
        val (state, _) = show(Rack(name = "Long", rows = 1, columns = 40))
        compose.onNodeWithTag("rack.overview").assertExists()
        assertTrue(state.contentSize.height < state.viewSize.height)
        val canvas = compose.onNodeWithTag("rack.canvas").fetchSemanticsNode().boundsInRoot
        val drawing = compose.onNodeWithTag("drawing").fetchSemanticsNode().boundsInRoot
        val padding = with(compose.density) { Dimens.l.toPx() }
        assertEquals(canvas.top + padding, drawing.top, 2f)
        assertEquals(canvas.left + padding, drawing.left, 2f)
    }

    @Test
    fun aFridgeTwoDeepKeepsItsBackPlacesAtTheMinimum() {
        val fridge = Rack(name = "F", kind = RackKind.Staggered, rows = 12, columns = 9, depth = 2, shelfLayers = List(6) { 2 })
        val (_, cell) = show(fridge)
        // The back places are 0.78 of a cell tall.
        assertEquals(48f / 0.78f, cell().value, 0.05f)
    }

    @Test
    fun aTapOutsideAHoneycombHoleMissesIt() {
        val rack = Rack(name = "H", kind = RackKind.Staggered, rows = 1, columns = 1)
        var taps = 0
        compose.setContent {
            EncaveTheme {
                RackGrid(rack, Cellar(racks = listOf(rack)), cellSize = 120.dp, showHeaders = false, onCellClick = { _, _ -> taps++ })
            }
        }
        val cell = compose.onNodeWithTag("cell.0.0")
        // The corner of the square around the round hole.
        cell.performTouchInput { click(Offset(4f, 4f)) }
        compose.waitForIdle()
        assertEquals(0, taps)
        cell.performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    private fun app.encave.core.ViewportArea.rounded() =
        app.encave.core.ViewportArea(Math.round(x * 100) / 100.0, Math.round(y * 100) / 100.0, Math.round(width * 100) / 100.0, Math.round(height * 100) / 100.0)

    private companion object {
        val ViewportAreaAll = app.encave.core.ViewportArea(0.0, 0.0, 1.0, 1.0)
    }
}
