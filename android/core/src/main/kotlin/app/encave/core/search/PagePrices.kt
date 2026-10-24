package app.encave.core.search

import java.math.BigDecimal
import java.net.InetAddress
import java.net.URI
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Reads the price of a bottle on the web pages a price search found, the Kotlin port of
 * CaveKit's PagePrices.swift. After the search, the app opens the first result pages from
 * the device (no cookies, cache, credentials or Referer) and reads them here, on the device:
 * the sites see the device's address and the page asked for, nothing else. Both apps are
 * checked with fixtures/prices.
 */
object PagePrices {
    /** The first results opened, in their order. */
    const val MAX_PAGES = 5

    /** Pages read at the same time. */
    const val PARALLEL_PAGES = 3

    /** Reading stops after this many bytes; the price is near the top of a product page. */
    const val MAX_BYTES = 1_500_000
    const val TIMEOUT_MS = 8_000
    const val MAX_REDIRECTS = 3

    /** The results whose pages are opened: the first [MAX_PAGES], if their address may be. */
    fun pages(results: List<WebSearchResult>): List<WebSearchResult> = results.take(MAX_PAGES).filter { isAllowed(it.url) }

    /**
     * https, no user or password, and a public name: never an IP address, this device or the
     * local network (a router, a NAS), whatever the search results say. Redirects are checked too.
     */
    fun isAllowed(url: String): Boolean {
        val address = WebImageDownload.httpsUrl(url) ?: return false
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        val name = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        // A name without a dot is completed with the local network's domain.
        if (!name.contains('.') || isAddress(name)) return false
        return !WebImageDownload.isPrivateHost(name)
    }

    /** IPv6, or a name whose last label is a number ("203.0.113.10", "2130706433", "0x7f.1"). */
    internal fun isAddress(host: String): Boolean {
        if (host.contains(':') || host.startsWith("[")) return true
        val last = host.substringAfterLast('.')
        return last.all { it in '0'..'9' } || last.startsWith("0x")
    }

    /** True when the addresses a name resolves to are all public: it does not lead to this device or the local network. */
    fun arePublic(addresses: List<String>): Boolean = addresses.isNotEmpty() && addresses.all { address ->
        val bare = address.substringBefore('%')
        bare.isNotEmpty() && !WebImageDownload.isPrivateHost(bare)
    }

    /** Resolves the name and checks its addresses ([arePublic]) before the page is asked for. */
    fun resolvesPublicly(url: String, resolver: (String) -> List<String> = ::systemAddresses): Boolean {
        if (!isAllowed(url)) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return arePublic(runCatching { resolver(host) }.getOrDefault(emptyList()))
    }

    private fun systemAddresses(host: String): List<String> = InetAddress.getAllByName(host).mapNotNull { it.hostAddress }

    /** "text/html; charset=utf-8" or XHTML; anything else is not read. */
    fun isHtml(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return type == "text/html" || type == "application/xhtml+xml"
    }

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
     * wine's name. Each keeps the result's site and address.
     */
    fun prices(html: String, result: WebSearchResult, sizeML: Int, wineName: String? = null): List<FoundPrice> {
        val page = Page(html, wineName)
        val found = page.structuredPrices(sizeML) ?: page.textPrices(sizeML)
        return found.map { FoundPrice(it.amount, it.currency, result.domain, result.url, it.excerpt) }.distinctBy { it.id }
    }

    /** [prices] with those of the pages added, without the ones already found at the same address. */
    fun merging(pagePrices: List<FoundPrice>, prices: List<FoundPrice>): List<FoundPrice> {
        val ids = prices.map { it.id }.toMutableSet()
        return prices + pagePrices.filter { ids.add(it.id) }
    }

    internal data class Item(val amount: BigDecimal, val currency: String, val excerpt: String)

    private val currencies = setOf("EUR", "USD", "GBP", "CHF")

    /** Text prices kept from one page at most: related products and the like are not wanted. */
    private const val MAX_TEXT_PRICES = 3

    /** How far after the wine's name (in UTF-16 units) a price or a size counts as near it. */
    private const val NEAR_NAME = 400

    internal class Page(private val html: String, wineName: String?) {
        val text: String = Html.visibleText(html)
        private val heading: String? = Html.firstElementText(html, "h1")
        private val title: String? =
            Html.metaTags(html).firstOrNull { it.first == "og:title" }?.second?.let(Html::cleanLine) ?: Html.firstElementText(html, "title")

        /** Where to look in the text: after each place the wine's name is, or everywhere. */
        private val areas: List<IntRange> = areas(text, wineName)

        /** The product's name, as the page shows it. */
        private val label: String? get() = heading ?: title

