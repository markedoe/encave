package app.encave.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.encave.EncaveApplication
import app.encave.R
import app.encave.core.PurchaseInfo
import app.encave.core.Wine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        val deadline = System.currentTimeMillis() + 10_000
        while (messages.size < 2 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        collector.cancel()
        assertEquals(1, app.repository.cellar.value.wines.values.single { it.name == wine.name }.attachments?.size)
        assertTrue(messages.any { (it.text as? UiText.Res)?.id == R.string.err_document_unreadable })
    }
}
