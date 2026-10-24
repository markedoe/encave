package app.encave.core

import app.encave.core.sync.PhotoId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A full backup: a standard ZIP file, not encrypted, that anyone can open. Mirrors CaveKit's
 * `CellarArchive`, so an archive made on one platform restores on the other.
 *
 * - `README.txt`: what the files are (English and French).
 * - `cellar.json`: the cellar as [CellarExport.json] writes it, the source of truth; it can
 *   also be imported on its own.
 * - `files.json`: which file of the archive is which photo or document ([Manifest]).
 * - `photos/` and `documents/`: the files, under readable names. A restore hashes each file
 *   again to find its ID, so a renamed or changed file is never taken for another.
 *
 * Entries are stored (photos and PDFs are compressed already), with their sizes and CRC-32
 * in the local header, as CaveKit writes them.
 */
object CellarArchive {
    const val FORMAT = "encave-archive"

    /** Version of the archive's layout and `files.json`. A newer one is refused. */
    const val VERSION = 1

    /** Largest photo or document read from an archive: the limit for adding a document. */
    const val MAX_FILE_BYTE_COUNT = Attachment.MAX_BYTE_COUNT
    private const val MAX_CELLAR_BYTE_COUNT = 256L * 1024 * 1024
    /** 65,535 in the end record means ZIP64: one less is the most a plain ZIP holds. */
    internal const val MAX_ENTRIES = 0xFFFE

    private const val README = "README.txt"
    private const val CELLAR = "cellar.json"
    private const val MANIFEST = "files.json"
    private const val PHOTOS = "photos/"
    private const val DOCUMENTS = "documents/"

