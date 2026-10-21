package app.encave.core

import app.encave.core.sync.PhotoId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Runs `unzip -t` on an archive when the tool is there; null otherwise. */
internal fun unzipTest(file: File): Pair<Int, String>? {
    val tool = listOf("/usr/bin/unzip", "/bin/unzip").map(::File).firstOrNull { it.canExecute() } ?: return null
    val process = ProcessBuilder(tool.path, "-t", file.path).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    return process.waitFor() to output
}

/** Mirrors CaveKit's BackupTests: the archive, the versions kept on the device and restoring. */
class CellarArchiveTest {
    @TempDir
    lateinit var folder: File

    @Test
    fun namesAreSafeAndReadable() {
        assertEquals("Château X 2015", CellarArchive.cleanStem("Château X 2015", "Photo"))
        assertEquals("a-b-c-d-e-f-g-h-i-j", CellarArchive.cleanStem("a/b\\c:d*e?f\"g<h>i|j", "Photo"))
        assertEquals("hidden", CellarArchive.cleanStem("..hidden. ", "Photo"))
        assertEquals("tab here new", CellarArchive.cleanStem("tab\there\nnew", "Photo"))
        assertEquals("Photo", CellarArchive.cleanStem(" . ", "Photo"))
        assertEquals(80, CellarArchive.cleanStem("é".repeat(100), "Photo").codePointCount(0, 80))

        fun document(name: String, type: String) = CellarArchive.documentName(Attachment("sha256-0", name, type, 1, Instant.EPOCH))
        assertEquals("Invoice" to "pdf", document("Invoice.pdf", "application/pdf"))
        assertEquals("Invoice" to "pdf", document("Invoice", "application/pdf"))
        assertEquals("Scan 12.03" to "2024", document("Scan 12.03.2024", "image/jpeg"))
        assertEquals("Notes" to null, document("Notes", "application/x-unknown"))
        assertEquals("pdf" to "pdf", document(".pdf", "application/pdf"))
        assertEquals("Facture n°12 – Cave" to "PDF", document("Facture n°12 – Cave.PDF", "application/pdf"))
    }

    @Test
    fun planNamesEachFileOnceAndUniquely() {
        val photo = PhotoId.make("p".toByteArray())
        val other = PhotoId.make("o".toByteArray())
        val doc = PhotoId.make("d".toByteArray())
        val a = Wine(
            id = UUID.fromString("00000000-0000-4000-8000-000000000001"), name = "Clos", vintage = 2015, photoId = photo,
            extraPhotoIds = listOf(other), attachments = listOf(Attachment(doc, "Invoice.pdf", "application/pdf", 1, Instant.EPOCH)),
        )
        val b = Wine(
            id = UUID.fromString("00000000-0000-4000-8000-000000000002"), name = "clos", vintage = 2015, photoId = photo,
            attachments = listOf(Attachment(other, "invoice.pdf", "application/pdf", 1, Instant.EPOCH)),
        )
        val bottle = Bottle(
            id = UUID.fromString("00000000-0000-4000-8000-000000000003"), wineId = a.id,
            attachments = listOf(Attachment(doc, "Same file", "application/pdf", 1, Instant.EPOCH)),
        )
        assertEquals(
            listOf(
                CellarArchive.Item("photos/Clos 2015.jpg", FileRef(photo, FileKind.Photo)),
                CellarArchive.Item("photos/Clos 2015 2.jpg", FileRef(other, FileKind.Photo)),
                CellarArchive.Item("documents/Invoice.pdf", FileRef(doc, FileKind.Document)),
                CellarArchive.Item("documents/invoice 2.pdf", FileRef(other, FileKind.Document)),
            ),
            CellarArchive.plan(Cellar(listOf(a, b), listOf(bottle))),
        )
    }

