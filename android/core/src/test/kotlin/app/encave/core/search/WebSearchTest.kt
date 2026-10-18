package app.encave.core.search

import app.encave.core.Wine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Answers in each provider's format, the same fixtures as CaveKit's WebSearchTests.swift. */
object WebSearchFixtures {
    val brave = """
    {"type":"search","query":{"original":"Domaine Example Chablis 1er Cru 2019 wine"},
     "web":{"type":"search","results":[
      {"title":"Domaine Example <strong>Chablis</strong> 1er Cru 2019","url":"https://www.example.com/chablis-2019",
       "description":"A <strong>Chardonnay</strong> from Burgundy &amp; best from 2023 to 2031. It&#39;s &quot;mineral&quot;.",
       "meta_url":{"hostname":"www.example.com"}},
      {"title":"No address","description":"skipped"},
      {"title":"Script","url":"javascript:alert(1)","description":"skipped"},
      {"title":"","url":"http://shop.example.org/p?id=1","description":"  Two\n lines  "}
     ]}}
    """

    const val BRAVE_NO_WEB = """{"type":"search","query":{"original":"zzz"}}"""

    val searxng = """
    {"query":"Domaine Example Chablis 1er Cru 2019 wine","number_of_results":0,
     "results":[
      {"title":"Chablis 1er Cru 2019 – Domaine Example","url":"https://example.net/wine","content":"Region: Burgundy. Grape: Chardonnay.","engine":"duckduckgo"},
      {"title":"Bad","url":"ftp://example.net/file","content":"skipped"}
     ],"answers":[],"suggestions":[],"unresponsive_engines":[]}
    """

    val tavily = """
    {"query":"Domaine Example Chablis 1er Cru 2019 wine","answer":null,"images":[],
     "results":[
      {"title":"Domaine Example","url":"https://example.org/domaine","content":"Family estate in Chablis, France.","score":0.91}
     ],"response_time":1.2}
    """
}

class WebSearchTest {
    private val fakeKey = "TEST-KEY-NOT-REAL"

    @Test
    fun queryHasProducerNameAppellationAndVintage() {
        val wine = Wine(
            name = "1er Cru Montmains", producer = "Domaine Example", vintage = 2019, region = "Burgundy",
            country = "France", appellation = "Chablis", grapes = listOf("Chardonnay"), notes = "Bought at the shop",
            tags = listOf("gift"),
        )
        assertEquals("Domaine Example 1er Cru Montmains Chablis 2019 wine", WebSearchQuery.terms(wine))
    }

    @Test
    fun queryDoesNotRepeatParts() {
        assertEquals("Château Margaux 2015 wine", WebSearchQuery.terms("Château Margaux", "Château Margaux", "Margaux", 2015))
        assertEquals("Domaine Example Chablis wine", WebSearchQuery.terms("domaine example", "Domaine Example Chablis", "chablis", null))
        assertEquals("Maison Example Frères vin", WebSearchQuery.terms("Maison Example Frères", "Maison Example", null, null, "vin"))
        assertEquals("Cuvée X wine", WebSearchQuery.terms("  ", "  Cuvée   X ", "", 0))
        assertEquals("", WebSearchQuery.terms(null, "", null, null))
    }

    @Test
    fun queryIsPercentEncoded() {
        val request = BraveSearch(fakeKey).request("Moët & Chandon+1 2012", 50)
        assertEquals("https://api.search.brave.com/res/v1/web/search?q=Mo%C3%ABt%20%26%20Chandon%2B1%202012&count=20", request.url)
    }

    @Test
    fun braveRequest() {
        val request = BraveSearch(fakeKey).request("Chablis 2019 wine", 10)
        assertEquals("GET", request.method)
        assertEquals("https://api.search.brave.com/res/v1/web/search?q=Chablis%202019%20wine&count=10", request.url)
        assertEquals(fakeKey, request.headers["X-Subscription-Token"])
        assertEquals("application/json", request.headers["Accept"])
        assertNull(request.body)
    }

