package app.encave.core.sync

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Mailboxes in a WebDAV folder the user already has (a NAS, Nextcloud, pCloud…): one
 * subfolder per mailbox under the folder they chose, files as in [FolderMailboxStore].
 * Ported from WebDAVMailbox.swift.
 *
 *     PROPFIND Depth: 1                   list (folder by folder)
 *     GET                                 read, 404 if missing
 *     PUT  If-None-Match: *               create, 412 if it exists
 *     DELETE                              404 is fine
 *     MKCOL                               missing folders, 405 if it exists
 *
 * The server's own login (Basic auth, ideally an app password) controls access, so
 * mailbox tokens are not sent. Credentials stay on each device; only the folder URL
 * travels to other devices. HTTPS, or HTTP only on the local network.
 */
class WebDavMailboxStore(baseUrl: String, username: String, password: String, private val client: OkHttpClient = defaultClient) : MailboxStore {
    /** The folder, ending with "/". */
    val baseUrl: HttpUrl = normalized(baseUrl) ?: throw IllegalArgumentException("not a WebDAV folder address")
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    override fun mailbox(id: String, token: String): Mailbox = WebDavMailbox(this, id)
    override fun inbox(id: String, token: String): Mailbox = WebDavMailbox(this, id)

    /**
     * Checks the folder and the login before turning sync on: creates the folder if
     * needed, then writes, reads back and deletes a test file.
     */
    suspend fun check() {
        val quick = client.newBuilder().callTimeout(CHECK_TIMEOUT_S, TimeUnit.SECONDS).build()
        // Look first: some servers refuse MKCOL (403) on a folder that exists, such as a share's root.
        suspend fun propfind() = send("PROPFIND", baseUrl, mapOf("Depth" to "0"), PROPFIND_BODY, XML, quick).second
        var found = propfind()
        if (found == 404) {
            val (_, made) = send("MKCOL", baseUrl, client = quick)
            if (made !in listOf(200, 201, 204, 405)) throw RemoteSyncException(RemoteSyncError.Unavailable("cannot create the folder ($made)"))
            found = propfind()
        }
        if (found != 207) throw RemoteSyncException(RemoteSyncError.Unavailable("not a WebDAV folder ($found)"))
        val file = baseUrl.resolve("encave-check-" + ByteArray(4).also { random.nextBytes(it) }.hex())!!
        val content = "Encave checks that it can write here. This file can be deleted.\n".toByteArray()
        val (_, put) = send("PUT", file, mapOf("If-None-Match" to "*"), content, client = quick)
        if (put !in listOf(200, 201, 204)) throw RemoteSyncException(RemoteSyncError.Unavailable("cannot write ($put)"))
        val (read, got) = send("GET", file, client = quick)
        runCatching { send("DELETE", file, client = quick) }
        if (got != 200 || !read.contentEquals(content)) throw RemoteSyncException(RemoteSyncError.Unavailable("cannot read back ($got)"))
    }

    /**
     * Sends a request. Statuses that say the same thing on every server become errors:
     * 401/403 refused, 413 file too large, 507 full, redirects, 423, 429 and 5xx unavailable.
     */
    internal suspend fun send(
        method: String,
        url: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String = OCTET_STREAM,
        client: OkHttpClient = this.client,
    ): Pair<ByteArray, Int> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .method(method, body?.toRequestBody(contentType.toMediaType()))
            .header("Authorization", authorization)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val (data, status) = try {
            client.newCall(request).execute().use { it.body.bytes() to it.code }
        } catch (error: IOException) {
            throw RemoteSyncException(RemoteSyncError.Unavailable(error.message ?: "no connection"))
        }
        when (status) {
            401, 403 -> throw RemoteSyncException(RemoteSyncError.Unauthorized)
            413 -> throw RemoteSyncException(RemoteSyncError.FileTooLarge)
            507 -> throw RemoteSyncException(RemoteSyncError.QuotaExceeded)
            in 300..399 -> throw RemoteSyncException(RemoteSyncError.Unavailable("redirected ($status): use the final address"))
            423, 429 -> throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
            else -> if (status >= 500) throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
        }
        data to status
    }

    companion object {
        internal const val XML = "application/xml; charset=utf-8"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val CHECK_TIMEOUT_S = 15L
        internal val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
                .toByteArray()

        /**
         * Requests never follow redirects: one to another host, or to plain HTTP, would
         * carry the password there. No cookies, no cache.
         */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * What the user typed, as a folder URL: https:// added if no scheme is given, no
         * query, a trailing "/". Null if it is not an address the app accepts.
         */
        fun folderUrl(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val url = normalized(if (trimmed.contains("://")) trimmed else "https://$trimmed") ?: return null
            return url.toString().takeIf { isAllowed(it) }
        }

        /**
         * HTTPS, or HTTP only for a server on the local network (no certificate there):
         * localhost, `.local` and `.home.arpa` names, private and link-local addresses.
         */
        fun isAllowed(url: String): Boolean {
            val scheme = url.trim().substringBefore("://", "").lowercase()
            if (scheme != "https" && scheme != "http") return false
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (parsed.host.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            return parsed.isHttps || LocalAddress.isLocal(parsed.host)
        }

        /** Lowercase scheme and host (OkHttp does it), no query or fragment, a path ending with "/". */
        internal fun normalized(text: String): HttpUrl? {
            val url = text.trim().toHttpUrlOrNull() ?: return null
            val builder = url.newBuilder().query(null).fragment(null)
            if (url.pathSegments.last().isNotEmpty()) builder.addPathSegment("")
            return builder.build()
        }
    }
}

