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
    private const val NUMBER = """(?:\d{1,3}(?:[ \u00A0\u202F.,'’]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)"""

    /** "€45", "CHF 45.90", "US$ 1,234.50". */
    private val prefix = Regex("""(?<![\p{L}\d])(US\$|€|\$|£|EUR|USD|GBP|CHF)[ \u00A0\u202F]?($NUMBER)(?!\d)""", RegexOption.IGNORE_CASE)

    /** "45 €", "1 234,50 €", "45 EUR", "45 euros". */
    private val suffix = Regex("""(?<![\d.,'’])($NUMBER)[ \u00A0\u202F]?(€|\$|£|EUR|USD|GBP|CHF|euros?)(?!\p{L})""", RegexOption.IGNORE_CASE)

    /** Several bottles, a case or delivery: not the price of one bottle. */
    private val notOneBottleBefore = Regex(
        """(?:\b\d+ ?x\b|\bx ?\d+\b|\bcase\b|\bcases\b|\bcarton|\bcaisse|\blot\b|\bpack\b|\bcoffret|\bbox\b|\bshipping|\bdelivery|\blivraison|\bfrais de port|\bdozen|\bdouzaine)""",
        RegexOption.IGNORE_CASE,
    )
    private val notOneBottleAfter = Regex(
        """^[ \u00A0]*(?:(?:for|pour|les|the) \d{1,2}\b|x ?\d+|/ ?(?:case|caisse|carton|lot|pack|\d)|\( ?\d+ ?(?:x|bottles|bouteilles|btl)|(?:per|par|la|le) (?:case|caisse|carton|lot))""",
        RegexOption.IGNORE_CASE,
    )

    /** Bottle sizes named near a price, larger sizes first ("double magnum" before "magnum"). */
    private val sizePatterns = listOf(
        3000 to Regex("""\bdouble[- ]magnum|\bjeroboam|\b3 ?l\b|\b300 ?cl""", RegexOption.IGNORE_CASE),
        1500 to Regex("""\bmagnum|\b1[.,]5 ?l\b|\b150 ?cl|\b1500 ?ml""", RegexOption.IGNORE_CASE),
        375 to Regex("""\bhalf[- ]bottle|\bdemi[- ]bouteille|\bfillette|\b37[.,]5 ?cl|\b375 ?ml""", RegexOption.IGNORE_CASE),
        750 to Regex("""\b75 ?cl|\b750 ?ml|\b0[.,]75 ?l\b""", RegexOption.IGNORE_CASE),
    )

    private val min = BigDecimal(3)
    private val max = BigDecimal(20_000)

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
    internal fun prices(text: String, sizeML: Int): List<Triple<BigDecimal, String, String>> {
        val matches = mutableListOf<Match>()
        for (match in prefix.findAll(text)) {
            matches += Match(match.range.first, match.range.last + 1, match.groupValues[2], match.groupValues[1], false)
        }
        for (match in suffix.findAll(text)) {
            matches += Match(match.range.first, match.range.last + 1, match.groupValues[1], match.groupValues[2], true)
        }
        // "2019 €45": the vintage followed by a price is not "2019 €". Otherwise the
        // amount before its symbol ("45,00 € 39,90 €") wins.
        fun isYear(amount: String) = amount.length == 4 && amount.all { it.isDigit() } && amount.toInt() in 1900..2100
        val kept = mutableListOf<Match>()
        for (match in matches.sortedBy { it.start }) {
            val index = kept.indexOfFirst { it.start < match.end && match.start < it.end }
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

        return kept.mapNotNull { match ->
            val amount = decimal(match.amount)?.takeIf { it >= min && it <= max } ?: return@mapNotNull null
            val before = contextBefore(text, match.start)
            val after = contextAfter(text, match.end)
            if (notOneBottleBefore.containsMatchIn(before) || notOneBottleAfter.containsMatchIn(after)) return@mapNotNull null
            // A price without a size is for 75 cl; another size must be named.
            if ((namedSize("$before $after") ?: 750) != sizeML) return@mapNotNull null
            val excerpt = (before + text.substring(match.start, match.end) + after)
                .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            Triple(amount, currencyCode(match.symbol), excerpt)
        }
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
            if (tail.length in 1..2 && tail.all { it.isDigit() }) {
                integer = text.substring(0, last)
                fraction = tail
            }
        }
        val digits = integer.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return BigDecimal(if (fraction.isEmpty()) digits else "$digits.$fraction").stripTrailingZeros()
    }

    internal fun currencyCode(symbol: String): String = when (symbol.uppercase(Locale.ROOT)) {
        "€", "EUR", "EURO", "EUROS" -> "EUR"
        "$", "US$", "USD" -> "USD"
        "£", "GBP" -> "GBP"
        else -> "CHF"
    }

    /** Up to 30 characters before the price, in the same sentence, lowercased. */
    private fun contextBefore(text: String, location: Int): String {
        var piece = text.substring(maxOf(0, location - 30), location)
        val cut = piece.indexOfLast { it in ".;|•·\n!?" }
        if (cut >= 0) piece = piece.substring(cut + 1)
        return piece.lowercase(Locale.ROOT)
    }

    /** Up to 25 characters after the price, in the same sentence, lowercased. */
    private fun contextAfter(text: String, location: Int): String {
        if (location >= text.length) return ""
        var piece = text.substring(location, minOf(text.length, location + 25))
        val cut = piece.indexOfFirst { it in ";|•·\n!?" }
        if (cut >= 0) piece = piece.substring(0, cut)
        val stop = piece.indexOf(". ")
        if (stop >= 0) piece = piece.substring(0, stop)
        return piece.lowercase(Locale.ROOT)
    }

    private fun namedSize(text: String): Int? = sizePatterns.firstOrNull { it.second.containsMatchIn(text) }?.first
}