    /** A cellar with two photos and a document, their files in the folder. */
    private fun sample(): Pair<Cellar, Map<FileRef, File>> {
        val data = listOf(
            FileRef(PhotoId.make("label".toByteArray()), FileKind.Photo) to "label",
            FileRef(PhotoId.make("back".toByteArray()), FileKind.Photo) to "back",
            FileRef(PhotoId.make("invoice".toByteArray()), FileKind.Document) to "invoice",
        )
        val files = data.mapIndexed { index, (ref, text) -> ref to File(folder, "store-$index").apply { writeText(text) } }.toMap()
        val cellar = SampleData.cellar(year = 2026)
        val wine = cellar.wines.values.minBy { it.id.toString().uppercase() }
        cellar.updateWine(
            wine.copy(
                photoId = data[0].first.id, extraPhotoIds = listOf(data[1].first.id),
                attachments = listOf(Attachment(data[2].first.id, "Invoice", "application/pdf", 7, Instant.EPOCH)),
            ),
        )
        return cellar to files
    }

    private fun work() = File(folder, "work-${UUID.randomUUID()}").apply { mkdirs() }

    @Test
    fun roundTripKeepsTheCellarAndItsFiles() {
        val (cellar, files) = sample()
        val zip = File(folder, "backup.zip")
        val exportedAt = Instant.ofEpochSecond(1_790_000_000)
        val count = zip.outputStream().use { CellarArchive.write(cellar, exportedAt, it, { ref -> files[ref] }) }
        assertEquals(3, count)

        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertEquals(listOf("README.txt", "cellar.json", "files.json"), names.take(3))
            assertEquals(2, names.count { it.startsWith("photos/") })
            assertTrue("documents/Invoice.pdf" in names)
            assertEquals(CellarArchive.readme, archive.getInputStream(archive.getEntry("README.txt")).readBytes().decodeToString())
            assertEquals(CellarExport.json(cellar, exportedAt), archive.getInputStream(archive.getEntry("cellar.json")).readBytes().decodeToString())
            assertTrue(archive.entries().toList().all { it.method == java.util.zip.ZipEntry.STORED })
        }
        unzipTest(zip)?.let { (status, output) -> assertEquals(0, status, output) }

