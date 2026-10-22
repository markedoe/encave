package app.encave.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddBottlesUndoTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun addingOffersUndoAndTellsTheScreenWhetherItWorked() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val rack = Rack(name = "Small", rows = 1, columns = 1)
        runBlocking { app.repository.update { it.addRack(rack) } }
        val wine = Wine(name = "Undo me", vintage = 2020)
        var done: Boolean? = null
        viewModel.addBottles(wine, 1, listOf(Slot(rack.id, 0, 0)), PurchaseInfo()) { done = it }
        waitFor { done != null && messages.isNotEmpty() }
        assertEquals(true, done)
        val added = messages.single { (it.text as? UiText.Res)?.id == R.string.msg_added }
        assertNotNull(added.undo)

        // The place is taken now: the screen stays open.
        var second: Boolean? = null
        viewModel.addBottles(Wine(name = "Too late"), 1, listOf(Slot(rack.id, 0, 0)), PurchaseInfo()) { second = it }
        waitFor { second != null }
        assertFalse(second!!)
        assertTrue(app.repository.cellar.value.wines.values.none { it.name == "Too late" })

        // Undo takes the bottle back, and the new wine with it.
        added.undo!!.invoke()
        waitFor { app.repository.cellar.value.wines.values.none { it.name == wine.name } }
        assertNull(app.repository.cellar.value.wines[wine.id])
        assertTrue(app.repository.cellar.value.bottlesInCellar.isEmpty())
        collector.cancel()
    }
}
