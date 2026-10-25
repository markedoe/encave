package app.encave.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
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
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected

/** On a tablet, Storage, Tonight and Journal show the chosen item beside their list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1280dp-h800dp-xhdpi")
class ListDetailPanesTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as EncaveApplication

    @Before
    fun loadSample() {
        runBlocking { app.repository.replace(SampleData.cellar()) }
        compose.waitForIdle()
    }

    private fun tab(label: String) {
        compose.onAllNodesWithText(label).onFirst().performClick()
        compose.waitForIdle()
    }

    private val inDetail = hasAnyAncestor(hasTestTag("panes.detail"))
    private val inList = hasAnyAncestor(hasTestTag("panes.list"))

    @Test
    fun storageShowsTheRackBesideTheList() {
        tab("Storage")
        compose.onNodeWithText("Select a storage to see its bottles.").assertIsDisplayed()
        compose.onAllNodes(hasText("Main rack") and inList).onFirst().performClick()
        compose.waitForIdle()
        // The rack opens in the detail pane, and the list stays beside it.
        compose.onAllNodes(hasContentDescription("A1", substring = true) and inDetail).onFirst().assertExists()
        compose.onAllNodes(hasText("Main rack") and inList).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Select a storage to see its bottles.").assertCountEquals(0)
        // Back closes the rack and stays in Storage.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithText("Select a storage to see its bottles.").assertIsDisplayed()
        compose.onAllNodes(hasText("Main rack") and inList).onFirst().assertIsDisplayed()
    }

    @Test
    fun tonightShowsTheWineBesideTheList() {
        tab("Tonight")
        compose.onNodeWithText("Select a bottle to see its details.").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("tonight.row") and inList).onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("Drinking window") and inDetail).onFirst().assertExists()
        compose.onAllNodesWithTag("panes.list").onFirst().assertIsDisplayed()
    }

    @Test
    fun journalShowsTheNoteBesideTheList() {
        tab("Journal")
        compose.onNodeWithText("Select a tasting to see its note.").assertIsDisplayed()
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("My tasting") and inDetail).onFirst().assertExists()
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().assertIsDisplayed()
    }

    private fun typeInTheNote(text: String) {
        compose.onAllNodes(hasSetTextAction() and inDetail).onFirst().performTextInput(text)
        compose.waitForIdle()
    }

    private fun recreateAt(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @Test
    fun aNoteBeingTypedStaysWhenTheWindowNarrowsAndWidens() {
        tab("Journal")
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().performClick()
        compose.waitForIdle()
        typeInTheNote("Kept while folding")
        // Portrait (one pane): the note stays open, full screen, with what was typed.
        recreateAt("en-w600dp-h1000dp-xhdpi")
        compose.onAllNodesWithText("Kept while folding", substring = true).onFirst().assertExists()
        // Landscape again: still beside the list.
        recreateAt("en-w1280dp-h800dp-xhdpi")
        compose.onAllNodes(hasText("Kept while folding", substring = true) and inDetail).onFirst().assertExists()
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().assertIsDisplayed()
    }

    @Test
    fun openingAnotherEntryWithAChangedNoteAsksFirst() {
        tab("Journal")
        val rows = compose.onAllNodesWithTag("journal.row.cellar")
        rows[0].performClick()
        compose.waitForIdle()
        typeInTheNote("Not lost")
        rows[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Discard this note?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasText("Not lost", substring = true) and inDetail).onFirst().assertExists()
        // Discarded: the other entry opens.
        rows[1].performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("discard.confirm").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Not lost", substring = true).assertCountEquals(0)
        compose.onAllNodes(hasText("My tasting") and inDetail).onFirst().assertExists()
    }
}