        private fun areas(text: String, name: String?): List<IntRange> {
            val whole = listOf(0 until text.length)
            val needle = Fold.units(Normalizer.normalize(name.orEmpty().trim(), Normalizer.Form.NFC))
            if (needle.length < 3) return whole
            val haystack = Fold.units(text)
            val found = mutableListOf<IntRange>()
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                found += index until minOf(text.length, index + NEAR_NAME)
                index = haystack.indexOf(needle, index + needle.length)
            }
            return found.ifEmpty { whole }
        }

        /** The one size named near the wine's name, if only one is. */
        private val areaSize: Pair<Int, String>? by lazy {
            val sizes = linkedMapOf<Int, String>()
            for (area in areas) {
                val piece = text.substring(area.first, area.last + 1)
                for ((size, pattern) in PriceExtractor.sizePatterns) {
                    val match = pattern.find(piece)
                    if (match != null && size !in sizes) sizes[size] = match.value
                }
            }
            sizes.entries.singleOrNull()?.let { it.key to it.value }
        }

        /** The size of what the page sells: named in [names] (the product, the offer), its heading, or near the wine's name. */
        private fun size(names: List<String?>): Pair<Int, String>? {
            for (name in names + listOf(heading, title)) {
                name?.let { PriceExtractor.sizeWords(it) }?.let { return it }
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
        fun structuredPrices(sizeML: Int): List<Item>? =
            listOf({ jsonLdPrices(sizeML) }, { metaPrices(sizeML) }, { microdataPrices(sizeML) })
                .firstNotNullOfOrNull { read -> read().takeIf { it.isNotEmpty() } }

        private fun item(amount: String, currency: String?, names: List<String?>, label: String?, sizeML: Int): Item? {
            val code = currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in currencies } ?: return null
            val value = PriceExtractor.decimal(amount)?.takeIf { PriceExtractor.inRange(it) } ?: return null
            // A case, a lot or a pack is not one bottle.
            if (names.any { it != null && PriceExtractor.notOneBottleBefore.containsMatchIn(it) }) return null
            val size = size(names)
            if ((size?.first ?: 750) != sizeML) return null
            return Item(value, code, excerpt(label, "$amount $code", size?.second))
        }

        private fun jsonLdPrices(sizeML: Int): List<Item> {
            val items = mutableListOf<Item>()
            for (script in Html.jsonLdScripts(html)) {
                val root = runCatching { Json.parseToJsonElement(script) }.getOrNull() ?: continue
                for (product in JsonLd.products(root)) {
                    val name = JsonLd.string(product["name"])
                    for (offer in JsonLd.offers(product)) {
                        val amount = offer.amount ?: continue
                        if ((offer.quantity ?: 1.0) > 1) continue
                        item(amount, offer.currency, listOf(offer.name, name), name, sizeML)?.let { items += it }
                    }
                }
            }
            return items
        }

        private fun metaPrices(sizeML: Int): List<Item> {
            val tags = Html.metaTags(html)
            fun value(key: String) = tags.firstOrNull { it.first == key }?.second
            for (prefix in listOf("product:price:", "og:price:")) {
                val amount = value(prefix + "amount") ?: continue
                item(amount, value(prefix + "currency"), emptyList(), null, sizeML)?.let { return listOf(it) }
            }
            return emptyList()
        }

        /** The first itemprop="price" of the page, with the first itemprop="priceCurrency". */
        private fun microdataPrices(sizeML: Int): List<Item> {
            val amount = Html.itemprop(html, "price") ?: return emptyList()
            return listOfNotNull(item(amount, Html.itemprop(html, "priceCurrency"), emptyList(), null, sizeML))
        }

        // Visible text

        fun textPrices(sizeML: Int): List<Item> {
            val size = size(emptyList())
            val prices = PriceExtractor.textPrices(text, sizeML, defaultSize = size?.first ?: 750)
            return prices.filter { price -> areas.any { price.location in it } }.take(MAX_TEXT_PRICES).map { price ->
                val parts = mutableListOf<String>()
                label?.let(Html::cleanLine)?.takeIf { !price.excerpt.contains(it, ignoreCase = true) }?.let { parts += it.take(100) }
                parts += price.excerpt
                val words = size?.second
                if (price.namedSize == null && words != null && parts.none { it.contains(words, ignoreCase = true) }) parts += words
                Item(price.amount, price.currency, parts.joinToString(" · "))
            }
        }
    }
}