private class WebDavMailbox(val store: WebDavMailboxStore, val id: String) : Mailbox {
    /**
     * The mailbox folder, or a folder or file in it. Only valid IDs and paths (lowercase
     * letters, digits, "-", "_", "/") ever become part of a URL.
     */
    private fun url(path: String = "", folder: Boolean = false): HttpUrl {
        if (!RelayPath.isValidMailbox(id)) throw RemoteSyncException(RemoteSyncError.Corrupted(id))
        if (path.isNotEmpty() && !RelayPath.isValid(path)) throw RemoteSyncException(RemoteSyncError.Corrupted(path))
        val relative = "$id/$path" + if (folder && path.isNotEmpty()) "/" else ""
        return store.baseUrl.resolve(relative) ?: throw RemoteSyncException(RemoteSyncError.Corrupted(path))
    }

    /**
     * Lists folder by folder (`Depth: infinity` is off on most servers), from the deepest
     * folder named by the prefix, going only into folders the prefix can be in.
     */
    override suspend fun list(prefix: String): List<String> {
        var start = prefix.substringBeforeLast("/", "")
        if (start.isNotEmpty() && !RelayPath.isValid(start)) start = ""
        val files = HashSet<String>()
        val folders = ArrayDeque(listOf(start))
        // Each folder once, even if a server lists a parent among the children.
        val visited = hashSetOf(start)
        while (folders.isNotEmpty()) {
            val folder = folders.removeLast()
            // A mailbox holds a few folders and at most a few thousand files.
            if (visited.size > 1_000 || files.size > 10_000) throw RemoteSyncException(RemoteSyncError.Unavailable("bad list"))
            val entries = entries(folder) ?: continue
            for (entry in entries) {
                if (entry.path == folder || !RelayPath.isValid(entry.path)) continue
                if (entry.isFolder) {
                    val inside = entry.path + "/"
                    if ((inside.startsWith(prefix) || prefix.startsWith(inside)) && visited.add(entry.path)) folders.addLast(entry.path)
                } else if (entry.path.startsWith(prefix)) {
                    files += entry.path
                }
            }
        }
        return files.sorted()
    }

    private data class Entry(val path: String, val isFolder: Boolean)

    /** The folder's own entry and its children, as paths in the mailbox. Null if it does not exist. */
    private suspend fun entries(folder: String): List<Entry>? {
        val requested = url(folder, folder = true)
        val (data, status) = store.send("PROPFIND", requested, mapOf("Depth" to "1"), WebDavMailboxStore.PROPFIND_BODY, WebDavMailboxStore.XML)
        when (status) {
            207 -> {}
            404, 409 -> return null
            else -> throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
        }
        val responses = WebDavMultistatus.parse(data) ?: throw RemoteSyncException(RemoteSyncError.Unavailable("bad list response"))
        val mailbox = url(folder = true)
        return responses.mapNotNull { response ->
            WebDavMultistatus.relativePath(response.href, requested, mailbox, id)?.let { Entry(it, response.isCollection) }
        }
    }

    override suspend fun get(path: String): ByteArray? {
        val (data, status) = store.send("GET", url(path))
        return when (status) {
            200 -> data
            404 -> null
            else -> throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
        }
    }

    override suspend fun create(path: String, data: ByteArray): Boolean {
        val target = url(path)
        repeat(2) { attempt ->
            val (_, status) = store.send("PUT", target, mapOf("If-None-Match" to "*"), data)
            when {
                status == 200 || status == 201 || status == 204 -> return true
                status == 412 -> return false
                // The folder is missing (409 by the standard, 404 on some servers): make it, then once more.
                (status == 404 || status == 409) && attempt == 0 -> makeFolders(path)
                else -> throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
            }
        }
        throw RemoteSyncException(RemoteSyncError.Unavailable("cannot create $path"))
    }

