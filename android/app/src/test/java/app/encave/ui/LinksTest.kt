package app.encave.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.MainActivity
import app.encave.core.SampleData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reminders and app shortcuts open the app on a section, a wine or the scanner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-xxhdpi")
class LinksTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<EncaveApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun loadSample() {
        runBlocking { app.repository.replace(SampleData.cellar()) }
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun open(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), app, MainActivity::class.java)
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    @Test
    fun tonight() {
        open(MainActivity.LINK_TONIGHT)
        compose.onNodeWithTag("nav.Tonight").assertIsSelected()
    }

    @Test
    fun journal() {
        // The notification of bottles opened on another device.
        open(MainActivity.LINK_JOURNAL)
        compose.onNodeWithTag("nav.Journal").assertIsSelected()
    }

    @Test
    fun cellar() {
        open(MainActivity.LINK_CELLAR)
        compose.onNodeWithTag("nav.Cellar").assertIsSelected()
    }

    @Test
    fun wine() {
        val port = app.repository.cellar.value.wines.values.first { it.name == "Vintage Port" }
        open(MainActivity.wineLink(port.id.toString()))
        compose.onNodeWithText("Vintage Port 2011").assertExists()
    }

    @Test
    fun scan() {
        open(MainActivity.LINK_SCAN)
        compose.onNodeWithTag("barcode.field").assertExists()
    }

    @Test
    fun addBottles() {
        // From a widget on an empty cellar.
        open(MainActivity.LINK_ADD)
        compose.onNodeWithTag("action.scan_barcode").assertExists()
        compose.onNodeWithTag("barcode.field").assertDoesNotExist()
    }
}
