package app.encave.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
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
        compose.onNodeWithContentDescription("Zoom in").performClick()
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
}
