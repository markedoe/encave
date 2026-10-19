package app.encave.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens every screen with the sample cellar, so a crash in any of them fails the build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as EncaveApplication

    @Before
    fun loadSample() {
        runBlocking { app.repository.replace(SampleData.cellar()) }
        compose.waitForIdle()
    }

    private fun tab(label: String) = compose.onAllNodesWithText(label).onFirst().performClick()

    @Test
    fun cellarListAndWineDetail() {
        compose.onAllNodesWithText("Clos des Tilleuls", substring = true).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Drinking window").onFirst().assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test
    fun addBottleForm() {
        compose.onNodeWithText("Add bottle", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Name").onFirst().assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
    }

    @Test
    fun racksAndRackDetail() {
        tab("Racks")
        compose.onNodeWithText("Main rack").performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("A1", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Bottle list").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("rack.list.row").onFirst().assertExists()
    }

    @Test
    fun tonightAndPairing() {
        tab("Tonight")
        compose.onNodeWithText("What are you eating?").performClick()
        compose.onNodeWithText("Fish").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("100% match", substring = true)).onFirst().assertExists()
    }

    @Test
    fun journalInsightsAndSettings() {
        tab("Journal")
        compose.onAllNodesWithText("Vieilles Vignes", substring = true).onFirst().assertExists()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Opened per month"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Source code"))
    }

    @Test
    fun discoveriesAndEditor() {
        tab("Journal")
        compose.onNodeWithText("Discoveries").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Alvarinho Reserva", substring = true).onFirst().assertExists().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Where").onFirst().assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Remember a wine", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Your impression").onFirst().assertExists()
    }

    @Test
    fun binsShowTheirBottles() {
        tab("Racks")
        compose.onNodeWithText("Diamond bins").performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Bin A1, 6 of 10 bottles", substring = true)).performClick()
        // Cells also take double taps (zoom): a single tap counts after the double-tap time.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onAllNodesWithText("Add a bottle here").onFirst().assertExists()
        compose.onAllNodesWithText("Terrasses du Sud", substring = true).onFirst().assertExists()
    }

    @Test
    fun shelvesAndRackEditor() {
        tab("Racks")
        compose.onNodeWithText("Wine fridge").performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Shelf A, 1", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Edit rack").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Honeycomb rack").onFirst().performClick()
        compose.onNode(hasText("Holds", substring = true)).assertExists()
    }

    @Test
    fun helpTopicsAndFormLinks() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Encave Help"))
        compose.onNodeWithText("Encave Help").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Setting up AI").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Download LM Studio"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Describe your storage so every bottle has a place.").onFirst().assertExists()
    }

    @Test
    fun addFormHelpOpensInASheet() {
        compose.onNodeWithText("Add bottle", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Help with adding bottles"))
        compose.onNodeWithText("Help with adding bottles").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Already in your cellar?").onFirst().assertExists()
    }
}
