package app.encave.ui.cellar

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.SampleData
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wine page's sheets and dialogs stay open, with what was typed, when the activity is recreated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class WineDetailSheetsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<EncaveApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    @Test
    fun thePriceSheetStaysOpenWithTheTypedPrice() {
        val cellar = SampleData.cellar()
        val wine = cellar.wines.values.first()
        cellar.updateWine(wine.copy(currentPrice = BigDecimal("20"), currentPriceCurrency = "EUR"))
        runBlocking { app.repository.replace(cellar) }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.wineLink(wine.id.toString())), app, MainActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
        compose.onNodeWithTag("wine.detail").performScrollToNode(hasTestTag("detail.edit.price"))
        compose.onNodeWithTag("detail.edit.price").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("price.value").performTextClearance()
        compose.onNodeWithTag("price.value").performTextInput("31")
        // Rotation, folding or a dark mode switch.
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("webSearch.sheet").assertExists()
        compose.onNodeWithTag("price.value").assertTextContains("31")
    }
}

/** The occasion typed in "Keep for an occasion" is kept when the activity is recreated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class ReserveDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTypedOccasionIsKept() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ReserveDialog(initial = "", onKeep = {}, onDismiss = {}) }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Anniversary")
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(hasSetTextAction()).onFirst().assertTextContains("Anniversary")
    }
}
