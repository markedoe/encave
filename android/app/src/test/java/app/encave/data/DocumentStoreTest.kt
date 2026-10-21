package app.encave.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.encave.core.Attachment
import app.encave.core.FileKind
import app.encave.core.ReferenceDates
import app.encave.core.sync.PhotoId
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain application: Encave's own cleans files at launch, at the real date.
@Config(sdk = [35], application = android.app.Application::class)
class DocumentStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun picked(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, "picked/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        return Uri.fromFile(file)
    }

    @Test
    fun aPickedFileIsStagedThenStoredUnderItsHash() {
        val store = DocumentStore(context)
        val pdf = "%PDF-1.7 invoice".toByteArray()
        val pending = store.stage(picked("Invoice.pdf", pdf))
        assertEquals(pdf.size.toLong(), pending.byteCount)
        val attachment = store.commit(pending)!!
        store.discard(pending)
        assertEquals(PhotoId.make(pdf), attachment.id)
        assertEquals(pdf.size.toLong(), attachment.byteCount)
        assertTrue(store.exists(attachment.id))
        assertArrayEquals(pdf, store.data(attachment.id))
        assertNull("the temporary copy is gone", store.commit(pending))
    }

    @Test
    fun aFileOverTheLimitIsRefused() {
        val store = DocumentStore(context)
        try {
            store.stage(picked("Big.pdf", ByteArray((Attachment.MAX_BYTE_COUNT + 1).toInt())))
            fail("over 8 MB")
        } catch (error: DocumentStore.AddException) {
            assertEquals(DocumentStore.Problem.TooLarge, error.problem)
        }
        val atLimit = store.stage(picked("Limit.pdf", ByteArray(Attachment.MAX_BYTE_COUNT.toInt())))
        assertEquals(Attachment.MAX_BYTE_COUNT, atLimit.byteCount)
        store.discard(atLimit)
    }

    @Test
    fun unreferencedDocumentsAreKeptForAWhile() {
        val store = DocumentStore(context)
        val kept = PhotoId.make(byteArrayOf(1)).also { store.store(it, byteArrayOf(1)) }
        val orphan = PhotoId.make(byteArrayOf(2)).also { store.store(it, byteArrayOf(2)) }
        val start = Instant.parse("2026-10-01T10:00:00Z")
        store.removeOrphans(setOf(kept), now = start)
        store.removeOrphans(setOf(kept), now = start.plus(Duration.ofDays(29)))
        assertTrue("an undo can still use it", store.exists(orphan))
        store.removeOrphans(setOf(kept), now = start.plus(Duration.ofDays(30)))
        assertFalse(store.exists(orphan))
        assertTrue(store.exists(kept))
    }

    @Test
    fun idsThatAreNotSafeFileNamesAreRefused() {
        val store = DocumentStore(context)
        assertNull(store.file("../cellar"))
        assertFalse(store.store("../cellar", byteArrayOf(1)))
        assertFalse(store.exists("../cellar"))
    }

    @Test
    fun copiesGivenToOtherAppsHaveTheNameAndTheExtensionOfTheirType() {
        val at = ReferenceDates.now()
        fun named(name: String, type: String) = Attachment("sha256-1", name, type, 1, at)
        assertEquals("Invoice.pdf", DocumentStore.fileName(named("Invoice.pdf", "application/pdf"), "Document"))
        assertEquals("Document", DocumentStore.fileName(named("..", "application/x-unknown-type"), "Document"))
        assertEquals("_.hidden", DocumentStore.fileName(named(".hidden", "application/x-unknown-type"), "Document"))
        val store = DocumentStore(context)
        val bytes = "certificate".toByteArray()
        val id = PhotoId.make(bytes)
        store.store(id, bytes)
        assertNotNull(store.sharedUri(Attachment(id, "Certificate", "application/pdf", bytes.size.toLong(), at)))
        // A long name is shortened, so the copy can be made. (FileProvider keeps the first
        // test's folders: every shared copy is made in this test.)
        assertNotNull(store.sharedUri(Attachment(id, "葡".repeat(200) + ".pdf", "application/pdf", bytes.size.toLong(), at)))
    }

    @Test
    fun longNamesAreShortenedBeforeTheirExtension() {
        val at = ReferenceDates.now()
        fun fileName(name: String) = DocumentStore.fileName(Attachment("sha256-1", name, "application/pdf", 1, at), "Document")
        for (letter in listOf("é", "葡", "😀")) {
            val name = fileName(letter.repeat(200) + ".pdf")
            assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 240)
            assertTrue(name, name.endsWith(".pdf") && name.removeSuffix(".pdf").all { it.toString() in letter })
            assertTrue(name.removeSuffix(".pdf").startsWith(letter))
        }
        assertEquals("Invoice.pdf", fileName("Invoice.pdf"))
    }

    @Test
    fun copiesGivenToOtherAppsAreRemovedOnlyOnceOld() {
        val store = DocumentStore(context)
        val bytes = "shared".toByteArray()
        // Copies as sharedUri makes them: one folder each.
        val shared = File(context.cacheDir, "documents")
        fun copy() = File(shared, "${java.util.UUID.randomUUID()}/Shared.pdf").apply { parentFile?.mkdirs(); writeBytes(bytes) }.parentFile!!
        val old = copy().apply { setLastModified(System.currentTimeMillis() - Duration.ofDays(2).toMillis()) }
        val recent = copy()
        val pending = store.stage(bytes, "Pending.pdf", "application/pdf")
        // Another app may be reading the recent copy as the app starts.
        store.removeTemporaryCopies()
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertNull(store.commit(pending))
    }

    @Test
    fun photosAndDocumentsSyncToTheirOwnFolders() {
        val photos = PhotoStore(context)
        val documents = DocumentStore(context)
        val files = SyncFiles(photos, documents)
        val bytes = "receipt".toByteArray()
        val id = PhotoId.make(bytes)
        files.save(id, FileKind.Document, bytes)
        assertTrue(files.exists(id, FileKind.Document))
        assertFalse(files.exists(id, FileKind.Photo))
        assertArrayEquals(bytes, files.data(id, FileKind.Document))
        assertNull(files.data(id, FileKind.Photo))
    }
}
