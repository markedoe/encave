package app.encave.core.sync

import app.encave.core.Wine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A WebDAV server in memory on localhost. Behaves like Nextcloud: PUT needs the parent
 * folder (409), MKCOL answers 405 for an existing folder, If-None-Match: * gives 412.
 * Mirrors CaveKit's FakeWebDAV.
 */
class FakeWebDav : AutoCloseable {
    enum class HrefStyle { AbsolutePath, AbsoluteUrl, Relative }

    /** Decoded; a space and an "@" exercise percent-encoding. */
    val basePath = "/remote.php/dav/files/jean dupont@example.com/Encave/"
    @Volatile var hrefStyle = HrefStyle.AbsolutePath
    @Volatile var honoursIfNoneMatch = true
    /** Answers every request with this status. */
    @Volatile var forcedStatus: Int? = null
    val username = "jean"
    val password = "app-password"

    private val folders = mutableSetOf<String>()
    private val files = mutableMapOf<String, ByteArray>()
    private val log = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        var path = "/"
        folders += path
        basePath.split("/").filter { it.isNotEmpty() }.dropLast(1).forEach {
            path += "$it/"
            folders += path
        }
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    val baseUrl: String
        get() = "http://localhost:${server.address.port}" + basePath.replace(" ", "%20").replace("@", "%40")

    fun store(password: String = this.password) = WebDavMailboxStore(baseUrl, username, password)

    val allFiles: Map<String, ByteArray> get() = synchronized(this) { files.toMap() }
    val requests: List<String> get() = synchronized(this) { log.toList() }

    override fun close() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.responseHeaders.add("Content-Type", if (status == 207) "application/xml" else "application/octet-stream")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
        }
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        val (status, data) = synchronized(this) { answer(exchange, body) }
        reply(exchange, status, data)
    }

    private fun answer(exchange: HttpExchange, body: ByteArray): Pair<Int, ByteArray> {
        val method = exchange.requestMethod
        var path = URI(exchange.requestURI.rawPath).path
        log += "$method $path"
        val expected = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        if (exchange.requestHeaders.getFirst("Authorization") != expected) return 401 to ByteArray(0)
        forcedStatus?.let { return it to ByteArray(0) }
        if (path.length > 1) path = path.trimEnd('/')
        val folder = if (path == "/") "/" else "$path/"
        val parent = path.substring(0, path.lastIndexOf('/') + 1)
        val none = ByteArray(0)
        return when (method) {
            "MKCOL" -> when {
                folder in folders || path in files -> 405 to none
                parent !in folders -> 409 to none
                else -> {
                    folders += folder
                    201 to none
                }
            }
            "PUT" -> when {
                parent !in folders || folder in folders -> 409 to none
                path in files && honoursIfNoneMatch && exchange.requestHeaders.getFirst("If-None-Match") == "*" -> 412 to none
                else -> {
                    files[path] = body
                    201 to none
                }
            }
            "GET" -> files[path]?.let { 200 to it } ?: (404 to none)
            "DELETE" -> when {
                files.remove(path) != null -> 204 to none
                folder in folders -> {
                    folders.removeAll { it.startsWith(folder) }
                    files.keys.removeAll { it.startsWith(folder) }
                    204 to none
                }
                else -> 404 to none
            }
            "PROPFIND" -> {
                val entries = mutableListOf<Pair<String, Boolean>>()
                when {
                    folder in folders -> {
                        entries += folder to true
                        if (exchange.requestHeaders.getFirst("Depth") != "0") {
                            fun child(name: String) = name.startsWith(folder) && name != folder && !name.removePrefix(folder).trimEnd('/').contains('/')
                            entries += folders.filter(::child).sorted().map { it to true }
                            entries += files.keys.filter(::child).sorted().map { it to false }
                        }
                    }
                    path in files -> entries += path to false
                    else -> return 404 to none
                }
                207 to multistatus(entries, if (folder in folders) folder else path, exchange)
            }
            else -> 405 to none
        }
    }

    private fun multistatus(entries: List<Pair<String, Boolean>>, requested: String, exchange: HttpExchange): ByteArray {
        fun encode(path: String) = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val responses = entries.joinToString("") { (path, isFolder) ->
            val href = when (hrefStyle) {
                HrefStyle.AbsolutePath -> encode(path).replace("%40", "@")
                HrefStyle.AbsoluteUrl -> "http://localhost:${exchange.localAddress.port}" + encode(path)
                HrefStyle.Relative -> if (path == requested) "./" else encode(path.removePrefix(requested))
            }
            val type = if (isFolder) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>"
            "<D:response><D:href>$href</D:href><D:propstat><D:prop>$type</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
        }
        return "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">$responses</D:multistatus>".toByteArray()
    }
}

