package app.encave.core.search

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Reads the price of a bottle on the web pages a price search found, the Kotlin port of
 * CaveKit's PagePrices.swift. After the search, the app opens the first result pages from
 * the device ([PageReader]: no cookies, cache, credentials or Referer) and reads them here,
 * on the device. Both apps are checked with fixtures/prices.
 *
 * Pages come from anyone: the reading is linear in the page's size (no regular expression
 * runs on the HTML), stops at [MAX_PARSED_CHARS], and checks shouldStop between steps. Any
 * text, cut anywhere, is read without failing (fixtures and fuzz tests on both platforms).
 */
object PagePrices {
    /** The first results opened, in their order. */
    const val MAX_PAGES = 5

    /** Pages read at the same time. */
    const val PARALLEL_PAGES = 3

    /** Reading stops after this many bytes; the price is near the top of a product page. */
    const val MAX_BYTES = 1_500_000

    /** At most this much of the page is parsed (UTF-16 units): all that was read, since product data often comes after a lot of inline styles. */
    internal const val MAX_PARSED_CHARS = MAX_BYTES

    /** For the whole page: name lookup, redirects and reading. */
    const val TIMEOUT_MS = 8_000
    const val MAX_REDIRECTS = 3

    /** The results whose pages are opened: the first [MAX_PAGES], if their address may be. */
    fun pages(results: List<WebSearchResult>): List<WebSearchResult> = results.take(MAX_PAGES).filter { isAllowed(it.url) }

    /**
     * https on the usual port, no user or password, and a public name: never an IP address,
     * this device or the local network (a router, a NAS), whatever the search results say.
     * Redirects are checked too.
     */
    fun isAllowed(url: String): Boolean {
        val address = WebImageDownload.httpsUrl(url) ?: return false
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val name = PublicHost.bare(uri.host ?: return false)
        return !PublicHost.isAddress(name) && PublicHost.mayBePublic(name)
    }

    /** True when every address a name resolves to is public ([PublicHost.isPublicAddress]). */
    fun arePublic(addresses: List<String>): Boolean = PublicHost.arePublic(addresses)

    /** Resolves the name and checks its addresses ([arePublic]). */
    fun resolvesPublicly(url: String, resolver: (String) -> List<String> = PublicHost::systemAddresses): Boolean {
        if (!isAllowed(url)) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return PublicHost.resolvesPublicly(host, resolver)
    }

    /** "text/html; charset=utf-8" or XHTML; anything else is not read. */
    fun isHtml(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return type == "text/html" || type == "application/xhtml+xml"
    }

    /** False when the server announces more than [MAX_BYTES] (-1: unknown, capped while reading). */
    fun acceptsLength(length: Long): Boolean = length <= MAX_BYTES

