package app.encave.ui

import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.R
import app.encave.idleMainLooperUntil
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.data.ai.LabelAnalysis
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddBottlesUndoTest {
    private fun waitFor(condition: () -> Boolean) = idleMainLooperUntil(condition = condition)

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

    @Test
    fun anAiCompletionWhileTheAddIsShownKeepsOneUndoForBoth() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val wine = Wine(name = "Read later")
        var done: Boolean? = null
        viewModel.addBottles(wine, 2, emptyList(), PurchaseInfo()) { done = it }
        waitFor { done != null }
        val added = messages.single { (it.text as? UiText.Res)?.id == R.string.msg_added }

        // The AI answers while the "Added" snackbar is still shown: it takes its place.
        viewModel.completeWine(wine, LabelAnalysis(producer = "Domaine Lu"))
        waitFor { messages.size == 2 }
        assertTrue(added.slot!!.replaced.value)
        val both = messages.last()
        assertEquals(R.string.msg_added_ai_completed, (both.text as UiText.Res).id)
        assertEquals("Domaine Lu", app.repository.cellar.value.wines[wine.id]?.producer)

        // Its Undo takes back the completion and the bottles.
        both.undo!!.invoke()
        waitFor { app.repository.cellar.value.wines[wine.id] == null }
        assertNull(app.repository.cellar.value.wines[wine.id])
        assertTrue(app.repository.cellar.value.bottlesInCellar.isEmpty())
        collector.cancel()
    }

    @Test
    fun anAiCompletionAfterTheAddWasShownHasItsOwnUndo() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val wine = Wine(name = "Read much later")
        var done: Boolean? = null
        viewModel.addBottles(wine, 1, emptyList(), PurchaseInfo()) { done = it }
        waitFor { done != null }
        // The snackbar has gone.
        messages.single().slot!!.finished = true

        viewModel.completeWine(wine, LabelAnalysis(producer = "Domaine Lu"))
        waitFor { messages.size == 2 }
        val completed = messages.last()
        assertEquals(R.string.msg_ai_completed, (completed.text as UiText.Res).id)
        completed.undo!!.invoke()
        waitFor { app.repository.cellar.value.wines[wine.id]?.producer == null }
        assertNull(app.repository.cellar.value.wines[wine.id]?.producer)
        assertEquals(1, app.repository.cellar.value.bottlesInCellar.count { it.wineId == wine.id })
        collector.cancel()
    }

    @Test
    fun anAiAnswerWithNothingNewSaysSoAndLeavesTheWine() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val wine = Wine(name = "Nothing new", producer = "Domaine Lu")
        var done: Boolean? = null
        viewModel.addBottles(wine, 1, emptyList(), PurchaseInfo()) { done = it }
        waitFor { done != null }

        viewModel.completeWine(wine, LabelAnalysis(producer = "Another producer"))
        waitFor { messages.size == 2 }
        assertEquals(R.string.msg_ai_nothing, (messages.last().text as UiText.Res).id)
        assertNull(messages.last().undo)
        assertNull(app.repository.cellar.value.wines[wine.id]?.aiCompletedAt)
        assertEquals("Domaine Lu", app.repository.cellar.value.wines[wine.id]?.producer)
        collector.cancel()
    }

    @Test
    fun anAiAnswerThatMakesTheWineATwinOffersToMergeWithUndo() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val existing = Wine(name = "Clos Twin", vintage = 2018)
        runBlocking { app.repository.update { it.addBottles(existing, 1) } }
        // Added without its vintage: not the same wine yet.
        val wine = Wine(name = "Clos Twin")
        var done: Boolean? = null
        viewModel.addBottles(wine, 2, emptyList(), PurchaseInfo()) { done = it }
        waitFor { done != null }
        messages.single().slot!!.finished = true

        // The AI reads the vintage: now the same wine as the one already there.
        viewModel.completeWine(wine, LabelAnalysis(vintage = 2018))
        waitFor { messages.size == 3 }
        assertEquals(R.string.msg_ai_completed, (messages[1].text as UiText.Res).id)
        val offer = messages[2]
        assertEquals(R.string.msg_same_wine, (offer.text as UiText.Res).id)
        assertEquals(R.string.action_merge, (offer.actionLabel as UiText.Res).id)

        offer.undo!!.invoke()
        waitFor { messages.size == 4 }
        val merged = messages[3]
        assertEquals(R.string.msg_merged, (merged.text as UiText.Res).id)
        val cellar = app.repository.cellar.value
        assertNull(cellar.wines[wine.id])
        assertEquals(3, cellar.bottlesInCellar.count { it.wineId == existing.id })

        // Undo: two wines again, the bottles back with the new one.
        merged.undo!!.invoke()
        waitFor { app.repository.cellar.value.wines[wine.id] != null }
        assertEquals(2, app.repository.cellar.value.bottlesInCellar.count { it.wineId == wine.id })
        assertEquals(1, app.repository.cellar.value.bottlesInCellar.count { it.wineId == existing.id })
        collector.cancel()
    }
}
