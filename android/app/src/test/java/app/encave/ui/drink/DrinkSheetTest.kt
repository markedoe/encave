package app.encave.ui.drink

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import app.encave.R
import app.encave.core.Cellar
import app.encave.core.PurchaseInfo
import app.encave.core.Tasting
import app.encave.core.Wine
import app.encave.core.bottleToOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The drink sheet opens a free bottle first, says which are kept, and asks before losing a note. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrinkSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val cellar = Cellar()
    private val wine = Wine(name = "Two places")
    private var dismissed = 0
    private val drunk = mutableListOf<Pair<String, Tasting>>()

    private fun show() {
        val bottles = cellar.addBottles(wine, 2, purchase = PurchaseInfo(storageNote = "Cave"))
        cellar.reserve(listOf(bottles[0].id), "Wedding")
        val start = cellar.bottlesOf(wine.id).bottleToOpen!!
        assertEquals(null, start.reservedFor)
        compose.setContent {
            DrinkSheet(cellar, start.id.toString(), onDismiss = { dismissed++ }, onDrink = { b, t -> drunk += b.reservedFor.orEmpty() to t })
        }
        compose.waitForIdle()
    }

    @Test
    fun aFreeBottleIsChosenFirstAndAKeptOneSaysSo() {
        show()
        compose.onAllNodesWithTag("drink.choice").onFirst().assertIsSelected()
        compose.onNodeWithTag("drink.choice.kept").assertExists()
        compose.onNodeWithText(app.getString(R.string.drink_choice_kept, "Cave", "Wedding")).assertExists()
        compose.onNodeWithTag("action.drink_confirm").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(""), drunk.map { it.first })
        assertEquals(1, dismissed)
    }

    @Test
    fun closingWithANoteAsksFirst() {
        show()
        compose.onNodeWithText(app.getString(R.string.drink_note)).performTextInput("Lovely")
        compose.waitForIdle()
        // A swipe down with a note asks instead of closing.
        compose.onNodeWithTag("drink.sheet").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 200) }
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.discard_note_title)).assertExists()
        assertEquals(0, dismissed)
        compose.onNodeWithTag("discard.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertTrue(drunk.isEmpty())
    }
}
