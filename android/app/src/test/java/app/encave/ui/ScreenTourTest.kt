package app.encave.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.SampleData
import app.encave.ui.components.sampleLabels
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walks through every main screen with the sample cellar, on a phone and a tablet,
 * in English and French. Saves a screenshot of each (build/screens, a CI artifact)
 * and checks the layout: action buttons have centered content, buttons side by side
 * have the same width, and every rack cell fits on screen at the default zoom.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenTourTest(private val device: String, private val language: String) {
    companion object {
        private val devices = mapOf(
            "phone" to "w411dp-h891dp-port-xxhdpi",
            "tablet" to "w1280dp-h800dp-land-xhdpi",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun configurations() = listOf("phone", "tablet").flatMap { d -> listOf("en", "fr").map { arrayOf(d, it) } }

        private const val DOUBLE_TAP_WAIT = 500L

        /** Buttons laid out side by side that must have the same width. */
        private val pairs = listOf("action.drink" to "action.add_bottles", "action.surprise_drink" to "action.surprise_next")
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val outDir = File(System.getProperty("encave.screens") ?: "build/screens", "$device-$language")

    @Before
    fun launch() {
        RuntimeEnvironment.setQualifiers("$language-${devices.getValue(device)}")
        Locale.setDefault(Locale.forLanguageTag(language))
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        // The Encave colors, not the test device's wallpaper colors.
        app.preferences.setDynamicColor(false)
        runBlocking {
            app.repository.replace(SampleData.cellar(labels = sampleLabels(app.resources)))
            // Devices: shown with a second device, without touching the network.
            app.sync.awaitStarted()
            app.sync.showDemo()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun close() {
        scenario?.close()
    }

    // region Helpers

    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun tap(tag: String) {
        compose.onAllNodesWithTag(tag).onFirst().performClick()
        // Rack cells also take double taps (zoom), so a single tap counts once the double-tap time has passed.
        if (tag.startsWith("cell.")) compose.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT)
        compose.waitForIdle()
    }

    private fun tab(name: String) = tap("nav.$name")

    private fun scrollTo(tag: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /** Scrolls the list tagged [list] until [tag] shows. */
    private fun scrollIn(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /** Saves the top window (a sheet or dialog when one is open) and runs the layout checks. */
    private fun capture(name: String) {
        compose.waitForIdle()
        outDir.mkdirs()
        // Sheets and dialogs are separate windows: draw every window's views, in order, at their screen position.
        val views = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }
        val metrics = RuntimeEnvironment.getApplication().resources.displayMetrics
        val image = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        views.forEach { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        File(outDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        checkActionButtons(name)
        checkPairs(name)
    }

    private fun tagOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.TestTag)

    private val actionButton = SemanticsMatcher("action button") { node ->
        val tag = tagOf(node)
        tag != null && tag.startsWith("action.") && tag != "action.content"
    }

    private fun bounds(tag: String): Rect? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    /** The icon and label of every tagged action button are centered in it, within 3dp. */
    private fun checkActionButtons(screen: String) {
        compose.onAllNodes(actionButton, useUnmergedTree = true).fetchSemanticsNodes().forEach { button ->
            val tag = tagOf(button)!!
            val content = compose.onAllNodes(hasTestTag("action.content").and(hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull() ?: return@forEach
            val offset = (content.boundsInWindow.center.x - button.boundsInWindow.center.x) / density
            assertTrue("$screen: $tag content is ${"%.1f".format(offset)}dp off center", kotlin.math.abs(offset) <= 3f)
        }
    }

    /** Buttons side by side have the same width. */
    private fun checkPairs(screen: String) {
        pairs.forEach { (a, b) ->
            val first = bounds(a) ?: return@forEach
            val second = bounds(b) ?: return@forEach
            val difference = kotlin.math.abs(first.width - second.width) / density
            assertTrue("$screen: $a and $b differ by ${"%.1f".format(difference)}dp", difference <= 1f)
        }
    }

    /** At the default zoom, every cell of the rack is inside the screen. */
    private fun checkRackFits(screen: String) {
        val width = compose.onAllNodes(isRoot()).onFirst().fetchSemanticsNode().boundsInWindow.width
        val cells = compose.onAllNodes(SemanticsMatcher("rack cell") { tagOf(it)?.startsWith("cell.") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("$screen: no rack cells", cells.isNotEmpty())
        cells.forEach { cell ->
            val box = cell.boundsInWindow
            assertTrue("$screen: ${tagOf(cell)} is cut off (${box.left}..${box.right} of $width)", box.left >= -1f && box.right <= width + 1f)
        }
    }

    // endregion

    @Test
    fun cellarWineDetailAndDrinkSheet() {
        capture("01-cellar")
        tap("wine.row")
        capture("02-wine-detail")
        tap("action.drink")
        capture("03-drink-sheet")
    }

    private fun wineRows() = compose.onAllNodesWithTag("wine.row").fetchSemanticsNodes().size

    @Test
    fun cellarFigures() {
        val all = wineRows()
        // "To drink soon" filters the list; a second tap shows all again.
        tap("cellar.stat.soon")
        compose.onNodeWithTag("cellar.stat.soon").assertIsSelected()
        val soon = wineRows()
        assertTrue("$device-$language: the list did not get shorter ($all to $soon)", soon in 1 until all)
        capture("20-cellar-drink-soon")
        tap("cellar.stat.soon")
        compose.onNodeWithTag("cellar.stat.soon").assertIsNotSelected()
        assertEquals("$device-$language: the list is not back to its full length", all, wineRows())
        // Bottles opens the cellar in figures.
        tap("cellar.stat.bottles")
        val insights = RuntimeEnvironment.getApplication().getString(app.encave.R.string.action_insights)
        compose.onAllNodesWithText(insights).onFirst().assertExists()
        capture("21-insights")
        scrollTo("insights.drinkdown.chart")
        capture("29-insights-drink-down")
    }

    @Test
    fun bottlesOnOrder() {
        scrollIn("cellar.list", "order.row")
        capture("25-cellar-on-order")
        tap("order.row")
        scrollIn("wine.detail", "detail.orders")
        capture("26-wine-on-order")
    }

    @Test
    fun tagFilter() {
        val all = wineRows()
        compose.onAllNodesWithTag("filter.tag").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        val tagged = wineRows()
        assertTrue("$device-$language: the tag did not filter the list ($all to $tagged)", tagged in 1 until all)
        capture("27-cellar-tag")
    }

    @Test
    fun addBottleOnOrder() {
        tap("action.fab_add")
        scrollTo("add.ordered")
        tap("add.ordered")
        tap("add.expected")
        scrollTo("add.order.footer")
        capture("28-add-bottle-on-order")
    }

    @Test
    fun bulkSelection() {
        tap("cellar.sort")
        tap("cellar.select")
        val rows = compose.onAllNodesWithTag("wine.select.row")
        rows[0].performClick()
        rows[2].performClick()
        compose.waitForIdle()
        rows[0].assertIsOn()
        capture("30-cellar-select")
        tap("cellar.select.delete")
        capture("31-cellar-select-delete")
    }

    @Test
    fun rackCheck() {
        tab("Racks")
        tap("rack.row.0")
        tap("rack.audit")
        tap("cell.0.0")
        tap("cell.0.1")
        capture("32-rack-check")
        tap("action.audit.finish")
        capture("33-rack-check-missing")
        tap("audit.take_out")
        compose.onNodeWithTag("action.audit.finish").assertDoesNotExist()
    }

    /** The first page of the printed inventory, drawn as in the PDF (at twice the size). */
    @Test
    fun inventoryPdf() {
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val image = Bitmap.createBitmap(595 * 2, 842 * 2, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.scale(2f, 2f)
        app.encave.ui.settings.InventoryPdf.drawPage(application.repository.cellar.value, application.resources, java.time.LocalDate.now().year, 0, canvas)
        outDir.mkdirs()
        File(outDir, "34-inventory-pdf.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun addBottleBarcode() {
        // A barcode seen before on a bottle of the Port.
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        runBlocking {
            application.repository.update { cellar ->
                val port = cellar.wines.values.first { it.name == "Vintage Port" }
                cellar.updateWine(port.addingBarcode("5601234567897"))
            }
        }
        tap("action.fab_add")
        tap("action.scan_barcode")
        capture("35-barcode-scanner")
        compose.onNodeWithTag("barcode.field").performTextInput("5601234567897")
        tap("barcode.use")
        compose.onNodeWithTag("barcode.match").assertExists()
        capture("36-barcode-match")
        tap("action.use_wine")
        compose.onNodeWithTag("barcode.match").assertDoesNotExist()
    }

    @Test
    fun addBottle() {
        tap("action.fab_add")
        capture("04-add-bottle")
        scrollTo("action.add_confirm")
        capture("04b-add-bottle-end")
    }

    @Test
    fun racks() {
        tab("Racks")
        capture("05-racks")
        for (index in 0 until 6) {
            scrollTo("rack.row.$index")
            tap("rack.row.$index")
            capture("06-rack-$index")
            checkRackFits("rack $index")
            scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
    }

    @Test
    fun rackCellSheet() {
        // A place holding two bottles opens the list of the place.
        tab("Racks")
        scrollTo("rack.row.3")
        tap("rack.row.3")
        tap("cell.0.0")
        capture("07-rack-cell-sheet")
    }

    /** Laid-out width, even when the cell is scrolled out of view. */
    private fun widthOf(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().fetchSemanticsNode().size.width.toFloat()

    @Test
    fun rackCardKeyZoomAndList() {
        tab("Racks")
        tap("rack.row.0")
        // One bottle per place: a tap shows it in a card above the key.
        tap("cell.0.0")
        compose.onNodeWithTag("rack.selection").assertExists()
        capture("15-rack-card")
        tap("rack.selection.close")
        compose.onNodeWithTag("rack.selection").assertDoesNotExist()
        // A wine in the key lights up its bottles.
        tap("rack.key.wine")
        val chip = if (language == "fr") "Clos des Tilleuls 2017, 3 bouteilles" else "Clos des Tilleuls 2017, 3 bottles"
        compose.onNodeWithContentDescription(chip).assertIsSelected()
        capture("16-rack-key")
        tap("rack.key.wine")
        // Pinch: the rack is drawn again with bigger cells.
        val before = widthOf("cell.0.0")
        compose.onNodeWithTag("rack.canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(320f, 0f), center + Offset(40f, 0f), center + Offset(320f, 0f))
        }
        compose.waitForIdle()
        val after = widthOf("cell.0.0")
        assertTrue("$device-$language: cells did not grow ($before to $after)", after > before * 1.5f)
        capture("17-rack-zoomed")
        // The bottle list: picking a bottle closes it and selects the bottle.
        tap("action.bottle_list")
        capture("18-rack-bottle-list")
        tap("rack.list.row")
        compose.onNodeWithTag("rack.list").assertDoesNotExist()
        compose.onNodeWithTag("rack.selection").assertExists()
        capture("19-rack-list-picked")
    }

    @Test
    fun rackEditor() {
        tab("Racks")
        tap("rack.row.0")
        compose.onNodeWithTag("action.edit_rack").performClick()
        compose.waitForIdle()
        capture("08-rack-editor")
        scrollTo("action.delete_rack")
        capture("08b-rack-editor-end")
    }

    @Test
    fun tonightAndJournal() {
        tab("Tonight")
        capture("09-tonight")
        tab("Journal")
        capture("10-journal-tastings")
        tap("journal.discoveries")
        capture("11-discoveries")
        tap("action.remember")
        capture("12-remember-a-wine")
    }

    @Test
    fun devicesAddAndJoin() {
        tap("action.settings")
        scrollTo("settings.devices")
        tap("settings.devices")
        compose.onNodeWithTag("devices.add").assertExists()
        capture("22-devices")
        tap("devices.add")
        compose.onNodeWithTag("devices.code").assertExists()
        compose.onNodeWithTag("devices.qr").assertExists()
        capture("23-add-device")
        tap("sheet.cancel")
        tap("devices.join")
        compose.onNodeWithTag("devices.codeField").assertExists()
        capture("24-join-device")
    }

    @Test
    fun settingsAndHelp() {
        tap("action.settings")
        capture("13-settings")
        scrollTo("settings.help")
        tap("settings.help")
        capture("14-help")
    }
}
