package app.encave.core.sync

import app.encave.core.Http
import java.io.IOException
import java.util.Locale
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Mailboxes on an Encave relay (see `relay/`), a small HTTP service anyone can host.
 * It stores the encrypted files and nothing else:
 *
 *     GET    /v1/m/{mailbox}?prefix=…&after=…   → {"files": [...], "next": "…"?}
 *     GET    /v1/m/{mailbox}/{path}             → 200 bytes, or 404
 *     PUT    /v1/m/{mailbox}/{path}             If-None-Match: *  → 201, or 412 if it exists
 *     DELETE /v1/m/{mailbox}/{path}             → 204
 *
 * Requests carry `Authorization: Bearer <token>`.
 */
class RelayMailboxStore(baseUrl: String) : MailboxStore {
    /** Without a trailing slash. */
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    init {
        // Checked here too, not only on the screens: the token must never travel in clear text.
        require(isAllowed(this.baseUrl)) { "a relay needs https, or http on this device or the local network" }
    }

    override fun mailbox(id: String, token: String): Mailbox = RelayMailbox(baseUrl, id, token)
    override fun inbox(id: String, token: String): Mailbox = RelayMailbox(baseUrl, id, token)

    /** Checks that the URL points to an Encave relay (`GET /v1/health`). */
    suspend fun check() = withContext(Dispatchers.IO) {
        val (status, body) = try {
            http("$baseUrl/v1/health", "GET", token = "", timeoutMs = 15_000)
        } catch (error: IOException) {
            throw RemoteSyncException(RemoteSyncError.Unavailable(error.message ?: "no connection"))
        }
        val service = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["service"]?.jsonPrimitive?.content }.getOrNull()
        if (status != 200 || service != "encave-relay") throw RemoteSyncException(RemoteSyncError.Unavailable("not an Encave relay"))
    }

    companion object {
        /**
         * A relay address the app accepts: https, or http only for this device or the
         * local network (localhost, *.local), where there is no certificate.
         */
        fun isAllowed(url: String): Boolean {
            // OkHttp reads hosts that java.net.URI leaves out ("my_nas.example", "café.example"),
            // as URLComponents does in Swift.
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            val host = parsed.host.lowercase(Locale.ROOT)
            return when (parsed.scheme) {
                "https" -> true
                "http" -> host == "localhost" || host.endsWith(".local")
                else -> false
            }
        }
    }
}

@Serializable
private data class Page(val files: List<String> = emptyList(), val next: String? = null)

private val lenient = Json { ignoreUnknownKeys = true }

/** Blocking HTTP request; returns the status and the body. */
internal fun http(url: String, method: String, token: String, body: ByteArray? = null, timeoutMs: Int = 60_000, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
    val connection = Http.open(URL(url))
    try {
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        // Never follow a redirect: the token would go to another address.
        connection.instanceFollowRedirects = false
        if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        return status to (stream?.use { it.readBytes() } ?: ByteArray(0))
    } finally {
        connection.disconnect()
    }
}

private class RelayMailbox(val base: String, val id: String, val token: String) : Mailbox {
    private fun url(path: String?): String {
        if (!RelayPath.isValidMailbox(id)) throw RemoteSyncException(RemoteSyncError.Corrupted(id))
        if (path == null) return "$base/v1/m/$id"
        if (!RelayPath.isValid(path)) throw RemoteSyncException(RemoteSyncError.Corrupted(path))
        return "$base/v1/m/$id/$path"
    }

    private suspend fun send(url: String, method: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val result = try {
                http(url, method, token, body, headers = headers)
            } catch (error: IOException) {
                throw RemoteSyncException(RemoteSyncError.Unavailable(error.message ?: "no connection"))
            }
            when (val status = result.first) {
                401, 403 -> throw RemoteSyncException(RemoteSyncError.Unauthorized)
                413 -> throw RemoteSyncException(RemoteSyncError.FileTooLarge)
                507 -> throw RemoteSyncException(RemoteSyncError.QuotaExceeded)
                in 300..399 -> throw RemoteSyncException(RemoteSyncError.Unavailable("relay redirect $status"))
                else -> if (status == 429 || status >= 500) throw RemoteSyncException(RemoteSyncError.Unavailable("relay status $status"))
            }
            result
        }

    override suspend fun list(prefix: String): List<String> {
        val files = mutableListOf<String>()
        var after: String? = null
        do {
            val query = "prefix=" + URLEncoder.encode(prefix, "UTF-8") + (after?.let { "&after=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val (status, data) = send(url(null) + "?" + query, "GET")
            if (status == 404) return emptyList()
            val page = runCatching { lenient.decodeFromString(Page.serializer(), data.toString(Charsets.UTF_8)) }.getOrNull()
            if (status != 200 || page == null) throw RemoteSyncException(RemoteSyncError.Unavailable("bad list response"))
            files += page.files.filter { it.startsWith(prefix) && RelayPath.isValid(it) }
            // A page must move forward, and a mailbox holds at most a few thousand files:
            // a relay that says otherwise is not followed for ever.
            val next = page.next ?: break
            if (next <= (after ?: "") || files.size > 10_000) throw RemoteSyncException(RemoteSyncError.Unavailable("bad list paging"))
            after = next
        } while (true)
        return files
    }

    override suspend fun get(path: String): ByteArray? {
        val (status, data) = send(url(path), "GET")
        return when (status) {
            200 -> data
            404 -> null
            else -> throw RemoteSyncException(RemoteSyncError.Unavailable("relay status $status"))
        }
    }

    override suspend fun create(path: String, data: ByteArray): Boolean {
        val (status, _) = send(url(path), "PUT", data, mapOf("If-None-Match" to "*", "Content-Type" to "application/octet-stream"))
        return when (status) {
            200, 201 -> true
            409, 412 -> false
            else -> throw RemoteSyncException(RemoteSyncError.Unavailable("relay status $status"))
        }
    }

    override suspend fun delete(path: String) {
        val (status, _) = send(url(path), "DELETE")
        if (status !in listOf(200, 204, 404)) throw RemoteSyncException(RemoteSyncError.Unavailable("relay status $status"))
    }
}