/** Just enough HTML reading for prices: no layout, no scripts run. */
internal object Html {
    private fun regex(pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val hidden = regex("""<!--.*?-->|<(script|style|noscript|template|svg|head|select)\b[^>]*>.*?</\1\s*>""")
    private val block = regex(
        """<(?:/?(?:p|div|li|ul|ol|tr|td|th|table|h[1-6]|section|article|header|footer|nav|main|aside|form|dt|dd|dl|blockquote|figure|figcaption|option)\b[^>]*|br\s*/?)>""",
    )
    private val tag = regex("""<[^>]*>""")

    /** Line breaks in the source are spaces; only blocks start a line. */
    private val spaces = Regex("""[ \t\n\r\u000C]+""")
    private val jsonLd = regex("""<script\b[^>]*\btype\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script\s*>""")
    private val meta = regex("""<meta\b[^>]*>""")
    private val attribute = regex("""([a-z_:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val number = Regex("""\d[\d .,'’]*""")

    /** The text a reader sees, a line per block (paragraph, cell, item), entities decoded. */
    fun visibleText(html: String): String {
        var text = hidden.replace(html, " ")
        text = spaces.replace(text, " ")
        text = block.replace(text, "\n")
        text = tag.replace(text, " ")
        text = Normalizer.normalize(WebSearchText.decodeEntities(text), Normalizer.Form.NFC)
        return text.split('\n')
            .map { line -> line.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** One line of plain text, or null if empty. */
    fun cleanLine(text: String): String? = Normalizer.normalize(WebSearchText.plain(text), Normalizer.Form.NFC).ifEmpty { null }

    fun firstElementText(html: String, name: String): String? =
        regex("""<$name\b[^>]*>(.*?)</$name\s*>""").find(html)?.groupValues?.get(1)?.let(::cleanLine)

    fun jsonLdScripts(html: String): List<String> = jsonLd.findAll(html).map { it.groupValues[1] }.toList()

    /** The attributes of a tag, names lowercased, values with entities decoded. */
    fun attributes(tag: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (match in attribute.findAll(tag)) {
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val value = (2..4).firstNotNullOfOrNull { match.groups[it]?.value }.orEmpty()
            if (name !in values) values[name] = WebSearchText.decodeEntities(value)
        }
        return values
    }

    /** (property or name, content) of each meta tag, keys lowercased. */
    fun metaTags(html: String): List<Pair<String, String>> = meta.findAll(html).mapNotNull { match ->
        val values = attributes(match.value)
        val key = values["property"] ?: values["name"] ?: return@mapNotNull null
        val content = values["content"] ?: return@mapNotNull null
        key.lowercase(Locale.ROOT) to content
    }.toList()

    /** The first element with itemprop=[name]: its content attribute, or the text right after it. */
    fun itemprop(html: String, name: String): String? {
        val match = regex("""<[a-z][a-z0-9]*\b[^>]*\bitemprop\s*=\s*["']?$name(?=["'\s>/])[^>]*>([^<]*)""").find(html) ?: return null
        val textGroup = match.groups[1]!!
        val tag = html.substring(match.range.first, textGroup.range.first)
        attributes(tag)["content"]?.let { return it.trim() }
        val text = WebSearchText.plain(textGroup.value)
        // "Fr. 13.90" or "13,90 €": the number only.
        val found = number.find(text) ?: return text.ifEmpty { null }
        return found.value.trim()
    }
}

internal object JsonLd {
    data class Offer(val amount: String?, val currency: String?, val name: String?, val quantity: Double?)

    private val productTypes = setOf("product", "productgroup", "individualproduct", "productmodel", "wine")

    /** Products at the top, in arrays, in @graph or as the page's main entity, and their variants. */
    fun products(node: JsonElement): List<JsonObject> {
        if (node is JsonArray) return node.flatMap { products(it) }
        if (node !is JsonObject) return emptyList()
        val found = mutableListOf<JsonObject>()
        if (types(node).any { it in productTypes } || "offers" in node) found += node
        for (key in listOf("@graph", "mainEntity", "hasVariant")) node[key]?.let { found += products(it) }
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
     * The offers of a product: price (or lowPrice), currency, name and quantity, in their
     * price specifications or in an aggregate offer's own offers when needed.
     */
    fun offers(product: JsonObject): List<Offer> {
        val found = mutableListOf<Offer>()
        fun read(node: JsonObject, depth: Int) {
            var amount = string(node["price"]) ?: string(node["lowPrice"])
            var currency = string(node["priceCurrency"])
            val specification = list(node["priceSpecification"]).firstOrNull { string(it["price"]) != null }
            if (amount == null && specification != null) amount = string(specification["price"])
            if (currency == null && specification != null) currency = string(specification["priceCurrency"])
            if (amount != null) {
                found += Offer(amount, currency, string(node["name"]), quantity(node["eligibleQuantity"]))
            } else if (depth < 2) {
                list(node["offers"]).forEach { read(it, depth + 1) }
            }
        }
        list(product["offers"]).forEach { read(it, 0) }
        return found
    }

    /** Text or a number as text ("13.00", 13.5 → "13.5"). */
    fun string(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
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
    fun units(text: String): String = buildString(text.length) {
        for (unit in text) {
            append(
                when {
                    unit.code < 0x80 -> if (unit in 'A'..'Z') unit + 32 else unit
                    unit == '’' || unit == '‘' -> '\''
                    unit.isSurrogate() -> unit
                    else -> Normalizer.normalize(unit.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
                },
            )
        }
    }
}
