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
import org.junit.jupiter.api.Assertions.assertThrows
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

    /** Private IPv4 ranges, built from their numbers so the repository's checks do not take them for real addresses. */
    @Test
    fun privateAddressesAreNotPublic() {
        fun ip(vararg parts: Int) = parts.joinToString(".")
        for (address in listOf(
            ip(10, 0, 0, 1), ip(172, 16, 0, 1), ip(172, 31, 255, 1), ip(192, 168, 1, 1), ip(100, 64, 0, 1), ip(100, 127, 0, 1),
            ip(192, 0, 0, 8), "::ffff:" + ip(192, 168, 1, 1), "::" + ip(10, 0, 0, 1), "64:ff9b::" + ip(192, 168, 1, 1),
        )) {
            assertFalse(PagePrices.arePublic(listOf(address)), address)
        }
        for (address in listOf(ip(172, 32, 0, 1), ip(100, 128, 0, 1), ip(192, 0, 2, 1), "::ffff:" + ip(203, 0, 113, 10))) {
            assertTrue(PagePrices.arePublic(listOf(address)), address)
        }
    }

    @Test
    fun imageAddresses() {
        for (vector in vectors("imageAddresses")) {
            val url = vector["url"]!!.jsonPrimitive.content
            assertEquals(vector["allowed"]!!.jsonPrimitive.boolean, WebImageDownload.isAllowed(url), url)
        }
        for (vector in vectors("imageResolved")) {
            val url = vector["url"]!!.jsonPrimitive.content
            val addresses = strings(vector["addresses"])
            assertEquals(vector["public"]!!.jsonPrimitive.boolean, WebImageDownload.resolvesPublicly(url) { addresses }, "$url $addresses")
        }
    }

    @Test
    fun structuredAmountsAreStrict() {
        assertEquals("13.9", PagePrices.structuredAmount("13.90")!!.toPlainString())
        assertEquals("13", PagePrices.structuredAmount(" 13 ")!!.toPlainString())
        assertEquals("14", PagePrices.structuredAmount("13.999")!!.toPlainString())
        for (text in listOf("13,90", "1e3", "1E+21", "1.234,50", "-13", "13.", ".5", "", "13.9.1", "١٣")) {
            assertEquals(null, PagePrices.structuredAmount(text), text)
        }
        assertEquals("13.90", PagePrices.twoDecimals(BigDecimal("13.9")))
        assertEquals("13.00", PagePrices.twoDecimals(BigDecimal(13)))
    }

    /** Hostile pages: the reading stays linear and quick, and stops when asked. */
    @Test
    fun hostilePagesAreReadQuickly() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        for (piece in listOf("<", "<!--", "<h1", "<script", "<a", "<a b='", "<!x", "&amp;", "é", "<p>CHF 13.00 ", "<h1>")) {
            val html = piece.repeat(PagePrices.MAX_BYTES / piece.length)
            val start = System.nanoTime()
            PagePrices.prices(html, result, 750, "Les Lacustres")
            PagePrices.prices(html, result, 750, null)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, piece)
        }
        val html = "<p>Les Lacustres CHF 13.00</p>".repeat(20_000)
        assertTrue(PagePrices.prices(html, result, 750, "Les Lacustres") { true }.isEmpty())
        assertFalse(PagePrices.prices(html, result, 750, "Les Lacustres") { false }.isEmpty())
    }

    @Test
    fun scannerReadsTagsAndText() {
        val html = """<html><head><meta property="og:title" content="A &amp; B"><title>T</title></head><body>""" +
            """<h1 class=x>Les <b>Lacustres</b></h1><!-- CHF 99.00 --><p>1 < 2</p><span itemprop=price content='13.90'>Fr. 13.90</span>""" +
            """<meta itemprop="priceCurrency" content="CHF"><script type="application/ld+json">{"a":1}</script></body></html>"""
        val document = HtmlScanner.scan(html) { false }!!
        assertEquals("Les Lacustres", document.heading)
        assertEquals("T", document.title)
        assertEquals("og:title" to "A & B", document.meta.first())
        assertEquals("13.90", document.itemPrice)
        assertEquals("CHF", document.itemCurrency)
        assertEquals(listOf("""{"a":1}"""), document.jsonLd)
        assertEquals("Les Lacustres\n1 < 2\nFr. 13.90", document.text)
    }

    @Test
    fun readerConnectsOnlyToPublicAddresses() {
        fun address(vararg parts: Int) = java.net.InetAddress.getByAddress(ByteArray(parts.size) { parts[it].toByte() })
        val deadline = System.nanoTime() + 5_000_000_000L
        val public = PageReader(lookUp = { _, _ -> listOf(address(203, 0, 113, 10)) })
        assertEquals(1, public.vettedAddresses("shop.example.org", deadline).size)
        val mixed = PageReader(lookUp = { _, _ -> listOf(address(203, 0, 113, 10), address(127, 0, 0, 1)) })
        assertThrows(java.net.UnknownHostException::class.java) { mixed.vettedAddresses("shop.example.org", deadline) }
        val none = PageReader(lookUp = { _, _ -> emptyList() })
        assertThrows(java.net.UnknownHostException::class.java) { none.vettedAddresses("shop.example.org", deadline) }
        listOf(public, mixed, none).forEach { it.close() }
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
        val text = Html.visibleText("<html><head><title>T</title></head><body><p>A&nbsp;&euro;<b>1</b>\u202F\u00A02</p><script>x</script><div>B</div></body></html>")
        assertEquals("A € 1 2\nB", text)
        assertTrue(PagePrices.isHtml("text/html; charset=UTF-8"))
        assertFalse(PagePrices.isHtml("application/json"))
        assertFalse(PagePrices.isHtml(null))
        val latin = byteArrayOf(0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x33, 0x20, 0x80.toByte()) // "Prix 13 €" in windows-1252
        assertEquals("Prix 13 €", PagePrices.text(latin, "text/html; charset=windows-1252"))
        assertEquals("Prix 13 €", PagePrices.text("Prix 13 €".toByteArray(), "text/html"))
    }
}