        val contents = zip.inputStream().use { CellarArchive.read(it, work()) }
        // Backups keep whole seconds.
        assertEquals(CellarExport.backupFromJson(CellarExport.json(cellar)).cellar, contents.backup.cellar)
        assertEquals(exportedAt, contents.backup.exportedAt)
        assertEquals(files.keys, contents.files.keys)
        for ((ref, file) in contents.files) assertEquals(files.getValue(ref).readText(), file.readText())
        assertEquals(0, contents.skipped)
    }

    @Test
    fun missingFilesAreLeftOut() {
        val (cellar, files) = sample()
        val photo = files.keys.first { it.kind == FileKind.Photo }
        val bytes = ByteArrayOutputStream().also { out ->
            CellarArchive.write(cellar, Instant.now(), out, { ref -> if (ref == photo) null else files[ref] })
        }.toByteArray()
        val zip = File(folder, "partial.zip").apply { writeBytes(bytes) }
        ZipFile(zip).use { assertEquals(5, it.size()) }
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(folder, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            for ((path, data) in entries) {
                out.putNextEntry(java.util.zip.ZipEntry(path))
                out.write(data)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun filesThatAreNotWhatTheySayAreIgnored() {
        val (cellar, _) = sample()
        val photo = FileRef(PhotoId.make("label".toByteArray()), FileKind.Photo)
        val manifest = CellarArchive.manifestJson(CellarArchive.Manifest(files = listOf(CellarArchive.Item("photos/Label.jpg", photo))))
        // Written by another tool (compressed), with a photo whose content changed, a file the
        // cellar does not refer to, a file outside the folders, and one without a manifest entry.
        val file = zip(
            "edited.zip",
            "cellar.json" to CellarExport.json(cellar).toByteArray(),
            "files.json" to manifest.toByteArray(),
            "photos/Label.jpg" to "edited label".toByteArray(),
            "photos/Other.jpg" to "not in the cellar".toByteArray(),
            "elsewhere/back.jpg" to "back".toByteArray(),
            "photos/Renamed back.jpg" to "back".toByteArray(),
            "documents/" to ByteArray(0),
        )
        val contents = file.inputStream().use { CellarArchive.read(it, work()) }
        assertEquals(setOf(FileRef(PhotoId.make("back".toByteArray()), FileKind.Photo)), contents.files.keys)
        assertEquals(2, contents.skipped)
    }

    @Test
    fun refusesNewerFormatsAndOtherFiles() {
        val newer = """{"format":"encave","version":99,"exportedAt":"2026-10-21T10:00:00Z","cellar":{"something":"else"}}"""
        assertEquals(ImportError.NewerVersion, assertThrows<ImportException> { CellarExport.backupFromJson(newer) }.error)
        assertEquals(ImportError.NotAnEncaveBackup, assertThrows<ImportException> { CellarExport.backupFromJson("""{"format":"other","version":1}""") }.error)

        fun read(file: File) = assertThrows<ImportException> { file.inputStream().use { CellarArchive.read(it, work()) } }.error
        assertEquals(ImportError.NewerVersion, read(zip("newer.zip", "cellar.json" to newer.toByteArray())))
        assertEquals(
            ImportError.NewerVersion,
            read(
                zip(
                    "manifest.zip",
                    "cellar.json" to CellarExport.json(Cellar()).toByteArray(),
                    "files.json" to """{"format":"encave-archive","version":2,"files":[]}""".toByteArray(),
                ),
            ),
        )
        assertEquals(ImportError.NotAnEncaveBackup, read(zip("other.zip", "photo.jpg" to "x".toByteArray())))
        assertEquals(ImportError.NotAnEncaveBackup, read(File(folder, "text.zip").apply { writeText("plain text") }))
    }

    @Test
    fun aDamagedArchiveIsRefused() {
        val (cellar, files) = sample()
        val bytes = ByteArrayOutputStream().also { CellarArchive.write(cellar, Instant.now(), it, { ref -> files[ref] }) }.toByteArray()
        // A byte of cellar.json changed: its CRC-32 no longer matches.
        val start = bytes.indexOfSequence("cellar.json".toByteArray()) + "cellar.json".length + 10
        bytes[start] = (bytes[start].toInt() xor 0x01).toByte()
        val error = assertThrows<ImportException> { CellarArchive.read(ByteArrayInputStream(bytes), work()) }.error
        assertEquals(ImportError.DamagedArchive, error)
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }

    @Test
    fun fileNameHasTheDay() {
        val date = Instant.ofEpochSecond(1_792_593_308) // 2026-10-21 14:35 UTC
        assertEquals("Encave Backup 2026-10-21.zip", CellarArchive.fileName(date, ZoneOffset.UTC))
        assertEquals("Encave Backup 2026-10-22.zip", CellarArchive.fileName(date, ZoneId.of("Pacific/Kiritimati")))
    }
}

class CellarVersionsTest {
    @TempDir
    lateinit var folder: File

    /** 2026-10-21 (a Wednesday) at [hour] UTC, [days] days later. */
    private fun day(days: Int, hour: Int = 12): Instant = Instant.ofEpochSecond(1_792_540_800L + days * 86_400L + hour * 3600L)

    @Test
    fun keepsTheNewestOfEachOfTheLastSevenDaysAndFourWeeks() {
        val now = day(0, hour = 9)
        val dates = (0 until 40).map { day(-it) } + listOf(day(-1, hour = 8), day(-10, hour = 8))
        val kept = CellarVersionFolder.kept(dates, now, ZoneOffset.UTC)
        val daily = (0 until 7).map { day(-it) }
        assertTrue(kept.containsAll(daily))
        assertFalse(day(-1, hour = 8) in kept)
        assertEquals(listOf(day(-10), day(-17), day(-24)), (kept - daily.toSet()).sortedDescending())
        assertEquals(10, kept.size)
    }

    @Test
    fun aCopyIsKeptWhenTheFileWasLastSavedOnAnEarlierDay() {
        val versions = CellarVersionFolder(File(folder, "versions"), ZoneOffset.UTC)
        val store = JsonFileCellarStore(File(folder, "cellar.json"))
        val cellar = SampleData.cellar(year = 2026)
        store.save(cellar)

        store.file.setLastModified(day(0, hour = 8).toEpochMilli())
        versions.keepCopy(store.file, day(0, hour = 9))
        assertTrue(versions.versions().isEmpty())

        store.file.setLastModified(day(-1, hour = 22).toEpochMilli())
        versions.keepCopy(store.file, day(0, hour = 9))
        assertEquals(listOf(day(-1, hour = 22)), versions.versions().map { it.date })
        assertEquals(cellar, versions.cellar(versions.versions()[0]))
        versions.keepCopy(store.file, day(0, hour = 10))
        assertEquals(1, versions.versions().size)

        store.save(Cellar())
        store.file.setLastModified(day(-3).toEpochMilli())
        versions.keepCopy(store.file, day(0))
        assertEquals(1, versions.versions().size)

        store.save(SampleData.cellar(year = 2026))
        store.file.setLastModified(day(40).toEpochMilli())
        versions.keepCopy(store.file, day(41))
        assertEquals(listOf(day(40)), versions.versions().map { it.date })
    }

    @Test
    fun theStoreKeepsTheOldFileBeforeSavingOnANewDay() {
        val versions = CellarVersionFolder(File(folder, "versions"))
        val store = JsonFileCellarStore(File(folder, "cellar.json"), versions = versions)
        val before = SampleData.cellar(year = 2026)
        store.save(before)
        store.file.setLastModified(Instant.now().minusSeconds(3 * 86_400L).toEpochMilli())
        store.save(Cellar())
        assertEquals(1, versions.versions().size)
        assertEquals(before, versions.cellar(versions.versions()[0]))
        assertEquals(Cellar(), store.load())
        assertTrue(store.keptAside().isEmpty())
    }

    @Test
    fun versionsKeepTheirFilesReferenced() {
        val versions = CellarVersionFolder(File(folder, "versions"), ZoneOffset.UTC)
        val store = JsonFileCellarStore(File(folder, "cellar.json"))
        val cellar = SampleData.cellar(year = 2026)
        val wine = cellar.wines.values.first()
        val photo = PhotoId.make("photo".toByteArray())
        cellar.updateWine(wine.copy(photoId = photo))
        store.save(cellar)
        store.file.setLastModified(day(-1).toEpochMilli())
        versions.keepCopy(store.file, day(0))
        assertTrue(FileRef(photo, FileKind.Photo) in versions.fileRefs())
        File(folder, "versions/notes.txt").writeText("x")
        assertEquals(1, versions.versions().size)
        assertNotNull(Files.size(File(folder, "versions").toPath()))
    }
}

class RestoreTest {
    @Test
    fun previewCountsAndCompares() {
        val backup = SampleData.cellar(year = 2026)
        val current = backup.copy()
        val bottle = current.bottles.values.first { it.status == Bottle.Status.InCellar }
        current.deleteBottle(bottle.id)
        current.addBottles(Wine(name = "New since"), 1, emptyList(), PurchaseInfo())
        val rack = current.racks[0]
        current.updateRack(rack.copy(name = "Renamed"))

        val preview = RestorePreview.of(backup, Instant.EPOCH, current)
        assertEquals(backup.wines.size, preview.wines)
        assertEquals(backup.bottlesInCellar.size, preview.bottles)
        assertEquals(backup.racks.size, preview.racks)
        assertEquals(backup.consumedBottles.size + backup.discoveries.size, preview.journalEntries)
        assertEquals(backup.photoIds.size, preview.photos)
        assertEquals(backup.attachmentIds.size, preview.documents)
        // The bottle removed since; the new wine and its bottle; the renamed rack.
        assertEquals(1, preview.added)
        assertEquals(2, preview.removed)
        assertEquals(1, preview.changed)
        assertFalse(preview.isSame)
        assertTrue(RestorePreview.of(backup, Instant.EPOCH, backup).isSame)
    }

    @Test
    fun addMissingKeepsTheCellarAndReplaceTakesTheBackup() {
        val backup = SampleData.cellar(year = 2026)
        val current = backup.copy()
        val bottle = current.bottles.values.first { it.status == Bottle.Status.InCellar }
        current.deleteBottle(bottle.id)
        val extra = Wine(name = "New since")
        current.addBottles(extra, 1, emptyList(), PurchaseInfo())
        current.settings = current.settings.copy(currency = "CHF")

        val added = current.restoring(backup, RestoreMode.AddMissing)
        assertNotNull(added.bottles[bottle.id])
        assertNotNull(added.wines[extra.id])
        assertEquals("CHF", added.settings.currency)
        assertNull(current.bottles[bottle.id])

        assertEquals(backup, current.restoring(backup, RestoreMode.Replace))
    }
}
