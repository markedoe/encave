package app.encave.core

import app.encave.core.sync.WebDavMailboxStore
import app.encave.core.sync.http
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** Requests say "Encave/<version>" and nothing about the device. */
@Timeout(60, unit = TimeUnit.SECONDS)
class HttpTest {
    private val agents = java.util.concurrent.CopyOnWriteArrayList<String?>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }
    private val base = "http://localhost:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun everyClientSendsOnlyTheAppAndItsVersion() {
        Http.setVersion("1.2.3")
        val connection = Http.open(URL("$base/a"))
        assertEquals(204, connection.responseCode)
        connection.disconnect()
        // The relay's requests.
        assertEquals(204, http("$base/b", "GET", token = "").first)
        // WebDAV's (OkHttp).
        WebDavMailboxStore.defaultClient.newCall(Request.Builder().url("$base/c").build()).execute().close()
        assertEquals(listOf("Encave/1.2.3", "Encave/1.2.3", "Encave/1.2.3"), agents.toList())
        agents.forEach { agent ->
            listOf("Dalvik", "Linux", "Android", "Java", "okhttp", "Build/").forEach { assertFalse(agent!!.contains(it), agent) }
        }
    }
}