    /** Makes the folders above [path], from the deepest one that is missing. */
    private suspend fun makeFolders(path: String) {
        val parts = path.split("/").dropLast(1).toMutableList()
        val missing = mutableListOf<String>()
        val made = listOf(200, 201, 204, 405)
        // Up from the file's folder until a folder can be made (409: its parent is missing too).
        while (true) {
            val folder = parts.joinToString("/")
            val (_, status) = store.send("MKCOL", url(folder, folder = true))
            if (status in made) break
            if (status != 409 && status != 404) throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
            if (parts.isEmpty()) {
                // The mailbox's folder needs the chosen folder.
                val (_, base) = store.send("MKCOL", store.baseUrl)
                if (base !in made) throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $base"))
                missing += ""
                break
            }
            missing += folder
            parts.removeAt(parts.lastIndex)
        }
        for (folder in missing.asReversed()) {
            val (_, status) = store.send("MKCOL", url(folder, folder = true))
            if (status !in made) throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
        }
    }

    override suspend fun delete(path: String) {
        val (_, status) = store.send("DELETE", url(path))
        if (status !in listOf(200, 204, 404)) throw RemoteSyncException(RemoteSyncError.Unavailable("WebDAV status $status"))
    }
}

/**
 * Reads a `207 Multi-Status` answer to PROPFIND: each response's href and whether it is
 * a collection. Namespace prefixes vary by server (`d:`, `D:`, none).
 */
internal object WebDavMultistatus {
    data class Response(val href: String, val isCollection: Boolean)

    fun parse(data: ByteArray): List<Response>? {
        // A multistatus never has a DTD; refusing one rules out entity tricks. A NUL byte means
        // UTF-16 or UTF-32, where the text check below would not see one: refused too.
        if (data.any { it == 0.toByte() }) return null
        val text = data.toString(Charsets.UTF_8)
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) return null
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        val reader = Reader()
        return runCatching {
            factory.newSAXParser().parse(ByteArrayInputStream(data), reader)
            reader.responses.takeIf { reader.sawMultistatus }
        }.getOrNull()
    }

    /**
     * The path of [href] (from the answer to a request for [requested]) inside the mailbox
     * folder [mailbox], without a trailing "/" ("" for the folder itself). Hrefs may be
     * absolute URLs, absolute paths or relative to the request, and are percent-encoded,
     * sometimes differently from the request. Null if outside it.
     */
    fun relativePath(href: String, requested: HttpUrl, mailbox: HttpUrl, id: String): String? {
        val resolved = requested.resolve(href.trim()) ?: return null
        val path = "/" + resolved.pathSegments.joinToString("/")
        val folder = "/" + mailbox.pathSegments.joinToString("/")
        var inside = when {
            path.startsWith(folder) -> path.removePrefix(folder)
            // A server behind a proxy may answer with another base path: the mailbox ID is unique.
            path.contains("/$id/") -> path.substringAfterLast("/$id/")
            "$path/" == folder || path.endsWith("/$id") -> return ""
            else -> return null
        }
        inside = inside.trimEnd('/')
        return inside
    }

    private class Reader : DefaultHandler() {
        val responses = mutableListOf<Response>()
        var sawMultistatus = false
        private var href: String? = null
        private val text = StringBuilder()
        private var inResponse = false
        private var inHref = false
        private var inResourceType = false
        private var isCollection = false

        private fun isDav(uri: String?) = uri == "DAV:" || uri.isNullOrEmpty()
        private fun name(localName: String?, qName: String?) = localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: ""

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (!isDav(uri)) return
            when (name(localName, qName)) {
                "multistatus" -> sawMultistatus = true
                "response" -> {
                    inResponse = true
                    href = null
                    isCollection = false
                }
                "href" -> if (inResponse && href == null) {
                    inHref = true
                    text.setLength(0)
                }
                "resourcetype" -> if (inResponse) inResourceType = true
                "collection" -> if (inResourceType) isCollection = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inHref) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (!isDav(uri)) return
            when (name(localName, qName)) {
                "href" -> if (inHref) {
                    inHref = false
                    href = text.toString()
                }
                "resourcetype" -> inResourceType = false
                "response" -> if (inResponse) {
                    inResponse = false
                    href?.let { responses += Response(it, isCollection) }
                }
            }
        }
    }
}

/** Addresses on this device or the local network, where a server has no certificate and plain HTTP is accepted. */
object LocalAddress {
    fun isLocal(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name.endsWith(".local") || name.endsWith(".home.arpa")) return true
        val octets = name.split(".").map { it.toIntOrNull()?.takeIf { value -> value in 0..255 && it.length <= 3 } }
        if (octets.size == 4 && octets.all { it != null }) {
            val (a, b) = octets[0]!! to octets[1]!!
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (!name.contains(":") || !name.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' || it == '%' }) return false
        // IPv6: loopback, unique local (fc00::/7), link-local (fe80::/10).
        if (name == "::1") return true
        val first = name.substringBefore(":")
        if (first.length != 4) return false
        val value = first.toIntOrNull(16) ?: return false
        return value and 0xFE00 == 0xFC00 || value and 0xFFC0 == 0xFE80
    }
}
