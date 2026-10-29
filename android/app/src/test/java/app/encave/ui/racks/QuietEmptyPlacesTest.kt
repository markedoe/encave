package app.encave.ui.racks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.encave.core.Cellar
import app.encave.core.Rack
import app.encave.ui.theme.EncaveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Browsing a rack, an empty place shows no name but keeps its accessibility label and its
 * touch target; where a place is chosen (moving, checking, pickers) the name shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class QuietEmptyPlacesTest {
    @get:Rule
    val compose = createComposeRule()

    private val rack = Rack(name = "Main", rows = 2, columns = 3)
    private val cellar = Cellar().apply { addRack(rack) }

    private fun show(quiet: Boolean) {
        compose.setContent {
            EncaveTheme {
                RackGrid(rack, cellar, cellSize = 56.dp, quietEmpty = quiet, onCellClick = { _, _ -> })
            }
        }
    }

    @Test
    fun emptyPlacesHaveNoVisibleNameWhileBrowsing() {
        show(quiet = true)
        compose.onAllNodesWithText("A1", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("B3", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun emptyPlacesKeepTheirLabelAndTouchTarget() {
        show(quiet = true)
        compose.onNodeWithTag("cell.0.0").assertContentDescriptionEquals("A1, empty")
        compose.onNodeWithTag("cell.1.2").assertContentDescriptionEquals("B3, empty")
        compose.onNodeWithTag("cell.0.0").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun emptyPlacesAreNamedWhereAPlaceIsChosen() {
        show(quiet = false)
        compose.onAllNodesWithText("A1", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("cell.0.0").assertContentDescriptionEquals("A1, empty")
    }
}
