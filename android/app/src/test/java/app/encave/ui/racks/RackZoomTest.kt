package app.encave.ui.racks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import app.encave.core.Rack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pinching zooms the rack, also once the rack was edited while its screen is open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RackZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private fun pinchOut() {
        compose.onNodeWithTag("rack.canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(320f, 0f), center + Offset(40f, 0f), center + Offset(320f, 0f))
        }
        compose.waitForIdle()
    }

    @Test
    fun pinchZoomsAfterTheRackChangesShape() {
        var rack by mutableStateOf(Rack(name = "R", rows = 4, columns = 6))
        var current: RackZoomState? = null
        compose.setContent {
            val state = rememberRackZoomState(rack)
            current = state
            ZoomableRack(rack, state, Modifier.size(360.dp, 480.dp)) { cell, _ ->
                Box(Modifier.size(cell * rack.maxColumns, cell * rack.rows))
            }
        }
        compose.waitForIdle()
        pinchOut()
        val first = current!!
        assertTrue("the first pinch zooms (${first.level})", first.level > 1.5f)

        // Edited meanwhile (or on another device): a new shape starts again from the whole rack.
        rack = rack.copy(columns = 7)
        compose.waitForIdle()
        val second = current!!
        assertNotSame(first, second)
        assertEquals(1f, second.level)
        pinchOut()
        assertTrue("a pinch after the change zooms (${second.level})", second.level > 1.5f)
    }

    @Test
    fun redrawingKeepsTheZoom() {
        var highlight by mutableStateOf(0)
        val rack = Rack(name = "R", rows = 4, columns = 6)
        var current: RackZoomState? = null
        compose.setContent {
            val state = rememberRackZoomState(rack)
            current = state
            ZoomableRack(rack, state, Modifier.size(360.dp, 480.dp)) { cell, _ ->
                // Something in the drawing changes (a highlight, a sync): not the zoom.
                Box(Modifier.size(cell * rack.maxColumns + highlight.dp * 0, cell * rack.rows))
            }
        }
        pinchOut()
        val zoomed = current!!.level
        assertTrue(zoomed > 1.5f)
        repeat(3) {
            highlight++
            compose.waitForIdle()
        }
        assertEquals(zoomed, current!!.level)
    }
}
