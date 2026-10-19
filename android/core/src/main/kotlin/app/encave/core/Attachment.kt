package app.encave.core

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * A document kept with a wine (for all its bottles) or with one bottle: an invoice, a
 * certificate of authenticity, a technical sheet, an article, a picture of a receipt.
 * The file is stored by the app under [id], a hash of its content like a photo ID, and
 * syncs like the photos (docs/SYNC.md). Backups hold this record, not the file. Mirrors
 * CaveKit's `Attachment`: every field is required.
 */
@Serializable
data class Attachment(
    /** `sha256-` and the hex SHA-256 of the file (`PhotoId.make`). */
    val id: String,
    /** The file name shown, normalized by [normalizedName]. */
    val name: String,
    /** MIME type ("application/pdf"), on every platform. */
    val contentType: String,
    val byteCount: Long,
    @Serializable(with = InstantSerializer::class) val addedAt: Instant,
) {
    fun normalized(): Attachment {
        val clean = normalizedName(name)
        return if (clean == name) this else copy(name = clean)
    }

    companion object {
        /**
         * Largest document accepted when adding one: 8 MB (8,000,000 bytes). Sealed for remote
         * sync it stays under a relay's 8 MiB file limit; on the local network its Base64 fits
         * a 16 MiB frame.
         */
        const val MAX_BYTE_COUNT = 8_000_000L

        /** Longest name kept, in Unicode code points (the same count as Swift's scalars). */
        const val MAX_NAME_LENGTH = 200

        /** When a file has no known type. */
        const val GENERIC_CONTENT_TYPE = "application/octet-stream"

        /**
         * The name as every device keeps it: control characters become spaces, `/` and `\`
         * become `-`, white space is trimmed at both ends, and at most 200 code points are
         * kept (then trimmed again). CaveKit applies the same rule.
         */
        fun normalizedName(name: String): String {
            val mapped = name.codePoints().map { cp ->
                when {
                    Character.getType(cp) == Character.CONTROL.toInt() -> ' '.code
                    cp == '/'.code || cp == '\\'.code -> '-'.code
                    else -> cp
                }
            }.toArray()
            // Once control characters are spaces, Unicode white space is exactly the space
            // separators (Zs, Zl, Zp), as Swift's `isWhitespace` sees it.
            fun trimmed(points: IntArray): IntArray {
                var start = 0
                var end = points.size
                while (start < end && Character.isSpaceChar(points[start])) start++
                while (end > start && Character.isSpaceChar(points[end - 1])) end--
                return points.copyOfRange(start, end)
            }
            val first = trimmed(mapped)
            val cut = trimmed(first.copyOf(minOf(first.size, MAX_NAME_LENGTH)))
            return String(cut, 0, cut.size)
        }

        /** Each document once (by ID, the first kept), names normalized; null when none are left. */
        fun unique(attachments: List<Attachment>?): List<Attachment>? =
            attachments?.map { it.normalized() }?.distinctBy { it.id }?.ifEmpty { null }
    }
}

/** What a document is attached to. */
sealed class AttachmentTarget {
    /** The wine, so every bottle of it. */
    data class OfWine(val id: UUID) : AttachmentTarget()
    data class OfBottle(val id: UUID) : AttachmentTarget()
}

/**
 * The two places the app keeps files: photos and documents. A file is named after its
 * content, so the same ID can be both (a picture of a receipt added both ways).
 */
enum class FileKind { Photo, Document }

/** A file the cellar refers to. */
data class FileRef(val id: String, val kind: FileKind)
