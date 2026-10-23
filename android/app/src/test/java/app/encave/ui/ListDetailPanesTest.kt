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
        tab("Racks")
        compose.onNodeWithText("Select a storage to see its bottles.").assertIsDisplayed()
        compose.onAllNodes(hasText("Main rack") and inList).onFirst().performClick()
        compose.waitForIdle()
        // The rack opens in the detail pane, and the list stays beside it.
        compose.onAllNodes(hasContentDescription("A1", substring = true) and inDetail).onFirst().assertExists()
        compose.onAllNodes(hasText("Main rack") and inList).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Select a storage to see its bottles.").assertCountEquals(0)
    }

    @Test
    fun tonightShowsTheWineBesideTheList() {
        tab("Tonight")
        compose.onNodeWithText("Select a bottle to see its details.").assertIsDisplayed()
        compose.onAllNodes(hasText("Clos des Tilleuls", substring = true) and inList).onFirst().performClick()
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
}
