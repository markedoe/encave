package app.encave.ui.racks

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.R
import app.encave.core.RackKind
import app.encave.core.SampleData
import app.encave.core.RackLayout
import app.encave.core.StorageRowSettings
import app.encave.core.StorageSettings
import app.encave.core.StorageType
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A new storage made step by step, and a storage deleted with a long press in the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RackWizardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as EncaveApplication
    private val cellar get() = app.repository.cellar.value

    @Before
    fun loadSample() {
        runBlocking { app.repository.replace(SampleData.cellar()) }
        compose.waitForIdle()
        tap("nav.Racks")
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun wizardCreatesAWineFridgeInLayers() {
        val before = cellar.racks.size
        tap("racks.add")
        tap("wizard.type.winefridge")
        tap("wizard.next")
        // A fridge's own settings: layers per shelf and head-to-tail, no rack layout.
        compose.onNodeWithTag("wizard.layers.3").assertExists()
        compose.onNodeWithTag("wizard.head_to_tail").assertExists()
        compose.onNodeWithTag("wizard.layout.grid").assertDoesNotExist()
        tap("wizard.next")
        tap("wizard.create")
        // Saved in the background.
        compose.waitUntil(5_000) { cellar.racks.size == before + 1 }
        val fridge = cellar.racks.last()
        assertEquals(RackKind.Staggered, fridge.kind)
        assertEquals(List(5) { 2 }, fridge.shelfLayers)
        assertTrue(fridge.isBackReversed)
        assertTrue(fridge.name, fridge.name.startsWith("Wine fridge"))
    }

    private fun scrollTap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun wizardSetsEachShelfOfAFridgeOnItsOwn() {
        val before = cellar.racks.size
        tap("racks.add")
        tap("wizard.type.winefridge")
        tap("wizard.next")
        scrollTap("wizard.rows_differ")
        // Each shelf's own settings take the place of those for all shelves.
        compose.onNodeWithTag("wizard.head_to_tail").assertDoesNotExist()
        compose.onNodeWithTag("wizard.row.0.head_to_tail").assertExists()
        // The second shelf one deep: no head-to-tail to set on it.
        scrollTap("wizard.row.1.depth.1")
        compose.onNodeWithTag("wizard.row.1.head_to_tail").assertDoesNotExist()
        tap("wizard.next")
        tap("wizard.create")
        compose.waitUntil(5_000) { cellar.racks.size == before + 1 }
        val fridge = cellar.racks.last()
        assertEquals(List(5) { 2 }, fridge.shelfLayers)
        assertEquals(2, fridge.depthOfShelf(0))
        assertEquals(1, fridge.depthOfShelf(1))
        assertTrue(fridge.isBackReversedShelf(0))
        assertFalse(fridge.hasDepthBandsOnShelf(1))
        assertEquals(5 * (6 + 5) * 2 - (6 + 5), fridge.capacity)
    }

    @Test
    fun rowsSetOneByOneAreKeptAndStartAgainWithAnotherLayout() {
        val rows = listOf(StorageRowSettings(6, 2, true, 2), StorageRowSettings(4, 1, false, 1))
        assertEquals(rows, decodeRows(encodeRows(rows)))
        assertNull(decodeRows(encodeRows(null)))
        // A grid whose middle row is single; as diamond bins, its rows start again from the uniform settings.
        val grid = StorageSettings(StorageType.Rack, rows = 3, columns = 4, depth = 2).withRowsDiffer(true)
        val mixed = grid.withRow(1, grid.rowSettings!![1].copy(depth = 1))
        assertEquals(listOf(2, 1, 2), (0 until 3).map { mixed.rack(name = "G").depthOfRow(it) })
        val bins = withLayout(mixed, RackLayout.Diamond)
        assertEquals(3, bins.rowSettings?.size)
        assertEquals(mixed, withLayout(mixed, RackLayout.Grid))
    }

    @Test
    fun aDoubleTapOnCreateAddsOneStorageAndStaysInStorage() {
        val before = cellar.racks.size
        tap("racks.add")
        tap("wizard.next")
        tap("wizard.next")
        // Two taps before the screen can react.
        compose.onNodeWithTag("wizard.create").performTouchInput {
            click()
            advanceEventTime(100)
            click()
        }
        compose.waitUntil(5_000) { cellar.racks.size > before }
        compose.waitForIdle()
        assertEquals(before + 1, cellar.racks.size)
        // Back on the Storage list, not one screen further back.
        compose.onNodeWithTag("racks.add").assertExists()
        compose.onNodeWithTag("wizard.create").assertDoesNotExist()
    }

    @Test
    fun closeLeavesTheWizardFromTheLastStep() {
        val before = cellar.racks.size
        tap("racks.add")
        tap("wizard.next")
        tap("wizard.next")
        compose.onNodeWithTag("wizard.create").assertExists()
        // The bottom Back goes to the previous step; Close leaves.
        compose.onNodeWithTag("wizard.back").assertExists()
        tap("wizard.close")
        compose.onNodeWithTag("racks.add").assertExists()
        compose.onNodeWithTag("wizard.create").assertDoesNotExist()
        assertEquals(before, cellar.racks.size)
    }

    @Test
    fun thePreviewFitsTheWholeDefaultFridgeInAPhoneCard() {
        // Five shelves two deep, head-to-tail: it used to show less than two of them.
        val fridge = StorageSettings(StorageType.WineFridge).rack(name = "Fridge")
        // The card of this test's phone (411 × 891 dp).
        val width = 411.dp - 16.dp * 2 - 12.dp * 2
        val height = 891.dp * PREVIEW_SHARE
        val cell = fittingCellSize(fridge, width, height, showHeaders = false, labelHeight = 20.dp)
        val (w, h) = rackGridSize(fridge, cell, showHeaders = false, labelHeight = 20.dp)
        assertTrue("$cell: $w × $h", w <= width && h <= height)
    }

    @Test
    fun longPressDeletesAStorageAfterAsking() {
        val first = cellar.racks.first()
        compose.onNodeWithTag("rack.row.0").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("rack.menu.edit").assertExists()
        tap("rack.menu.delete")
        // The same confirmation as in the editor.
        compose.onNodeWithText(app.getString(R.string.rack_delete_title, first.name)).assertExists()
        compose.onNodeWithText(app.getString(R.string.rack_delete)).performClick()
        compose.waitUntil(5_000) { cellar.racks.none { it.id == first.id } }
    }
}
