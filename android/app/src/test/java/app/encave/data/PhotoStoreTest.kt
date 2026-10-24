package app.encave.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.encave.core.sync.PhotoId
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain application: Encave's own cleans photos at launch, at the real date.
@Config(sdk = [35], application = android.app.Application::class)
class PhotoStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun unreferencedPhotosAreKeptForAWhile() {
        val store = PhotoStore(context)
        val kept = store.save(byteArrayOf(1))!!
        val orphan = store.save(byteArrayOf(2))!!
        val start = Instant.parse("2026-10-01T10:00:00Z")
        store.removeOrphans(setOf(kept), now = start)
        assertTrue("an undo can still use it", store.exists(orphan))
        store.removeOrphans(setOf(kept), now = start.plus(Duration.ofDays(29)))
        assertTrue(store.exists(orphan))
        store.removeOrphans(setOf(kept), now = start.plus(Duration.ofDays(30)))
        assertFalse(store.exists(orphan))
        assertTrue(store.exists(kept))
    }

    @Test
    fun aPhotoReferredToAgainStartsOver() {
        val store = PhotoStore(context)
        val id = store.save(byteArrayOf(3))!!
        val start = Instant.parse("2026-10-01T10:00:00Z")
        store.removeOrphans(emptySet(), now = start)
        store.removeOrphans(setOf(id), now = start.plus(Duration.ofDays(10)))
        store.removeOrphans(emptySet(), now = start.plus(Duration.ofDays(35)))
        assertTrue(store.exists(id))
        assertFalse(File(context.filesDir, "photos/unreferenced.json").readText().isEmpty())
    }

    @Test
    fun afterEraseAllWithBackupsThePhotosAndDocumentsGoAtOnce() {
        val photos = PhotoStore(context)
        val documents = DocumentStore(context)
        val photo = photos.save(byteArrayOf(5))!!
        val back = photos.save(byteArrayOf(6))!!
        val document = PhotoId.make(byteArrayOf(7))
        assertTrue(documents.store(document, byteArrayOf(7)))
        val now = Instant.now()
        photos.expireOrphans(emptySet())
        documents.expireOrphans(emptySet())
        // The Undo is gone; the erase was undone for "back" (referred to again).
        photos.removeOrphans(setOf(back), now)
        documents.removeOrphans(emptySet(), now)
        assertFalse(photos.exists(photo))
        assertFalse(documents.exists(document))
        assertTrue(photos.exists(back))
        // No longer due: unreferenced again, it gets the whole grace period.
        photos.removeOrphans(emptySet(), now)
        assertTrue(photos.exists(back))
    }

    @Test
    fun idsThatAreNotSafeFileNamesAreRefused() {
        val store = PhotoStore(context)
        assertNull(store.file("../cellar"))
        assertFalse(store.exists("../cellar"))
        assertNull(store.load("../cellar"))
        assertFalse(store.store("../cellar", byteArrayOf(1)))
        val id = PhotoId.make(byteArrayOf(4))
        assertTrue(store.store(id, byteArrayOf(4)))
        assertEquals(1, store.data(id)?.size)
    }
}
