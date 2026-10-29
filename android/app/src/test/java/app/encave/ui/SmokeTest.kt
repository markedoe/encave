package app.encave.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
import androidx.compose.ui.test.assertCountEquals

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
        // The first match is now a bottle in the cellar (the order comes after): the window may be below the fold.
        compose.onNodeWithTag("wine.detail").performScrollToNode(hasText("Drinking window"))
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
        tab("Storage")
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
        compose.onNodeWithText("Find the right bottle for your dish").performClick()
        compose.onNodeWithText("Fish").performClick()
        compose.waitForIdle()
        // The best matches say so in words; no percentage.
        compose.onAllNodes(hasText("Great match")).onFirst().assertExists()
        compose.onAllNodes(hasText("% match", substring = true)).assertCountEquals(0)
    }

    @Test
    fun journalInsightsAndSettings() {
        tab("Journal")
        // Each entry says where it comes from, in words.
        compose.onAllNodes(hasContentDescription("From my cellar, Vieilles Vignes", substring = true)).onFirst().assertExists()
        compose.onAllNodes(hasContentDescription("Tasted elsewhere", substring = true)).onFirst().assertExists()
        // TalkBack reads the style in words and still finds the rows' actions.
        compose.onAllNodes(hasContentDescription("Vieilles Vignes", substring = true) and hasContentDescription(", Red, ", substring = true))
            .onFirst().assertExists()
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().assertHasClickAction()
        // Bottles from the cellar: a long-press menu, and the same actions for TalkBack.
        compose.onAllNodes(
            hasContentDescription("From my cellar", substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        ).onFirst().assertHasClickAction()
        compose.onAllNodes(
            hasContentDescription("Tasted elsewhere", substring = true) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
        ).onFirst().assertHasClickAction()
        compose.onNodeWithContentDescription("Insights").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Opened per month"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Source code"))
    }

    @Test
    fun winesTastedElsewhereAndEditor() {
        tab("Journal")
        compose.onNodeWithText("Tasted elsewhere").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("From my cellar", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Alvarinho Reserva", substring = true)).onFirst().assertExists().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Where").onFirst().assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Add a tasting", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Somewhere else").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Tasted elsewhere").onFirst().assertExists()
        compose.onAllNodesWithText("Your impression").onFirst().assertExists()
    }

    @Test
    fun openABottleFromTheJournal() {
        tab("Journal")
        compose.onNodeWithText("Add a tasting", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("A bottle from my cellar").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Which bottle?").onFirst().assertExists()
        compose.onAllNodesWithText("Clos des Tilleuls", substring = true).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Open a bottle").onFirst().assertExists()
        compose.onAllNodesWithText("Clos des Tilleuls", substring = true).onFirst().assertExists()
    }

    @Test
    fun aBottleOpenedWithATagChosenShowsInTheJournal() {
        tab("Journal")
        compose.onNodeWithText("Gift idea").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("From my cellar", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Add a tasting", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("A bottle from my cellar").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Clos des Tilleuls", substring = true).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("action.drink_confirm").performScrollTo().performClick()
        compose.waitForIdle()
        // The tag only picks wines tasted elsewhere: it is cleared so the new entry shows.
        compose.onAllNodes(hasContentDescription("From my cellar, Clos des Tilleuls", substring = true)).onFirst().assertExists()
    }

    @Test
    fun binsShowTheirBottles() {
        tab("Storage")
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
        tab("Storage")
        compose.onNodeWithText("Cabinet").performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Shelf\u00A01, position\u00A01", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Edit storage").performClick()
        compose.waitForIdle()
        // A wine fridge keeps its kind: only its own settings are shown.
        compose.onAllNodesWithText("Honeycomb rack").assertCountEquals(0)
        compose.onAllNodesWithText("Bottles per shelf").onFirst().assertExists()
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
        compose.onAllNodesWithText("Describe your storage so every bottle has a position.").onFirst().assertExists()
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
