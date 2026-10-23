package app.encave.ui.cellar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import app.encave.core.Cellar
import app.encave.core.CellarFilter
import app.encave.core.Maturity
import app.encave.core.Wine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The figures above the cellar list: bottles, bottles to keep and to drink soon (never a
 * count of wine records), as tiles, or one short line each from 150 % font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CellarSummaryTest {
    @get:Rule
    val compose = createComposeRule()

    private val cellar = Cellar().apply {
        addBottles(Wine(name = "Young", drinkFrom = 2030, drinkUntil = 2040), quantity = 2)
        addBottles(Wine(name = "Soon", drinkFrom = 2020, drinkUntil = 2026), quantity = 1)
    }

    private fun show(fontScale: Float, filter: CellarFilter = CellarFilter(), onFilter: (CellarFilter) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CellarSummary(cellar, 2026, filter, onBottles = {}, onFilter = onFilter)
            }
        }
    }

    @Test
    fun tilesCountBottlesToKeepAndFilterToThem() {
        var picked: CellarFilter? = null
        show(1f) { picked = it }
        compose.onAllNodesWithTag("cellar.stats.compact").assertCountEquals(0)
        compose.onAllNodesWithTag("cellar.stat.wines").assertCountEquals(0)
        compose.onNodeWithTag("cellar.stat.keep").assertExists()
        compose.onAllNodes(hasContentDescription("2 bottles to keep", substring = true)).assertCountEquals(1)
        compose.onNodeWithTag("cellar.stat.keep").performClick()
        assertEquals(setOf(Maturity.Young), picked?.maturities)
    }

    @Test
    fun keepTileOnShowsEverythingAgain() {
        var picked: CellarFilter? = null
        show(1f, CellarFilter(maturities = setOf(Maturity.Young))) { picked = it }
        compose.onNodeWithTag("cellar.stat.keep").performClick()
        assertEquals(emptySet<Maturity>(), picked?.maturities)
    }

    @Test
    fun largestFontScalesShowAShortList() {
        show(2f)
        compose.onNodeWithTag("cellar.stats.compact").assertExists()
        for (tag in listOf("cellar.stat.bottles", "cellar.stat.keep", "cellar.stat.soon")) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        }
        val bottles = compose.onNodeWithTag("cellar.stat.bottles", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val soon = compose.onNodeWithTag("cellar.stat.soon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // One line a figure, not three tall tiles.
        assert(soon.top >= bottles.bottom - 1f) { "the figures are not listed one above the other" }
        assert(soon.bottom - bottles.top < 3 * 4 * bottles.height) { "the figures are too tall" }
    }
}

/** The sample banner at 200 % font scale: the button goes under the text, which keeps its whole width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SampleBannerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { SampleBanner(onRemove = {}) }
        }
    }

    @Test
    fun buttonGoesUnderTheTextAtLargeFontScales() {
        show(2f)
        val title = compose.onNodeWithTag("cellar.sample.title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("action.remove_sample").fetchSemanticsNode().boundsInRoot
        assert(button.top >= title.bottom - 1f) { "the button is beside the text at 200 %" }
    }

    @Test
    fun buttonStaysBesideTheTextAtTheDefaultSize() {
        show(1f)
        val title = compose.onNodeWithTag("cellar.sample.title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("action.remove_sample").fetchSemanticsNode().boundsInRoot
        assert(button.left >= title.right - 1f) { "the button is not beside the text" }
    }
}

/** The place under a wine of the list: the first one, then "and N more", on one line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationLineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstPlaceAndHowManyMore() {
        val cellar = Cellar()
        val rack = app.encave.core.Rack(name = "Main rack", rows = 4, columns = 4)
        cellar.addRack(rack)
        val wine = Wine(name = "Placed")
        val slots = listOf(app.encave.core.Slot(rack.id, 0, 0), app.encave.core.Slot(rack.id, 0, 1), app.encave.core.Slot(rack.id, 1, 0))
        val bottles = cellar.addBottles(wine, quantity = 3, slots = slots)
        compose.setContent { app.encave.ui.components.LocationLine(cellar, bottles) }
        val text = compose.onNodeWithTag("wine.row.location").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assert(text.startsWith("Main rack · ") && text.endsWith(" and 2 more")) { "location line: $text" }
    }
}
