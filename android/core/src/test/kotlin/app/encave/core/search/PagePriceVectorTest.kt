package app.encave.core.search

import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Prices on web pages and in snippets. CaveKit (PagePriceVectorTests.swift) reads the same
 * files, in `fixtures/prices`.
 */
class PagePriceVectorTest {
    private val folder = File("../../fixtures/prices")
    private val root: JsonObject = Json.parseToJsonElement(File(folder, "vectors.json").readText()).jsonObject

    private fun vectors(key: String): List<JsonObject> = root[key]!!.jsonArray.map { it.jsonObject }

    private fun strings(element: Any?): List<String> = (element as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun label(amount: BigDecimal, currency: String) = "${amount.stripTrailingZeros().toPlainString()} $currency"

    @Test
    fun sizeWords() {
        for (vector in vectors("sizeWords")) {
            val size = vector["sizeML"]!!.jsonPrimitive.int
            assertEquals(vector["words"]!!.jsonPrimitive.contentOrNull, WebSearchQuery.sizeWords(size), "$size")
        }
    }

    @Test
    fun snippets() {
        for (vector in vectors("snippets")) {
            val text = vector["text"]!!.jsonPrimitive.content
            val size = vector["sizeML"]!!.jsonPrimitive.int
            val found = PriceExtractor.prices(text, size).map { label(it.first, it.second) }
            assertEquals(strings(vector["prices"]), found, "$text ($size ml)")
        }
    }

    @Test
    fun pages() {
        for (vector in vectors("pages")) {
            val file = vector["file"]!!.jsonPrimitive.content
            val url = vector["url"]!!.jsonPrimitive.content
            val size = vector["sizeML"]!!.jsonPrimitive.int
            val result = WebSearchResult("Result", url, "")
            val found = PagePrices.prices(File(folder, file).readText(), result, size, vector["wineName"]?.jsonPrimitive?.contentOrNull)
            val name = "$file ($size ml)"
            assertEquals(strings(vector["prices"]), found.map { label(it.amount, it.currency) }, name)
            assertTrue(found.all { it.url == url && it.domain == result.domain }, name)
            for (words in strings(vector["excerptContains"])) {
                assertTrue(found.first().excerpt.contains(words), "$name: ${found.first().excerpt} has $words")
            }
        }
    }

    @Test
    fun addresses() {
        for (vector in vectors("addresses")) {
            val url = vector["url"]!!.jsonPrimitive.content
            assertEquals(vector["allowed"]!!.jsonPrimitive.boolean, PagePrices.isAllowed(url), url)
        }
    }

    @Test
    fun resolvedAddresses() {
        for (vector in vectors("resolved")) {
            val addresses = strings(vector["addresses"])
            assertEquals(vector["public"]!!.jsonPrimitive.boolean, PagePrices.arePublic(addresses), "$addresses")
        }
    }

    @Test
    fun resolvingChecksEveryAddress() {
        val url = "https://shop.example.org/vin"
        assertTrue(PagePrices.resolvesPublicly(url) { listOf("203.0.113.10") })
        assertFalse(PagePrices.resolvesPublicly(url) { listOf("203.0.113.10", "127.0.0.1") })
        assertFalse(PagePrices.resolvesPublicly(url) { throw java.net.UnknownHostException(it) })
        assertFalse(PagePrices.resolvesPublicly("https://localhost/vin") { listOf("203.0.113.10") })
    }

    @Test
    fun onlyTheFirstPagesAreOpened() {
        val urls = listOf("https://a.example.com", "http://b.example.com", "https://c.example.com", "https://localhost", "https://e.example.com", "https://f.example.com")
        val results = urls.map { WebSearchResult("R", it, "") }
        assertEquals(listOf("https://a.example.com", "https://c.example.com", "https://e.example.com"), PagePrices.pages(results).map { it.url })
    }

    @Test
    fun pagePricesJoinTheSnippetPrices() {
        val url = "https://shop.example.org/vin"
        val snippet = FoundPrice(BigDecimal(13), "CHF", "shop.example.org", url, "CHF 13")
        val page = listOf(
            FoundPrice(PriceExtractor.decimal("13.00")!!, "CHF", "shop.example.org", url, "Les Lacustres · CHF 13.00"),
            FoundPrice(BigDecimal(14), "CHF", "shop.example.org", url, ""),
        )
        assertEquals(listOf("13", "14"), PagePrices.merging(page, listOf(snippet)).map { it.amount.toPlainString() })
    }

    @Test
    fun pageText() {
        val text = Html.visibleText("<html><head><title>T</title></head><body><p>A&nbsp;&euro;<b>1</b></p><script>x</script><div>B</div></body></html>")
        assertEquals("A € 1\nB", text)
        assertTrue(PagePrices.isHtml("text/html; charset=UTF-8"))
        assertFalse(PagePrices.isHtml("application/json"))
        assertFalse(PagePrices.isHtml(null))
        val latin = byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x33, 0x20, 0x80.toByte()) // "Prix 13 €" in windows-1252
        assertEquals("Prix 13 €", PagePrices.text(latin, "text/html; charset=windows-1252"))
        assertEquals("Prix 13 €", PagePrices.text("Prix 13 €".toByteArray(), "text/html"))
    }
}
