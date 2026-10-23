package app.encave.ui.racks

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.RackKind
import app.encave.core.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(before + 1, cellar.racks.size)
        val fridge = cellar.racks.last()
        assertEquals(RackKind.Staggered, fridge.kind)
        assertEquals(List(5) { 2 }, fridge.shelfLayers)
        assertTrue(fridge.isBackReversed)
        assertTrue(fridge.name, fridge.name.startsWith("Wine fridge"))
    }

    @Test
    fun longPressDeletesAStorageAfterAsking() {
        val first = cellar.racks.first()
        compose.onNodeWithTag("rack.row.0").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag("rack.menu.edit").assertExists()
        tap("rack.menu.delete")
        // The same confirmation as in the editor.
        compose.onNodeWithText("Delete “${first.name}”?").assertExists()
        compose.onNodeWithText("Delete rack").performClick()
        compose.waitForIdle()
        assertFalse(cellar.racks.any { it.id == first.id })
    }
}
