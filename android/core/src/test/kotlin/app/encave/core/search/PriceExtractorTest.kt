package app.encave.core.search

import app.encave.core.Bottle
import app.encave.core.Cellar
import app.encave.core.CellarExport
import app.encave.core.CellarJson
import app.encave.core.Wine
import app.encave.core.importCsv
import app.encave.core.statistics
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Snippets in French and English formats, the same as CaveKit's WinePriceTests.swift. */
object PriceFixtures {
    val snippets = listOf(
        "https://www.caviste.example.fr/chablis" to "Chablis 1er Cru 2019 Domaine Example : 34,90 € la bouteille. Livraison offerte dès 150 €.",
        "https://shop.example.com/chablis" to "Domaine Example Chablis Premier Cru 2019 – €1,234.50 for the rare one, usually €36.50 per bottle.",
        "https://uk.example.co.uk/wine" to "Buy Chablis 1er Cru 2019 for £29.99, or a case of 6 for £170.00.",
        "https://ch.example.ch/vin" to "Prix CHF 42.90 · Magnum 89 CHF",
        "https://de.example.de/wein" to "2019 €38 · 6 x 75cl 210 € · 45 EUR",
        "https://fr2.example.fr/vin" to "Ancien prix 45,00 € 39,90 € ; le carton de 6 : 220 €",
        "https://big.example.fr/vin" to "Prix 1 234,50 € (millésime rare) et 40 euros pour 2019",
    )

    val results: List<WebSearchResult> get() = snippets.map { WebSearchResult("Result", it.first, it.second) }
}

class PriceExtractorTest {
    @Test
    fun priceQueryAddsSizeAndPriceWord() {
        assertEquals("Domaine Example Chablis 1er Cru 2019 price", WebSearchQuery.priceTerms("Domaine Example", "Chablis 1er Cru", "Chablis", 2019))
        assertEquals("Château Example Pauillac 2010 magnum prix", WebSearchQuery.priceTerms(null, "Château Example", "Pauillac", 2010, 1500, "prix"))
        assertEquals("Sauternes 37.5 cl price", WebSearchQuery.priceTerms(null, "Sauternes", null, null, 375))
        assertEquals("W 50 cl price", WebSearchQuery.priceTerms(null, "W", null, null, 500))
    }

    @Test
    fun amounts() {
        for ((text, expected) in listOf("1 234,50" to "1234.5", "1,234.50" to "1234.5", "1.234,5" to "1234.5", "1’234.50" to "1234.5", "45" to "45", "34,90" to "34.9")) {
            assertEquals(expected, PriceExtractor.decimal(text)!!.toPlainString(), text)
        }
    }

    private fun found(index: Int, sizeML: Int = 750): List<String> =
        PriceExtractor.prices(PriceFixtures.snippets[index].second, sizeML).map { "${it.first.toPlainString()} ${it.second}" }

    @Test
    fun frenchFormats() {
        assertEquals(listOf("34.9 EUR"), found(0))
        assertEquals(listOf("45 EUR", "39.9 EUR"), found(5))
        assertEquals(listOf("1234.5 EUR", "40 EUR"), found(6))
    }

    @Test
    fun englishFormats() {
        assertEquals(listOf("1234.5 EUR", "36.5 EUR"), found(1))
        assertEquals(listOf("29.99 GBP"), found(2))
    }

    @Test
    fun vintageBeforeASymbolIsNotAPrice() {
        assertEquals(listOf("38 EUR", "45 EUR"), found(4))
    }

    @Test
    fun otherBottleSizes() {
        assertEquals(listOf("42.9 CHF"), found(3))
        assertEquals(listOf("89 CHF"), found(3, 1500))
    }

    @Test
    fun pricesKeepTheirSource() {
        val prices = PriceExtractor.prices(PriceFixtures.results)
        assertEquals("caviste.example.fr", prices.first().domain)
        assertTrue(prices.first().excerpt.contains("34,90 €"))
        assertEquals(9, prices.count { it.currency == "EUR" })
        assertEquals(setOf("EUR", "GBP", "CHF"), prices.map { it.currency }.toSet())
    }

