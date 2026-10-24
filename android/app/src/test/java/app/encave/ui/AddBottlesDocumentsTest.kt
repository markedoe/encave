package app.encave.ui

import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.R
import app.encave.idleMainLooperUntil
import app.encave.core.PurchaseInfo
import app.encave.core.Rack
import app.encave.core.Slot
import app.encave.core.Wine
import app.encave.core.sync.PhotoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddBottlesDocumentsTest {
    @Test
    fun aDocumentThatCouldNotBeStoredIsReported() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val messages = mutableListOf<UiMessage>()
        val collector = CoroutineScope(Dispatchers.Unconfined)
        collector.launch { viewModel.messages.collect { messages += it } }
        val kept = app.documents.stage("invoice".toByteArray(), "Invoice.pdf", "application/pdf")
        // Its temporary copy is gone (removed meanwhile, or the disk is full): it cannot be stored.
        val lost = app.documents.stage("certificate".toByteArray(), "Certificate.pdf", "application/pdf")
            .also(app.documents::discard)
        val wine = Wine(name = "With documents")
        viewModel.addBottles(wine, 1, emptyList(), PurchaseInfo(), pendingDocuments = listOf(kept, lost))
        idleMainLooperUntil { messages.size >= 2 }
        collector.cancel()
        assertEquals(1, app.repository.cellar.value.wines.values.single { it.name == wine.name }.attachments?.size)
        assertTrue(messages.any { (it.text as? UiText.Res)?.id == R.string.err_document_unreadable })
    }

    @Test
    fun aFailedAddLeavesNoFilesBehind() {
        val app = ApplicationProvider.getApplicationContext<EncaveApplication>()
        val viewModel = AppViewModel(app.repository, app.preferences, app.photos, app.documents, app.ai, app.backups)
        val rack = Rack(name = "Full", rows = 1, columns = 1)
        runBlocking {
            app.repository.update { it.addRack(rack) }
            app.repository.update { it.addBottles(Wine(name = "Already there"), 1, listOf(Slot(rack.id, 0, 0)), PurchaseInfo()) }
        }
        val photo = "label photo".toByteArray()
        val pending = app.documents.stage("receipt".toByteArray(), "Receipt.pdf", "application/pdf")
        val documentId = PhotoId.make("receipt".toByteArray())
        var done: Boolean? = null
        // The place is taken: the save fails.
        viewModel.addBottles(Wine(name = "Too late"), 1, listOf(Slot(rack.id, 0, 0)), PurchaseInfo(), photo = photo, pendingDocuments = listOf(pending)) { done = it }
        idleMainLooperUntil { done != null }
        assertEquals(false, done)
        assertFalse(app.photos.exists(PhotoId.make(photo)))
        assertFalse(app.documents.exists(documentId))
        // The temporary copy stays, so trying again can still store it.
        assertNotNull(app.documents.commit(pending))
    }
}
