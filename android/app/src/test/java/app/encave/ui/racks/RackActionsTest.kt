package app.encave.ui.racks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.RackCell
import app.encave.core.RackKind
import app.encave.core.SampleData
import app.encave.core.StorageSettings
import app.encave.core.StorageType
import app.encave.core.Wine
import app.encave.ui.components.labelRes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving bottles in a rack, editing a fridge's shelves, and what TalkBack reads for a place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RackActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as EncaveApplication
    private val cellar get() = app.repository.cellar.value

    @Before
    fun loadSample() {
        runBlocking { app.repository.replace(SampleData.cellar()) }
        compose.waitForIdle()
    }

    private fun openRack(name: String) {
        compose.onAllNodesWithText("Storage").onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backWhileMovingStaysInTheRack() {
        val rack = cellar.racks.first()
        openRack(rack.name)
        val bottle = cellar.bottlesInCellar.first { it.slot?.rackId == rack.id }
        val slot = bottle.slot!!
        compose.onNodeWithTag("cell.${slot.row}.${slot.column}").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithText("Choose where to put", substring = true).assertExists()
        back()
        compose.onNodeWithText("Choose where to put", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("rack.canvas").assertExists()
    }

    @Test
    fun duplicatingAShelfKeepsBottlesOnTheirShelf() {
        val fridge = cellar.racks.first { it.hasShelfLayers }
        // A bottle below the first shelf: it goes one shelf down, same layer and place.
        val free = cellar.freeSlots(fridge).first { fridge.shelfPlace(it.row)!!.shelf > 0 && it.column > 0 }
        val bottle = runBlocking { app.repository.update { it.addBottles(Wine(name = "Shelf test"), slots = listOf(free)) }.getOrThrow() }.first()
        val before = fridge.shelfPlace(bottle.slot!!.row)!!
        openRack(fridge.name)
        compose.onNodeWithTag("action.edit_rack").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Duplicate"))
        compose.onAllNodesWithText("Duplicate").onFirst().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { cellar.racks.first { it.id == fridge.id }.shelfLayers!!.size == fridge.shelfLayers!!.size + 1 }
        val edited = cellar.racks.first { it.id == fridge.id }
        val slot = cellar.bottles[bottle.id]!!.slot!!
        val after = edited.shelfPlace(slot.row)!!
        assertEquals(before.shelf + 1, after.shelf)
        assertEquals(before.layer, after.layer)
        assertEquals(bottle.slot!!.column, slot.column)
        assertEquals(bottle.slot!!.depth, slot.depth)
    }

    @Test
    fun leavingAChangedEditorAsksFirst() {
        val rack = cellar.racks.first()
        openRack(rack.name)
        compose.onNodeWithTag("action.edit_rack").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(" 2")
        compose.waitForIdle()
        back()
        compose.onNodeWithText("Discard changes?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Save").assertExists()
    }

    @Test
    fun placesAreReadWithEachBottleAndItsStyle() {
        val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources
        val rack = cellar.racks.first { it.depth == 2 && it.kind == RackKind.Grid }
        val behind = cellar.bottlesInCellar.first { it.slot?.rackId == rack.id && it.slot!!.depth == 1 }
        val cell = behind.slot!!.cell
        val bottles = cellar.bottlesIn(cell)
        val text = cellDescription(rack, RackCell(rack.id, cell.row, cell.column), bottles, cellar, resources)
        val wine = cellar.wines[behind.wineId]!!
        assertTrue(text, text.startsWith(rack.label(cell) + ": front "))
        assertTrue(text, text.contains("; back ${wine.displayName}"))
        assertTrue(text, wine.color == null || text.contains(resources.getString(wine.color.labelRes)))
        val dimmed = cellDescription(rack, cell, bottles, cellar, resources, isDimmed = true)
        assertTrue(dimmed, dimmed.endsWith(", other wine"))
    }

    @Test
    fun aPlaceOfSeveralBottlesCanBeCheckedOneByOne() {
        val rack = cellar.racks.first { it.depth == 2 && it.kind == RackKind.Grid }
        val cell = cellar.bottlesInCellar.first { it.slot?.rackId == rack.id && it.slot!!.depth == 1 }.slot!!.cell
        openRack(rack.name)
        compose.onNodeWithTag("rack.audit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cell.${cell.row}.${cell.column}").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onAllNodesWithTag("rack.check.bottle").onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Found: 1 of 2").onFirst().assertExists()
    }

    @Test
    fun deletingAStorageFromTheListMenuStaysInStorage() {
        val rack = cellar.racks.first()
        compose.onAllNodesWithText("Storage").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rack.row.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onNodeWithTag("rack.menu.edit").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasTestTag("action.delete_rack"))
        compose.onNodeWithTag("action.delete_rack").performClick()
        compose.waitForIdle()
        // The dialog's button, after the editor's.
        compose.onAllNodesWithText("Delete storage").onLast().performClick()
        compose.waitUntil(10_000) { cellar.racks.none { it.id == rack.id } }
        compose.waitForIdle()
        compose.onNodeWithTag("nav.Racks").assertIsSelected()
        compose.onNodeWithTag("rack.row.0").assertExists()
    }

    @Test
    fun aBottleCanBeMovedFromTalkBack() {
        val rack = cellar.racks.first { it.kind == RackKind.Grid && it.depth == 1 && it.rowColumns == null }
        val slot = cellar.bottlesInCellar.first { it.slot?.rackId == rack.id }.slot!!
        openRack(rack.name)
        val cell = compose.onNodeWithTag("cell.${slot.row}.${slot.column}")
        val move = cell.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move" }
        compose.runOnUiThread { move.action() }
        compose.waitForIdle()
        compose.onNodeWithText("Choose where to put", substring = true).assertExists()
    }

    @Test
    fun theCratesEditorGivesTheCrateHint() {
        val crates = StorageSettings(StorageType.Crates).rack(name = "Garage crates")
        runBlocking { app.repository.replaceWith { it.copy().apply { addRack(crates) } }.getOrThrow() }
        compose.waitForIdle()
        openRack("Garage crates")
        compose.onNodeWithTag("action.edit_rack").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("A wooden crate usually holds 6 or 12 bottles."))
        compose.onNodeWithText("A wooden crate usually holds 6 or 12 bottles.").assertExists()
        compose.onAllNodesWithText("A diamond bin", substring = true).assertCountEquals(0)
    }
}
