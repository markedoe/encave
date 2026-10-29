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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.data.ai.AiConfiguration
import app.encave.data.ai.AiConnection
import app.encave.data.ai.AiProvider
import app.encave.MainActivity
import app.encave.core.SampleData
import app.encave.ui.components.sampleLabels
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
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
import androidx.compose.ui.test.assertCountEquals

/**
 * Walks through every main screen with the sample cellar, on a phone and a tablet,
 * in English and French. Saves a screenshot of each (build/screens, a CI artifact)
 * and checks the layout: action buttons have centered content, buttons side by side
 * have the same width, and every rack cell fits on screen at the default zoom.
 *
 * Files are named like the Apple tour: phone/en-01-cellar.png, tablet/fr-dark-28-rack-3.png,
 * phone/xl-01-cellar.png (English at font scale 2.0). Navigation uses test tags and content
 * descriptions only, never displayed text, so renamed strings don't break it.
 *
 * The full tour (-Dencave.tour=full: phone and tablet, English and French, light and dark,
 * plus the phone at font scale 2.0) runs in CI on main and manual runs; other runs take
 * the quicker set below, for the layout checks.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenTourTest(private val device: String, private val variant: String) {
    companion object {
        private val devices = mapOf(
            "phone" to "w411dp-h891dp-port",
            "tablet" to "w1280dp-h800dp-land",
            // A 7-inch tablet, for the store listing only (storeListing).
            "small-tablet" to "w960dp-h600dp-land",
        )
        private val densities = mapOf("phone" to "xxhdpi", "tablet" to "xhdpi", "small-tablet" to "xhdpi")

        private val full = System.getProperty("encave.tour") == "full"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun configurations(): List<Array<String>> = if (full) {
            listOf("phone", "tablet").flatMap { d -> listOf("en", "fr", "en-dark", "fr-dark").map { arrayOf(d, it) } } +
                listOf(arrayOf("phone", "xl"), arrayOf("small-tablet", "en"), arrayOf("small-tablet", "fr"))
        } else {
            listOf("phone", "tablet").flatMap { d -> listOf("en", "fr").map { arrayOf(d, it) } } +
                listOf(arrayOf("phone", "en-dark"))
        }

        /**
         * The screens in tour order: a screenshot's number is its place here, so the files
         * sort as a walk through the app. "rack-3" takes the number of "rack".
         */
        private val screens = listOf(
            "cellar", "cellar-drink-soon", "cellar-tag", "cellar-occasion", "cellar-menu", "save-search",
            "saved-search", "cellar-on-order", "cellar-select", "cellar-select-delete", "cellar-table",
            "cellar-table-wine", "insights", "insights-drink-down",
            "wine", "wine-critic-scores", "drink-sheet", "wine-on-order", "wine-occasion", "wine-explore", "wine-edit-window",
            "wine-tags", "more-photos",
            "add-bottle", "add-bottle-end", "add-bottle-on-order", "barcode-scanner", "barcode-match",
            "racks", "rack", "rack-place-sheet", "rack-card", "rack-key", "rack-zoomed", "rack-bottle-list",
            "rack-list-picked", "rack-head-to-tail", "rack-back-bottle", "rack-head-to-tail-zoomed",
            "rack-check", "rack-check-missing", "rack-dragging", "rack-dropped", "rack-editor", "rack-editor-end",
            "rack-wizard",
            "tonight", "journal", "journal-tasted-elsewhere", "journal-add-tasting", "tasted-elsewhere-editor",
            "journal-which-bottle", "journal-open-a-bottle", "tasting-guided",
            "settings", "ai-connections", "ai-models", "help", "devices", "devices-invite", "devices-join", "settings-backup",
            "previous-versions", "restore-preview", "restore-actions", "inventory-pdf",
            "cellar-font-200", "tonight-font-200",
            "store-cellar", "store-wine", "store-rack", "store-add", "store-tonight", "store-journal", "store-insights",
        )

        /** The tabs, by position in the navigation (their tags follow the destination's name, which may change). */
        private const val RACKS = 1
        private const val TONIGHT = 2
        private const val JOURNAL = 3

        private const val DOUBLE_TAP_WAIT = 500L

        /** Buttons laid out side by side that must have the same width. */
        private val pairs = listOf("action.drink" to "action.add_bottles", "action.surprise_drink" to "action.surprise_next")
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    /** The smaller tablet only makes the store listing's screens: its other tests are skipped. */
    @get:Rule(order = -1)
    val storeOnly = org.junit.rules.TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                org.junit.Assume.assumeTrue(device != "small-tablet" || description.methodName.startsWith("storeListing"))
                base.evaluate()
            }
        }
    }

    private var scenario: ActivityScenario<MainActivity>? = null
    private val outDir = File(System.getProperty("encave.screens") ?: "build/screens", device)
    private val language = if (variant.startsWith("fr")) "fr" else "en"
    private val dark = variant.endsWith("-dark")
    private val largeText = variant == "xl"

    private var defaultLocale: Locale? = null

    @Before
    fun launch() {
        val night = if (dark) "night" else "notnight"
        RuntimeEnvironment.setQualifiers("$language-${devices.getValue(device)}-$night-${densities.getValue(device)}")
        if (largeText) RuntimeEnvironment.setFontScale(2f)
        defaultLocale = Locale.getDefault()
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
        // This device as it would be named ("robolectric" otherwise).
        android.provider.Settings.Global.putString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME, "Pixel 9")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun close() {
        scenario?.close()
        RuntimeEnvironment.setFontScale(1f)
        defaultLocale?.let(Locale::setDefault)
    }

    // region Helpers

    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    private fun tap(tag: String) {
        // Not composed yet or cut off (below a lazy list's fold, as at font scale 2.0, where a
        // floating button may cover it): scroll it into view first.
        val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        if (node == null || node.boundsInWindow.height < node.size.height - 1) {
            val lists = compose.onAllNodes(hasScrollAction())
            for (index in lists.fetchSemanticsNodes().indices) {
                if (runCatching { lists[index].performScrollToNode(hasTestTag(tag)) }.isSuccess) break
            }
            compose.waitForIdle()
        }
        compose.onAllNodesWithTag(tag).onFirst().performClick()
        // Rack cells also take double taps (zoom), so a single tap counts once the double-tap time has passed.
        if (tag.startsWith("cell.")) compose.mainClock.advanceTimeBy(DOUBLE_TAP_WAIT)
        compose.waitForIdle()
    }

    private fun tab(index: Int) = tap("nav.${app.encave.ui.navigation.TopLevel.entries[index].name}")

    private fun scrollTo(tag: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /** Scrolls the list tagged [list] until [tag] shows. */
    private fun scrollIn(list: String, tag: String) {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /** Scrolls the list tagged [list] until [tag] is at its top, below [header]. */
    private fun scrollToTop(list: String, tag: String, header: androidx.compose.ui.unit.Dp = 0.dp) {
        scrollIn(list, tag)
        val top = compose.onNodeWithTag(list).fetchSemanticsNode().boundsInWindow.top
        val row = compose.onAllNodesWithTag(tag).onFirst().fetchSemanticsNode().boundsInWindow.top
        val by = row - top - header.value * density
        compose.onNodeWithTag(list).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        settle()
    }

    /**
     * For the store's Cellar image: the list scrolled to its end, so its last rows sit in the
     * space left above the floating buttons (Add, new discovery) instead of under them.
     */
    private fun clearOfFloatingButtons() {
        val list = compose.onNodeWithTag("cellar.list")
        val page = list.fetchSemanticsNode().boundsInWindow.height
        // A page at a time until the end: a lazy list stops there.
        repeat(8) {
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, page) }
            settle()
        }
        val discovery = RuntimeEnvironment.getApplication().getString(app.encave.R.string.discovery_new_long)
        val buttonsTop = minOf(
            compose.onNodeWithTag("action.fab_add").fetchSemanticsNode().boundsInWindow.top,
            compose.onNodeWithContentDescription(discovery).fetchSemanticsNode().boundsInWindow.top,
        )
        compose.onAllNodesWithTag("wine.row").fetchSemanticsNodes().filter { it.boundsInWindow.height > 0 }.forEach {
            assertTrue("A row under the floating buttons", it.boundsInWindow.bottom <= buttonsTop + 1)
        }
    }

    /** Lets animations (a collapsing bar, a scroll) end. */
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The file for a screen: en-01-cellar.png, fr-dark-28-rack-3.png, xl-45-journal.png. */
    private fun screenFile(name: String): File {
        val key = name.takeIf { it in screens } ?: name.substringBeforeLast('-').takeIf { it in screens }
        requireNotNull(key) { "$name is not in the list of screens" }
        outDir.mkdirs()
        return File(outDir, "%s-%02d-%s.png".format(variant, screens.indexOf(key) + 1, name))
    }

    /** Saves the top window (a sheet or dialog when one is open) and runs the layout checks. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val file = screenFile(name)
        // Sheets and dialogs are separate windows: draw every window's views, in order, at their screen position.
        val views = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }
        val metrics = RuntimeEnvironment.getApplication().resources.displayMetrics
        val image = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        views.forEachIndexed { index, view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val params = view.layoutParams as? android.view.WindowManager.LayoutParams
            if (index > 0 && params != null) {
                // A dialog over the window below: its dim, as the system draws it…
                if (params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                // …and where its gravity puts it: Robolectric leaves every window at the top left.
                if (location[0] == 0 && location[1] == 0) {
                    val placed = android.graphics.Rect()
                    android.view.Gravity.apply(
                        params.gravity, view.width, view.height, android.graphics.Rect(0, 0, image.width, image.height),
                        params.x, params.y, placed,
                    )
                    location[0] = placed.left
                    location[1] = placed.top
                }
            }
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            view.draw(canvas)
            canvas.restore()
        }
        checkNotBlank(name, image)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        checkActionButtons(name)
        checkPairs(name)
    }

    /** A screen drawn for real shows text and controls: many colors (antialiased text), not one or two. */
    private fun checkNotBlank(screen: String, image: Bitmap) {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val colors = pixels.toHashSet().size
        assertTrue("$device-$variant: $screen looks blank ($colors colors)", colors >= 8)
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
            // Partly scrolled under the top bar: its visible part is not centered on it.
            if (button.boundsInWindow.height < button.size.height - 1 || button.boundsInWindow.width < button.size.width - 1) return@forEach
            val content =compose.onAllNodes(hasTestTag("action.content").and(hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
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
        capture("cellar")
        tap("wine.row")
        capture("wine")
        tap("action.drink")
        capture("drink-sheet")
    }

    private fun wineRows() = compose.onAllNodesWithTag("wine.row").fetchSemanticsNodes().size

    /** Tablet: Back first closes the wine shown beside the list, then leaves the Cellar. */
    @Test
    fun tabletBackClosesTheWineFirst() {
        if (device != "tablet") return
        tap("wine.row")
        compose.onAllNodesWithTag("action.drink").onFirst().assertExists()
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("cellar.list").assertExists()
        assertEquals("$device-$variant: Back left the Cellar instead of closing the wine", 0,
            compose.onAllNodesWithTag("action.drink").fetchSemanticsNodes().size)
    }

    /**
     * Checks a count of the rows on screen. Skipped at font scale 2.0, where only a row or two
     * fits on the phone: the other runs check the same filters.
     */
    private fun checkRows(message: String, condition: Boolean) {
        if (!largeText) assertTrue("$device-$variant: $message", condition)
    }

    @Test
    fun cellarFigures() {
        val all = wineRows()
        // "To drink soon" filters the list; a second tap shows all again.
        tap("cellar.stat.soon")
        compose.onNodeWithTag("cellar.stat.soon").assertIsSelected()
        val soon = wineRows()
        checkRows("the list did not get shorter ($all to $soon)", soon in 1 until all)
        capture("cellar-drink-soon")
        tap("cellar.stat.soon")
        compose.onNodeWithTag("cellar.stat.soon").assertIsNotSelected()
        // At least as many rows as before (the list may have composed one more ahead meanwhile).
        val again = wineRows()
        checkRows("the list is not back to its full length ($all to $again)", again >= all)
        // Bottles opens the cellar in figures.
        tap("cellar.stat.bottles")
        compose.onNodeWithTag("insights.screen").assertExists()
        capture("insights")
        scrollTo("insights.drinkdown.chart")
        capture("insights-drink-down")
    }

    /** Switches to 200 % font scale, unless the whole run is at that scale (xl). Saves [name] only when it switches. */
    private fun largestFontScale(name: String) {
        if (largeText) return
        RuntimeEnvironment.setFontScale(2f)
        scenario?.recreate()
        compose.waitForIdle()
        capture(name)
    }

    /** The cellar at 200 % font scale on a phone: the figures are a short list, one line each. */
    @Test
    fun cellarAtLargestFontScale() {
        if (device != "phone" || dark) return
        largestFontScale("cellar-font-200")
        val bottles = compose.onNodeWithTag("cellar.stat.bottles").fetchSemanticsNode().boundsInRoot
        val keep = compose.onNodeWithTag("cellar.stat.keep").fetchSemanticsNode().boundsInRoot
        val soon = compose.onNodeWithTag("cellar.stat.soon").fetchSemanticsNode().boundsInRoot
        assertTrue("$device-$variant: the cellar figures are not listed at 200 %", keep.top >= bottles.bottom - 1f)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$device-$variant: the cellar figures fill the screen at 200 %", soon.bottom - bottles.top < screen.height / 2)
    }

    /** Tonight and the wine page at 200 % font scale on a phone: paired buttons stack, one above the other. */
    @Test
    fun pairedButtonsStackAtLargestFontScale() {
        if (device != "phone" || dark) return
        if (!largeText) {
            RuntimeEnvironment.setFontScale(2f)
            scenario?.recreate()
            compose.waitForIdle()
        }
        tab(TONIGHT)
        if (!largeText) capture("tonight-font-200")
        val drink = bounds("action.surprise_drink")!!
        val next = bounds("action.surprise_next")!!
        assertTrue("$device-$variant: Tonight's buttons are not stacked at 200 %", next.top >= drink.bottom - 1f)
    }

    @Test
    fun bottlesOnOrder() {
        // The orders come after the bottles in the cellar: scrolled so their header sits at the top.
        scrollToTop("cellar.list", "order.row", header = 56.dp)
        capture("cellar-on-order")
        tap("order.row")
        scrollIn("wine.detail", "detail.orders")
        capture("wine-on-order")
    }

    @Test
    fun tagFilter() {
        val all = wineRows()
        compose.onAllNodesWithTag("filter.tag").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        val tagged = wineRows()
        checkRows("the tag did not filter the list ($all to $tagged)", tagged in 1 until all)
        capture("cellar-tag")
    }

    @Test
    fun addBottleOnOrder() {
        tap("action.fab_add")
        scrollTo("add.ordered")
        tap("add.ordered")
        tap("add.expected")
        scrollTo("add.order.footer")
        capture("add-bottle-on-order")
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
        capture("cellar-select")
        tap("cellar.select.delete")
        capture("cellar-select-delete")
    }

    @Test
    fun rackCheck() {
        tab(RACKS)
        tap("rack.row.0")
        tap("rack.audit")
        tap("cell.0.0")
        tap("cell.0.1")
        capture("rack-check")
        tap("action.audit.finish")
        capture("rack-check-missing")
        tap("audit.take_out")
        compose.onNodeWithTag("action.audit.finish").assertDoesNotExist()
    }

    /** The first page of the printed inventory, drawn as in the PDF (at twice the size). */
    @Test
    fun inventoryPdf() {
        // Paper: the same in dark mode and at any font scale.
        if (dark || largeText) return
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val image = Bitmap.createBitmap(595 * 2, 842 * 2, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(image)
        canvas.scale(2f, 2f)
        app.encave.ui.settings.InventoryPdf.drawPage(application.repository.cellar.value, application.resources, java.time.LocalDate.now().year, 0, canvas)
        screenFile("inventory-pdf").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        capture("barcode-scanner")
        compose.onNodeWithTag("barcode.field").performTextInput("5601234567897")
        tap("barcode.use")
        compose.onNodeWithTag("barcode.match").assertExists()
        capture("barcode-match")
        tap("action.use_wine")
        compose.onNodeWithTag("barcode.match").assertDoesNotExist()
    }

    @Test
    fun cellarTable() {
        // Wide screens only: the list or a sortable table.
        if (device != "tablet") return
        tap("cellar.layout")
        compose.onNodeWithTag("cellar.table").assertExists()
        tap("table.header.Vintage")
        capture("cellar-table")
        tap("table.row")
        capture("cellar-table-wine")
        tap("cellar.layout")
        compose.onNodeWithTag("cellar.table").assertDoesNotExist()
    }

    @Test
    fun rackDragAndDrop() {
        if (device != "tablet") return
        tab(RACKS)
        tap("rack.row.0")
        // Let the rack settle (fitted to the screen) before measuring places.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val from = bounds("cell.0.0")!!
        val to = bounds("cell.2.3")!!
        compose.onNodeWithTag("cell.0.0").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            val delta = to.center - from.center
            for (step in 1..10) moveBy(delta / 10f)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("rack.dragging").assertExists()
        capture("rack-dragging")
        compose.onNodeWithTag("cell.0.0").performTouchInput { up() }
        compose.waitForIdle()
        // The move is saved in the background: wait for it.
        val repository = ApplicationProvider.getApplicationContext<EncaveApplication>().repository
        // The save runs on another thread and publishes on the main one: let both run.
        fun dropped(): Boolean {
            val cellar = repository.cellar.value
            return cellar.bottleAt(app.encave.core.Slot(cellar.racks.first().id, 2, 3)) != null
        }
        // Idle the main looper on each check so the result posted there is seen, and give a
        // busy CI runner more time.
        compose.waitUntil(30_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            dropped()
        }
        assertTrue("the dragged bottle is in its new place", dropped())
        capture("rack-dropped")
    }

    @Test
    fun wineExplore() {
        tap("wine.row")
        // The first wine's country is shared with other wines; its producer is not, so it edits.
        scrollIn("wine.detail", "detail.edit.producer")
        scrollIn("wine.detail", "detail.explore.country")
        tap("detail.explore.country")
        assertTrue(
            "$device-$variant: no other wine listed for the country",
            compose.onAllNodesWithTag("explore.row").fetchSemanticsNodes().isNotEmpty(),
        )
        // The wine it was opened from comes first, and goes back to its page.
        compose.onAllNodesWithTag("explore.current").onFirst().assertExists()
        capture("wine-explore")
        // A wine of the list opens its page.
        tap("explore.row")
        compose.onAllNodesWithTag("wine.detail").onFirst().assertExists()
    }

    @Test
    fun wineEditFromDetail() {
        tap("wine.row")
        scrollIn("wine.detail", "detail.edit.window")
        tap("detail.edit.window")
        compose.onNodeWithTag("wine.edit").assertExists()
        capture("wine-edit-window")
    }

    @Test
    fun wineTags() {
        tap("wine.row")
        tap("action.edit_wine")
        scrollTo("field.tags")
        capture("wine-tags")
    }

    @Test
    fun occasion() {
        compose.onAllNodesWithTag("filter.reservation").onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        val kept = wineRows()
        checkRows("$kept wines kept for the occasion, not 1", kept == 1)
        capture("cellar-occasion")
        tap("wine.row")
        scrollIn("wine.detail", "bottle.reserved")
        capture("wine-occasion")
    }

    @Test
    fun savedSearch() {
        tap("cellar.stat.soon")
        tap("cellar.sort")
        capture("cellar-menu")
        tap("cellar.save_search")
        compose.onNodeWithTag("save_search.field").performTextInput(if (language == "fr") "Bientôt" else "Soon")
        capture("save-search")
        tap("save_search.save")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("filter.saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter.saved").assertIsSelected()
        capture("saved-search")
    }

    @Test
    fun morePhotos() {
        // Two photos of the first wine in the list, drawn here (no real photos in the tour).
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        // A back label and a receipt, photographed on a table: paper, lines of text, a barcode.
        fun jpeg(table: Int, paper: Int, ink: Int, lines: Int, barcode: Boolean): ByteArray {
            val image = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(image)
            canvas.drawColor(table)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            canvas.save()
            canvas.rotate(-4f, 150f, 200f)
            paint.color = 0x40000000
            canvas.drawRect(52f, 46f, 256f, 366f, paint)
            paint.color = paper
            canvas.drawRect(46f, 40f, 250f, 360f, paint)
            paint.color = ink
            val random = java.util.Random(lines.toLong())
            for (line in 0 until lines) {
                val y = 70f + line * 240f / lines
                canvas.drawRect(66f, y, 66f + 80f + random.nextInt(90), y + 5f, paint)
                if (!barcode) canvas.drawRect(200f, y, 230f, y + 5f, paint)
            }
            if (barcode) {
                var x = 80f
                while (x < 210f) {
                    val bar = 1f + random.nextInt(4)
                    canvas.drawRect(x, 320f, x + bar, 346f, paint)
                    x += bar + 1f + random.nextInt(3)
                }
            }
            canvas.restore()
            return java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
        val ids = listOf(
            jpeg(0xFF6B4A35.toInt(), 0xFFF1E6CF.toInt(), 0xFF5A4636.toInt(), lines = 9, barcode = true),
            jpeg(0xFF3C3A38.toInt(), 0xFFFAFAF7.toInt(), 0xFF404040.toInt(), lines = 14, barcode = false),
        ).mapNotNull { application.photos.save(it) }
        runBlocking {
            application.repository.update { cellar ->
                val wine = cellar.groups().first().wine
                cellar.updateWine(wine.copy(extraPhotoIds = ids))
            }
        }
        compose.waitForIdle()
        tap("wine.row")
        scrollIn("wine.detail", "wine.more_photos")
        scrollIn("wine.detail", "action.add_photo")
        capture("more-photos")
        tap("photo.thumb")
        compose.onNodeWithTag("photo.done").assertExists()
    }

    @Test
    fun addBottle() {
        tap("action.fab_add")
        capture("add-bottle")
        scrollTo("action.add_confirm")
        capture("add-bottle-end")
    }

    @Test
    fun racks() {
        tab(RACKS)
        capture("racks")
        // Every rack of the sample, including the head-to-tail fridge.
        val count = ApplicationProvider.getApplicationContext<EncaveApplication>().repository.cellar.value.racks.size
        assertTrue("the sample has racks", count >= 8)
        for (index in 0 until count) {
            // The list, not the rack shown beside it on a tablet.
            scrollIn("racks.list", "rack.row.$index")
            // The row's own click: a touch at its center can land on the button floating above the list.
            compose.onAllNodesWithTag("rack.row.$index").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            capture("rack-${index + 1}")
            checkRackFits("rack $index")
            scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
        }
    }

    @Test
    fun rackCellSheet() {
        // A place holding two bottles opens the list of the place.
        tab(RACKS)
        scrollTo("rack.row.3")
        tap("rack.row.3")
        tap("cell.0.0")
        capture("rack-place-sheet")
    }

    @Test
    fun headToTailFridge() {
        // The sample's fridge two deep, head-to-tail: each shelf shows its front bottles, then the
        // ones behind (necks facing you). A back place is tapped directly and shows its bottle.
        tab(RACKS)
        scrollTo("rack.row.7")
        tap("rack.row.7")
        compose.onAllNodesWithTag("rack.band.back").onFirst().assertExists()
        compose.onAllNodesWithTag("cell.1.0", useUnmergedTree = true).onFirst().assertExists()
        capture("rack-head-to-tail")
        checkRackFits("head-to-tail fridge")
        tap("cell.1.0.back")
        compose.onNodeWithTag("rack.selection").assertExists()
        capture("rack-back-bottle")
        tap("rack.selection.close")
        // Pinch: the taller drawing still zooms.
        val before = widthOf("cell.1.0.back")
        compose.onNodeWithTag("rack.canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(320f, 0f), center + Offset(40f, 0f), center + Offset(320f, 0f))
        }
        compose.waitForIdle()
        val after = widthOf("cell.1.0.back")
        assertTrue("$device-$variant: back places did not grow ($before to $after)", after > before * 1.5f)
        capture("rack-head-to-tail-zoomed")
    }

    /** Laid-out width, even when the cell is scrolled out of view. */
    private fun widthOf(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().fetchSemanticsNode().size.width.toFloat()

    @Test
    fun rackCardKeyZoomAndList() {
        tab(RACKS)
        tap("rack.row.0")
        // One bottle per place: a tap shows it in a card above the key.
        tap("cell.0.0")
        compose.onNodeWithTag("rack.selection").assertExists()
        capture("rack-card")
        tap("rack.selection.close")
        compose.onNodeWithTag("rack.selection").assertDoesNotExist()
        // A wine in the key lights up its bottles.
        tap("rack.key.wine")
        // The sample's vintages follow the current year.
        val vintage = ApplicationProvider.getApplicationContext<EncaveApplication>().repository.cellar.value.wines.values
            .filter { it.name == "Clos des Tilleuls" }.mapNotNull { it.vintage }.min()
        val chip = if (language == "fr") "Clos des Tilleuls $vintage, Rouge, 3 bouteilles" else "Clos des Tilleuls $vintage, Red, 3 bottles"
        compose.onNodeWithContentDescription(chip).assertIsSelected()
        capture("rack-key")
        tap("rack.key.wine")
        // Pinch: the rack is drawn again with bigger cells.
        val before = widthOf("cell.0.0")
        compose.onNodeWithTag("rack.canvas").performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(320f, 0f), center + Offset(40f, 0f), center + Offset(320f, 0f))
        }
        compose.waitForIdle()
        val after = widthOf("cell.0.0")
        assertTrue("$device-$variant: cells did not grow ($before to $after)", after > before * 1.5f)
        capture("rack-zoomed")
        // The bottle list: picking a bottle closes it and selects the bottle.
        tap("action.bottle_list")
        capture("rack-bottle-list")
        tap("rack.list.row")
        compose.onNodeWithTag("rack.list").assertDoesNotExist()
        compose.onNodeWithTag("rack.selection").assertExists()
        capture("rack-list-picked")
    }

    @Test
    fun rackEditor() {
        tab(RACKS)
        tap("rack.row.0")
        compose.onNodeWithTag("action.edit_rack").performClick()
        compose.waitForIdle()
        capture("rack-editor")
        scrollTo("action.delete_rack")
        capture("rack-editor-end")
    }

    @Test
    fun rackWizard() {
        tab(RACKS)
        // A new storage, step by step: its type, its settings, its name.
        tap("racks.add")
        capture("rack-wizard-1")
        tap("wizard.type.winefridge")
        tap("wizard.next")
        capture("rack-wizard-2")
        tap("wizard.next")
        compose.onNodeWithTag("wizard.create").assertExists()
        capture("rack-wizard-3")
    }

    @Test
    fun tonightAndJournal() {
        tab(TONIGHT)
        capture("tonight")
        tab(JOURNAL)
        // One diary: bottles from the cellar and wines tasted elsewhere together.
        compose.onAllNodesWithTag("journal.row.cellar").onFirst().assertExists()
        compose.onAllNodesWithTag("journal.row.elsewhere").onFirst().assertExists()
        capture("journal")
        tap("journal.filter.elsewhere")
        compose.onAllNodesWithTag("journal.row.cellar").assertCountEquals(0)
        capture("journal-tasted-elsewhere")
        tap("journal.filter.all")
        tap("action.add_tasting")
        capture("journal-add-tasting")
        tap("journal.add.elsewhere")
        capture("tasted-elsewhere-editor")
    }

    @Test
    fun journalOpenABottleFromTheCellar() {
        tab(JOURNAL)
        tap("action.add_tasting")
        tap("journal.add.cellar")
        compose.onAllNodesWithTag("drink.pick.row").onFirst().assertExists()
        capture("journal-which-bottle")
        tap("drink.pick.row")
        compose.onNodeWithTag("action.drink_confirm").assertExists()
        capture("journal-open-a-bottle")
    }

    /** A sample wine, by name (sample data, the same in every language). */
    private fun sampleWine(name: String): app.encave.core.Wine =
        ApplicationProvider.getApplicationContext<EncaveApplication>().repository.cellar.value.wines.values.first { it.name == name }

    /**
     * The guided tasting sheet: the sample's full tasting of Vieilles Vignes, scored 95/100,
     * on a device that rates out of 100, scrolled to its open "More details".
     */
    @Test
    fun guidedTasting() {
        val preferences = ApplicationProvider.getApplicationContext<EncaveApplication>().preferences
        preferences.setRatingScale(app.encave.core.RatingScale.Hundred)
        try {
            // The newest tasting of the wine comes first: the guided one.
            openJournalRow(sampleWine("Vieilles Vignes"))
            compose.onNodeWithTag("rating.slider").assertExists()
            compose.onNodeWithTag("aroma.fruit.redCherry").assertIsSelected()
            compose.onNodeWithTag("hue.ruby").assertIsSelected()
            scrollToTop("tasting.screen", "tasting.more_details")
            capture("tasting-guided")
        } finally {
            preferences.setRatingScale(app.encave.core.RatingScale.Stars)
        }
    }

    /** The wine page of the sample's Clos des Tilleuls, scrolled to its details and critics' scores. */
    @Test
    fun wineCriticScores() {
        relaunch("encave://wine/${sampleWine("Clos des Tilleuls").id}")
        scrollToTop("wine.detail", "detail.critics")
        assertEquals("$device-$variant: the sample's critic scores", 3, compose.onAllNodesWithTag("critic.row").fetchSemanticsNodes().size)
        capture("wine-critic-scores")
    }

    @Test
    fun devicesAddAndJoin() {
        tap("action.settings")
        scrollTo("settings.devices")
        tap("settings.devices")
        compose.onNodeWithTag("devices.add").assertExists()
        compose.onNodeWithTag("devices.status_local").assertExists()
        compose.onNodeWithTag("devices.notify").assertExists()
        capture("devices")
        tap("devices.add")
        compose.onNodeWithTag("devices.code").assertExists()
        compose.onNodeWithTag("devices.qr").assertExists()
        capture("devices-invite")
        tap("sheet.cancel")
        tap("devices.join")
        compose.onNodeWithTag("devices.codeField").assertExists()
        capture("devices-join")
    }

    /** Settings › Your data › Previous versions, kept by saving the cellar on a new day, and a version's preview. */
    @Test
    fun previousVersionsAndRestorePreview() {
        val encave = ApplicationProvider.getApplicationContext<EncaveApplication>()
        runBlocking {
            // The sample saved two days ago, then changed today: the earlier state is kept.
            File(encave.filesDir, "cellar.json").setLastModified(System.currentTimeMillis() - 2 * 86_400_000L)
            val changed = encave.repository.cellar.value.copy()
            changed.bottles.values.firstOrNull { it.status == app.encave.core.Bottle.Status.InCellar }?.let { changed.deleteBottle(it.id) }
            encave.repository.replace(changed)
        }
        tap("action.settings")
        scrollTo("settings.versions")
        capture("settings-backup")
        tap("settings.versions")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("versions.row").fetchSemanticsNodes().isNotEmpty() }
        capture("previous-versions")
        tap("versions.row")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("restore.preview").fetchSemanticsNodes().isNotEmpty() }
        capture("restore-preview")
        compose.onNodeWithTag("restore.preview").performScrollToNode(hasTestTag("restore.replace"))
        compose.onNodeWithTag("restore.add").assertExists()
        capture("restore-actions")
    }

    /** Settings › AI: made-up connections and models, with no key. */
    @Test
    fun aiConnectionsAndModels() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val openRouter = AiConnection(AiProvider.OpenRouter, name = "OpenRouter perso")
        val anthropic = AiConnection(AiProvider.Anthropic)
        val ollama = AiConnection(AiProvider.Ollama, name = "Mac mini")
        app.ai.update {
            AiConfiguration().upserting(openRouter).upserting(anthropic).upserting(ollama)
                .addingModel("claude-opus-5-5", anthropic.id).addingModel("google/gemini-3-flash", openRouter.id)
                .addingModel("gemma3:27b", ollama.id)
        }
        compose.waitForIdle()
        tap("action.settings")
        scrollTo("settings.ai.connections")
        tap("settings.ai.connections")
        capture("ai-connections")
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        scrollTo("settings.ai.models")
        tap("settings.ai.models")
        compose.onNodeWithTag("ai.model.0").assertExists()
        capture("ai-models")
    }

    @Test
    fun settingsAndHelp() {
        tap("action.settings")
        capture("settings")
        scrollTo("settings.help")
        tap("settings.help")
        capture("help")
    }

    /**
     * The Google Play listing's screens (android/tools/play_listing_images.py, docs/store/play.json):
     * the sample cellar without its banner (a test-only switch: the app always shows it), on
     * tablets a bottle open beside each list, the head-to-tail fridge and a recognised barcode.
     * The only test of the smaller tablet, which makes the 7-inch screenshots.
     */
    @Test
    fun storeListing() {
        if (dark || largeText) return
        val phone = device == "phone"
        // A barcode seen before on a bottle of the Port, as in addBottleBarcode.
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        runBlocking {
            application.repository.update { cellar ->
                cellar.updateWine(cellar.wines.values.first { it.name == "Vintage Port" }.addingBarcode("5601234567897"))
            }
        }
        app.encave.ui.cellar.storeCapture = true
        try {
            scenario?.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("cellar.sample").assertDoesNotExist()
            if (phone) {
                clearOfFloatingButtons()
                capture("store-cellar")
                tap("wine.row")
                capture("store-wine")
                scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
            } else {
                tap("wine.row")
                clearOfFloatingButtons()
                capture("store-cellar")
            }
            tap("cellar.stat.bottles")
            compose.onNodeWithTag("insights.screen").assertExists()
            capture("store-insights")
            scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            tab(RACKS)
            scrollIn("racks.list", "rack.row.7")
            compose.onAllNodesWithTag("rack.row.7").onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            compose.onAllNodesWithTag("rack.band.back").onFirst().assertExists()
            settle()
            checkRackFits("store-rack")
            capture("store-rack")
            tab(TONIGHT)
            if (!phone) tap("tonight.row")
            // The large title bar fully open, not caught mid-collapse.
            compose.onNodeWithTag("tonight.list").performScrollToIndex(0)
            settle()
            capture("store-tonight")
            tab(JOURNAL)
            if (!phone) tap("journal.row.cellar")
            capture("store-journal")
            // Last: the form leaves the navigation.
            tab(0)
            tap("action.fab_add")
            tap("action.scan_barcode")
            compose.onNodeWithTag("barcode.field").performTextInput("5601234567897")
            tap("barcode.use")
            compose.onNodeWithTag("barcode.match").assertExists()
            capture("store-add")
        } finally {
            app.encave.ui.cellar.storeCapture = false
        }
    }

    // region Navigation links

    /**
     * The sample's first wine (by name) whose bottles are all in the cellar, given a label
     * photo, with every bottle drunk: the state where links to a wine used to lead nowhere.
     */
    private fun drinkOutAWine(): app.encave.core.Wine {
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val image = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFF1E6CF.toInt()) }
        val photo = java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            .let { requireNotNull(application.photos.save(it)) }
        val drunk = runBlocking {
            application.repository.update { cellar ->
                val wine = cellar.wines.values.sortedBy { it.name }.first { wine ->
                    val bottles = cellar.bottles.values.filter { it.wineId == wine.id }
                    bottles.isNotEmpty() && bottles.all { it.status == app.encave.core.Bottle.Status.InCellar }
                }
                cellar.updateWine(wine.copy(photoId = photo))
                cellar.bottles.values.filter { it.wineId == wine.id }.forEach { cellar.drink(it.id, app.encave.core.Tasting()) }
                cellar.wines.getValue(wine.id)
            }.getOrThrow()
        }
        compose.waitForIdle()
        return drunk
    }

    /** A new window on the same cellar, opened from [link] (encave://…) if given. */
    private fun relaunch(link: String? = null) {
        scenario?.close()
        val context = ApplicationProvider.getApplicationContext<EncaveApplication>()
        scenario = if (link != null) {
            ActivityScenario.launch(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link), context, MainActivity::class.java))
        } else {
            ActivityScenario.launch(MainActivity::class.java)
        }
        compose.waitForIdle()
    }

    /** The screen tagged [anchor] is shown and is not empty: some text or description under it. */
    private fun assertLeadsTo(anchor: String, link: String) {
        val nodes = compose.onAllNodesWithTag(anchor, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$device-$variant: $link leads nowhere (no $anchor)", nodes.isNotEmpty())
        fun content(node: SemanticsNode): Int =
            (if (SemanticsProperties.Text in node.config || SemanticsProperties.ContentDescription in node.config) 1 else 0) +
                node.children.sumOf { content(it) }
        assertTrue("$device-$variant: $link leads to an empty $anchor", content(nodes.first()) >= 2)
    }

    /** The Journal row of a bottle of [wine] from the cellar. */
    private fun journalRow(wine: app.encave.core.Wine): SemanticsMatcher =
        hasTestTag("journal.row.cellar") and androidx.compose.ui.test.hasContentDescription(wine.displayName, substring = true)

    private fun openJournalRow(wine: app.encave.core.Wine) {
        tab(JOURNAL)
        compose.onNodeWithTag("journal.list").performScrollToNode(journalRow(wine))
        compose.onAllNodes(journalRow(wine)).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Opening the cellar's Add button when the cellar is empty leads where the Add button of a full cellar does. */
    @Test
    fun emptyCellarAddLeadsWhereAddDoes() {
        tap("action.fab_add")
        assertLeadsTo("add.screen", "the Add button")
        val application = ApplicationProvider.getApplicationContext<EncaveApplication>()
        runBlocking { application.repository.replace(app.encave.core.Cellar()) }
        relaunch()
        tap("action.empty_add")
        assertLeadsTo("add.screen", "the empty cellar's Add button")
    }

    /** Every bottle of a wine drunk: the Journal still shows its picture and every link to it opens its page. */
    @Test
    fun drunkWineStaysReachableFromTheJournal() {
        val wine = drinkOutAWine()
        tab(JOURNAL)
        compose.onNodeWithTag("journal.list").performScrollToNode(journalRow(wine))
        assertTrue(
            "$device-$variant: the Journal row of a drunk wine shows no picture",
            compose.onAllNodes(hasTestTag("journal.row.picture") and androidx.compose.ui.test.hasAnyDescendant(journalRow(wine)))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        val wineLinks = listOf("tasting.show_wine", "tasting.wine_name", "journal.menu.show_wine")
        for (link in wineLinks) {
            relaunch()
            if (link == "journal.menu.show_wine") {
                // The row's long press menu.
                tab(JOURNAL)
                compose.onNodeWithTag("journal.list").performScrollToNode(journalRow(wine))
                compose.onAllNodes(journalRow(wine)).onFirst().performTouchInput { longClick() }
                compose.waitForIdle()
            } else {
                openJournalRow(wine)
                assertLeadsTo("tasting.screen", "a Journal entry")
                compose.onNodeWithTag("tasting.photo").assertExists()
            }
            tap(link)
            assertLeadsTo("wine.detail", link)
            compose.onAllNodesWithTag("detail.photo").onFirst().assertExists()
            // The wine's page, never a Cellar list that no longer shows it.
            if (device == "phone") compose.onNodeWithTag("cellar.list").assertDoesNotExist()
        }
        // A widget, reminder or shortcut link to it.
        relaunch("encave://wine/${wine.id}")
        assertLeadsTo("wine.detail", "encave://wine/<id> of a wine with no bottle left")
    }

    /**
     * No dead ends: each link (or button that navigates) opens a screen that shows something.
     * Every new link gets a row here (docs/development.md). Each starts from a new window.
     */
    @Test
    fun navigationLinksLeadSomewhere() {
        class Link(val name: String, val destination: String, val open: () -> Unit)
        val links = listOf(
            Link("cellar row", "wine.detail") { tap("wine.row") },
            Link("cellar figures", "insights.screen") { tap("cellar.stat.bottles") },
            Link("tonight row", "wine.detail") { tab(TONIGHT); tap("tonight.row") },
            Link("rack position", "rack.selection") { tab(RACKS); tap("rack.row.0"); tap("cell.0.0") },
            Link("rack card wine", "wine.detail") { tab(RACKS); tap("rack.row.0"); tap("cell.0.0"); tap("rack.selection.wine") },
            Link("journal entry", "tasting.screen") { tab(JOURNAL); tap("journal.row.cellar") },
            Link("tasting wine name", "wine.detail") { tab(JOURNAL); tap("journal.row.cellar"); tap("tasting.wine_name") },
            Link("tasting Show wine", "wine.detail") { tab(JOURNAL); tap("journal.row.cellar"); tap("tasting.show_wine") },
        )
        for (link in links) {
            relaunch()
            link.open()
            assertLeadsTo(link.destination, link.name)
        }
        // The same links once the wine has no bottle left.
        val wine = drinkOutAWine()
        val noneLeft = listOf(
            Link("tasting wine name, no bottle left", "wine.detail") { openJournalRow(wine); tap("tasting.wine_name") },
            Link("tasting Show wine, no bottle left", "wine.detail") { openJournalRow(wine); tap("tasting.show_wine") },
            Link("wine link, no bottle left", "wine.detail") { relaunch("encave://wine/${wine.id}") },
        )
        for (link in noneLeft) {
            relaunch()
            link.open()
            assertLeadsTo(link.destination, link.name)
        }
    }

    // endregion
}