class WebDavTests {
    private val servers = mutableListOf<FakeWebDav>()

    private fun server() = FakeWebDav().also { servers += it }

    @AfterEach
    fun stop() = servers.forEach { it.close() }

    // region Multistatus

    @Test
    fun hrefsAreReadWhateverTheirForm() {
        val id = "c0123456789abcdef0123456789abcdef"
        val mailbox = "https://cloud.example.com/remote.php/dav/files/jean%40example.com/Encave/$id/".toHttpUrl()
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/jean@example.com/Encave/$id/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>https://cloud.example.com/remote.php/dav/files/jean%40example.com/Encave/$id/snap/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>photo%2Dlike</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
              <d:response><d:href>/proxied/base/$id/grant/x-00000001-y</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
              <d:response><d:href>/elsewhere/file</d:href>
                <d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val responses = WebDavMultistatus.parse(xml.toByteArray())!!
        assertEquals(listOf(true, true, false, false, false), responses.map { it.isCollection })
        assertEquals(
            listOf("", "snap", "photo-like", "grant/x-00000001-y", null),
            responses.map { WebDavMultistatus.relativePath(it.href, mailbox, mailbox, id) },
        )
    }

    @Test
    fun defaultNamespaceAndOtherPrefixesParse() {
        val xml = """<multistatus xmlns="DAV:"><response><href>/a/</href><propstat><prop><resourcetype><collection/></resourcetype></prop></propstat></response>""" +
            """<response><href>/a/b</href><propstat><prop><resourcetype/></prop></propstat></response></multistatus>"""
        assertEquals(
            listOf(WebDavMultistatus.Response("/a/", true), WebDavMultistatus.Response("/a/b", false)),
            WebDavMultistatus.parse(xml.toByteArray()),
        )
        val other = """<d:multistatus xmlns:d="DAV:" xmlns:x="urn:x"><d:response><d:href>/a</d:href><d:propstat><d:prop>""" +
            """<d:resourcetype><x:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>"""
        assertEquals(listOf(WebDavMultistatus.Response("/a", false)), WebDavMultistatus.parse(other.toByteArray()))
    }

    @Test
    fun badListAnswersAreRefused() {
        assertNull(WebDavMultistatus.parse("<html><body>Login</body></html>".toByteArray()))
        assertNull(WebDavMultistatus.parse("not xml".toByteArray()))
        val entity = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        assertNull(WebDavMultistatus.parse(entity.toByteArray()))
    }

    // endregion

    @Test
    fun addressesMustBeHttpsOrOnTheLocalNetwork() {
        assertEquals("https://nas.example.com/encave/", WebDavMailboxStore.folderUrl("nas.example.com/encave"))
        assertEquals("https://webdav.example.com/Encave/", WebDavMailboxStore.folderUrl(" https://webdav.example.com/Encave?x=1 "))
        // Example private addresses, not real hosts.
        val privateIps = listOf("http://192.168.1.20/dav", "http://10.0.0.2/", "http://172.16.4.1/") // public-safety: allow
        for (local in listOf("http://nas.local:5005/encave", "http://localhost:8080", "http://[fd12::1]/dav", "http://[fe80::1]", "http://box.home.arpa") + privateIps) {
            assertNotNull(WebDavMailboxStore.folderUrl(local), local)
        }
        for (refused in listOf("http://webdav.example.com/Encave", "http://8.8.8.8", "http://172.32.0.1", "http://[2001:db8::1]", "ftp://nas.local", "https://jean:secret@nas.example.com/", "", "https://")) {
            assertNull(WebDavMailboxStore.folderUrl(refused), refused)
        }
    }

    @Test
    fun checkMakesTheFolderAndLeavesNothing() = runBlocking {
        val server = server()
        server.store().check()
        assertTrue(server.allFiles.isEmpty())
        assertEquals("MKCOL ${server.basePath}", server.requests.first())
        server.store().check()
    }

    @Test
    fun aWrongPasswordIsRefused() {
        val server = server()
        val error = assertThrows<RemoteSyncException> { runBlocking { server.store("wrong").check() } }
        assertEquals(RemoteSyncError.Unauthorized, error.error)
    }

