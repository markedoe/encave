package app.encave.core.search

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/*
 * Finds what a wine sells for today in web search results (general search engines only,
 * no wine-platform API), the Kotlin port of CaveKit's WinePrice.swift. The results are
 * read on the device; the user always sees the prices found and accepts the value.
 */

/** A price seen in a search result. */
data class FoundPrice(
    val amount: BigDecimal,
    /** ISO code: EUR, USD, GBP or CHF. */
    val currency: String,
    val domain: String,
    val url: String,
    /** The words around the price, to judge it. */
    val excerpt: String,
) {
    val id: String get() = "$url|${amount.toPlainString()}|$currency"
}

object PriceExtractor {
    // The same patterns as the Apple app (WinePrice.swift).
    // At most four groups of thousands: a long run of digit groups is read in linear time.
    private const val NUMBER = """(?:\d{1,3}(?:[ \u00A0\u202F.,'’]\d{3}){1,4}(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)"""

    /** Swiss whole francs: "13.–", "13.-". */
    private const val NO_CENTS = """(?:[.,][–—-]{1,2})?"""

    /** "€45", "CHF 45.90", "US$ 1,234.50", "CHF 13.–", "Fr. 13.50", "SFr. 13.50". */
    private val prefix = Regex(
        """(?<![\p{L}\d])(US\$|€|\$|£|EUR|USD|GBP|CHF|SFr\.?|Fr\.)[ \u00A0\u202F]?($NUMBER)$NO_CENTS(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** "45 €", "1 234,50 €", "45 EUR", "45 euros", "13.– CHF", "13.50 Fr.". */
    private val suffix = Regex(
        """(?<![\d.,'’])($NUMBER)$NO_CENTS[ \u00A0\u202F]?(€|\$|£|EUR|USD|GBP|CHF|euros?|S?Fr\.?)(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    /** Several bottles, a case or delivery: not the price of one bottle. */
    internal val notOneBottleBefore = Regex(
        """(?:\b\d+ ?x\b|\bx ?\d+\b|\bcase\b|\bcases\b|\bcarton|\bcaisse|\blot\b|\bpack\b|\bcoffret|\bbox\b|\bshipping|\bdelivery|\blivraison|\bfrais de port|\bdozen|\bdouzaine)""",
        RegexOption.IGNORE_CASE,
    )
    private val notOneBottleAfter = Regex(
        """^[ \u00A0]*(?:(?:for|pour|les|the) \d{1,2}\b|x ?\d+|/ ?(?:case|caisse|carton|lot|pack|\d)|\( ?\d+ ?(?:x|bottles|bouteilles|btl)|(?:per|par|la|le) (?:case|caisse|carton|lot))""",
        RegexOption.IGNORE_CASE,
    )

    /** "|6 l|6 litres|600 cl": a whole number of litres, not the end of "1,6 l". */
    private fun litres(count: String) =
        """|(?<![\d.,])$count ?l\b|(?<![\d.,])$count ?lit(?:re|er)s?\b|\b${count}00 ?cl"""

    /**
     * Bottle sizes named near a price, larger sizes first ("double magnum" before "magnum"),
     * and a volume given in litres before a name that has several ("jéroboam 5 l").
     */
    internal val sizePatterns = listOf(
        18000 to """\bmelchior""" + litres("18"),
        15000 to """\bnabuchodonosor|\bnebuchadnezzar""" + litres("15"),
        12000 to """\bbalthazar""" + litres("12"),
        9000 to """\bsalmanazar""" + litres("9"),
        6000 to """\bimp[eé]riale?\b|\bmathusalem|\bmethuselah""" + litres("6"),
        5000 to litres("5").drop(1),
        4500 to """\br[eé]hoboam|(?<![\d.,])4[.,]5 ?l\b|(?<![\d.,])4[.,]5 ?lit(?:re|er)s?\b|\b450 ?cl""",
        3000 to """\bdouble[- ]magnum|\bj[eé]roboam|\b3 ?l\b|\b300 ?cl""",
        1500 to """\bmagnum|\b1[.,]5 ?l\b|\b150 ?cl|\b1500 ?ml""",
        1000 to """\b1 ?l\b|\b1 ?lit(?:re|er)\b|\b1[.,]0 ?l\b|\b100 ?cl|\b1000 ?ml""",
        375 to """\bhalf[- ]bottle|\bdemi[- ]bouteille|\bfillette|\b37[.,]5 ?cl|\b375 ?ml|\b0[.,]375 ?l\b""",
        750 to """\b75 ?cl|\b750 ?ml|\b0[.,]75 ?l\b""",
        700 to """\b70 ?cl|\b700 ?ml|\b0[.,]7 ?l\b""",
        620 to """\bclavelin|\b62 ?cl|\b620 ?ml|\b0[.,]62 ?l\b""",
        500 to """\b50 ?cl|\b500 ?ml|\b0[.,]5 ?l\b""",
    ).map { (size, pattern) -> size to Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }

    /** Matches of each pattern read in one text, at most. */
    private const val MAX_MATCHES = 50

    private val min = BigDecimal(3)
    private val max = BigDecimal(20_000)

    internal fun inRange(amount: BigDecimal) = amount >= min && amount <= max

    /** Prices of one bottle of [sizeML] in the titles and snippets of the results. */
    fun prices(results: List<WebSearchResult>, sizeML: Int = 750): List<FoundPrice> {
        val seen = mutableSetOf<String>()
        val found = mutableListOf<FoundPrice>()
        for (result in results) {
            for (text in listOf(result.title, result.snippet)) {
                for ((amount, currency, excerpt) in prices(text, sizeML)) {
                    val price = FoundPrice(amount, currency, result.domain, result.url, excerpt)
                    if (seen.add(price.id)) found += price
                }
            }
        }
        return found
    }

    private data class Match(val start: Int, val end: Int, val amount: String, val symbol: String, val isSuffix: Boolean)

    /** Amounts with a currency in a text, without those that are not for one bottle. */
    internal fun prices(text: String, sizeML: Int): List<Triple<BigDecimal, String, String>> =
        textPrices(text, sizeML).map { Triple(it.amount, it.currency, it.excerpt) }

    /** What a price in a text is for. */
    internal enum class Kind {
        OneBottle,

        /** Several bottles, a case or delivery. */
        NotOneBottle,

        /** One bottle of another size. */
        OtherSize,
    }

    /** A price in a text: where it is (UTF-16 offset and length), the size named next to it, if any, and what it is for. */
    internal data class TextPrice(
        val amount: BigDecimal,
        val currency: String,
        val excerpt: String,
        val location: Int,
        val namedSize: Int?,
        val length: Int = 0,
        val kind: Kind = Kind.OneBottle,
    )

    /** As [prices]; a price without a size next to it is for [defaultSize]. */
    internal fun textPrices(text: String, sizeML: Int, defaultSize: Int = 750): List<TextPrice> =
        allTextPrices(text, sizeML, defaultSize).filter { it.kind == Kind.OneBottle }

    /** Every amount with a currency in a text, in order, with what it is for. */
    internal fun allTextPrices(text: String, sizeML: Int, defaultSize: Int = 750): List<TextPrice> {
        val matches = mutableListOf<Match>()
        for (match in prefix.findAll(text).take(MAX_MATCHES)) {
            matches += Match(match.range.first, match.range.last + 1, match.groupValues[2], match.groupValues[1], false)
        }
        for (match in suffix.findAll(text).take(MAX_MATCHES)) {
            matches += Match(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[2], true)
        }
        // "2019 €45": the vintage followed by a price is not "2019 €". Otherwise the
        // amount before its symbol ("45,00 € 39,90 €") wins.
        fun isYear(amount: String) = amount.length == 4 && amount.all { it.isDigit() } && amount.toInt() in 1900..2100
        val kept = mutableListOf<Match>()
        // Sorted, so only the last kept match can overlap the next one.
        for (match in matches.sortedBy { it.start }) {
            val index = kept.lastIndex.takeIf { it >= 0 && kept[it].start < match.end && match.start < kept[it].end } ?: -1
            if (index >= 0) {
                val other = kept[index]
                val suffixMatch = if (match.isSuffix) match else other
                val prefixMatch = if (match.isSuffix) other else match
                if (suffixMatch.isSuffix != prefixMatch.isSuffix) {
                    kept[index] = if (isYear(suffixMatch.amount)) prefixMatch else suffixMatch
                }
                continue
            }
            kept += match
        }

        val found = mutableListOf<TextPrice>()
        // The words before a price stop at the price before it: "Case 6x75cl - £269.70 / 1 Bottle
        // 75cl - £44.95" is a case, then one bottle.
        var previousEnd = 0
        for (match in kept) {
            val from = previousEnd
            previousEnd = match.end
            val amount = decimal(match.amount)?.takeIf { it >= min && it <= max } ?: continue
            val before = contextBefore(text, match.start, from)
            val after = contextAfter(text, match.end)
            // A price without a size is for 75 cl (or the page's size); another size must be named.
            val size = namedSize("$before $after")
            val kind = when {
                notOneBottleBefore.containsMatchIn(before) || notOneBottleAfter.containsMatchIn(after) -> Kind.NotOneBottle
                (size ?: defaultSize) != sizeML -> Kind.OtherSize
                else -> Kind.OneBottle
            }
            val excerpt = (before + text.substring(match.start, match.end) + after)
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            found += TextPrice(amount, currencyCode(match.symbol), excerpt, match.start, size, match.end - match.start, kind)
        }
        return found
    }

    /** The median of the prices in [currency], rounded to the cent; null if there are none. */
    fun suggested(prices: List<FoundPrice>, currency: String): BigDecimal? {
        val amounts = prices.filter { it.currency == currency }.map { it.amount }.sorted()
        if (amounts.isEmpty()) return null
        val middle = amounts.size / 2
        val median = if (amounts.size % 2 == 0) {
            (amounts[middle - 1] + amounts[middle]).divide(BigDecimal(2))
        } else {
            amounts[middle]
        }
        return median.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
    }

    /** "1 234,50" → 1234.5; "1,234.50" → 1234.5; "45" → 45. */
    internal fun decimal(text: String): BigDecimal? {
        var integer = text
        var fraction = ""
        val last = text.indexOfLast { it == '.' || it == ',' }
        if (last >= 0) {
            val tail = text.substring(last + 1)
            if (tail.length in 1..2 && tail.all { it in '0'..'9' }) {
                integer = text.substring(0, last)
                fraction = tail
            }
        }
        // ASCII digits only, as in Swift (`BigDecimal` would also read other scripts' digits).
        val digits = integer.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return BigDecimal(if (fraction.isEmpty()) digits else "$digits.$fraction").stripTrailingZeros()
    }

    internal fun currencyCode(symbol: String): String = when (symbol.uppercase(Locale.ROOT)) {
        "€", "EUR", "EURO", "EUROS" -> "EUR"
        "$", "US$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        else -> "CHF"
    }

    /**
     * Up to 40 characters before the price and after [lowest], in the same sentence ("0.7 l"
     * does not end one), without the end of a word cut at its start ("hipping" of "shipping").
     */
    private fun contextBefore(text: String, location: Int, lowest: Int = 0): String {
        var start = maxOf(lowest, location - 40)
        if (start > lowest && isWordUnit(text[start - 1])) {
            while (start < location && isWordUnit(text[start])) start++
        }
        var piece = text.substring(start, location)
        val cut = piece.indexOfLast { it in ";|•·\n!?" }
        if (cut >= 0) piece = piece.substring(cut + 1)
        val stop = piece.lastIndexOf(". ")
        if (stop >= 0) piece = piece.substring(stop + 2)
        return piece
    }

    /** A letter or a digit (UTF-16 unit), the same test as on Apple. */
    internal fun isWordUnit(unit: Char): Boolean = when {
        unit in '0'..'9' || unit in 'A'..'Z' || unit in 'a'..'z' -> true
        unit.code < 0xC0 -> false
        else -> Character.isAlphabetic(unit.code)
    }

    /** Up to 25 characters after the price, in the same sentence. */
    private fun contextAfter(text: String, location: Int): String {
        if (location >= text.length) return ""
        var piece = text.substring(location, minOf(text.length, location + 25))
        val cut = piece.indexOfFirst { it in ";|•·\n!?" }
        if (cut >= 0) piece = piece.substring(0, cut)
        val stop = piece.indexOf(". ")
        if (stop >= 0) piece = piece.substring(0, stop)
        return piece
    }

    internal fun namedSize(text: String): Int? = sizePatterns.firstOrNull { it.second.containsMatchIn(text) }?.first

    /** The size named in a text and its words as written ("70 cl"), the first pattern that matches. */
    internal fun sizeWords(text: String): Pair<Int, String>? {
        for ((size, pattern) in sizePatterns) {
            pattern.find(text)?.let { return size to it.value }
        }
        return null
    }
}