    @Test
    fun braveResults() {
        val results = BraveSearch(fakeKey).results(WebSearchFixtures.brave, 200)
        assertEquals(2, results.size)
        assertEquals("Domaine Example Chablis 1er Cru 2019", results[0].title)
        assertEquals("A Chardonnay from Burgundy & best from 2023 to 2031. It's \"mineral\".", results[0].snippet)
        assertEquals("example.com", results[0].domain)
        assertEquals("shop.example.org", results[1].title)
        assertEquals("Two lines", results[1].snippet)
        assertTrue(BraveSearch(fakeKey).results(WebSearchFixtures.BRAVE_NO_WEB, 200).isEmpty())
    }

    @Test
    fun braveErrors() {
        val brave = BraveSearch(fakeKey)
        assertEquals(WebSearchError.Unauthorized, assertThrows<WebSearchError> { brave.results("{}", 401) })
        assertEquals(WebSearchError.Unauthorized, assertThrows<WebSearchError> { brave.results("{}", 403) })
        assertEquals(WebSearchError.RateLimited, assertThrows<WebSearchError> { brave.results("{}", 429) })
        assertEquals(WebSearchError.Server(500), assertThrows<WebSearchError> { brave.results("", 500) })
        assertEquals(WebSearchError.InvalidResponse, assertThrows<WebSearchError> { brave.results("<html>", 200) })
    }

    @Test
    fun searxngRequest() {
        for (address in listOf("http://nas.local:8080", "http://nas.local:8080/", " http://nas.local:8080/search?q=x ")) {
            val request = SearXNGSearch(address).request("Chablis 2019", 10)
            assertEquals("http://nas.local:8080/search?q=Chablis%202019&format=json", request.url)
            assertEquals("GET", request.method)
        }
        assertEquals("https://search.example.org/searx/search?q=a&format=json", SearXNGSearch("https://search.example.org/searx/").request("a", 5).url)
        for (bad in listOf("nas.local:8080", "", "ftp://nas.local")) {
            assertEquals(WebSearchError.InvalidServerUrl, assertThrows<WebSearchError> { SearXNGSearch(bad) })
        }
    }

    @Test
    fun searxngResults() {
        val searx = SearXNGSearch("http://nas.local:8080")
        val results = searx.results(WebSearchFixtures.searxng, 200)
        assertEquals(listOf("Chablis 1er Cru 2019 – Domaine Example"), results.map { it.title })
        assertEquals("Region: Burgundy. Grape: Chardonnay.", results[0].snippet)
        assertEquals("example.net", results[0].domain)
        assertEquals(WebSearchError.JsonFormatDisabled, assertThrows<WebSearchError> { searx.results("", 403) })
    }

    @Test
    fun tavilyRequestAndResults() {
        val request = TavilySearch(fakeKey).request("Chablis 2019", 8)
        assertEquals("POST", request.method)
        assertEquals("https://api.tavily.com/search", request.url)
        assertEquals("Bearer $fakeKey", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("Chablis 2019", body["query"]!!.jsonPrimitive.content)
        assertEquals(8, body["max_results"]!!.jsonPrimitive.int)
        assertEquals(listOf("max_results", "query", "search_depth"), body.keys.sorted())
        assertEquals(
            listOf(WebSearchResult("Domaine Example", "https://example.org/domaine", "Family estate in Chablis, France.")),
            TavilySearch(fakeKey).results(WebSearchFixtures.tavily, 200),
        )
    }

    @Test
    fun servicesNeedTheirKeyOrServer() {
        assertEquals(WebSearchError.MissingKey, assertThrows<WebSearchError> { WebSearchService.Brave.provider(" ", "") })
        assertEquals(WebSearchError.MissingKey, assertThrows<WebSearchError> { WebSearchService.Tavily.provider(null, "") })
        assertEquals(WebSearchError.InvalidServerUrl, assertThrows<WebSearchError> { WebSearchService.SearXNG.provider(null, "") })
        assertTrue(WebSearchService.Brave.provider(fakeKey, "") is BraveSearch)
        assertTrue(WebSearchService.SearXNG.provider(null, "http://nas.local:8080") is SearXNGSearch)
        assertEquals(listOf(WebSearchService.Brave, WebSearchService.Tavily), WebSearchService.entries.filter { it.requiresKey })
    }

    @Test
    fun entities() {
        assertEquals(
            "Saint-&Eacute;milion été … &unknown; & co",
            WebSearchText.plain("<b>Saint-&Eacute;milion</b> &#233;t&#xE9; &hellip; &unknown; & co"),
        )
    }
}