    /** "Encave Backup 2026-10-21.zip". */
    fun fileName(date: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        "Encave Backup ${DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).format(date.atZone(zone))}.zip"

    /** Which file of the archive is which photo or document. */
    @Serializable
    data class Manifest(val format: String = FORMAT, val version: Int = VERSION, val files: List<Item>)

    /** [path] "photos/Château X 2015.jpg"; [id] the hash of the file; [kind] "photo" or "document". */
    @Serializable
    data class Item(val path: String, val id: String, val kind: String) {
        constructor(path: String, ref: FileRef) : this(path, ref.id, kindName(ref.kind))

        val ref: FileRef? get() = kindOf(kind)?.let { FileRef(id, it) }
    }

    private fun kindName(kind: FileKind) = when (kind) {
        FileKind.Photo -> "photo"
        FileKind.Document -> "document"
    }

    private fun kindOf(name: String): FileKind? = when (name) {
        "photo" -> FileKind.Photo
        "document" -> FileKind.Document
        else -> null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pretty = Json(json) { prettyPrint = true; prettyPrintIndent = "  " }

    // region Writing

    /**
     * Where each file the cellar refers to goes, in archive order: the photos of each wine,
     * named after it, then the documents of the wines, then those of the bottles, under their
     * own names. Names are made safe for any file system and unique (ignoring case) with
     * " 2", " 3"… CaveKit names them the same way.
     */
    fun plan(cellar: Cellar): List<Item> {
        val items = mutableListOf<Item>()
        val seen = HashSet<FileRef>()
        val used = HashSet<String>()
        fun add(ref: FileRef, stem: String, extension: String?, folder: String) {
            if (!PhotoId.isValid(ref.id) || !seen.add(ref)) return
            items += Item(folder + uniqueName(stem, extension, folder, used), ref)
        }
        val wines = cellar.wines.values.sortedBy { it.id.toString().uppercase(Locale.ROOT) }
        for (wine in wines) {
            for (id in wine.allPhotoIds) add(FileRef(id, FileKind.Photo), cleanStem(wine.displayName, "Photo"), "jpg", PHOTOS)
        }
        fun addDocuments(attachments: List<Attachment>?) {
            for (attachment in attachments.orEmpty()) {
                val (stem, extension) = documentName(attachment)
                add(FileRef(attachment.id, FileKind.Document), stem, extension, DOCUMENTS)
            }
        }
        wines.forEach { addDocuments(it.attachments) }
        cellar.bottles.values.sortedBy { it.id.toString().uppercase(Locale.ROOT) }.forEach { addDocuments(it.attachments) }
        return items
    }

    /**
     * What a backup holds of the photos and documents the cellar refers to: [included] in the
     * archive, [missing] not on this device (not synced yet), [leftOut] because the archive
     * would be over 4 GB or 65,535 files, the limits of a ZIP file every system opens.
     */
    data class WriteResult(val included: Int, val missing: Int, val leftOut: Int)

    /**
     * Writes the archive to [output] (left open). [file] gives the local file of a photo or
     * document, null if it is not on this device (it is then left out). A file that would take
     * the archive over the ZIP limits is left out too ([fitting]); the cellar itself is always
     * written. [isCancelled] is checked between files.
     */
    fun write(
        cellar: Cellar,
        exportedAt: Instant,
        output: OutputStream,
        file: (FileRef) -> File?,
        isCancelled: () -> Boolean = { false },
    ): WriteResult {
        val readmeBytes = readme.toByteArray(Charsets.UTF_8)
        val cellarBytes = CellarExport.json(cellar, exportedAt).toByteArray(Charsets.UTF_8)
        val planned = plan(cellar)
        val available = planned.mapNotNull { item -> item.ref?.let(file)?.takeIf { it.isFile }?.let { item to it } }
        val fits = fitting(
            available.map { (item, source) -> item.path.toByteArray(Charsets.UTF_8).size to source.length() },
            fixedBytes = (readmeBytes.size + cellarBytes.size).toLong(),
        )
        val present = available.filterIndexed { index, _ -> fits[index] }
        val zip = ZipOutputStream(output)
        val time = LocalDateTime.ofInstant(exportedAt, ZoneOffset.UTC)
        fun entry(name: String, size: Long, crc: Long) = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
            timeLocal = time
        }
        fun add(name: String, data: ByteArray) {
            zip.putNextEntry(entry(name, data.size.toLong(), CRC32().apply { update(data) }.value))
            zip.write(data)
            zip.closeEntry()
        }
        add(README, readmeBytes)
        add(CELLAR, cellarBytes)
        // The manifest lists only what is written, and comes before the files.
        add(MANIFEST, manifestJson(Manifest(files = present.map { it.first })).toByteArray(Charsets.UTF_8))
        for ((item, source) in present) {
            if (isCancelled()) throw java.util.concurrent.CancellationException()
            // Read once for the CRC-32 and size (a stored entry needs them first), then copied.
            val crc = CRC32()
            var size = 0L
            source.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    size += read
                }
            }
            if (size >= ZIP_LIMIT) throw ZipException("file too large")
            zip.putNextEntry(entry(item.path, size, crc.value))
            source.inputStream().use { it.copyTo(zip, CHUNK) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return WriteResult(present.size, planned.size - available.size, available.size - present.size)
    }

    /**
     * Which files fit in an archive, in order: at most 65,534 entries and 4 GB in all, with
     * room for the headers, the manifest and the central directory. [files]: the size of each
     * one's name in UTF-8 and its size; [fixedBytes]: the README and the cellar. Counted as
     * CaveKit counts.
     */
    internal fun fitting(
        files: List<Pair<Int, Long>>,
        fixedBytes: Long,
        limit: Long = ZIP_LIMIT,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Boolean> {
        // README, cellar and manifest: two headers each (local and central) and a name.
        var total = fixedBytes + 3 * (30 + 46 + 16) + 22 + RESERVE
        var entries = 3
        return files.map { (nameBytes, size) ->
            // Two headers, the name three times (twice in headers, once in the manifest), and the
            // manifest's line for it (ID, kind, JSON).
            val cost = size + 76 + 3L * nameBytes + 160
            if (entries >= maxEntries || total + cost >= limit) {
                false
            } else {
                total += cost
                entries++
                true
            }
        }
    }

    /** 0xFFFF_FFFF bytes, the most a plain ZIP file holds. */
    internal const val ZIP_LIMIT = 0xFFFF_FFFFL

    /** Room kept below the 4 GB limit for what the estimate leaves out. */
    internal const val RESERVE = 1L shl 20

    internal fun manifestJson(manifest: Manifest): String =
        pretty.encodeToString(JsonElement.serializer(), CellarJson.sortKeys(json.encodeToJsonElement(Manifest.serializer(), manifest)))

    private const val CHUNK = 256 * 1024

    // endregion

    // region Reading

    /**
     * What an archive holds, read and checked: [files] are the files of the archive the
     * cellar refers to, copied to the working folder, each one's content having the ID it is
     * kept under; [skipped] counts those left out (damaged, too large, or not referred to).
     */
    class Contents(val backup: CellarBackup, val files: Map<FileRef, File>, val skipped: Int)

    /**
     * Reads an archive from [input], copying its photos and documents into [workingFolder]
     * (which must exist; the caller removes it afterwards). An archive made by a newer
     * version of Encave is refused, as is a file that is not an Encave archive.
     */
    fun read(input: InputStream, workingFolder: File): Contents {
        var cellarText: String? = null
        var manifestText: String? = null
        class Extracted(val name: String, val file: File, val id: String)
        val extracted = mutableListOf<Extracted>()
        var skipped = 0
        val tail = TailInputStream(input)
        var count = 0
        try {
            val zip = ZipInputStream(tail)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw ImportException(ImportError.DamagedArchive)
                val name = entry.name
                // Encave stores every entry: a compressed one could inflate to fill the disk,
                // so it is never inflated (CaveKit refuses it too).
                val compressed = entry.method != ZipEntry.STORED
                when {
                    entry.isDirectory -> Unit
                    (name == CELLAR || name == MANIFEST) && compressed -> throw ImportException(ImportError.UnsupportedArchive)
                    compressed -> skipped++
                    name == CELLAR -> cellarText = readText(zip, MAX_CELLAR_BYTE_COUNT)
                    name == MANIFEST -> manifestText = runCatching { readText(zip, MAX_CELLAR_BYTE_COUNT) }.getOrNull()
                    name.startsWith(PHOTOS) || name.startsWith(DOCUMENTS) -> {
                        val target = File(workingFolder, "file-$count")
                        // A damaged file (its CRC-32) is skipped and the next ones read, as
                        // CaveKit does; an archive cut short stops the reading (below).
                        val id = try {
                            copy(zip, target)
                        } catch (_: ZipException) {
                            null
                        }
                        if (id == null) {
                            target.delete()
                            skipped++
                        } else {
                            extracted += Extracted(name, target, id)
                        }
                    }
                }
            }
            // What is left is the central directory: read to the end, which the archive must have.
            if (count > 0) {
                tail.drain()
                if (!tail.endsWithEndRecord()) throw ImportException(ImportError.DamagedArchive)
            }
        } catch (error: ImportException) {
            throw error
        } catch (_: java.io.IOException) {
            // A damaged header, or an archive cut short (ZipException is one too): never restored
            // in part, without the photos it was missing.
            throw ImportException(ImportError.DamagedArchive)
        }
        val backup = CellarExport.backupFromJson(cellarText ?: throw ImportException(ImportError.NotAnEncaveBackup))

        val kinds = HashMap<String, Item>()
        manifestText?.let { text ->
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val format = (root?.get("format") as? JsonPrimitive)?.content
            val version = (root?.get("version") as? JsonPrimitive)?.content?.toIntOrNull()
            if (format == FORMAT && version != null && version > VERSION) throw ImportException(ImportError.NewerVersion)
            runCatching { json.decodeFromString(Manifest.serializer(), text) }.getOrNull()
                ?.takeIf { it.format == FORMAT }?.files?.forEach { kinds[it.path] = it }
        }

        val referenced = backup.cellar.fileRefs
        val files = LinkedHashMap<FileRef, File>()
        for (item in extracted) {
            val kind = kinds[item.name]?.ref?.kind ?: if (item.name.startsWith(PHOTOS)) FileKind.Photo else FileKind.Document
            val expected = kinds[item.name]?.id ?: item.id
            // A photo taken before sync existed has an ID that is not its hash: kept under it.
            val id = if (!expected.startsWith("sha256-") && PhotoId.isValid(expected)) expected else item.id
            val ref = FileRef(id, kind)
            // A file whose content is not the one the manifest names, that the cellar does not
            // refer to, or that is there twice is not kept.
            if (expected != id || ref !in referenced || ref in files) {
                item.file.delete()
                skipped++
            } else {
                files[ref] = item.file
            }
        }
        return Contents(backup, files, skipped)
    }

    private fun readText(input: InputStream, limit: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ImportException(ImportError.DamagedArchive)
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8)
    }

