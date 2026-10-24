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
        struct ImageResolved: Decodable { var url: String; var addresses: [String]; var `public`: Bool }
        var sizeWords: [SizeWords]
        var snippets: [Snippet]
        var pages: [Page]
        var addresses: [Address]
        var resolved: [Resolved]
        var imageAddresses: [Address]
        var imageResolved: [ImageResolved]
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

    /// Private IPv4 ranges, built from their numbers so the repository's checks do not take
    /// them for real addresses.
    @Test func privateAddressesAreNotPublic() {
        func ip(_ parts: Int...) -> String { parts.map(String.init).joined(separator: ".") }
        for address in [ip(10, 0, 0, 1), ip(172, 16, 0, 1), ip(172, 31, 255, 1), ip(192, 168, 1, 1), ip(100, 64, 0, 1),
                        ip(100, 127, 0, 1), ip(192, 0, 0, 8), "::ffff:" + ip(192, 168, 1, 1), "::" + ip(10, 0, 0, 1),
                        "64:ff9b::" + ip(192, 168, 1, 1)] {
            #expect(!PagePrices.arePublic([address]), "\(address)")
        }
        for address in [ip(172, 32, 0, 1), ip(100, 128, 0, 1), ip(192, 0, 2, 1), "::ffff:" + ip(203, 0, 113, 10)] {
            #expect(PagePrices.arePublic([address]), "\(address)")
        }
    }

    @Test func imageAddresses() async throws {
        let vectors = try Self.vectors()
        for vector in vectors.imageAddresses {
            #expect(WebImageDownload.isAllowed(URL(string: vector.url)!) == vector.allowed, "\(vector.url)")
        }
        for vector in vectors.imageResolved {
            let addresses = vector.addresses
            let found = await WebImageDownload.resolvesPublicly(URL(string: vector.url)!) { _ in addresses }
            #expect(found == vector.public, "\(vector.url) \(addresses)")
        }
    }

    @Test func structuredAmountsAreStrict() {
        #expect(PagePrices.structuredAmount("13.90") == Decimal(string: "13.9"))
        #expect(PagePrices.structuredAmount(" 13 ") == 13)
        #expect(PagePrices.structuredAmount("13.999") == 14)
        for text in ["13,90", "1e3", "1E+21", "1.234,50", "-13", "13.", ".5", "", "13.9.1", "١٣"] {
            #expect(PagePrices.structuredAmount(text) == nil, "\(text)")
        }
        #expect(PagePrices.twoDecimals(Decimal(string: "13.9")!) == "13.90")
        #expect(PagePrices.twoDecimals(13) == "13.00")
    }

    /// Hostile pages: the reading stays linear and quick, and stops when asked.
    @Test func hostilePagesAreReadQuickly() {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        let size = PagePrices.maxBytes
        for piece in ["<", "<!--", "<h1", "<script", "<a", "<a b='", "<!x", "&amp;", "é", "<p>CHF 13.00 ", "<h1>"] {
            let html = String(repeating: piece, count: size / piece.utf16.count)
            let start = Date()
            _ = PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: "Les Lacustres", shouldStop: { false })
            _ = PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: nil, shouldStop: { false })
            #expect(Date().timeIntervalSince(start) < 1, "\(piece)")
        }
        let html = String(repeating: "<p>Les Lacustres CHF 13.00</p>", count: 20_000)
        #expect(PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: "Les Lacustres", shouldStop: { true }).isEmpty)
        #expect(!PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: "Les Lacustres", shouldStop: { false }).isEmpty)
    }

    @Test func scannerReadsTagsAndText() {
        let html = #"<html><head><meta property="og:title" content="A &amp; B"><title>T</title></head><body>"#
            + #"<h1 class=x>Les <b>Lacustres</b></h1><!-- CHF 99.00 --><p>1 < 2</p><span itemprop=price content='13.90'>Fr. 13.90</span>"#
            + #"<meta itemprop="priceCurrency" content="CHF"><script type="application/ld+json">{"a":1}</script></body></html>"#
        let document = HTMLScanner.scan(html, shouldStop: { false })!
        #expect(document.heading == "Les Lacustres")
        #expect(document.title == "T")
        #expect(document.meta.first?.key == "og:title" && document.meta.first?.value == "A & B")
        #expect(document.itemPrice == "13.90" && document.itemCurrency == "CHF")
        #expect(document.jsonLD == [#"{"a":1}"#])
        #expect(document.text == "Les Lacustres\n1 < 2\nFr. 13.90")
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
        let text = HTML.visibleText("<html><head><title>T</title></head><body><p>A&nbsp;&euro;<b>1</b>\u{202F}\u{A0}2</p><script>x</script><div>B</div></body></html>")
        #expect(text == "A € 1 2\nB")
        #expect(PagePrices.isHTML(contentType: "text/html; charset=UTF-8"))
        #expect(!PagePrices.isHTML(contentType: "application/json"))
        #expect(!PagePrices.isHTML(contentType: nil))
        let latin = Data([0x50, 0x72, 0x69, 0x78, 0x20, 0x31, 0x33, 0x20, 0x80]) // "Prix 13 €" in windows-1252
        #expect(PagePrices.text(of: latin, contentType: "text/html; charset=windows-1252") == "Prix 13 €")
        #expect(PagePrices.text(of: Data("Prix 13 €".utf8), contentType: "text/html") == "Prix 13 €")
    }
}
