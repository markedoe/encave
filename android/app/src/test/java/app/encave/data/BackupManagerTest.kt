package app.encave.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.encave.core.CellarArchive
import app.encave.core.CellarExport
import app.encave.core.FileKind
import app.encave.core.FileRef
import app.encave.core.ImportError
import app.encave.core.ImportException
import app.encave.core.InMemoryCellarStore
import app.encave.core.InMemoryCellarVersions
import app.encave.core.SampleData
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The weekly archive (in a folder instead of Download/Encave), Back up now and reading a picked backup. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var now = Instant.parse("2026-10-21T09:00:00Z")

    private fun TestScope.manager(cellar: app.encave.core.Cellar = SampleData.cellar(year = 2026)): Pair<BackupManager, File> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = CellarRepository(InMemoryCellarStore(cellar), TestScope(dispatcher), dispatcher)
        advanceUntilIdle()
        val folder = File(context.cacheDir, "weekly-${System.nanoTime()}")
        val manager = BackupManager(
            repository, PhotoStore(context), DocumentStore(context), InMemoryCellarVersions(emptyList()),
            context.getSharedPreferences("backups-${System.nanoTime()}", Context.MODE_PRIVATE),
            FolderDestination(folder), File(context.cacheDir, "backup-work"), clock = { now },
        )
        return manager to folder
    }

    @Test
    fun theWeeklyArchiveIsMadeWhenDueAndOnlyTheLastTwoAreKept() = runTest {
        val (manager, folder) = manager()
        assertTrue(manager.runAutomaticIfDue())
        assertFalse("not again within the week", manager.runAutomaticIfDue())
        assertEquals(now, manager.lastBackup.value)
        repeat(3) {
            now += Duration.ofDays(8)
            assertTrue(manager.runAutomaticIfDue())
        }
        val names = folder.list().orEmpty().sorted()
        assertEquals(listOf(CellarArchive.fileName(now - Duration.ofDays(8)), CellarArchive.fileName(now)), names)
        assertEquals(2, manager.automaticIds().size)
        // A real archive, with the cellar in it.
        ZipFile(File(folder, names.last())).use { assertNotNull(it.getEntry("cellar.json")) }
    }

    @Test
    fun noWeeklyArchiveWhenOffOrForAnEmptyCellar() = runTest {
        val (empty, emptyFolder) = manager(app.encave.core.Cellar())
        assertFalse(empty.runAutomaticIfDue())
        assertTrue(emptyFolder.list().isNullOrEmpty())

        val (manager, folder) = manager()
        manager.setWeeklyEnabled(false)
        assertFalse(manager.runAutomaticIfDue())
        assertTrue(folder.list().isNullOrEmpty())
    }

    @Test
    fun aReminderAfterAMonthWithoutBackup() = runTest {
        val (manager, _) = manager()
        assertFalse(manager.reminderDue(SampleData.cellar(year = 2026)))
        now += Duration.ofDays(31)
        assertTrue(manager.reminderDue(SampleData.cellar(year = 2026)))
        assertFalse(manager.reminderDue(app.encave.core.Cellar()))
        manager.runAutomaticIfDue()
        assertFalse(manager.reminderDue(SampleData.cellar(year = 2026)))
    }

    @Test
    fun backUpNowWritesAnArchiveThatReadsBack() = runTest {
        val cellar = SampleData.cellar(year = 2026)
        val photo = PhotoStore(context).save(byteArrayOf(1, 2, 3, 4))!!
        val wine = cellar.wines.values.first()
        cellar.updateWine(wine.copy(photoId = photo))
        val (manager, _) = manager(cellar)
        val file = File(context.cacheDir, "now.zip")
        assertEquals(1, file.outputStream().use { manager.writeArchive(it).getOrThrow() })
        assertEquals(now, manager.lastBackup.value)

        val source = manager.source(context.contentResolver, Uri.fromFile(file))
        assertEquals(RestoreSource.Kind.Archive, source.kind)
        assertEquals(CellarExport.backupFromJson(CellarExport.json(cellar)).cellar, source.cellar)
        assertEquals(setOf(FileRef(photo, FileKind.Photo)), source.files.keys)
        assertNotNull(source.workingFolder)

        // A JSON backup reads too; another file says what it is.
        val json = File(context.cacheDir, "backup.json").apply { writeText(CellarExport.json(cellar)) }
        assertEquals(RestoreSource.Kind.Json, manager.source(context.contentResolver, Uri.fromFile(json)).kind)
        val newer = File(context.cacheDir, "newer.json").apply { writeText("""{"format":"encave","version":9,"cellar":{}}""") }
        try {
            manager.source(context.contentResolver, Uri.fromFile(newer))
            fail("a newer backup was read")
        } catch (error: ImportException) {
            assertEquals(ImportError.NewerVersion, error.error)
        }
    }
}