    /**
     * Passes [input] through, keeping its last bytes: a ZIP file ends with its end record (22
     * bytes and a comment of up to 65,535). A streaming reader stops at the central directory
     * without telling whether the file was cut short; a file without its end record was.
     */
    private class TailInputStream(input: InputStream) : java.io.FilterInputStream(input) {
        private val ring = ByteArray(22 + 0xFFFF)
        private var total = 0L

        private fun keep(bytes: ByteArray, offset: Int, length: Int) {
            // Only the last ring.size bytes can matter.
            val kept = minOf(length, ring.size)
            var from = offset + length - kept
            var at = ((total + length - kept) % ring.size).toInt()
            var left = kept
            while (left > 0) {
                val run = minOf(left, ring.size - at)
                System.arraycopy(bytes, from, ring, at, run)
                from += run
                left -= run
                at = (at + run) % ring.size
            }
            total += length
        }

        override fun read(): Int {
            val byte = super.read()
            if (byte >= 0) keep(byteArrayOf(byte.toByte()), 0, 1)
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) keep(b, off, read)
            return read
        }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(CHUNK)
            var skipped = 0L
            while (skipped < n) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }

        override fun markSupported() = false

        fun drain() {
            val buffer = ByteArray(CHUNK)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }

        /** Whether the bytes read end with an end record whose comment runs to the end. */
        fun endsWithEndRecord(): Boolean {
            val size = minOf(total, ring.size.toLong()).toInt()
            val start = total - size
            fun at(index: Int): Int = ring[((start + index) % ring.size).toInt()].toInt() and 0xFF
            for (index in size - 22 downTo 0) {
                if (at(index) == 0x50 && at(index + 1) == 0x4B && at(index + 2) == 0x05 && at(index + 3) == 0x06) {
                    val comment = at(index + 20) or (at(index + 21) shl 8)
                    if (index + 22 + comment == size) return true
                }
            }
            return false
        }
    }

    /** Copies an entry to [target], at most [MAX_FILE_BYTE_COUNT] bytes; its ID, or null if too large. */
    private fun copy(input: InputStream, target: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTE_COUNT) return null
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return "sha256-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    // endregion

    // region Names

    /** Characters some file systems refuse, replaced by "-". */
    private val forbidden = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').map { it.code }.toSet()

    /** Trimmed at both ends: spaces, and dots (a hidden file, or a name Windows refuses). */
    private val trimmed = setOf(0x20, 0xA0, 0x3000, '.'.code)
    private const val MAX_STEM_LENGTH = 80

    /**
     * A name safe on any file system: control characters become spaces, the characters some
     * systems refuse become "-", spaces and dots are trimmed at both ends, and at most 80
     * code points are kept (CaveKit counts the same, in Unicode scalars).
     */
    internal fun cleanStem(text: String, fallback: String): String {
        var points = Normalizer.normalize(text, Normalizer.Form.NFC).codePoints().map { cp ->
            when {
                Character.getType(cp) == Character.CONTROL.toInt() -> ' '.code
                cp in forbidden -> '-'.code
                else -> cp
            }
        }.toArray()
        fun trim(array: IntArray): IntArray {
            var start = 0
            var end = array.size
            while (start < end && array[start] in trimmed) start++
            while (end > start && array[end - 1] in trimmed) end--
            return array.copyOfRange(start, end)
        }
        points = trim(points)
        if (points.size > MAX_STEM_LENGTH) points = points.copyOf(MAX_STEM_LENGTH)
        points = trim(points)
        return if (points.isEmpty()) fallback else String(points, 0, points.size)
    }

    /**
     * A document's name and extension: its own extension if it has one (1 to 8 letters and
     * digits), otherwise the one of its type when known.
     */
    internal fun documentName(attachment: Attachment): Pair<String, String?> {
        val name = attachment.name
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val extension = name.substring(dot + 1)
            val points = extension.codePoints().toArray()
            if (points.size in 1..8 && points.all { it < 128 && Character.isLetterOrDigit(it) }) {
                return cleanStem(name.substring(0, dot), "Document") to extension
            }
        }
        return cleanStem(name, "Document") to extensions[attachment.contentType.lowercase(Locale.ROOT)]
    }

    private fun uniqueName(stem: String, extension: String?, folder: String, used: MutableSet<String>): String {
        var number = 1
        while (true) {
            val base = if (number == 1) stem else "$stem $number"
            val name = extension?.let { "$base.$it" } ?: base
            if (used.add((folder + name).lowercase(Locale.ROOT))) return name
            number++
        }
    }

    /** File name extensions of the usual document types (the same table as in CaveKit). */
    internal val extensions = mapOf(
        "application/pdf" to "pdf",
        "image/jpeg" to "jpg", "image/png" to "png", "image/heic" to "heic", "image/heif" to "heif",
        "image/gif" to "gif", "image/webp" to "webp", "image/tiff" to "tiff",
        "text/plain" to "txt", "text/csv" to "csv", "text/html" to "html", "application/rtf" to "rtf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.apple.pages" to "pages", "application/vnd.apple.numbers" to "numbers",
        "application/vnd.apple.keynote" to "key",
        "application/zip" to "zip", "message/rfc822" to "eml",
    )

    /** The archive's README.txt, the same on every platform. */
    val readme = """
        |Encave backup
        |
        |This archive holds your whole cellar, as it was when the backup was made:
        |- cellar.json: wines, bottles, racks, the Journal and settings. It is the backup
        |  itself: in Encave, choose Settings > Your data > Import… and pick this archive
        |  (or cellar.json alone, without the photos and documents).
        |- photos/ and documents/: the label photos and documents, under readable names.
        |- files.json: which file is which photo or document.
        |The archive is not encrypted: keep it somewhere safe.
        |
        |Sauvegarde Encave
        |
        |Cette archive contient toute votre cave, telle qu'elle était au moment de la sauvegarde :
        |- cellar.json : vins, bouteilles, casiers, le Journal et les réglages. C'est la
        |  sauvegarde elle-même : dans Encave, choisissez Réglages > Vos données > Importer…
        |  et prenez cette archive (ou cellar.json seul, sans les photos ni les documents).
        |- photos/ et documents/ : les photos d'étiquettes et les documents, sous des noms lisibles.
        |- files.json : quel fichier est quelle photo ou quel document.
        |L'archive n'est pas chiffrée : gardez-la en lieu sûr.
        |
    """.trimMargin()

    // endregion
}
