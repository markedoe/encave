package app.encave.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** A response body over its limit: a server that sends more is not read into memory. */
class ResponseTooLargeException(val limit: Long) : IOException("response over $limit bytes")

/**
 * Response bodies read with a size limit, so that a hostile server (a relay, a WebDAV, AI or
 * search server) cannot fill the memory. The same limits as CaveKit's `CappedRead`.
 */
object CappedRead {
    /** Sync files (relay, WebDAV): a frame holds at most 16 MiB (docs/SYNC.md). */
    const val SYNC_LIMIT: Long = 16L shl 20

    /** AI and search answers (JSON). */
    const val JSON_LIMIT: Long = 4L shl 20

    /**
     * Reads [input] up to [limit] bytes; [ResponseTooLargeException] as soon as it is over, or
     * at once when [contentLength] (-1 if unknown) says so. Does not close [input].
     */
    fun bytes(input: InputStream, contentLength: Long, limit: Long): ByteArray {
        if (contentLength > limit) throw ResponseTooLargeException(limit)
        val output = ByteArrayOutputStream(contentLength.coerceIn(0, minOf(limit, 1L shl 20)).toInt())
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw ResponseTooLargeException(limit)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
