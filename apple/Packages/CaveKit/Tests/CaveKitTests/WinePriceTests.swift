import Foundation
import Testing
@testable import CaveKit

/// Snippets in French and English formats. The Android tests (PriceExtractorTest.kt) use the same ones.
enum PriceFixtures {
    static let snippets: [(String, String)] = [
        ("https://www.caviste.example.fr/chablis", "Chablis 1er Cru 2019 Domaine Example : 34,90 € la bouteille. Livraison offerte dès 150 €."),
        ("https://shop.example.com/chablis", "Domaine Example Chablis Premier Cru 2019 – €1,234.50 for the rare one, usually €36.50 per bottle."),
        ("https://uk.example.co.uk/wine", "Buy Chablis 1er Cru 2019 for £29.99, or a case of 6 for £170.00."),
        ("https://ch.example.ch/vin", "Prix CHF 42.90 · Magnum 89 CHF"),
        ("https://de.example.de/wein", "2019 €38 · 6 x 75cl 210 € · 45 EUR"),
        ("https://fr2.example.fr/vin", "Ancien prix 45,00 € 39,90 € ; le carton de 6 : 220 €"),
        ("https://big.example.fr/vin", "Prix 1 234,50 € (millésime rare) et 40 euros pour 2019"),
    ]

    static var results: [WebSearchResult] {
        snippets.map { WebSearchResult(title: "Result", url: URL(string: $0.0)!, snippet: $0.1) }
    }
}