    @Test
    fun filesAreCreatedOnceWithTheirFolders() = runBlocking {
        val server = server()
        val mailbox = server.store().mailbox("c" + "a".repeat(32), "ignored")
        assertTrue(mailbox.list("snap/").isEmpty())
        assertTrue(mailbox.create("snap/dev/0001-ab", byteArrayOf(1)))
        assertFalse(mailbox.create("snap/dev/0001-ab", byteArrayOf(2)), "412: never overwritten")
        assertArrayEquals(byteArrayOf(1), mailbox.get("snap/dev/0001-ab"))
        assertNull(mailbox.get("snap/dev/missing"))
        assertTrue(mailbox.create("photo/sha256-12", byteArrayOf(3)))
        assertEquals(listOf("snap/dev/0001-ab"), mailbox.list("snap/"))
        assertEquals(listOf("photo/sha256-12", "snap/dev/0001-ab"), mailbox.list(""))
        assertEquals(listOf("snap/dev/0001-ab"), mailbox.list("snap/dev/0"))
        mailbox.delete("snap/dev/0001-ab")
        mailbox.delete("snap/dev/0001-ab")
        assertTrue(mailbox.list("snap/").isEmpty())
        // No mailbox token went to the server: its own login controls access.
        assertFalse(server.requests.any { it.contains("ignored") })
    }

    @Test
    fun aServerIgnoringIfNoneMatchOverwrites() = runBlocking {
        // The protocol tolerates it: every name is written by one device, with the same content.
        val server = server()
        server.honoursIfNoneMatch = false
        val mailbox = server.store().mailbox("i" + "b".repeat(32), "")
        assertTrue(mailbox.create("grant/a", byteArrayOf(1)))
        assertTrue(mailbox.create("grant/a", byteArrayOf(1)))
    }

    @Test
    fun statusesBecomeErrors() {
        val server = server()
        val mailbox = server.store().mailbox("c" + "c".repeat(32), "")
        val cases = listOf(
            401 to RemoteSyncError.Unauthorized,
            403 to RemoteSyncError.Unauthorized,
            507 to RemoteSyncError.QuotaExceeded,
            413 to RemoteSyncError.FileTooLarge,
            500 to RemoteSyncError.Unavailable("WebDAV status 500"),
            423 to RemoteSyncError.Unavailable("WebDAV status 423"),
        )
        for ((status, expected) in cases) {
            server.forcedStatus = status
            val error = assertThrows<RemoteSyncException> { runBlocking { mailbox.create("photo/x", byteArrayOf(1)) } }
            assertEquals(expected, error.error, "status $status")
        }
        server.forcedStatus = 302
        assertThrows<RemoteSyncException> { runBlocking { mailbox.get("photo/x") } }
        server.forcedStatus = 400
        val error = assertThrows<RemoteSyncException> { runBlocking { mailbox.list("") } }
        assertEquals(RemoteSyncError.Unavailable("WebDAV status 400"), error.error)
    }

    @Test
    fun invalidNamesNeverReachTheServer() {
        val server = server()
        val store = server.store()
        assertThrows<RemoteSyncException> { runBlocking { store.mailbox("../x", "").list("") } }
        val mailbox = store.mailbox("c" + "d".repeat(32), "")
        assertThrows<RemoteSyncException> { runBlocking { mailbox.create("../escape", ByteArray(0)) } }
        assertThrows<RemoteSyncException> { runBlocking { mailbox.get("Snap/%2e%2e") } }
        assertThrows<RemoteSyncException> { runBlocking { mailbox.delete("a//b") } }
        assertTrue(server.requests.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(FakeWebDav.HrefStyle::class)
    fun devicesSyncThroughAWebDavFolder(style: FakeWebDav.HrefStyle) = runBlocking {
        val server = server()
        server.hrefStyle = style
        val store = server.store()
        store.check()
        val devices = household(3)
        grantAll(devices, store, "webdav:" + store.baseUrl)
        val label = "label".toByteArray()
        val photo = PhotoId.make(label)
        devices[0].photos.save(photo, label)
        devices[0].edit { it.addBottles(Wine(name = "Château WebDAV", photoId = photo), 2) }
        devices[0].sync(store)
        devices[1].sync(store)
        devices[1].edit { it.addBottles(Wine(name = "From B"), 1) }
        devices[1].sync(store)
        devices[2].sync(store)
        devices[0].sync(store)
        assertEquals(devices[0].snapshot.cellar, devices[1].snapshot.cellar)
        assertEquals(3, devices[2].snapshot.cellar.bottles.size)
        assertArrayEquals(label, devices[2].photos.stored[photo])
        // Only ciphertext, and only under the chosen folder.
        val files = server.allFiles
        assertTrue(files.isNotEmpty())
        for ((path, data) in files) {
            assertTrue(path.startsWith(server.basePath), path)
            if ("/grant/" in path) continue
            assertEquals("ENCV", data.copyOfRange(0, 4).decodeToString())
            assertFalse(String(data, Charsets.ISO_8859_1).contains("WebDAV"))
        }
    }
}
