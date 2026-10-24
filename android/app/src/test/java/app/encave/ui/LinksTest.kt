package app.encave.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
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

/** Reminders, app shortcuts and pairing links open the app on a section, a wine, the scanner or Join. */
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
        // Its page: the header shows the name (the top bar only once it scrolls away).
        compose.onNodeWithTag("wine.detail").assertExists()
        compose.onAllNodesWithText("Vintage Port").onFirst().assertExists()
    }

    @Test
    fun pairingCodeScannedWithTheCamera() {
        // The QR code shown when adding a device: Join opens with the code filled in.
        open("encave://pair?code=123456&c=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        compose.onNodeWithTag("devices.codeField").assertTextContains("123 456", substring = true)
        compose.onNodeWithTag("action.join").assertIsEnabled()
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
