package app.encave.core.sync

import app.encave.core.search.SearXNGSearch
import app.encave.core.search.WebSearchError
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Plain http to a server named on the home network. CaveKit (LocalNameTests.swift) checks the
 * same vectors, `fixtures/local-name-vectors.json`, so the two cannot drift.
 */
class LocalNameTest {
    /** Tests run in android/core. */
    private val root: JsonObject = Json.parseToJsonElement(File("../../fixtures/local-name-vectors.json").readText()).jsonObject

    @Test
    fun sharedVectors() {
        for (item in root.getValue("resolve").jsonArray.map { it.jsonObject }) {
            val url = item.getValue("url").jsonPrimitive.content
            val addresses = item.getValue("addresses").jsonArray.map { it.jsonPrimitive.content }
            val expect = item["expect"]?.jsonPrimitive?.content
            val error = item["error"]?.jsonPrimitive?.content
            val result = runCatching { LocalName.resolve(url, LocalName.Resolver { addresses }) }
            if (error == null) {
                assertEquals(expect, result.getOrThrow(), url)
            } else {
                val reason = (result.exceptionOrNull() as? LocalNetworkException)?.reason
                val expected = if (error == "notLocal") LocalNetworkException.Reason.NotLocal else LocalNetworkException.Reason.NotFound
                assertEquals(expected, reason, url)
            }
        }
    }

    @Test
    fun sharedChoices() {
        for (item in root.getValue("choose").jsonArray.map { it.jsonObject }) {
            val addresses = item.getValue("addresses").jsonArray.map { it.jsonPrimitive.content }
            val expect = item["expect"]?.jsonPrimitive?.content
            val error = item["error"]?.jsonPrimitive?.content
            val result = runCatching { LocalName.choose(addresses) }
            if (error == null) {
                assertEquals(expect, result.getOrThrow(), addresses.toString())
            } else {
                val reason = (result.exceptionOrNull() as? LocalNetworkException)?.reason
                val expected = if (error == "notLocal") LocalNetworkException.Reason.NotLocal else LocalNetworkException.Reason.NotFound
                assertEquals(expected, reason, addresses.toString())
            }
        }
    }

    @Test
    fun mayBeLocal() {
        val hosts = root.getValue("mayBeLocal").jsonObject
        hosts.getValue("accepted").jsonArray.forEach { assertTrue(LocalName.mayBeLocal(it.jsonPrimitive.content), it.toString()) }
        hosts.getValue("refused").jsonArray.forEach { assertFalse(LocalName.mayBeLocal(it.jsonPrimitive.content), it.toString()) }
    }

    /** The resolver is not asked for https, an address or localhost; .local and .home.arpa names are resolved like the others. */
    @Test
    fun resolvesOnlyNamesOverHttp() {
        for (url in listOf("https://searxng.home/search", "http://localhost:11434/", "http://[fd12:3456::20]:8080/")) {
            assertEquals(url, LocalName.resolve(url, LocalName.Resolver { error("resolved $url") }))
        }
    }

    /** A link-local address with an interface is skipped; one without comes after the others. */
    @Test
    fun linkLocalLast() {
        assertEquals("fd12:3456::20", LocalName.choose(listOf("fe80::1%wlan0", "fd12:3456::20")))
        assertEquals("fd12:3456::20", LocalName.choose(listOf("fe80::1", "fd12:3456::20")))
        assertEquals(LocalNetworkException.Reason.NotFound, assertThrows<LocalNetworkException> { LocalName.choose(listOf("fe80::1%wlan0")) }.reason)
    }

    /** Plain HTTP goes where OkHttp reads the host, even when java.net.URL reads the text otherwise. */
    @Test
    fun sentAsChecked() {
        // The String form: newer JDKs refuse this text in java.net.URL before resolve sees it.
        val resolved = java.net.URL(LocalName.resolve("http://127.0.0.1\\@evil.example/"))
        assertEquals("127.0.0.1", resolved.host)
    }

    /** OkHttp's resolver for names gives only the local addresses, best first. */
    @Test
    fun dnsGivesOnlyLocalAddresses() {
        val dns = LocalName.dns(LocalName.Resolver { listOf("2001:db8::7", "fd12:3456::20", "10.0.0.20") }) // public-safety: allow
        assertEquals(listOf("10.0.0.20", "fd12:3456:0:0:0:0:0:20"), dns.lookup("nas.lan").map { it.hostAddress }) // public-safety: allow
        assertThrows<java.net.UnknownHostException> { LocalName.dns(LocalName.Resolver { listOf("203.0.113.7") }).lookup("nas.lan") }
    }

    /** The settings fields accept a name of the home network over http. */
    @Test
    fun entryAcceptsHomeNames() {
        assertNotNull(WebDavMailboxStore.folderUrl("http://nas.lan:5005/encave"))
        assertNull(WebDavMailboxStore.folderUrl("http://203.0.113.7/encave"))
        assertTrue(RelayMailboxStore.isAllowed("http://relay.home:8787"))
        assertFalse(RelayMailboxStore.isAllowed("http://203.0.113.7:8787"))
        assertNull(WebDavMailboxStore.folderUrl("http://8.8.8.8/encave"))
        assertEquals("http://searxng.home:8888", SearXNGSearch("http://searxng.home:8888").base)
        assertThrows<WebSearchError> { SearXNGSearch("http://203.0.113.7:8888") }
    }

    /** A name that cannot exist (RFC 6761) is reported as not found by the system resolver. */
    @Test
    fun systemResolverNotFound() {
        val error = assertThrows<LocalNetworkException> { LocalName.resolve("http://no-such-host.invalid/") }
        assertEquals(LocalNetworkException.Reason.NotFound, error.reason)
    }
}
