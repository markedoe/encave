package app.encave.core.search

import app.encave.core.Wine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Image answers in each provider's format, the same fixtures as CaveKit's WebImageSearchTests.swift. */
object WebImageFixtures {
    val brave = """
    {"type":"images","query":{"original":"Domaine Example Chablis 2019 bottle"},
     "results":[
      {"type":"image_result","title":"Domaine Example Chablis 2019 &amp; co","url":"https://www.caviste.example.fr/chablis",
       "source":"caviste.example.fr","thumbnail":{"src":"https://imgs.search.brave.example/abc.jpg"},
       "properties":{"url":"https://www.caviste.example.fr/img/chablis.jpg","placeholder":"https://imgs.search.brave.example/p.jpg"}},
      {"type":"image_result","title":"Plain http picture","url":"https://shop.example.com/p",
       "thumbnail":{"src":"https://imgs.search.brave.example/def.jpg"},"properties":{"url":"http://shop.example.com/p.jpg"}},
      {"type":"image_result","title":"No thumbnail","url":"https://shop.example.com/q",
       "properties":{"url":"https://shop.example.com/q.png"}},
      {"type":"image_result","title":"Same picture again","url":"https://other.example.com/",
       "thumbnail":{"src":"https://imgs.search.brave.example/abc.jpg"},
       "properties":{"url":"https://www.caviste.example.fr/img/chablis.jpg"}}
     ]}
    """

    val searxng = """
    {"query":"Domaine Example Chablis 2019 bottle","results":[
      {"title":"Chablis 2019","url":"https://example.net/wine","img_src":"https://example.net/bottle.webp",
       "thumbnail_src":"https://example.net/thumb.webp","engine":"bing images","category":"images"},
      {"title":"Data","url":"https://example.net/x","img_src":"data:image/png;base64,AAAA"}
    ]}
    """

    val tavily = """
    {"query":"Chablis bottle","images":["https://example.org/a.jpg",{"url":"https://example.org/b.jpg","description":"A bottle of Chablis"}],
     "results":[]}
    """
}

class WebImageSearchTest {
    private val fakeKey = "TEST-KEY-NOT-REAL"

    @Test
    fun pictureQuery() {
        val wine = Wine(name = "Chablis 1er Cru", producer = "Domaine Example", vintage = 2019, appellation = "Chablis", notes = "secret")
        assertEquals("Domaine Example Chablis 1er Cru 2019 bottle", WebSearchQuery.pictureTerms(wine))
        assertEquals("Domaine Example Chablis 1er Cru 2019 bouteille", WebSearchQuery.pictureTerms(wine, "bouteille"))
    }

    @Test
    fun braveImages() {
        val brave = BraveSearch(fakeKey)
        val request = brave.imageRequest("Chablis bottle", 30)
        assertEquals("https://api.search.brave.com/res/v1/images/search?q=Chablis%20bottle&count=30&safesearch=strict", request.url)
        assertEquals(fakeKey, request.headers["X-Subscription-Token"])
        val results = brave.imageResults(WebImageFixtures.brave, 200)
        assertEquals(listOf("Domaine Example Chablis 2019 & co", "No thumbnail"), results.map { it.title })
        assertEquals("https://imgs.search.brave.example/abc.jpg", results[0].thumbnailUrl)
        assertEquals("https://www.caviste.example.fr/img/chablis.jpg", results[0].imageUrl)
        assertEquals("caviste.example.fr", results[0].domain)
        assertEquals(results[1].imageUrl, results[1].thumbnailUrl)
        assertEquals(WebSearchError.Unauthorized, assertThrows<WebSearchError> { brave.imageResults("", 401) })
    }

    @Test
    fun searxngImages() {
        val searx = SearXNGSearch("http://nas.local:8080/")
        assertEquals(
            "http://nas.local:8080/search?q=Chablis%20bottle&format=json&categories=images&safesearch=1",
            searx.imageRequest("Chablis bottle", 30).url,
        )
        val results = searx.imageResults(WebImageFixtures.searxng, 200)
        assertEquals(1, results.size)
        assertEquals("https://example.net/bottle.webp", results[0].imageUrl)
        assertEquals("https://example.net/thumb.webp", results[0].thumbnailUrl)
        assertEquals("example.net", results[0].domain)
        assertEquals(WebSearchError.JsonFormatDisabled, assertThrows<WebSearchError> { searx.imageResults("", 403) })
    }

    @Test
    fun tavilyImages() {
        val tavily = TavilySearch(fakeKey)
        val body = Json.parseToJsonElement(tavily.imageRequest("Chablis bottle", 10).body!!).jsonObject
        assertTrue(body["include_images"]!!.jsonPrimitive.boolean)
        val results = tavily.imageResults(WebImageFixtures.tavily, 200)
        assertEquals(listOf("https://example.org/a.jpg", "https://example.org/b.jpg"), results.map { it.imageUrl })
        assertEquals("A bottle of Chablis", results[1].title)
        assertEquals("example.org", results[0].domain)
    }

    @Test
    fun downloadChecks() {
        assertTrue(WebImageDownload.isAllowed("https://example.org/a.jpg"))
        assertFalse(WebImageDownload.isAllowed("http://example.org/a.jpg"))
        assertFalse(WebImageDownload.isAllowed("file:///etc/passwd"))
        // Never this device or the local network: the results come from anyone. Addresses are
        // built from their numbers, so the repository's checks do not take them for real ones.
        fun ip(vararg parts: Int) = parts.joinToString(".")
        listOf(
            "https://${ip(192, 168, 1, 1)}/a.jpg", "https://localhost/a.jpg", "https://[fd00::1]/a.jpg", "https://nas.local/a.jpg",
            "https://${ip(127, 0, 0, 1)}/a.jpg", "https://${ip(10, 0, 0, 2)}/a.jpg", "https://${ip(172, 16, 4, 1)}/a.jpg",
            "https://${ip(169, 254, 169, 254)}/a.jpg", "https://[::1]/a.jpg", "https://[fe80::1]/a.jpg",
            "https://[::ffff:${ip(192, 168, 1, 1)}]/a.jpg", "https://${ip(0, 0, 0, 0)}/a.jpg", "https://2130706433/a.jpg",
            "https://127.1/a.jpg", "https://0x7f.0.0.1/a.jpg", "https://NAS.Local./a.jpg", "https://router.home.arpa/a.jpg",
            "https://app.localhost/a.jpg",
        ).forEach { assertFalse(WebImageDownload.isAllowed(it), it) }
        // A public address (from the range kept for documentation), and a public IPv6 one.
        assertTrue(WebImageDownload.isAllowed("https://${ip(203, 0, 113, 7)}/a.jpg"))
        assertTrue(WebImageDownload.isAllowed("https://[2001:db8::1]/a.jpg"))
        assertFalse(WebImageDownload.isAllowed("https://images.example.com:8443/a.jpg"))
        assertTrue(WebImageDownload.isAllowed("https://images.example.com:443/a.jpg"))
        assertTrue(WebImageDownload.isImage("image/jpeg"))
        assertTrue(WebImageDownload.isImage("Image/WebP; charset=binary"))
        assertFalse(WebImageDownload.isImage("text/html"))
        assertFalse(WebImageDownload.isImage("image/svg+xml"))
        assertFalse(WebImageDownload.isImage(null))
        assertTrue(WebImageDownload.acceptsLength(null) && WebImageDownload.acceptsLength(-1))
        assertTrue(WebImageDownload.acceptsLength(8L * 1024 * 1024))
        assertFalse(WebImageDownload.acceptsLength(8L * 1024 * 1024 + 1))
    }
}
