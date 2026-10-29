package app.encave.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.encave.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Paired buttons stack at the largest font scales; the French strings of Tonight, the note and Insights. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionButtonPairTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showPair(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ActionButtonPair(
                    first = { width -> Button(onClick = {}, modifier = width.testTag("first")) { ActionContent("Drink") } },
                    second = { width -> OutlinedButton(onClick = {}, modifier = width.testTag("second")) { ActionContent("Autre idée") } },
                )
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun sideBySideAtDefaultFontScale() {
        showPair(1f)
        assertEquals(0, compose.onAllNodesWithTag("action.pair.stacked").fetchSemanticsNodes().size)
        val first = bounds("first")
        val second = bounds("second")
        assertTrue("not side by side", second.left >= first.right - 1f)
        assertEquals(first.width, second.width, 1f)
    }

    @Test
    fun stackedFromFontScale150() {
        showPair(STACKED_ACTIONS_FONT_SCALE)
        compose.onNodeWithTag("action.pair.stacked").assertExists()
        val first = bounds("first")
        val second = bounds("second")
        assertTrue("not stacked", second.top >= first.bottom - 1f)
        assertEquals(first.width, second.width, 1f)
    }

    private fun french(): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.FRENCH) }
        return app.createConfigurationContext(configuration)
    }

    @Test
    fun frenchDiscardSaysAbandonNotDelete() {
        val fr = french()
        assertEquals("Abandonner les modifications ?", fr.getString(R.string.discard_note_title))
        assertEquals("Abandonner", fr.getString(R.string.action_discard))
    }

    @Test
    fun decadesInWords() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("2010s", app.getString(R.string.insights_decade, 2010))
        assertEquals("années 2010", french().getString(R.string.insights_decade, 2010))
    }

    /** Guillemets hold their words with narrow no-break spaces, never ordinary ones. */
    @Test
    fun frenchGuillemetsUseNarrowNoBreakSpaces() {
        val fr = french()
        val texts = listOf(
            fr.getString(R.string.err_missing_name),
            fr.getString(R.string.rack_delete_title, "Cave"),
            fr.getString(R.string.help_ai_complete),
            fr.getString(R.string.ai_tip_lmstudio),
        )
        texts.forEach { text ->
            assertTrue(text, "« " in text && " »" in text)
            assertTrue(text, "« " !in text && " »" !in text)
        }
    }
}
