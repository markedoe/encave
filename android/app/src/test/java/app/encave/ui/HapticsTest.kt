package app.encave.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Tasting
import app.encave.core.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HapticsTest {
    private val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
    private val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
    private val haptics = mutableListOf<Haptic>()

    private fun waitFor(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (haptics.size < count && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun confirmsWhatIsDoneAndRejectsATakenPlace() {
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.haptics.collect { haptics += it } }
        val rack = Rack(name = "Haptics", rows = 1, columns = 2)
        viewModel.addRack(rack)
        shadowOf(Looper.getMainLooper()).idle()
        // Adding a rack is not one of the confirmed actions.
        assertEquals(emptyList<Haptic>(), haptics)

        val first = Slot(rack.id, 0, 0)
        viewModel.addBottles(Wine(name = "Placed"), 1, listOf(first), PurchaseInfo())
        waitFor(1)
        viewModel.addBottles(Wine(name = "Same place"), 1, listOf(first), PurchaseInfo())
        waitFor(2)
        val bottle = app.repository.cellar.value.bottles.values.single { it.slot == first }
        viewModel.move(bottle.id, Slot(rack.id, 0, 1))
        waitFor(3)
        viewModel.drink(bottle.id, Tasting())
        waitFor(4)
        collector.cancel()
        assertEquals(listOf(Haptic.Confirm, Haptic.Reject, Haptic.Confirm, Haptic.Confirm), haptics)
    }
}