    /** The page's text, in the character set the server or the page names (UTF-8 otherwise). */
    fun text(bytes: ByteArray, contentType: String?): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
        val charset = charsetName(contentType.orEmpty()) ?: charsetName(head)
        val decoded = when (charset) {
            "iso-8859-1", "latin1", "iso8859-1", "us-ascii", "ascii" -> Charsets.ISO_8859_1
            "windows-1252", "cp1252", "iso-8859-15", "latin-9" -> runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.UTF_8)
            else -> Charsets.UTF_8
        }
        return String(bytes, decoded)
    }

    private val charsetPattern = Regex("""charset\s*=\s*["']?([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)

    internal fun charsetName(text: String): String? = charsetPattern.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    // Prices on a page

    /**
     * Prices of one bottle of [sizeML] on a result's page, most trusted first: schema.org data
     * (JSON-LD), then the product meta tags, then microdata, then the visible text near the
     * wine's name. A structured amount the text shows for a case (or 6 or 12 times a price of
     * one bottle there) is left out, and the text's price is used. With [vintage], prices
     * named for another vintage are left out. Each keeps the result's site and address. Empty
     * once [shouldStop] is true.
     */
    fun prices(
        html: String,
        result: WebSearchResult,
        sizeML: Int,
        wineName: String? = null,
        vintage: Int? = null,
        shouldStop: () -> Boolean = { false },
    ): List<FoundPrice> {
        val document = HtmlScanner.scan(html, shouldStop) ?: return emptyList()
        if (shouldStop()) return emptyList()
        val page = Page(document, wineName)
        if (shouldStop()) return emptyList()
        val text = page.textReading(sizeML, vintage)
        if (shouldStop()) return emptyList()
        var found = text.items
        page.structuredPrices(sizeML, vintage, shouldStop)?.let { structured ->
            val kept = withoutCasePrices(structured, text)
            if (kept.isNotEmpty() || text.items.isEmpty()) found = kept
        }
        if (shouldStop()) return emptyList()
        return found.map { FoundPrice(it.amount, it.currency, result.domain, result.url, it.excerpt) }.distinctBy { it.id }
    }

    /** [prices] with those of the pages added, without the ones already found at the same address. */
    fun merging(pagePrices: List<FoundPrice>, prices: List<FoundPrice>): List<FoundPrice> {
        val ids = prices.map { it.id }.toMutableSet()
        return prices + pagePrices.filter { ids.add(it.id) }
    }

    /**
     * A schema.org, meta or microdata amount: digits with an optional decimal point ("13",
     * "13.90"), rounded to the cent. "13,90", "1e3" or "1.234,50" are not read.
     */
    internal fun structuredAmount(text: String): BigDecimal? {
        val value = text.trim()
        val parts = value.split('.')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.length > 9 || !it.all { c -> c in '0'..'9' } }) return null
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
    }

    /** "13.90", "13.00": an amount with two decimals, the same on both platforms. */
    internal fun twoDecimals(amount: BigDecimal): String = amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    internal data class Item(val amount: BigDecimal, val currency: String, val excerpt: String)

    /** The prices of the text near the wine's name. */
    internal class TextReading {
        /** Prices of one bottle, at most [MAX_TEXT_PRICES]. */
        var items: List<Item> = emptyList()

        /** Amounts and currencies of one bottle (all of them), and of several bottles, a case or delivery. */
        val oneBottle = mutableListOf<Pair<BigDecimal, String>>()
        val notOneBottle = mutableListOf<Pair<BigDecimal, String>>()
    }

    /** How far before a price (UTF-16 units) a vintage counts as its own. */
    private const val VINTAGE_BEFORE = 150

    /** How far after a price, on its line, a vintage counts as its own when none is before it. */
    private const val VINTAGE_AFTER = 60

    /** Years 1900 to 2099 written as four digits on their own in `text[start, end)`. */
    internal fun years(text: String, start: Int, end: Int): Set<Int> {
        val found = mutableSetOf<Int>()
        var index = maxOf(0, start)
        val stopAt = minOf(end, text.length)
        while (index < stopAt) {
            if (text[index] !in '0'..'9') {
                index++
                continue
            }
            var stop = index
            while (stop < stopAt && text[stop] in '0'..'9') stop++
            val before = if (index > 0) text[index - 1] else ' '
            val after = if (stop < text.length) text[stop] else ' '
            if (stop - index == 4 && before !in '0'..'9' && after !in '0'..'9') {
                val value = text.substring(index, stop).toInt()
                if (value in 1900..2099) found += value
            }
            index = stop
        }
        return found
    }

    internal fun years(text: String): Set<Int> = text.take(300).let { years(it, 0, it.length) }

    /**
     * Without the structured amounts that the text near the wine's name shows for several
     * bottles, or that are 6 or 12 times (±2 %) a price of one bottle there: a case.
     */
    internal fun withoutCasePrices(items: List<Item>, text: TextReading): List<Item> {
        val tolerance = BigDecimal("0.02")
        return items.filter { item ->
            if (text.notOneBottle.any { it.first.compareTo(item.amount) == 0 && it.second == item.currency }) return@filter false
            text.oneBottle.none { (amount, currency) ->
                currency == item.currency && listOf(6, 12).any { count ->
                    val expected = amount.multiply(BigDecimal(count))
                    item.amount.subtract(expected).abs() <= expected.multiply(tolerance)
                }
            }
        }
    }

    private val currencies = setOf("EUR", "USD", "GBP", "CHF")

    /** Text prices kept from one page at most: related products and the like are not wanted. */
    private const val MAX_TEXT_PRICES = 3

    /** How far after the wine's name (in UTF-16 units) a price or a size counts as near it. */
    private const val NEAR_NAME = 400

    /** Places of the wine's name looked at, at most. */
    private const val MAX_AREAS = 20

    /** Without the wine's name on the page, only the start of its text is looked at. */
    private const val MAX_TEXT_WITHOUT_NAME = 50_000

    internal class Page(private val document: HtmlDocument, wineName: String?) {
        val text: String = document.text
        private val heading: String? = document.heading
        private val title: String? =
            document.meta.firstOrNull { it.first == "og:title" }?.second?.let(Html::cleanLine) ?: document.title

        /** Where to look in the text: after the places the wine's name is, or the start of it ([start, end)). */
        private val areas: List<IntRange> = areas(text, wineName)

        /** The one size named near the wine's name, if only one is. */
        private val areaSize: Pair<Int, String>? = sizeIn(text, areas)

        /** The product's name, as the page shows it. */
        private val label: String? get() = heading ?: title

        private fun areas(text: String, name: String?): List<IntRange> {
            val start = listOf(0 until minOf(text.length, MAX_TEXT_WITHOUT_NAME))
            val cleanName = Normalizer.normalize(name.orEmpty().trim(), Normalizer.Form.NFC)
            val needle = Fold.units(cleanName.take(100))
            if (needle.length < 3) return start
            val haystack = Fold.units(text)
            val found = mutableListOf<IntRange>()
            var index = haystack.indexOf(needle)
            // At most MAX_AREAS × NEAR_NAME units in all, however often the name appears.
            var covered = 0
            while (index >= 0 && found.size < MAX_AREAS && covered < MAX_AREAS * NEAR_NAME) {
                val end = minOf(text.length, index + NEAR_NAME)
                val last = found.lastOrNull()
                // Places close together make one area.
                if (last != null && index <= last.last + 1) {
                    covered += end - (last.last + 1)
                    found[found.size - 1] = last.first until end
                } else {
                    covered += end - index
                    found += index until end
                }
                index = haystack.indexOf(needle, index + needle.length)
            }
            return found.ifEmpty { start }
        }

        private fun sizeIn(text: String, areas: List<IntRange>): Pair<Int, String>? {
            val sizes = linkedMapOf<Int, String>()
            for (area in areas) {
                if (area.isEmpty()) continue
                val piece = text.substring(area.first, area.last + 1)
                for ((size, pattern) in PriceExtractor.sizePatterns) {
                    if (size in sizes) continue
                    pattern.find(piece)?.let { sizes[size] = it.value }
                }
            }
            return sizes.entries.singleOrNull()?.let { it.key to it.value }
        }

        /** The size of what the page sells: named in [names] (the product, the offer), its heading, or near the wine's name. */
        private fun size(names: List<String?>): Pair<Int, String>? {
            for (name in names + listOf(heading, title)) {
                name?.let { PriceExtractor.sizeWords(it.take(300)) }?.let { return it }
            }
            return areaSize
        }

        private fun excerpt(name: String?, price: String, size: String?): String {
            val parts = mutableListOf<String>()
            (name ?: label)?.let(Html::cleanLine)?.let { parts += it.take(100) }
            parts += price
            if (size != null && parts.none { it.contains(size, ignoreCase = true) }) parts += size
            return parts.joinToString(" · ")
        }

        // Structured data

        /** Null when the page has no structured price for one bottle of the size. */
        fun structuredPrices(sizeML: Int, vintage: Int?, shouldStop: () -> Boolean): List<Item>? {
            for (step in 0 until 3) {
                if (shouldStop()) return emptyList()
                val items = when (step) {
                    0 -> jsonLdPrices(sizeML, vintage, shouldStop)
                    1 -> metaPrices(sizeML, vintage)
                    else -> microdataPrices(sizeML, vintage)
                }
                if (items.isNotEmpty()) return items
            }
            return null
        }

        private fun item(
            amount: String,
            currency: String?,
            names: List<String?>,
            label: String?,
            sizeML: Int,
            vintage: Int?,
            vintageNames: List<String?>,
        ): Item? {
            val code = currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in currencies } ?: return null
            val value = structuredAmount(amount)?.takeIf { PriceExtractor.inRange(it) } ?: return null
            // A product named for another vintage.
            if (vintage != null) {
                val years = vintageNames.filterNotNull().flatMap { years(it) }.toSet()
                if (years.isNotEmpty() && vintage !in years) return null
            }
            // A case, a lot or a pack is not one bottle.
            if (names.any { it != null && PriceExtractor.notOneBottleBefore.containsMatchIn(it.take(300)) }) return null
            val size = size(names)
            if ((size?.first ?: 750) != sizeML) return null
            return Item(value, code, excerpt(label, "${twoDecimals(value)} $code", size?.second))
        }

        private fun jsonLdPrices(sizeML: Int, vintage: Int?, shouldStop: () -> Boolean): List<Item> {
            val items = mutableListOf<Item>()
            for (script in document.jsonLd) {
                if (shouldStop()) return emptyList()
                val root = runCatching { Json.parseToJsonElement(JsonLd.withoutExponents(script)) }.getOrNull() ?: continue
                for (product in JsonLd.products(root)) {
                    val name = JsonLd.string(product["name"])
                    for (offer in JsonLd.offers(product)) {
                        if ((offer.quantity ?: 1.0) > 1) continue
                        item(offer.amount, offer.currency, listOf(offer.name, name), name, sizeML, vintage, listOf(offer.name, name))
                            ?.let { items += it }
                    }
                }
            }
            return items
        }

        private fun metaPrices(sizeML: Int, vintage: Int?): List<Item> {
            fun value(key: String) = document.meta.firstOrNull { it.first == key }?.second
            for (prefix in listOf("product:price:", "og:price:")) {
                val amount = value(prefix + "amount") ?: continue
                item(amount, value(prefix + "currency"), emptyList(), null, sizeML, vintage, listOf(label))?.let { return listOf(it) }
            }
            return emptyList()
        }

        /** The first itemprop="price" of the page, with the first itemprop="priceCurrency". */
        private fun microdataPrices(sizeML: Int, vintage: Int?): List<Item> {
            val amount = document.itemPrice ?: return emptyList()
            return listOfNotNull(item(amount, document.itemCurrency, emptyList(), null, sizeML, vintage, listOf(label)))
        }

        // Visible text

        /**
         * The prices near the wine's name. With [vintage], a price whose own words (before it,
         * else after it on its line) name another vintage is left out, and none is kept when the
         * text names several vintages but not this one.
         */
        fun textReading(sizeML: Int, vintage: Int?): TextReading {
            val size = size(emptyList())
            val reading = TextReading()
            var otherVintagesOnly = false
            if (vintage != null) {
                val seen = mutableSetOf<Int>()
                for (area in areas) if (!area.isEmpty()) seen += years(text, area.first, area.last + 1)
                otherVintagesOnly = seen.size > 1 && vintage !in seen
            }
            val prices = mutableListOf<PriceExtractor.TextPrice>()
            for (area in areas) {
                if (area.isEmpty()) continue
                // A little text around the area, for the words next to a price.
                val start = maxOf(0, area.first - 40)
                val end = minOf(text.length, area.last + 1 + 30)
                val all = PriceExtractor.allTextPrices(text.substring(start, end), sizeML, defaultSize = size?.first ?: 750)
                for ((index, price) in all.withIndex()) {
                    if (start + price.location !in area) continue
                    when (price.kind) {
                        PriceExtractor.Kind.OtherSize -> continue
                        PriceExtractor.Kind.NotOneBottle -> {
                            reading.notOneBottle += price.amount to price.currency
                            continue
                        }
                        PriceExtractor.Kind.OneBottle -> Unit
                    }
                    if (vintage != null) {
                        if (otherVintagesOnly) continue
                        val location = start + price.location
                        val priceEnd = location + price.length
                        val previousEnd = if (index > 0) start + all[index - 1].location + all[index - 1].length else 0
                        var found = years(text, maxOf(area.first, previousEnd, location - VINTAGE_BEFORE), location)
                        if (found.isEmpty()) {
                            var stop = minOf(text.length, priceEnd + VINTAGE_AFTER)
                            if (index + 1 < all.size) stop = minOf(stop, start + all[index + 1].location)
                            // Only up to `stop`: a page's text is never searched to its end for each price.
                            for (at in priceEnd until stop) {
                                if (text[at] == '\n') {
                                    stop = at
                                    break
                                }
                            }
                            found = years(text, priceEnd, stop)
                        }
                        if (found.isNotEmpty() && vintage !in found) continue
                    }
                    reading.oneBottle += price.amount to price.currency
                    prices += price
                }
            }
            reading.items = prices.take(MAX_TEXT_PRICES).map { price ->
                val parts = mutableListOf<String>()
                label?.let(Html::cleanLine)?.takeIf { !price.excerpt.contains(it, ignoreCase = true) }?.let { parts += it.take(100) }
                parts += price.excerpt
                val words = size?.second
                if (price.namedSize == null && words != null && parts.none { it.contains(words, ignoreCase = true) }) parts += words
                Item(price.amount, price.currency, parts.joinToString(" · "))
            }
            return reading
        }
    }
}

/** Whether a name or an address leads outside this device and the local network. Used for the result pages and for pictures from the web. */
object PublicHost {
    /** Lowercased, without brackets or a final dot. */
    internal fun bare(host: String): String = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')

    /** IPv6, or a name whose last label is a number ("203.0.113.10", "2130706433", "0x7f.1"). */
    internal fun isAddress(host: String): Boolean {
        if (host.contains(':')) return true
        val last = host.substringAfterLast('.')
        return last.all { it in '0'..'9' } || last.startsWith("0x")
    }

    /** A name that may be public: it has a dot (a name without one is completed with the local network's domain) and is not localhost, .local or .home.arpa. */
    internal fun mayBePublic(name: String): Boolean = name.contains('.') && !WebImageDownload.isPrivateHost(name)

    /** True when every address is public; false for none. */
    fun arePublic(addresses: List<String>): Boolean = addresses.isNotEmpty() && addresses.all(::isPublicAddress)

    /** Resolves [host] (a name, or an address used as is) and checks every address. */
    fun resolvesPublicly(host: String, resolver: (String) -> List<String> = ::systemAddresses): Boolean {
        val name = bare(host)
        if (isAddress(name)) return isPublicAddress(name)
        if (!mayBePublic(name)) return false
        return arePublic(runCatching { resolver(name) }.getOrDefault(emptyList()))
    }

    /** A numeric IPv4 or IPv6 address (a zone such as "%en0" is ignored) that is public, read by its bytes ([isPublic]). False for anything that is not an address. */
    fun isPublicAddress(text: String): Boolean {
        val address = bare(text).substringBefore('%')
        return isPublic(literalBytes(address) ?: return false)
    }

    /** The bytes of a numeric address; null for a name. Never looks anything up. */
    internal fun literalBytes(address: String): ByteArray? {
        if (address.isEmpty()) return null
        if (!address.contains(':')) {
            val parts = address.split('.')
            if (parts.size != 4) return null
            val numbers = parts.map { part -> part.takeIf { it.length in 1..3 && it.all { c -> c in '0'..'9' } }?.toInt()?.takeIf { it <= 255 } ?: return null }
            return ByteArray(4) { numbers[it].toByte() }
        }
        // Only hex digits, colons and the dots of an embedded IPv4: InetAddress then parses it as a literal.
        if (!address.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        val parsed = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
        // Java turns "::ffff:a.b.c.d" into the IPv4 address itself, checked the same way.
        return parsed.address
    }

    /**
     * 4 or 16 bytes. Not public: this network, private, shared (carrier-grade NAT), loopback,
     * link-local, benchmark, multicast and reserved IPv4; unspecified, loopback, unique local,
     * link-local, site-local and multicast IPv6; Teredo and local NAT64; and the IPv4 address
     * inside a mapped, compatible, NAT64 or 6to4 address is checked.
     */
    fun isPublic(bytes: ByteArray): Boolean {
        val b = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        if (b.size == 4) return isPublicIpv4(b[0], b[1], b[2])
        if (b.size != 16) return false
        fun v4(at: Int) = isPublicIpv4(b[at], b[at + 1], b[at + 2])
        fun zeros(range: IntRange) = range.all { b[it] == 0 }
        if (zeros(0..9) && b[10] == 0xFF && b[11] == 0xFF) return v4(12)
        if (zeros(0..11)) return v4(12)
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B) {
            if (zeros(4..11)) return v4(12)
            if (b[4] == 0x00 && b[5] == 0x01) return false
        }
        if (b[0] == 0x20 && b[1] == 0x02) return v4(2)
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return false
        if (b[0] and 0xFE == 0xFC) return false
        if (b[0] == 0xFE && b[1] and 0x80 != 0) return false
        if (b[0] == 0xFF) return false
        return true
    }

    private fun isPublicIpv4(a: Int, b: Int, c: Int): Boolean = !(
        a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && c == 0) ||
            (a == 198 && (b == 18 || b == 19))
        )

    private val lookups = Executors.newCachedThreadPool { runnable -> Thread(runnable, "encave-public-dns").apply { isDaemon = true } }

    /** The system's addresses for [host], within [timeoutMs]; a lookup that takes longer counts as none. */
    fun lookUp(host: String, timeoutMs: Long): List<InetAddress> = lookUp(host, timeoutMs) { InetAddress.getAllByName(it).toList() }

    internal fun lookUp(host: String, timeoutMs: Long, resolve: (String) -> List<InetAddress>): List<InetAddress> {
        if (timeoutMs <= 0) return emptyList()
        val future = lookups.submit<List<InetAddress>> { resolve(host) }
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrElse {
            future.cancel(true)
            emptyList()
        }
    }

    internal fun systemAddresses(host: String): List<String> = lookUp(host, PagePrices.TIMEOUT_MS.toLong()).mapNotNull { it.hostAddress }
}

/** What a page holds for prices. */
internal class HtmlDocument {
    /** The text a reader sees, a line per block (paragraph, cell, item), entities decoded. */
    var text = ""
    var heading: String? = null
    var title: String? = null
    val jsonLd = mutableListOf<String>()

    /** (property or name, content) of each meta tag, keys lowercased. */
    val meta = mutableListOf<Pair<String, String>>()

    /** The first itemprop="price" and itemprop="priceCurrency": content, or the text after the tag. */
    var itemPrice: String? = null
    var itemCurrency: String? = null
}

/**
 * Reads a page in one pass, without regular expressions: the time grows with the page's size
 * only. A tag, comment or script that does not end stops the reading there.
 */
internal object HtmlScanner {
    private val blocks = setOf(
        "p", "div", "li", "ul", "ol", "tr", "td", "th", "table", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article",
        "header", "footer", "nav", "main", "aside", "form", "dt", "dd", "dl", "blockquote", "figure", "figcaption", "option", "br", "hr",
    )

    /** Elements whose content is not text to read (scripts, styles, and so on). */
    private val raw = setOf("script", "style", "noscript", "template", "svg", "textarea", "select", "title", "iframe")

    fun scan(html: String, shouldStop: () -> Boolean): HtmlDocument? {
        val s = if (html.length > PagePrices.MAX_PARSED_CHARS) html.substring(0, PagePrices.MAX_PARSED_CHARS) else html
        val n = s.length
        val document = HtmlDocument()
        val out = StringBuilder(minOf(n, 64 * 1024))
        var headingStart = -1
        var headingDone = false
        var index = 0
        var tags = 0

        // Raw elements found not to end: later ones cannot end either, so they are not looked for again.
        val unclosed = HashSet<String>()

        fun heading(start: Int) = Html.cleanLine(out.substring(start, minOf(out.length, start + 1000)))

        scanning@ while (index < n) {
            val open = s.indexOf('<', index)
            if (open < 0) {
                out.append(s, index, n)
                break
            }
            out.append(s, index, open)
            tags += 1
            if (tags % 512 == 0 && shouldStop()) return null
            val next = if (open + 1 < n) s[open + 1] else '\u0000'
            // A comment ends at "-->"; a declaration or "<?…" at ">".
            if (next == '!' || next == '?') {
                if (next == '!' && open + 3 < n && s[open + 2] == '-' && s[open + 3] == '-') {
                    val end = s.indexOf("-->", open + 4)
                    if (end < 0) break@scanning
                    index = end + 3
                } else {
                    val end = s.indexOf('>', open + 1)
                    if (end < 0) break@scanning
                    index = end + 1
                }
                continue
            }
            val closing = next == '/'
            val nameStart = if (closing) open + 2 else open + 1
            if (nameStart >= n || !isLetter(s[nameStart])) {
                // "a < b": text.
                out.append('<')
                index = open + 1
                continue
            }
            val end = s.indexOf('>', nameStart)
            if (end < 0) break@scanning
            var nameEnd = nameStart
            while (nameEnd < end && isNameChar(s[nameEnd])) nameEnd += 1
            val name = s.substring(nameStart, nameEnd).lowercase(Locale.ROOT)
            index = end + 1

            if (closing) {
                if (name == "h1" && headingStart >= 0 && !headingDone) {
                    document.heading = heading(headingStart)
                    headingDone = true
                    headingStart = -1
                }
                out.append(if (name in blocks) '\n' else ' ')
                continue
            }

            val attributes = parseAttributes(s, nameEnd, end)
            if (name in raw) {
                // The content runs to "</name".
                if (end > nameStart && s[end - 1] == '/') continue
                // A script or style that does not end runs to the end of the page; another
                // element that does not end is only a tag, and the rest is read.
                val close = if (name in unclosed) -1 else findClose(s, name, end + 1)
                if (close < 0) {
                    if (name == "script" || name == "style") break@scanning
                    unclosed += name
                    out.append(' ')
                    continue
                }
                val content = s.substring(end + 1, close)
                val type = attributes["type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (name == "script" && type == "application/ld+json") {
                    document.jsonLd += content
                } else if (name == "title" && document.title == null) {
                    document.title = Html.cleanLine(content)
                }
                val closeEnd = s.indexOf('>', close)
                if (closeEnd < 0) break@scanning
                index = closeEnd + 1
                out.append(' ')
                continue
            }
            if (name == "meta") {
                val key = attributes["property"] ?: attributes["name"]
                val content = attributes["content"]
                if (key != null && content != null) document.meta += key.lowercase(Locale.ROOT) to content
            }
            attributes["itemprop"]?.lowercase(Locale.ROOT)?.let { itemprop ->
                val props = itemprop.split(' ')
                fun value(): String? {
                    attributes["content"]?.let { return it.trim() }
                    val stop = s.indexOf('<', end + 1).let { if (it < 0) n else it }
                    return Html.number(s.substring(end + 1, minOf(stop, end + 1 + 200)))
                }
                if (document.itemPrice == null && "price" in props) document.itemPrice = value()
                if (document.itemCurrency == null && "pricecurrency" in props) document.itemCurrency = value()
            }
            if (name == "h1" && headingStart < 0 && !headingDone) headingStart = out.length
            out.append(if (name in blocks) '\n' else ' ')
        }
        if (headingStart >= 0 && !headingDone) document.heading = heading(headingStart)
        if (shouldStop()) return null
        document.text = Html.lines(out.toString())
        return document
    }

    private fun isLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    private fun isNameChar(c: Char) = isLetter(c) || c in '0'..'9' || c == '-' || c == ':' || c == '_'

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    /** The start of the first "</name" (any case, followed by a non-name character) after [start]; -1 if none. */
    private fun findClose(s: String, name: String, start: Int): Int {
        var k = start
        // "</" and the name must fit; the end of the text counts as a non-name character.
        while (k + 2 + name.length <= s.length) {
            val after = k + 2 + name.length
            if (s[k] == '<' && s[k + 1] == '/' && s.regionMatches(k + 2, name, 0, name.length, ignoreCase = true) &&
                (after == s.length || !isNameChar(s[after]))
            ) {
                return k
            }
            k += 1
        }
        return -1
    }

    /** The attributes of a tag (between its name and its ">"), names lowercased, values with entities decoded. The first of a name is kept. */
    private fun parseAttributes(s: String, start: Int, end: Int): Map<String, String> {
        val values = HashMap<String, String>()
        var k = start
        while (k < end) {
            while (k < end && (isSpace(s[k]) || s[k] == '/')) k += 1
            val nameStart = k
            while (k < end && !isSpace(s[k]) && s[k] != '=' && s[k] != '/' && s[k] != '>') k += 1
            if (k == nameStart) {
                k += 1
                continue
            }
            val name = s.substring(nameStart, k).lowercase(Locale.ROOT)
            while (k < end && isSpace(s[k])) k += 1
            var value = ""
            if (k < end && s[k] == '=') {
                k += 1
                while (k < end && isSpace(s[k])) k += 1
                if (k < end && (s[k] == '"' || s[k] == '\'')) {
                    val mark = s[k]
                    val valueStart = k + 1
                    k = valueStart
                    while (k < end && s[k] != mark) k += 1
                    value = s.substring(valueStart, k)
                    k += 1
                } else {
                    val valueStart = k
                    while (k < end && !isSpace(s[k])) k += 1
                    value = s.substring(valueStart, k)
                }
            }
            if (name !in values) values[name] = WebSearchText.decodeEntities(value)
        }
        return values
    }
}

internal object Html {
    /** Spaces of the text, no-break and narrow ones too, collapsed to one; line breaks kept. */
    private fun isSpace(c: Char) = when (c) {
        ' ', '\t', '\r', '\u000C', '\u000B', ' ', ' ', ' ', ' ', ' ', '　' -> true
        else -> false
    }

    /** Entities decoded, spaces collapsed, one line per block, no empty line. */
    fun lines(raw: String): String {
        val text = Normalizer.normalize(WebSearchText.decodeEntities(raw), Normalizer.Form.NFC)
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var lineHasText = false
        for (c in text) {
            when {
                c == '\n' -> {
                    if (lineHasText) out.append('\n')
                    lineHasText = false
                    pendingSpace = false
                }
                isSpace(c) -> pendingSpace = lineHasText
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    lineHasText = true
                    out.append(c)
                }
            }
        }
        if (out.endsWith('\n')) out.setLength(out.length - 1)
        return out.toString()
    }

    /** One line of plain text, or null if empty. */
    fun cleanLine(text: String): String? = lines(WebSearchText.plain(text).replace('\n', ' ')).ifEmpty { null }

    /** "Fr. 13.90" or "13,90 €": the number only (the first one). */
    fun number(html: String): String? {
        val text = WebSearchText.plain(html)
        val start = text.indexOfFirst { it in '0'..'9' }
        if (start < 0) return text.ifEmpty { null }
        var end = start
        while (end < text.length && (text[end] in '0'..'9' || text[end] in " .,'’")) end += 1
        return text.substring(start, end).trim()
    }

    /** The visible text of a page (for tests and other readers). */
    fun visibleText(html: String): String = HtmlScanner.scan(html) { false }?.text.orEmpty()
}

internal object JsonLd {
    /**
     * The JSON with each number written with an exponent ("1e3") replaced by null: such a
     * number is never read as a price, on either platform. One pass; strings are kept as they are.
     */
    fun withoutExponents(json: String): String {
        val out = StringBuilder(json.length)
        var k = 0
        while (k < json.length) {
            val c = json[k]
            if (c == '"') {
                val start = k
                k += 1
                while (k < json.length && json[k] != '"') k += if (json[k] == '\\') 2 else 1
                k = minOf(k + 1, json.length)
                out.append(json, start, k)
            } else if (c == '-' || c in '0'..'9') {
                val start = k
                var exponent = false
                while (k < json.length && (json[k] in '0'..'9' || json[k] in "-+.eE")) {
                    if (json[k] == 'e' || json[k] == 'E') exponent = true
                    k += 1
                }
                if (exponent) out.append("null") else out.append(json, start, k)
            } else {
                out.append(c)
                k += 1
            }
        }
        return out.toString()
    }

    data class Offer(val amount: String, val currency: String?, val name: String?, val quantity: Double?)

    private val productTypes = setOf("product", "productgroup", "individualproduct", "productmodel", "wine")

    /** Products at the top, in arrays, in @graph or as the page's main entity, and their variants. */
    fun products(node: JsonElement, depth: Int = 0): List<JsonObject> {
        if (depth >= 6) return emptyList()
        if (node is JsonArray) return node.take(200).flatMap { products(it, depth + 1) }
        if (node !is JsonObject) return emptyList()
        val found = mutableListOf<JsonObject>()
        if (types(node).any { it in productTypes } || "offers" in node) found += node
        for (key in listOf("@graph", "mainEntity", "hasVariant")) node[key]?.let { found += products(it, depth + 1) }
        return found
    }

    private fun types(node: JsonObject): List<String> {
        val names = when (val value = node["@type"]) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> listOfNotNull(value.takeIf { it.isString }?.content)
            else -> emptyList()
        }
        return names.map { it.substringAfterLast('/').lowercase(Locale.ROOT) }
    }

    private fun list(value: JsonElement?): List<JsonObject> = when (value) {
        is JsonArray -> value.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(value)
        else -> emptyList()
    }

    /**
     * The offers of a product: price, currency, name and quantity, in their price
     * specifications or in an aggregate offer's own offers when needed. An aggregate offer's
     * lowPrice counts only for a single price (lowPrice = highPrice, or one offer).
     */
    fun offers(product: JsonObject): List<Offer> {
        val found = mutableListOf<Offer>()
        fun read(node: JsonObject, depth: Int) {
            var amount = string(node["price"])
            var currency = string(node["priceCurrency"])
            if (amount == null) {
                string(node["lowPrice"])?.let { low ->
                    val lowAmount = PagePrices.structuredAmount(low)
                    val highAmount = string(node["highPrice"])?.let(PagePrices::structuredAmount)
                    val single = lowAmount != null && (highAmount?.compareTo(lowAmount) == 0 || string(node["offerCount"]) == "1")
                    if (single) amount = low
                }
            }
            val specification = list(node["priceSpecification"]).firstOrNull { string(it["price"]) != null }
            if (amount == null && specification != null) amount = string(specification["price"])
            if (currency == null && specification != null) currency = string(specification["priceCurrency"])
            val price = amount
            if (price != null) {
                found += Offer(price, currency, string(node["name"]), quantity(node["eligibleQuantity"]))
            } else if (depth < 2) {
                list(node["offers"]).take(50).forEach { read(it, depth + 1) }
            }
        }
        list(product["offers"]).take(50).forEach { read(it, 0) }
        return found
    }

    /** Text or a number as text ("13.00", 13.5 → "13.5"; a number written with an exponent is not read as a price later). */
    fun string(value: JsonElement?): String? = when (value) {
        // true and false are not amounts: the strict reading leaves them out.
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> value.firstNotNullOfOrNull { string(it) }
        else -> null
    }

    private fun quantity(value: JsonElement?): Double? = when (value) {
        is JsonObject -> quantity(value["value"])
        is JsonPrimitive -> value.contentOrNull?.toDoubleOrNull()
        else -> null
    }
}

/** Lowercase without accents, one UTF-16 unit for each, so offsets match the text's. */
internal object Fold {
    fun units(text: String): String {
        val cache = HashMap<Char, Char>()
        return buildString(text.length) {
            for (unit in text) {
                append(
                    when {
                        unit.code < 0x80 -> if (unit in 'A'..'Z') unit + 32 else unit
                        unit == '’' || unit == '‘' -> '\''
                        unit.isSurrogate() -> unit
                        else -> cache.getOrPut(unit) { Normalizer.normalize(unit.toString(), Normalizer.Form.NFD)[0].lowercaseChar() }
                    },
                )
            }
        }
    }
}
