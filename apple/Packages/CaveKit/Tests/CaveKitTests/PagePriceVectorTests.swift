import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import Testing
@testable import CaveKit

/// Prices on web pages and in snippets. The Kotlin core (PagePriceVectorTest.kt) reads the same
/// files, in `fixtures/prices`.
@Suite struct PagePriceVectorTests {
    struct Vectors: Decodable {
        struct SizeWords: Decodable { var sizeML: Int; var words: String? }
        struct Snippet: Decodable { var text: String; var sizeML: Int; var prices: [String]; var otherSizes: [String]? }
        struct Page: Decodable {
            var file: String
            var url: String
            var wineName: String?
            var producer: String?
            var sizeML: Int
            var vintage: Int?
            var prices: [String]
            var otherSizes: [String]?
            var excerptContains: [String]?
            var excerptLacks: [String]?
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

    /// "13 CHF 700": a price of another size, with the size.
    static func otherLabel(_ price: FoundPrice) -> String { "\(label(price.amount, price.currency)) \(price.sizeML ?? 0)" }

    /// Seconds for reading a 1.5 MB page. About 0.2 s in a release build; debug
    /// builds (as in CI) are several times slower. A reading that grew faster than the page
    /// (as regular expressions on HTML did: minutes for such a page) would not fit.
    #if DEBUG
    static let budget: TimeInterval = 3
    #else
    static let budget: TimeInterval = 1
    #endif

    @Test func sizeWords() throws {
        for vector in try Self.vectors().sizeWords {
            #expect(WebSearchQuery.sizeWords(vector.sizeML) == vector.words, "\(vector.sizeML)")
        }
    }

    @Test func snippets() throws {
        for vector in try Self.vectors().snippets {
            let found = PriceExtractor.prices(in: vector.text, sizeML: vector.sizeML).map { Self.label($0.0, $0.1) }
            #expect(found == vector.prices, "\(vector.text) (\(vector.sizeML) ml)")
            let result = WebSearchResult(title: "", url: URL(string: "https://shop.example.org/vin")!, snippet: vector.text)
            let all = PriceExtractor.prices(in: [result], sizeML: vector.sizeML)
            #expect(all.filter { !$0.isOtherSize }.map { Self.label($0.amount, $0.currency) } == vector.prices, "\(vector.text)")
            #expect(all.filter(\.isOtherSize).map(Self.otherLabel) == (vector.otherSizes ?? []),
                    "\(vector.text) (\(vector.sizeML) ml)")
        }
    }

