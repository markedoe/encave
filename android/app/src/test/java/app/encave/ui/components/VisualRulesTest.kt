package app.encave.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.encave.core.Cellar
import app.encave.core.Wine
import app.encave.ui.add.stacksFormFields
import app.encave.ui.journal.yearLabelStride
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layout rules from the visual review: wording of rows, wrapping at large font sizes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualRulesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val saved = Locale.getDefault()

    @Before
    fun english() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun originGivesTheAppellationFirstAsOnApple() {
        val wine = Wine(name = "Test", producer = "Domaine Arnaud", appellation = "Pauillac", region = "Bordeaux", country = "France")
        assertEquals("Domaine Arnaud\u00A0· Pauillac", wine.origin())
        assertEquals("Domaine Arnaud\u00A0· Bordeaux", wine.copy(appellation = null).origin())
        assertEquals("France", wine.copy(producer = null, appellation = null, region = null).origin())
    }

    @Test
    fun anArrivalInAnotherYearSaysTheYear() {
        val zone = ZoneId.systemDefault()
        val nextYear = LocalDate.now(zone).plusYears(1).withDayOfYear(6)
        compose.setContent { ArrivalLabel(nextYear.atStartOfDay(zone).toInstant()) }
        compose.onNodeWithText(nextYear.year.toString(), substring = true).assertExists()
    }

    @Test
    fun theLocationTakesTwoLinesAtLargeFontSizes() {
        val cellar = Cellar()
        val rack = app.encave.core.Rack(name = "Cabinet under the stairs on the left", rows = 2, columns = 2)
        cellar.addRack(rack)
        val bottles = cellar.addBottles(Wine(name = "Placed"), quantity = 1, slots = listOf(app.encave.core.Slot(rack.id, 0, 0)))
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Box(Modifier.width(180.dp)) { LocationLine(cellar, bottles) }
            }
        }
        fun height(): Int {
            compose.waitForIdle()
            return compose.onNodeWithTag("wine.row.location").fetchSemanticsNode().size.height
        }
        val one = height()
        scale = 1.5f
        val large = height()
        // One line would be 1.5 times as tall; two lines are about 3 times.
        assertTrue("$one to $large", large > one * 2.2f)
    }

    @Test
    fun yearLabelsSkipYearsWhenTheyDoNotFit() {
        assertEquals(1, yearLabelStride(labelWidth = 30, columnWidth = 40))
        assertEquals(2, yearLabelStride(labelWidth = 60, columnWidth = 40))
        assertEquals(5, yearLabelStride(labelWidth = 90, columnWidth = 20))
        assertEquals(10, yearLabelStride(labelWidth = 900, columnWidth = 20))
    }

    @Test
    fun producerAndVintageStackAtLargeFontOrInNarrowPanes() {
        assertFalse(stacksFormFields(1f, 400.dp))
        assertTrue(stacksFormFields(1.3f, 400.dp))
        assertTrue(stacksFormFields(1f, 320.dp))
    }

    @Test
    fun aSeparatorNeverStartsALine() {
        assertTrue(SEPARATOR.startsWith("\u00A0"))
    }
}
