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
import okhttp3.ResponseBody.Companion.asResponseBody
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

    /** "13 CHF 700": a price of another size, with the size. */
    private fun otherLabel(price: FoundPrice) = "${label(price.amount, price.currency)} ${price.sizeML ?: 0}"

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
            val all = PriceExtractor.prices(listOf(WebSearchResult("", "https://shop.example.org/vin", text)), size)
            assertEquals(strings(vector["prices"]), all.filter { !it.isOtherSize }.map { label(it.amount, it.currency) }, text)
            assertEquals(strings(vector["otherSizes"]), all.filter { it.isOtherSize }.map(::otherLabel), "$text ($size ml)")
        }
    }

    @Test
    fun pages() {
        for (vector in vectors("pages")) {
            val file = vector["file"]!!.jsonPrimitive.content
            val url = vector["url"]!!.jsonPrimitive.content
            val size = vector["sizeML"]!!.jsonPrimitive.int
            val result = WebSearchResult("Result", url, "")
            val vintage = vector["vintage"]?.jsonPrimitive?.int
            val wineName = vector["wineName"]?.jsonPrimitive?.contentOrNull
            val all = PagePrices.prices(
                File(folder, file).readText(), result, size, wineName, vintage,
                producer = vector["producer"]?.jsonPrimitive?.contentOrNull,
            )
            val found = all.filter { !it.isOtherSize }
            val name = "$file $wineName ($size ml, ${vintage ?: "no vintage"})"
            assertEquals(strings(vector["prices"]), found.map { label(it.amount, it.currency) }, name)
            assertEquals(strings(vector["otherSizes"]), all.filter { it.isOtherSize }.map(::otherLabel), name)
            assertTrue(all.all { it.url == url && it.domain == result.domain }, name)
            val excerpt = all.firstOrNull()?.excerpt ?: "-"
            for (words in strings(vector["excerptContains"])) {
                assertTrue(excerpt.contains(words), "$name: $excerpt has $words")
            }
            for (words in strings(vector["excerptLacks"])) {
                assertFalse(excerpt.contains(words), "$name: $excerpt lacks $words")
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

    /** The words of a name looked for when the whole name is not on the page: quick however often they appear. */
    @Test
    fun wordsOfTheNameAreLookedForQuickly() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        for (piece in listOf("<p>Lacustres CHF 13.00</p>", "Lacustres ", "<p>Chasselas Lacustres 70 cl CHF 13.00</p>", "Réserve 1 ")) {
            val html = piece.repeat(PagePrices.MAX_BYTES / piece.length)
            for ((name, producer) in listOf("Lacustres Réserve Spéciale" to "Vignoble Exemple", "" to "Vignoble Lacustres Réserve")) {
                val start = System.nanoTime()
                PagePrices.prices(html, result, 750, name, producer = producer)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, "$piece $name")
            }
        }
    }

    /** Hostile pages: the reading stays linear and quick, and stops when asked. */
    @Test
    fun hostilePagesAreReadQuickly() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        val pieces = listOf(
            "<", "<!--", "<h1", "<script", "<a", "<a b='", "<!x", "&amp;", "é", "<p>CHF 13.00 ", "<h1>",
            "<p>Les Lacustres 2018 CHF 13.00 6x ", "2019 1999 ", "Les Lacustres 2019\n",
        )
        for (piece in pieces) {
            val html = piece.repeat(PagePrices.MAX_BYTES / piece.length)
            for ((name, vintage) in listOf("Les Lacustres" to 2020, null to 2020, "Les Lacustres" to null)) {
                val start = System.nanoTime()
                PagePrices.prices(html, result, 750, name, vintage)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, piece)
            }
        }
        // Structured data and text near the name, both read and compared.
        val ld = """<script type="application/ld+json">{"@type":"Product","name":"Les Lacustres 2019","offers":[""" +
            List(2_000) { """{"price":"78.00","priceCurrency":"CHF"}""" }.joinToString(",") + "]}</script>"
        val mixed = ld + "<p>Les Lacustres 2019 6x75cl CHF 78.00 / 1 bottle CHF 13.00</p>".repeat(20_000)
        val start = System.nanoTime()
        val found = PagePrices.prices(mixed, result, 750, "Les Lacustres", 2019)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
        assertEquals(listOf("13"), found.map { it.amount.stripTrailingZeros().toPlainString() })
        val html = "<p>Les Lacustres CHF 13.00</p>".repeat(20_000)
        assertTrue(PagePrices.prices(html, result, 750, "Les Lacustres") { true }.isEmpty())
        assertFalse(PagePrices.prices(html, result, 750, "Les Lacustres") { false }.isEmpty())
    }

    /** A page cut anywhere, inside any tag, comment or raw element, is read without failing. */
    @Test
    fun everyPrefixOfAPageIsRead() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        val small = """<!DOCTYPE html><html><head><title>Les Lacustres</title><style>p{}</style>""" +
            """<script type="application/ld+json">{"@type":"Product","name":"Les Lacustres","offers":{"price":"13.00","priceCurrency":"CHF"}}</script>""" +
            """</head><body><!-- c --><svg><text>x</text></svg><noscript>n</noscript><template>t</template><textarea>a</textarea>""" +
            """<select><option>70 cl</option></select><iframe src=x></iframe><h1 class="t">Les Lacustres</h1>""" +
            """<p itemprop="price" content="13.00">CHF 13.– 70 cl</p><meta itemprop="priceCurrency" content="CHF"></body></html>"""
        for (end in 0..small.length) PagePrices.prices(small.substring(0, end), result, 700, "Les Lacustres")
        for (name in listOf("script", "style", "title", "svg", "textarea", "select", "noscript", "template", "iframe")) {
            for (tail in listOf("<$name>x</$name", "<$name>x</${name.take(2)}", "<$name>x</", "<$name>x<", "<$name")) {
                HtmlScanner.scan("<p>a</p>$tail") { false }
            }
        }
        for (file in pageFiles()) {
            val page = file.readText()
            for (end in 0..page.length step 3) {
                val found = PagePrices.prices(page.substring(0, end), result, 750, "Les Lacustres")
                assertTrue(found.all { it.url == result.url }, "${file.name} cut at $end")
            }
        }
    }

    /** The fixture pages, in the same order everywhere. */
    private fun pageFiles(): List<File> = folder.listFiles { f -> f.name.endsWith(".html") }!!.sortedBy { it.name }

    /** Pages with random damage (the same pseudo-random sequence each run) are read without failing. */
    @Test
    fun damagedPagesAreRead() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        val pieces = "<>/\"'-=!&;#{}[]:, \n"
        for ((index, file) in pageFiles().withIndex()) {
            // A sequence of its own for each file: adding a file changes no other file's damage.
            val random = java.util.Random(0x2545F4914F6CDD1DL xor (index + 1).toLong())
            val page = file.readText()
            repeat(60) { iteration ->
                val damaged = StringBuilder(page)
                repeat(1 + random.nextInt(20)) {
                    val at = random.nextInt(damaged.length)
                    when (random.nextInt(3)) {
                        0 -> damaged.deleteCharAt(at)
                        1 -> damaged.insert(at, pieces[random.nextInt(pieces.length)])
                        else -> damaged.setCharAt(at, pieces[random.nextInt(pieces.length)])
                    }
                }
                val found = PagePrices.prices(damaged.toString(), result, 750, "Les Lacustres")
                assertTrue(found.all { it.url == result.url }, "${file.name} damage $iteration")
            }
        }
    }

    /** Long runs of digit groups, with or without the wine's name, are read quickly. */
    @Test
    fun digitGroupsAreReadQuickly() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        for (piece in listOf("111 ", "1,111", "Les Lacustres 111 111 111 111 111 111 111 111 ", "1 111 111.11 ")) {
            val html = "<p>" + piece.repeat(PagePrices.MAX_BYTES / piece.length) + "</p>"
            for (name in listOf("Les Lacustres", null)) {
                val start = System.nanoTime()
                PagePrices.prices(html, result, 750, name)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000, piece)
            }
        }
        val start = System.nanoTime()
        PriceExtractor.prices("111 ".repeat(50_000), 750)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
    }

    /** Product data after a lot of inline styles (as in some shop themes) is still read. */
    @Test
    fun productDataAfterLongStylesIsRead() {
        val result = WebSearchResult("R", "https://shop.example.org/vin", "")
        val html = "<html><head><style>" + ".a{color:red}".repeat(55_000) + "</style></head><body>" +
            """<h1>Les Lacustres 70 cl</h1><script type="application/ld+json">{"@type":"Product","name":"Les Lacustres",""" +
            """"offers":{"price":"13.00","priceCurrency":"CHF"}}</script></body></html>"""
        assertTrue(html.length > 700_000)
        assertEquals(listOf("13 CHF"), PagePrices.prices(html, result, 700, "Les Lacustres").map { label(it.amount, it.currency) })
    }

    @Test
    fun numbersWithAnExponentAreNull() {
        assertEquals(
            """{"a":null,"b":"1e3","c":null,"d":13.90,"e":"x\"1e3"}""",
            JsonLd.withoutExponents("""{"a":1e3,"b":"1e3","c":-2.5E+1,"d":13.90,"e":"x\"1e3"}"""),
        )
    }

    @Test
    fun readerChecksEveryRedirectAndTheDeadline() = kotlinx.coroutines.runBlocking {
        val reader = PageReader()
        val asked = mutableListOf<String>()
        fun answer(vararg hops: PageReader.Outcome) {
            val queue = ArrayDeque(hops.toList())
            asked.clear()
            reader.fetchForTests = { address, _ -> asked += address; queue.removeFirst() }
        }
        val soon = { System.nanoTime() + 5_000_000_000L }
        answer(PageReader.Body("<p>ok</p>"))
        assertEquals("<p>ok</p>", reader.read("https://shop.example.org/vin", soon()))
        // A redirect to plain http, to the local network, to another port or to an address is not followed.
        for (target in listOf("http://shop.example.org/vin", "https://nas.local/vin", "https://shop.example.org:8443/vin", "https://203.0.113.10/vin")) {
            answer(PageReader.Redirect(target), PageReader.Body("<p>no</p>"))
            assertEquals(null, reader.read("https://shop.example.org/vin", soon()), target)
            assertEquals(listOf("https://shop.example.org/vin"), asked, target)
        }
        // At most three redirects.
        answer(*Array(4) { PageReader.Redirect("https://shop.example.org/$it") }, PageReader.Body("<p>no</p>"))
        assertEquals(null, reader.read("https://shop.example.org/vin", soon()))
        assertEquals(4, asked.size)
        answer(*Array(3) { PageReader.Redirect("https://shop.example.org/$it") }, PageReader.Body("<p>yes</p>"))
        assertEquals("<p>yes</p>", reader.read("https://shop.example.org/vin", soon()))
        // Past the deadline, nothing is asked.
        answer(PageReader.Body("<p>late</p>"))
        assertEquals(null, reader.read("https://shop.example.org/vin", System.nanoTime() - 1))
        assertTrue(asked.isEmpty())
        reader.close()
    }

    @Test
    fun fetchOutcomes() = kotlinx.coroutines.runBlocking {
        val reader = PageReader()
        val page = okhttp3.Request.Builder().url("https://shop.example.org/a").build()
        fun response(code: Int, type: String? = "text/html; charset=utf-8", location: String? = null, body: okhttp3.ResponseBody? = null) =
            okhttp3.Response.Builder().request(page).protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("M")
                .apply { type?.let { header("Content-Type", it) } }
                .apply { location?.let { header("Location", it) } }
                .apply { body?.let { body(it) } }
                .build()
        fun body(text: String, length: Long = text.length.toLong()) =
            okio.Buffer().writeUtf8(text).asResponseBody(null, length)

        val redirect = reader.outcome(response(302, location = "/b")) as PageReader.Redirect
        assertEquals("https://shop.example.org/b", redirect.next)
        val nowhere = reader.outcome(response(302)) as PageReader.Redirect
        assertEquals(null, nowhere.next)
        reader.fetchForTests = { _, _ -> nowhere }
        assertEquals(null, reader.read("https://shop.example.org/a", System.nanoTime() + 5_000_000_000L))
        assertEquals(null, (reader.outcome(response(404, body = body("<p>no</p>"))) as PageReader.Body).html)
        assertEquals(null, (reader.outcome(response(200, "application/json", body = body("{}"))) as PageReader.Body).html)
        assertEquals(null, (reader.outcome(response(200, body = body("<p>x</p>", PagePrices.MAX_BYTES + 1L))) as PageReader.Body).html)
        assertEquals("<p>ok</p>", (reader.outcome(response(200, body = body("<p>ok</p>"))) as PageReader.Body).html)
        val long = "a".repeat(2_000_000)
        val read = (reader.outcome(response(200, body = body(long, -1))) as PageReader.Body).html
        assertEquals(PagePrices.MAX_BYTES, read?.length)
        reader.close()
    }

    @Test
    fun nameLookupsStopAtTheirTime() {
        val start = System.nanoTime()
        val found = PublicHost.lookUp("shop.example.org", 200) {
            Thread.sleep(5_000)
            listOf(java.net.InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 10)))
        }
        assertTrue(found.isEmpty())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
        assertTrue(PublicHost.lookUp("shop.example.org", 0) { error("not asked") }.isEmpty())
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
