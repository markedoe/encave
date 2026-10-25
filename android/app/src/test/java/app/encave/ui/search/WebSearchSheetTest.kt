package app.encave.ui.search

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.activity.ComponentActivity
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The search and price sheets: web search off (the default), and what they keep when the activity is recreated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class WebSearchSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val price = PriceOptions(750, "EUR", BigDecimal("20"), "Clos") { _, _ -> }

    @Test
    fun withSearchOffThePriceSheetIsOnlyThePrice() {
        compose.setContent { WebSearchSheet("Clos 2019 price", onDismiss = {}, price = price) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Price today").onFirst().assertExists()
        compose.onAllNodesWithText("Find current price").assertCountEquals(0)
        compose.onNodeWithTag("webSearch.query").assertDoesNotExist()
        compose.onNodeWithTag("webSearch.search").assertDoesNotExist()
        compose.onNodeWithTag("price.value").assertExists()
    }

    @Test
    fun aTypedPriceIsKeptWhenTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WebSearchSheet("Clos 2019 price", onDismiss = {}, price = price) }
        compose.onNodeWithTag("price.value").performTextClearance()
        compose.onNodeWithTag("price.value").performTextInput("31")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("price.value").assertTextContains("31")
    }

    @Test
    fun editedTermsAreKeptWhenTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WebSearchSheet("Clos 2019", onDismiss = {}) }
        compose.onNodeWithTag("webSearch.query").performTextInput(" magnum")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("webSearch.query").assertTextContains("magnum", substring = true)
    }
}
