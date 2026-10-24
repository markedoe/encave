import Foundation
import Testing
@testable import CaveKit

/// Prices on web pages and in snippets. The Kotlin core (PagePriceVectorTest.kt) reads the same
/// files, in `fixtures/prices`.
@Suite struct PagePriceVectorTests {
    struct Vectors: Decodable {
        struct SizeWords: Decodable { var sizeML: Int; var words: String? }
        struct Snippet: Decodable { var text: String; var sizeML: Int; var prices: [String] }
        struct Page: Decodable {
            var file: String
            var url: String
            var wineName: String?
            var sizeML: Int
            var prices: [String]
            var excerptContains: [String]?
        }
        struct Address: Decodable { var url: String; var allowed: Bool }
        struct Resolved: Decodable { var addresses: [String]; var `public`: Bool }
        var sizeWords: [SizeWords]
        var snippets: [Snippet]
        var pages: [Page]
        var addresses: [Address]
        var resolved: [Resolved]
    }

    static let folder = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
        .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
        .deletingLastPathComponent().deletingLastPathComponent() // apple
        .deletingLastPathComponent().appendingPathComponent("fixtures/prices")

    static func vectors() throws -> Vectors {
        try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: folder.appendingPathComponent("vectors.json")))
    }

    static func label(_ amount: Decimal, _ currency: String) -> String { "\(amount) \(currency)" }

    @Test func sizeWords() throws {
        for vector in try Self.vectors().sizeWords {
            #expect(WebSearchQuery.sizeWords(vector.sizeML) == vector.words, "\(vector.sizeML)")
        }
    }

    @Test func snippets() throws {
        for vector in try Self.vectors().snippets {
            let found = PriceExtractor.prices(in: vector.text, sizeML: vector.sizeML).map { Self.label($0.0, $0.1) }
            #expect(found == vector.prices, "\(vector.text) (\(vector.sizeML) ml)")
        }
    }

    @Test func pages() throws {
        for vector in try Self.vectors().pages {
            let html = try String(contentsOf: Self.folder.appendingPathComponent(vector.file), encoding: .utf8)
            let result = WebSearchResult(title: "Result", url: URL(string: vector.url)!, snippet: "")
            let found = PagePrices.prices(inPage: html, of: result, sizeML: vector.sizeML, wineName: vector.wineName)
            let name = "\(vector.file) (\(vector.sizeML) ml)"
            #expect(found.map { Self.label($0.amount, $0.currency) } == vector.prices, "\(name)")
            #expect(found.allSatisfy { $0.url == result.url && $0.domain == result.domain }, "\(name)")
            for words in vector.excerptContains ?? [] {
                #expect(found.first?.excerpt.contains(words) == true, "\(name): \(found.first?.excerpt ?? "-") has \(words)")
            }
        }
    }

    @Test func addresses() throws {
        for vector in try Self.vectors().addresses {
            #expect(PagePrices.isAllowed(URL(string: vector.url)!) == vector.allowed, "\(vector.url)")
        }
    }

    @Test func resolvedAddresses() throws {
        for vector in try Self.vectors().resolved {
            #expect(PagePrices.arePublic(vector.addresses) == vector.public, "\(vector.addresses)")
        }
    }

    @Test func resolvingChecksEveryAddress() async {
        let url = URL(string: "https://shop.example.org/vin")!
        #expect(await PagePrices.resolvesPublicly(url) { _ in ["203.0.113.10"] })
        #expect(await !PagePrices.resolvesPublicly(url) { _ in ["203.0.113.10", "127.0.0.1"] })
        #expect(await !PagePrices.resolvesPublicly(url) { _ in throw LocalNetworkError.notFound })
        #expect(await !PagePrices.resolvesPublicly(URL(string: "https://localhost/vin")!) { _ in ["203.0.113.10"] })
    }

    @Test func onlyTheFirstPagesAreOpened() {
        let urls = ["https://a.example.com", "http://b.example.com", "https://c.example.com", "https://localhost",
                    "https://e.example.com", "https://f.example.com"]
        let results = urls.map { WebSearchResult(title: "R", url: URL(string: $0)!, snippet: "") }
        #expect(PagePrices.pages(of: results).map(\.url.absoluteString)
            == ["https://a.example.com", "https://c.example.com", "https://e.example.com"])
    }

    @Test func pagePricesJoinTheSnippetPrices() {
        let url = URL(string: "https://shop.example.org/vin")!
        let snippet = FoundPrice(amount: 13, currency: "CHF", domain: "shop.example.org", url: url, excerpt: "CHF 13")
        let page = [FoundPrice(amount: Decimal(string: "13.00")!, currency: "CHF", domain: "shop.example.org", url: url,
                               excerpt: "Les Lacustres · CHF 13.00"),
                    FoundPrice(amount: 14, currency: "CHF", domain: "shop.example.org", url: url, excerpt: "")]
        #expect(PagePrices.merging(page, into: [snippet]).map(\.amount) == [13, 14])
    }

    @Test func pageText() {
        let text = HTML.visibleText("<html><head><title>T</title></head><body><p>A&nbsp;&euro;<b>1</b></p><script>x</script><div>B</div></body></html>")
        #expect(text == "A € 1\nB")
        #expect(PagePrices.isHTML(contentType: "text/html; charset=UTF-8"))
        #expect(!PagePrices.isHTML(contentType: "application/json"))
        #expect(!PagePrices.isHTML(contentType: nil))
        let latin = Data([0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x33, 0x20, 0x80]) // "Prix 13 €" in windows-1252
        #expect(PagePrices.text(of: latin, contentType: "text/html; charset=windows-1252") == "Prix 13 €")
        #expect(PagePrices.text(of: Data("Prix 13 €".utf8), contentType: "text/html") == "Prix 13 €")
    }
}