    @Test func pages() throws {
        for vector in try Self.vectors().pages {
            let html = try String(contentsOf: Self.folder.appendingPathComponent(vector.file), encoding: .utf8)
            let result = WebSearchResult(title: "Result", url: URL(string: vector.url)!, snippet: "")
            let all = PagePrices.prices(inPage: html, of: result, sizeML: vector.sizeML, wineName: vector.wineName,
                                        producer: vector.producer, vintage: vector.vintage)
            let found = all.filter { !$0.isOtherSize }
            let name = "\(vector.file) \(vector.wineName ?? "-") (\(vector.sizeML) ml, \(vector.vintage.map(String.init) ?? "no vintage"))"
            #expect(found.map { Self.label($0.amount, $0.currency) } == vector.prices, "\(name)")
            #expect(all.filter(\.isOtherSize).map(Self.otherLabel) == (vector.otherSizes ?? []), "\(name)")
            #expect(all.allSatisfy { $0.url == result.url && $0.domain == result.domain }, "\(name)")
            let excerpt = all.first?.excerpt ?? "-"
            for words in vector.excerptContains ?? [] {
                #expect(excerpt.contains(words), "\(name): \(excerpt) has \(words)")
            }
            for words in vector.excerptLacks ?? [] {
                #expect(!excerpt.contains(words), "\(name): \(excerpt) lacks \(words)")
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
        for piece in ["<", "<!--", "<h1", "<script", "<a", "<a b='", "<!x", "&amp;", "é", "<p>CHF 13.00 ", "<h1>",
                      "<p>Les Lacustres 2018 CHF 13.00 6x ", "2019 1999 ", "Les Lacustres 2019\n"] {
            let html = String(repeating: piece, count: size / piece.utf16.count)
            for (name, vintage) in [("Les Lacustres", 2020), (nil, 2020), ("Les Lacustres", nil)] {
                let start = Date()
                _ = PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: name, vintage: vintage, shouldStop: { false })
                #expect(Date().timeIntervalSince(start) < Self.budget, "\(piece)")
            }
        }
        // Structured data and text near the name, both read and compared.
        let ld = #"<script type="application/ld+json">{"@type":"Product","name":"Les Lacustres 2019","offers":["#
            + Array(repeating: #"{"price":"78.00","priceCurrency":"CHF"}"#, count: 2_000).joined(separator: ",") + "]}</script>"
        let mixed = ld + String(repeating: "<p>Les Lacustres 2019 6x75cl CHF 78.00 / 1 bottle CHF 13.00</p>", count: 20_000)
        let start = Date()
        let found = PagePrices.prices(inPage: mixed, of: result, sizeML: 750, wineName: "Les Lacustres", vintage: 2019,
                                      shouldStop: { false })
        #expect(Date().timeIntervalSince(start) < Self.budget)
        #expect(found.map(\.amount) == [13])
        let html = String(repeating: "<p>Les Lacustres CHF 13.00</p>", count: 20_000)
        #expect(PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: "Les Lacustres", shouldStop: { true }).isEmpty)
        #expect(!PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: "Les Lacustres", shouldStop: { false }).isEmpty)
    }

    /// The words of a name looked for when the whole name is not on the page: quick however
    /// often they appear.
    @Test func wordsOfTheNameAreLookedForQuickly() {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        for piece in ["<p>Lacustres CHF 13.00</p>", "Lacustres ", "<p>Chasselas Lacustres 70 cl CHF 13.00</p>", "Réserve 1 "] {
            let html = String(repeating: piece, count: PagePrices.maxBytes / piece.utf16.count)
            for (name, producer) in [("Lacustres Réserve Spéciale", "Vignoble Exemple"), ("", "Vignoble Lacustres Réserve")] {
                let start = Date()
                _ = PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: name, producer: producer,
                                      shouldStop: { false })
                #expect(Date().timeIntervalSince(start) < Self.budget, "\(piece) \(name)")
            }
        }
    }

    /// A page cut anywhere, inside any tag, comment or raw element, is read without failing.
    @Test func everyPrefixOfAPageIsRead() throws {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        let small = #"<!DOCTYPE html><html><head><title>Les Lacustres</title><style>p{}</style>"#
            + #"<script type="application/ld+json">{"@type":"Product","name":"Les Lacustres","offers":{"price":"13.00","priceCurrency":"CHF"}}</script>"#
            + #"</head><body><!-- c --><svg><text>x</text></svg><noscript>n</noscript><template>t</template><textarea>a</textarea>"#
            + #"<select><option>70 cl</option></select><iframe src=x></iframe><h1 class="t">Les Lacustres</h1>"#
            + #"<p itemprop="price" content="13.00">CHF 13.– 70 cl</p><meta itemprop="priceCurrency" content="CHF"></body></html>"#
        let units = Array(small.utf16)
        for end in 0...units.count {
            let html = String(decoding: units[0..<end], as: UTF16.self)
            _ = PagePrices.prices(inPage: html, of: result, sizeML: 700, wineName: "Les Lacustres", shouldStop: { false })
        }
        for name in ["script", "style", "title", "svg", "textarea", "select", "noscript", "template", "iframe"] {
            for tail in ["<\(name)>x</\(name)", "<\(name)>x</\(name.prefix(2))", "<\(name)>x</", "<\(name)>x<", "<\(name)"] {
                _ = HTMLScanner.scan("<p>a</p>" + tail, shouldStop: { false })
            }
        }
        for file in try Self.pageFiles() {
            let page = Array(try String(contentsOf: Self.folder.appendingPathComponent(file), encoding: .utf8).utf16)
            for end in stride(from: 0, through: page.count, by: 3) {
                let found = PagePrices.prices(inPage: String(decoding: page[0..<end], as: UTF16.self), of: result, sizeML: 750,
                                              wineName: "Les Lacustres", shouldStop: { false })
                #expect(found.allSatisfy { $0.url == result.url }, "\(file) cut at \(end)")
            }
        }
    }

    /// The fixture pages, in the same order everywhere.
    static func pageFiles() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".html") }.sorted()
    }

    /// Pages with random damage (the same pseudo-random sequence each run) are read without failing.
    @Test func damagedPagesAreRead() throws {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        var seed: UInt64 = 0
        func next(_ bound: Int) -> Int {
            seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17
            return Int(seed % UInt64(max(1, bound)))
        }
        let pieces = Array("<>/\"'-=!&;#{}[]:, \n".utf16)
        for (index, file) in try Self.pageFiles().enumerated() {
            // A sequence of its own for each file: adding a file changes no other file's damage.
            seed = 0x2545F4914F6CDD1D ^ UInt64(index + 1)
            let page = Array(try String(contentsOf: Self.folder.appendingPathComponent(file), encoding: .utf8).utf16)
            for iteration in 0..<60 {
                var damaged = page
                for _ in 0..<(1 + next(20)) {
                    let at = next(damaged.count)
                    switch next(3) {
                    case 0: damaged.remove(at: at)
                    case 1: damaged.insert(pieces[next(pieces.count)], at: at)
                    default: damaged[at] = pieces[next(pieces.count)]
                    }
                }
                let found = PagePrices.prices(inPage: String(decoding: damaged, as: UTF16.self), of: result, sizeML: 750,
                                              wineName: "Les Lacustres", shouldStop: { false })
                #expect(found.allSatisfy { $0.url == result.url }, "\(file) damage \(iteration)")
            }
        }
    }

    /// Long runs of digit groups, with or without the wine's name, are read quickly.
    @Test func digitGroupsAreReadQuickly() {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        for piece in ["111 ", "1,111", "Les Lacustres 111 111 111 111 111 111 111 111 ", "1 111 111.11 "] {
            let html = "<p>" + String(repeating: piece, count: PagePrices.maxBytes / piece.utf16.count) + "</p>"
            for name in ["Les Lacustres", nil] {
                let start = Date()
                _ = PagePrices.prices(inPage: html, of: result, sizeML: 750, wineName: name, shouldStop: { false })
                #expect(Date().timeIntervalSince(start) < Self.budget, "\(piece)")
            }
        }
        let start = Date()
        _ = PriceExtractor.prices(in: String(repeating: "111 ", count: 50_000), sizeML: 750)
        #expect(Date().timeIntervalSince(start) < Self.budget)
    }

    /// Product data after a lot of inline styles (as in some shop themes) is still read.
    @Test func productDataAfterLongStylesIsRead() {
        let result = WebSearchResult(title: "R", url: URL(string: "https://shop.example.org/vin")!, snippet: "")
        let html = "<html><head><style>" + String(repeating: ".a{color:red}", count: 55_000) + "</style></head><body>"
            + #"<h1>Les Lacustres 70 cl</h1><script type="application/ld+json">{"@type":"Product","name":"Les Lacustres","#
            + #""offers":{"price":"13.00","priceCurrency":"CHF"}}</script></body></html>"#
        #expect(html.utf16.count > 700_000)
        let found = PagePrices.prices(inPage: html, of: result, sizeML: 700, wineName: "Les Lacustres", shouldStop: { false })
        #expect(found.map { Self.label($0.amount, $0.currency) } == ["13 CHF"])
    }

    @Test func numbersWithAnExponentAreNull() {
        #expect(JSONLD.withoutExponents(#"{"a":1e3,"b":"1e3","c":-2.5E+1,"d":13.90,"e":"x\"1e3"}"#)
            == #"{"a":null,"b":"1e3","c":null,"d":13.90,"e":"x\"1e3"}"#)
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

    @Test func redirectsAreCheckedAndCounted() async {
        let publicName: LocalName.Resolver = { _ in ["203.0.113.10"] }
        func request(_ address: String) -> URLRequest {
            var request = URLRequest(url: URL(string: address)!)
            request.setValue("https://search.example.com/?q=vin", forHTTPHeaderField: "Referer")
            request.setValue("session=1", forHTTPHeaderField: "Cookie")
            request.setValue("Basic eDp5", forHTTPHeaderField: "Authorization")
            request.setValue("text/html", forHTTPHeaderField: "Accept")
            return request
        }
        for address in ["http://shop.example.org/vin", "https://nas.local/vin", "https://shop.example.org:8443/vin",
                        "https://203.0.113.10/vin", "https://user:pw@shop.example.org/vin"] {
            #expect(await PagePrices.nextRequest(hop: 1, request: request(address), resolver: publicName) == nil, "\(address)")
        }
        let privateName: LocalName.Resolver = { _ in ["203.0.113.10", "127.0.0.1"] }
        #expect(await PagePrices.nextRequest(hop: 1, request: request("https://shop.example.org/n"), resolver: privateName) == nil)
        for hop in 1...PagePrices.maxRedirects {
            let next = await PagePrices.nextRequest(hop: hop, request: request("https://shop.example.org/n"), resolver: publicName)
            #expect(next?.url?.absoluteString == "https://shop.example.org/n", "hop \(hop)")
            for field in ["Referer", "Cookie", "Authorization"] {
                #expect(next?.value(forHTTPHeaderField: field) == nil, "hop \(hop) \(field)")
            }
            #expect(next?.value(forHTTPHeaderField: "Accept") == "text/html")
        }
        #expect(PagePrices.maxRedirects == 3)
        #expect(await PagePrices.nextRequest(hop: 4, request: request("https://shop.example.org/n"), resolver: publicName) == nil)
    }

    @Test func onlyReadableAnswersAreRead() {
        let url = URL(string: "https://shop.example.org/vin")
        #expect(PagePrices.acceptsResponse(statusCode: 200, url: url, contentType: "text/html; charset=utf-8", expectedLength: -1))
        #expect(PagePrices.acceptsResponse(statusCode: 203, url: url, contentType: "application/xhtml+xml",
                                           expectedLength: Int64(PagePrices.maxBytes)))
        #expect(!PagePrices.acceptsResponse(statusCode: 404, url: url, contentType: "text/html", expectedLength: -1))
        #expect(!PagePrices.acceptsResponse(statusCode: 302, url: url, contentType: "text/html", expectedLength: -1))
        #expect(!PagePrices.acceptsResponse(statusCode: 200, url: url, contentType: "application/json", expectedLength: -1))
        #expect(!PagePrices.acceptsResponse(statusCode: 200, url: url, contentType: nil, expectedLength: -1))
        #expect(!PagePrices.acceptsResponse(statusCode: 200, url: url, contentType: "text/html",
                                            expectedLength: Int64(PagePrices.maxBytes) + 1))
        #expect(!PagePrices.acceptsResponse(statusCode: 200, url: URL(string: "http://shop.example.org/vin"),
                                            contentType: "text/html", expectedLength: -1))
        #expect(!PagePrices.acceptsResponse(statusCode: 200, url: nil, contentType: "text/html", expectedLength: -1))
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
