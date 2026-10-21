package app.encave.ui.components

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.encave.data.PendingDocument
import app.encave.ui.add.PickedDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedValuesTest {
    private val document = PendingDocument(name = "Invoice.pdf", contentType = "application/pdf", byteCount = 10)

    private fun retained(store: ViewModelStore) =
        ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[RetainedValues::class.java]

    @Test
    fun documentsPickedAreDiscardedOnlyWhenTheScreenIsLeftWithoutAdding() {
        val discarded = mutableListOf<PendingDocument>()
        val store = ViewModelStore()
        val picked = PickedDocuments { discarded += it }.apply { documents = listOf(document) }
        retained(store).values["add.documents"] = picked
        // Recreating the activity keeps the store: same holder, nothing discarded.
        assertTrue(retained(store).values["add.documents"] === picked)
        assertTrue(discarded.isEmpty())
        // Leaving the screen clears it.
        store.clear()
        assertEquals(listOf(document), discarded)
    }

    @Test
    fun documentsAddedAreKept() {
        val discarded = mutableListOf<PendingDocument>()
        val store = ViewModelStore()
        retained(store).values["add.documents"] = PickedDocuments { discarded += it }.apply {
            documents = listOf(document)
            added = true
        }
        store.clear()
        assertTrue(discarded.isEmpty())
    }
}