    @Test
    fun suggestedIsTheMedianInTheCellarCurrency() {
        val prices = PriceExtractor.prices(PriceFixtures.results)
        val euros = prices.filter { it.currency == "EUR" }.map { it.amount }.sorted().map { it.toPlainString() }
        assertEquals(listOf("34.9", "36.5", "38", "39.9", "40", "45", "45", "1234.5", "1234.5"), euros)
        assertEquals("40", PriceExtractor.suggested(prices, "EUR")!!.toPlainString())
        assertEquals("29.99", PriceExtractor.suggested(prices, "GBP")!!.toPlainString())
        assertNull(PriceExtractor.suggested(prices, "USD"))
        val two = listOf(BigDecimal("39.95"), BigDecimal(45)).map { FoundPrice(it, "EUR", "a", "https://a.example", "") }
        assertEquals("42.48", PriceExtractor.suggested(two, "EUR")!!.toPlainString())
    }

    @Test
    fun otherSizesAreKeptApartAndNeverSuggested() {
        val url = "https://shop.example.org/vin"
        val normal = FoundPrice(BigDecimal(15), "CHF", "a", url, "")
        val other = FoundPrice(BigDecimal(13), "CHF", "a", url, "", sizeML = 700)
        assertTrue(other.isOtherSize && !normal.isOtherSize)
        assertTrue(other.id != FoundPrice(BigDecimal(13), "CHF", "a", url, "").id)
        assertNull(PriceExtractor.suggested(listOf(other), "CHF"))
        assertEquals("15", PriceExtractor.suggested(listOf(other, normal), "CHF")!!.toPlainString())
        // A result with a price for the size searched gives no price of another size.
        val both = WebSearchResult("Les Lacustres", url, "70 cl CHF 13.00 · 75 cl CHF 15.00")
        assertEquals(listOf("15"), PriceExtractor.prices(listOf(both)).map { it.amount.toPlainString() })
        val only = WebSearchResult("Les Lacustres", url, "70 cl CHF 13.00 · Magnum CHF 30.00")
        assertEquals(listOf("13 700", "30 1500"), PriceExtractor.prices(listOf(only)).map { "${it.amount.toPlainString()} ${it.sizeML}" })
    }

    @Test
    fun currentPriceIsStoredAndOptional() {
        val plain = Cellar(wines = listOf(Wine(name = "Chablis")))
        assertFalse(CellarJson.encode(plain).contains("currentPrice"))
        val wine = Wine(
            name = "Chablis", currentPrice = BigDecimal("39.9"), currentPriceCurrency = "EUR",
            currentPriceDate = Instant.parse("2026-10-17T00:00:00Z"),
        )
        val back = CellarJson.decode(CellarJson.encode(Cellar(wines = listOf(wine)))).wines.values.single()
        assertEquals(0, BigDecimal("39.9").compareTo(back.currentPrice))
        assertEquals("EUR", back.currentPriceCurrency)
        assertEquals(wine.currentPriceDate, back.currentPriceDate)
    }

    @Test
    fun estimatedValueCountsBottlesInTheCellar() {
        val priced = Wine(name = "A", currentPrice = BigDecimal(40), currentPriceCurrency = "EUR")
        val other = Wine(name = "B")
        val bottles = List(3) { Bottle(wineId = priced.id, purchasePrice = BigDecimal(25), currency = "EUR") } +
            List(2) { Bottle(wineId = other.id) }
        val stats = Cellar(wines = listOf(priced, other), bottles = bottles).statistics(2026)
        assertEquals(mapOf("EUR" to BigDecimal(120)), stats.estimatedValueByCurrency)
        assertEquals(3, stats.pricedBottleCount)
    }

    @Test
    fun csvKeepsTheCurrentPrice() {
        val wine = Wine(
            name = "Chablis", currentPrice = BigDecimal("39.9"), currentPriceCurrency = "CHF",
            currentPriceDate = Instant.parse("2026-10-18T00:00:00Z"),
        )
        val cellar = Cellar(wines = listOf(wine), bottles = listOf(Bottle(wineId = wine.id)))
        val csv = CellarExport.csv(cellar)
        assertTrue(csv.startsWith(CellarExport.csvColumns.joinToString(",")))
        assertTrue(csv.contains(",39.9,CHF,2026-10-18"), csv)
        val imported = Cellar()
        imported.importCsv(csv)
        val back = imported.wines.values.single()
        assertEquals(0, BigDecimal("39.9").compareTo(back.currentPrice))
        assertEquals("CHF", back.currentPriceCurrency)
        assertEquals(wine.currentPriceDate, back.currentPriceDate)
        val french = Cellar()
        french.importCsv("nom,prix actuel,devise\nChablis,\"42,5\",EUR\n")
        assertEquals(0, BigDecimal("42.5").compareTo(french.wines.values.single().currentPrice))
        assertEquals("EUR", french.wines.values.single().currentPriceCurrency)
    }
}
