package app.encave.core.sync

import app.encave.core.CappedRead
import app.encave.core.ResponseTooLargeException
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A server named on the home network over http, and responses read with a limit. */
class HomeNameHttpTest {
    /** Answers every request with [size] bytes, streamed without a length unless [sized]; keeps the Host headers. */
    private class Server(val size: Long, val sized: Boolean = false) : AutoCloseable {
        val hosts = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                synchronized(hosts) { hosts += exchange.requestHeaders.getFirst("Host").orEmpty() }
                exchange.requestBody.readBytes()
                val status = if (exchange.requestMethod == "PROPFIND") 207 else 200
                exchange.sendResponseHeaders(status, if (sized) size else 0)
                runCatching {
                    exchange.responseBody.use { out ->
                        val chunk = ByteArray(64 * 1024)
                        var left = size
                        while (left > 0) {
                            val n = minOf(left, chunk.size.toLong()).toInt()
                            out.write(chunk, 0, n)
                            left -= n
                        }
                    }
                }
                exchange.close()
            }
            start()
        }
        val port get() = server.address.port
        override fun close() = server.stop(0)
    }

    @Test
    fun readsUpToTheLimit() {
        assertEquals(1000, CappedRead.bytes(ByteArrayInputStream(ByteArray(1000)), -1, 1000).size)
        assertThrows<ResponseTooLargeException> { CappedRead.bytes(ByteArrayInputStream(ByteArray(1001)), -1, 1000) }
        assertThrows<ResponseTooLargeException> { CappedRead.bytes(ByteArrayInputStream(ByteArray(0)), 1001, 1000) }
    }

    /** A relay that streams one byte more than a sync file may hold is not read into memory. */
    @Test
    fun relayAnswersAreCapped() {
        Server(CappedRead.SYNC_LIMIT + 1).use { server ->
            assertThrows<ResponseTooLargeException> { http("http://127.0.0.1:${server.port}/v1/m/x", "GET", token = "") }
        }
        Server(CappedRead.SYNC_LIMIT + 1, sized = true).use { server ->
            assertThrows<ResponseTooLargeException> { http("http://127.0.0.1:${server.port}/v1/m/x", "GET", token = "") }
        }
    }

    /** WebDAV over http to a home name keeps the name (Host header), and connects only to its local address. */
    @Test
    fun webDavKeepsTheName() = runBlocking<Unit> {
        Server(10).use { server ->
            val local = WebDavMailboxStore("http://nas.lan:${server.port}/dav/", "u", "p", resolver = LocalName.Resolver { listOf("2001:db8::7", "127.0.0.1") })
            local.send("GET", "http://nas.lan:${server.port}/dav/a".toHttpUrl())
            assertEquals(listOf("nas.lan:${server.port}"), synchronized(server.hosts) { server.hosts.toList() })
            val outside = WebDavMailboxStore("http://nas.lan:${server.port}/dav/", "u", "p", resolver = LocalName.Resolver { listOf("203.0.113.7") })
            assertThrows<RemoteSyncException> { runBlocking { outside.send("GET", "http://nas.lan:${server.port}/dav/a".toHttpUrl()) } }
            assertEquals(1, synchronized(server.hosts) { server.hosts.size })
        }
    }

    /** No file is written that a device would refuse to read back. */
    @Test
    fun writesOverTheLimitAreRefused() = runBlocking<Unit> {
        val big = ByteArray(CappedRead.SYNC_LIMIT.toInt() + 1)
        val id = "c" + "d".repeat(32)
        for (store in listOf(RelayMailboxStore("http://127.0.0.1:9/"), WebDavMailboxStore("http://127.0.0.1:9/dav/", "u", "p"))) {
            val error = assertThrows<RemoteSyncException> { runBlocking { store.mailbox(id, "t").create("snap/x", big) } }
            assertEquals(RemoteSyncError.FileTooLarge, error.error)
        }
    }

    /** A WebDAV file over the limit is refused. */
    @Test
    fun webDavAnswersAreCapped() = runBlocking<Unit> {
        Server(CappedRead.SYNC_LIMIT + 1).use { server ->
            val store = WebDavMailboxStore("http://127.0.0.1:${server.port}/dav/", "u", "p")
            assertThrows<RemoteSyncException> { runBlocking { store.send("GET", "http://127.0.0.1:${server.port}/dav/a".toHttpUrl()) } }
        }
        Unit
    }
}