@Suite struct WinePriceTests {
    @Test func priceQueryAddsSizeAndPriceWord() {
        #expect(WebSearchQuery.priceTerms(producer: "Domaine Example", name: "Chablis 1er Cru", appellation: "Chablis",
                                          vintage: 2019) == "Domaine Example Chablis 1er Cru 2019 price")
        #expect(WebSearchQuery.priceTerms(producer: nil, name: "Château Example", appellation: "Pauillac", vintage: 2010,
                                          sizeML: 1500, priceWord: "prix") == "Château Example Pauillac 2010 magnum prix")
        #expect(WebSearchQuery.priceTerms(producer: nil, name: "Sauternes", appellation: nil, vintage: nil,
                                          sizeML: 375) == "Sauternes 37.5 cl price")
        #expect(WebSearchQuery.priceTerms(producer: nil, name: "W", appellation: nil, vintage: nil, sizeML: 500) == "W 50 cl price")
    }

    @Test func amounts() {
        #expect(PriceExtractor.decimal("1 234,50") == Decimal(string: "1234.5"))
        #expect(PriceExtractor.decimal("1,234.50") == Decimal(string: "1234.5"))
        #expect(PriceExtractor.decimal("1.234,5") == Decimal(string: "1234.5"))
        #expect(PriceExtractor.decimal("1’234.50") == Decimal(string: "1234.5"))
        #expect(PriceExtractor.decimal("45") == 45)
        #expect(PriceExtractor.decimal("34,90") == Decimal(string: "34.9"))
    }

    static func found(_ index: Int, sizeML: Int = 750) -> [String] {
        let (_, text) = PriceFixtures.snippets[index]
        return PriceExtractor.prices(in: text, sizeML: sizeML).map { "\($0.0) \($0.1)" }
    }

    @Test func frenchFormats() {
        // "dès 150 €" is about delivery.
        #expect(Self.found(0) == ["34.9 EUR"])
        // The old and the new price; the case of 6 is left out.
        #expect(Self.found(5) == ["45 EUR", "39.9 EUR"])
        #expect(Self.found(6) == ["1234.5 EUR", "40 EUR"])
    }

    @Test func englishFormats() {
        #expect(Self.found(1) == ["1234.5 EUR", "36.5 EUR"])
        #expect(Self.found(2) == ["29.99 GBP"])
    }

    @Test func vintageBeforeASymbolIsNotAPrice() {
        #expect(Self.found(4) == ["38 EUR", "45 EUR"])
    }

    @Test func otherBottleSizes() {
        #expect(Self.found(3) == ["42.9 CHF"])
        #expect(Self.found(3, sizeML: 1500) == ["89 CHF"])
    }

    @Test func pricesKeepTheirSource() {
        let prices = PriceExtractor.prices(in: PriceFixtures.results)
        #expect(prices.first?.domain == "caviste.example.fr")
        #expect(prices.first?.excerpt.contains("34,90 €") == true)
        #expect(prices.filter { $0.currency == "EUR" }.count == 9)
        #expect(Set(prices.map(\.currency)) == ["EUR", "GBP", "CHF"])
    }

    @Test func suggestedIsTheMedianInTheCellarCurrency() {
        let prices = PriceExtractor.prices(in: PriceFixtures.results)
        // The same amount on two sites counts twice; the median is not moved by the rare 1 234,50 €.
        let euros = prices.filter { $0.currency == "EUR" }.map(\.amount).sorted()
        #expect(euros == ["34.9", "36.5", "38", "39.9", "40", "45", "45", "1234.5", "1234.5"].map { Decimal(string: $0)! })
        #expect(PriceExtractor.suggested(prices, currency: "EUR") == 40)
        #expect(PriceExtractor.suggested(prices, currency: "GBP") == Decimal(string: "29.99"))
        #expect(PriceExtractor.suggested(prices, currency: "USD") == nil)
        let two = [Decimal(string: "39.95")!, 45].map {
            FoundPrice(amount: $0, currency: "EUR", domain: "a", url: URL(string: "https://a.example")!, excerpt: "")
        }
        #expect(PriceExtractor.suggested(two, currency: "EUR") == Decimal(string: "42.48"))
    }

    @Test func promptListsThePrices() {
        let prices = PriceExtractor.prices(in: PriceFixtures.results)
        let prompt = PriceSuggestions.prompt(terms: "Chablis 2019 price", prices: prices, currency: "EUR", sizeML: 750, limit: 2)
        #expect(prompt.contains("one bottle of 750 ml, in EUR"))
        #expect(prompt.contains("1. 34.9 EUR on caviste.example.fr:"))
        #expect(!prompt.contains("3. "))
    }

    // MARK: The wine's current price

    @Test func currentPriceIsStoredAndOptional() throws {
        var wine = Wine(name: "Chablis")
        let plain = try JSONEncoder().encode(wine)
        #expect(!String(decoding: plain, as: UTF8.self).contains("currentPrice"))
        wine.currentPrice = Decimal(string: "39.9")
        wine.currentPriceCurrency = "EUR"
        wine.currentPriceDate = Date(timeIntervalSinceReferenceDate: 800_000_000)
        let decoded = try JSONDecoder().decode(Wine.self, from: JSONEncoder().encode(wine))
        #expect(decoded == wine)
        let old = try JSONDecoder().decode(Wine.self, from: Data(#"{"id":"11111111-1111-4111-8111-111111111111","name":"W","grapes":[]}"#.utf8))
        #expect(old.currentPrice == nil)
    }

    @Test func estimatedValueCountsBottlesInTheCellar() throws {
        var cellar = Cellar()
        var priced = Wine(name: "A")
        priced.currentPrice = 40
        priced.currentPriceCurrency = "EUR"
        let other = Wine(name: "B")
        try cellar.addBottles(of: priced, quantity: 3, purchase: PurchaseInfo(price: 25, currency: "EUR"))
        try cellar.addBottles(of: other, quantity: 2)
        let stats = cellar.statistics(year: 2026)
        #expect(stats.estimatedValueByCurrency == ["EUR": 120])
        #expect(stats.pricedBottleCount == 3)
        #expect(stats.valueByCurrency == ["EUR": 75])
    }

    @Test func csvKeepsTheCurrentPrice() throws {
        var cellar = Cellar()
        var wine = Wine(name: "Chablis")
        wine.currentPrice = Decimal(string: "39.9")
        wine.currentPriceCurrency = "CHF"
        wine.currentPriceDate = CellarExport.dayFormatter().date(from: "2026-10-18")
        try cellar.addBottles(of: wine, quantity: 1)
        let csv = CellarExport.csv(cellar)
        #expect(csv.hasPrefix(CellarExport.csvColumns.joined(separator: ",")))
        #expect(csv.contains(",39.9,CHF,2026-10-18"))
        var imported = Cellar()
        try imported.importCSV(csv)
        let back = try #require(imported.wines.values.first)
        #expect(back.currentPrice == Decimal(string: "39.9"))
        #expect(back.currentPriceCurrency == "CHF")
        #expect(back.currentPriceDate == wine.currentPriceDate)
        var french = Cellar()
        try french.importCSV("nom,prix actuel,devise\nChablis,\"42,5\",EUR\n")
        #expect(french.wines.values.first?.currentPrice == Decimal(string: "42.5"))
        #expect(french.wines.values.first?.currentPriceCurrency == "EUR")
    }
}
