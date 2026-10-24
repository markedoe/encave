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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Runs `unzip -t` on an archive. Without the tool the rest of the test is skipped, and
 * reported as such: call it last.
 */
internal fun unzipTest(file: File): Pair<Int, String> {
    val tool = listOf("/usr/bin/unzip", "/bin/unzip").map(::File).firstOrNull { it.canExecute() }
    assumeTrue(tool != null, "unzip is not installed")
    val process = ProcessBuilder(tool!!.path, "-t", file.path).redirectErrorStream(true).start()
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
    fun namesAreComposedSoOneSpellingIsOneName() {
        // "Château" typed composed and decomposed: the same name once in the archive, as
        // CaveKit writes and reads it.
        val composed = "Ch\u00E2teau"
        val decomposed = "Cha\u0302teau"
        assertEquals(composed, CellarArchive.cleanStem(decomposed, "Photo"))
        val photo = PhotoId.make("p".toByteArray())
        val other = PhotoId.make("o".toByteArray())
        val a = Wine(id = UUID.fromString("00000000-0000-4000-8000-000000000001"), name = composed, photoId = photo)
        val b = Wine(id = UUID.fromString("00000000-0000-4000-8000-000000000002"), name = decomposed, photoId = other)
        assertEquals(
            listOf(
                CellarArchive.Item("photos/$composed.jpg", FileRef(photo, FileKind.Photo)),
                CellarArchive.Item("photos/$composed 2.jpg", FileRef(other, FileKind.Photo)),
            ),
            CellarArchive.plan(Cellar(listOf(a, b))),
        )
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
        val result = zip.outputStream().use { CellarArchive.write(cellar, exportedAt, it, { ref -> files[ref] }) }
        assertEquals(CellarArchive.WriteResult(included = 3, missing = 0, leftOut = 0), result)

        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertEquals(listOf("README.txt", "cellar.json", "files.json"), names.take(3))
            assertEquals(2, names.count { it.startsWith("photos/") })
            assertTrue("documents/Invoice.pdf" in names)
            assertEquals(CellarArchive.readme, archive.getInputStream(archive.getEntry("README.txt")).readBytes().decodeToString())
            assertEquals(CellarExport.json(cellar, exportedAt), archive.getInputStream(archive.getEntry("cellar.json")).readBytes().decodeToString())
            assertTrue(archive.entries().toList().all { it.method == java.util.zip.ZipEntry.STORED })
        }

        val contents = zip.inputStream().use { CellarArchive.read(it, work()) }
        // Backups keep whole seconds.
        assertEquals(CellarExport.backupFromJson(CellarExport.json(cellar)).cellar, contents.backup.cellar)
        assertEquals(exportedAt, contents.backup.exportedAt)
        assertEquals(files.keys, contents.files.keys)
        for ((ref, file) in contents.files) assertEquals(files.getValue(ref).readText(), file.readText())
        assertEquals(0, contents.skipped)
        val (status, output) = unzipTest(zip)
        assertEquals(0, status, output)
    }

    @Test
    fun roundTripKeepsAPhotoFromBeforeSync() {
        // A photo taken before sync existed: its ID is a UUID, not its hash.
        val photo = FileRef(UUID.randomUUID().toString().uppercase(), FileKind.Photo)
        val source = File(folder, "old-photo").apply { writeText("old label") }
        val cellar = SampleData.cellar(year = 2026)
        val wine = cellar.wines.values.minBy { it.id.toString().uppercase() }
        cellar.updateWine(wine.copy(photoId = photo.id))
        val zip = File(folder, "backup.zip")
        val result = zip.outputStream().use { CellarArchive.write(cellar, Instant.now(), it, { ref -> if (ref == photo) source else null }) }
        assertEquals(1, result.included)
        val contents = zip.inputStream().use { CellarArchive.read(it, work()) }
        assertEquals(setOf(photo), contents.files.keys)
        assertEquals("old label", contents.files.getValue(photo).readText())
        assertEquals(0, contents.skipped)
    }

    @Test
    fun missingFilesAreLeftOut() {
        val (cellar, files) = sample()
        val photo = files.keys.first { it.kind == FileKind.Photo }
        val out = ByteArrayOutputStream()
        val result = CellarArchive.write(cellar, Instant.now(), out, { ref -> if (ref == photo) null else files[ref] })
        assertEquals(CellarArchive.WriteResult(included = 2, missing = 1, leftOut = 0), result)
        val zip = File(folder, "partial.zip").apply { writeBytes(out.toByteArray()) }
        ZipFile(zip).use { assertEquals(5, it.size()) }
    }

    @Test
    fun filesBeyondTheZipLimitsAreLeftOut() {
        // Counted as CaveKit counts: the first file fits, the second is too large, the third fits.
        val fixed = 1000L
        val base = fixed + 3 * (30 + 46 + 16) + 22 + CellarArchive.RESERVE
        fun cost(size: Long) = size + 76 + 3 * 10 + 160
        val limit = base + cost(40) + cost(10) + 1
        assertEquals(listOf(true, false, true), CellarArchive.fitting(listOf(10 to 40L, 10 to 200L, 10 to 10L), fixed, limit))
        assertEquals(listOf(true, true, true, false, false), CellarArchive.fitting(List(5) { 10 to 1L }, 0, maxEntries = 6))
    }

    @Test
    fun aDamagedPhotoIsSkippedAndTheNextOnesRead() {
        val (cellar, files) = sample()
        val bytes = ByteArrayOutputStream().also { CellarArchive.write(cellar, Instant.now(), it, { ref -> files[ref] }) }.toByteArray()
        // A byte of the first photo changed: its CRC-32 no longer matches.
        // Its name comes first in files.json, then in its own header.
        val name = CellarArchive.plan(cellar).first().path.toByteArray()
        val start = bytes.indexOfSequence(name, from = bytes.indexOfSequence(name) + 1) + name.size
        bytes[start] = (bytes[start].toInt() xor 0x01).toByte()
        val contents = CellarArchive.read(ByteArrayInputStream(bytes), work())
        assertEquals(1, contents.skipped)
        assertEquals(2, contents.files.size)
    }

    /** A ZIP file written by another tool: entries stored, or [compressed]. */
    private fun zip(name: String, vararg entries: Pair<String, ByteArray>, compressed: Boolean = false): File {
        val file = File(folder, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            for ((path, data) in entries) {
                val entry = java.util.zip.ZipEntry(path)
                if (!compressed) {
                    entry.method = java.util.zip.ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().also { it.update(data) }.value
                }
                out.putNextEntry(entry)
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
        // Written by another tool, with a photo whose content changed, a file the
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

    @Test
    fun anArchiveCutShortIsRefusedAsDamaged() {
        val (cellar, files) = sample()
        val bytes = ByteArrayOutputStream().also { CellarArchive.write(cellar, Instant.now(), it, { ref -> files[ref] }) }.toByteArray()
        fun read(data: ByteArray) = assertThrows<ImportException> { CellarArchive.read(ByteArrayInputStream(data), work()) }.error
        // Cut in a photo, right after the cellar, and just before the end record.
        val afterCellar = bytes.indexOfSequence(byteArrayOf(0x50, 0x4B, 0x03, 0x04), from = 4)
        for (length in listOf(bytes.size / 2, afterCellar, bytes.size - 10)) {
            assertEquals(ImportError.DamagedArchive, read(bytes.copyOf(length)), "cut at $length")
        }
        // The whole archive still reads.
        assertEquals(files.keys, CellarArchive.read(ByteArrayInputStream(bytes), work()).files.keys)
    }

    @Test
    fun anArchiveWithACommentStillReads() {
        // The end record may be followed by a comment (another tool's): it is still found.
        val json = CellarExport.json(SampleData.cellar(year = 2026)).toByteArray()
        val file = File(folder, "comment.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            out.setComment("Made by another tool")
            out.putNextEntry(
                java.util.zip.ZipEntry("cellar.json").apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = json.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(json) }.value
                },
            )
            out.write(json)
            out.closeEntry()
        }
        val contents = file.inputStream().use { CellarArchive.read(it, work()) }
        assertEquals(CellarExport.backupFromJson(String(json)).cellar, contents.backup.cellar)
        // Cut inside the comment: the end record no longer reaches the end.
        val cut = file.readBytes().let { it.copyOf(it.size - 3) }
        val error = assertThrows<ImportException> { CellarArchive.read(ByteArrayInputStream(cut), work()) }.error
        assertEquals(ImportError.DamagedArchive, error)
    }

    @Test
    fun compressedEntriesAreNeverInflated() {
        val (cellar, _) = sample()
        val json = CellarExport.json(cellar).toByteArray()
        fun read(file: File) = file.inputStream().use { CellarArchive.read(it, work()) }
        val error = assertThrows<ImportException> { read(zip("deflated.zip", "cellar.json" to json, compressed = true)) }.error
        assertEquals(ImportError.UnsupportedArchive, error)
        // A compressed photo (it could inflate to fill the disk) is left out.
        val photo = "back".toByteArray()
        val file = File(folder, "mixed.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(
                java.util.zip.ZipEntry("cellar.json").apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = json.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(json) }.value
                },
            )
            out.write(json)
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("photos/Back.jpg"))
            out.write(photo)
            out.closeEntry()
        }
        val contents = read(file)
        assertTrue(contents.files.isEmpty())
        assertEquals(1, contents.skipped)
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray, from: Int = 0): Int =
        (from..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }

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
        val dates = (0 until 40).map { day(-it) } + listOf(day(-1, hour = 8), day(-10, hour = 8))
        val kept = CellarVersionFolder.kept(dates, ZoneOffset.UTC)
        val daily = (0 until 7).map { day(-it) }
        assertTrue(kept.containsAll(daily))
        assertFalse(day(-1, hour = 8) in kept)
        // Then the newest of each of the 4 ISO weeks before (as CaveKit keeps them).
        assertEquals(listOf(day(-7), day(-10), day(-17), day(-24)), (kept - daily.toSet()).sortedDescending())
        assertEquals(11, kept.size)
        val later = CellarVersionFolder.kept(kept.toList() + day(1), ZoneOffset.UTC)
        assertEquals(11, later.size)
        assertTrue((kept + day(1)).containsAll(later))
    }

    @Test
    fun aLongPauseKeepsTheLastVersions() {
        val kept = CellarVersionFolder.kept((60 until 100).map { day(-it) }, ZoneOffset.UTC)
        assertEquals(11, kept.size)
        assertTrue(day(-60) in kept)
        assertTrue(CellarVersionFolder.kept(emptyList(), ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun aVersionIsSavedBeforeReplacingAndOutlivesTheNextCopies() {
        val versions = CellarVersionFolder(File(folder, "versions"), ZoneOffset.UTC)
        val store = JsonFileCellarStore(File(folder, "cellar.json"))
        val mine = Cellar().apply { addBottles(Wine(name = "Mine"), 3) }
        store.save(mine)
        // Nothing to keep: an empty cellar, or only the sample.
        assertNull(versions.keepBeforeReplacing(Cellar(), day(0, hour = 9)))
        assertNull(versions.keepBeforeReplacing(SampleData.cellar(year = 2026), day(0, hour = 9)))
        assertTrue(versions.versions().isEmpty())

        // Replace and join: the cellar as it was is a version.
        val kept = requireNotNull(versions.keepBeforeReplacing(mine, day(0, hour = 9)))
        assertTrue(kept.isBeforeReplacing)
        assertEquals(listOf(kept), versions.versions())
        assertEquals(mine, versions.cellar(kept))

        // The other cellar arrives and is saved the same day; the next day's copy of it does not
        // take the place of the one saved before replacing.
        store.save(Cellar())
        store.file.setLastModified(day(0, hour = 20).toEpochMilli())
        store.save(SampleData.cellar(year = 2026))
        store.file.setLastModified(day(0, hour = 21).toEpochMilli())
        versions.keepCopy(store.file, day(1))
        assertEquals(2, versions.versions().size)
        assertTrue(kept in versions.versions())

        // Only the last 3 saved before replacing are kept.
        for (hour in 10 until 14) versions.keepBeforeReplacing(mine, day(2, hour = hour))
        assertEquals(listOf(day(2, hour = 13), day(2, hour = 12), day(2, hour = 11)), versions.versions().filter { it.isBeforeReplacing }.map { it.date })
        assertTrue(versions.versions().any { !it.isBeforeReplacing })

        // Erase all data with the previous versions.
        versions.removeAll()
        assertTrue(versions.versions().isEmpty())
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
        assertEquals(listOf(day(40), day(-1, hour = 22)), versions.versions().map { it.date })

        for (offset in 41 until 70) {
            store.file.setLastModified(day(offset).toEpochMilli())
            versions.keepCopy(store.file, day(offset + 1))
        }
        assertEquals(11, versions.versions().size)
        assertEquals(day(69), versions.versions().first().date)
        assertFalse(versions.versions().any { it.date == day(40) })
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
        // Wines with bottles in the cellar, not every wine record (those tasted elsewhere).
        assertEquals(backup.bottlesInCellar.map { it.wineId }.toSet().size, preview.wines)
        assertTrue(preview.wines < backup.wines.size)
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

    /** A cellar whose dates have fractions of a second, as live dates do. */
    private fun liveCellar(): Cellar {
        val now = ReferenceDates.now().plusNanos(123_456_000)
        val cellar = Cellar()
        val rack = Rack(name = "A", rows = 2, columns = 2)
        cellar.addRack(rack)
        val wine = Wine(name = "Clos", currentPriceDate = now)
        cellar.addBottles(wine, 2, listOf(Slot(rack.id, 0, 0)), PurchaseInfo(date = now), addedAt = now)
        cellar.addBottles(Wine(name = "Other"), 1, emptyList(), PurchaseInfo(), addedAt = now)
        cellar.addDiscovery(Discovery(wineId = wine.id, date = now), wine)
        return cellar
    }

    @Test
    fun aBackupJustMadeIsTheSameCellar() {
        val current = liveCellar()
        val backup = CellarJson.backupFrom(CellarJson.backup(current)).cellar
        assertNotEquals(current, backup, "backups keep whole seconds")
        assertTrue(RestorePreview.of(backup, Instant.EPOCH, current).isSame)

        // Replace keeps the identical records as they are, and still takes what differs.
        assertEquals(current, current.restoring(backup, RestoreMode.Replace))
        val bottle = backup.bottles.values.first()
        backup.updateBottle(bottle.copy(storageNote = "Kitchen"))
        val replaced = current.restoring(backup, RestoreMode.Replace)
        assertEquals("Kitchen", replaced.bottles[bottle.id]?.storageNote)
        replaced.bottles.values.filter { it.id != bottle.id }.forEach { assertEquals(current.bottles[it.id], it) }
        assertEquals(current.wines, replaced.wines)
        assertEquals(1, RestorePreview.of(backup, Instant.EPOCH, current).changed)
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

        // Replace takes the backup, its settings included.
        val replaced = current.restoring(backup, RestoreMode.Replace)
        assertEquals(backup, replaced)
        assertEquals(backup.settings.currency, replaced.settings.currency)
    }

    @Test
    fun replaceRestoresTheSettingsAndAddMissingOnlyAddsSearches() {
        val backup = SampleData.cellar(year = 2026)
        backup.settings = backup.settings.copy(currency = "CHF")
        backup.saveSearch("Ready", CellarFilter(query = "bordeaux"))
        backup.saveSearch("Old", CellarFilter(query = "1990"))
        val current = backup.copy()
        current.settings = current.settings.copy(currency = "USD", savedSearches = null)
        current.saveSearch("ready", CellarFilter(query = "mine"))
        current.saveSearch("Mine", CellarFilter(query = "loire"))

        val replaced = current.restoring(backup, RestoreMode.Replace)
        assertEquals(backup.settings, replaced.settings)
        assertEquals("CHF", replaced.settings.currency)
        assertEquals(listOf("Old", "Ready"), replaced.settings.savedSearches?.map { it.name }?.sorted())

        // Only the searches whose name is not there yet (ignoring case); the currency stays.
        val added = current.restoring(backup, RestoreMode.AddMissing)
        assertEquals("USD", added.settings.currency)
        assertEquals(listOf("Mine", "Old", "ready"), added.settings.savedSearches?.map { it.name }?.sorted())
        assertEquals("mine", added.settings.savedSearches?.first { it.name == "ready" }?.query)
    }

    @Test
    fun addMissingBringsBackSavedSearchesAndKeepsBottlesInTheirRack() {
        val backup = SampleData.cellar(year = 2026)
        backup.saveSearch("Ready", CellarFilter(query = "bordeaux"))
        backup.saveSearch("Old", CellarFilter(query = "1990"))
        // A bottle in the second row of a rack, deleted since; the rack now has one row.
        val bottle = backup.bottlesInCellar.first { (it.slot?.row ?: 0) >= 1 }
        val slot = requireNotNull(bottle.slot)
        val current = Cellar(
            backup.wines.values,
            backup.bottles.values.filter { it.id != bottle.id },
            backup.racks.map { if (it.id == slot.rackId) it.copy(rows = 1) else it },
            // A search deleted since, one renamed and saved again under the same name.
            backup.settings.copy(savedSearches = backup.settings.savedSearches!!.filter { it.name == "Old" }.map { it.copy(id = UUID.randomUUID()) }),
            backup.discoveries.values,
        )

        val added = current.restoring(backup, RestoreMode.AddMissing)
        assertNull(requireNotNull(added.bottles[bottle.id]).slot)
        assertEquals(listOf("Old", "Ready"), added.settings.savedSearches?.map { it.name })
        assertEquals(current.settings.savedSearches?.first()?.id, added.settings.savedSearches?.first()?.id)
    }
}
