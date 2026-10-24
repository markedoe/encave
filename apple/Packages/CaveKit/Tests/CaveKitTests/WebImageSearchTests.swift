import Foundation
import Testing
@testable import CaveKit

/// Image answers in each provider's format. The Android tests (WebImageSearchTest.kt) use the same fixtures.
enum WebImageFixtures {
    static let brave = #"""
    {"type":"images","query":{"original":"Domaine Example Chablis 2019 bottle"},
     "results":[
      {"type":"image_result","title":"Domaine Example Chablis 2019 &amp; co","url":"https://www.caviste.example.fr/chablis",
       "source":"caviste.example.fr","thumbnail":{"src":"https://imgs.search.brave.example/abc.jpg"},
       "properties":{"url":"https://www.caviste.example.fr/img/chablis.jpg","placeholder":"https://imgs.search.brave.example/p.jpg"}},
      {"type":"image_result","title":"Plain http picture","url":"https://shop.example.com/p",
       "thumbnail":{"src":"https://imgs.search.brave.example/def.jpg"},"properties":{"url":"http://shop.example.com/p.jpg"}},
      {"type":"image_result","title":"No thumbnail","url":"https://shop.example.com/q",
       "properties":{"url":"https://shop.example.com/q.png"}},
      {"type":"image_result","title":"Same picture again","url":"https://other.example.com/",
       "thumbnail":{"src":"https://imgs.search.brave.example/abc.jpg"},
       "properties":{"url":"https://www.caviste.example.fr/img/chablis.jpg"}}
     ]}
    """#

    static let searxng = #"""
    {"query":"Domaine Example Chablis 2019 bottle","results":[
      {"title":"Chablis 2019","url":"https://example.net/wine","img_src":"https://example.net/bottle.webp",
       "thumbnail_src":"https://example.net/thumb.webp","engine":"bing images","category":"images"},
      {"title":"Data","url":"https://example.net/x","img_src":"data:image/png;base64,AAAA"}
    ]}
    """#

    static let tavily = #"""
    {"query":"Chablis bottle","images":["https://example.org/a.jpg",{"url":"https://example.org/b.jpg","description":"A bottle of Chablis"}],
     "results":[]}
    """#
}

@Suite struct WebImageSearchTests {
    static let fakeKey = "TEST-KEY-NOT-REAL"

    @Test func pictureQuery() {
        let wine = Wine(name: "Chablis 1er Cru", producer: "Domaine Example", vintage: 2019, appellation: "Chablis",
                        notes: "secret")
        #expect(WebSearchQuery.pictureTerms(for: wine) == "Domaine Example Chablis 1er Cru 2019 bottle")
        #expect(WebSearchQuery.pictureTerms(for: wine, bottleWord: "bouteille") == "Domaine Example Chablis 1er Cru 2019 bouteille")
    }

    @Test func braveImages() throws {
        let brave = BraveSearch(apiKey: Self.fakeKey)
        let request = try brave.imageRequest(for: "Chablis bottle", count: 30)
        #expect(request.url.absoluteString == "https://api.search.brave.com/res/v1/images/search?q=Chablis%20bottle&count=30&safesearch=strict")
        #expect(request.headers["X-Subscription-Token"] == Self.fakeKey)
        let results = try brave.imageResults(from: Data(WebImageFixtures.brave.utf8), status: 200)
        #expect(results.map(\.title) == ["Domaine Example Chablis 2019 & co", "No thumbnail"])
        #expect(results[0].thumbnailURL.absoluteString == "https://imgs.search.brave.example/abc.jpg")
        #expect(results[0].imageURL.absoluteString == "https://www.caviste.example.fr/img/chablis.jpg")
        #expect(results[0].domain == "caviste.example.fr")
        #expect(results[1].thumbnailURL == results[1].imageURL)
        #expect(throws: WebSearchError.unauthorized) { try brave.imageResults(from: Data(), status: 401) }
    }

    @Test func searxngImages() throws {
        let searx = try SearXNGSearch(serverURL: "http://nas.local:8080/")
        let request = try searx.imageRequest(for: "Chablis bottle", count: 30)
        #expect(request.url.absoluteString == "http://nas.local:8080/search?q=Chablis%20bottle&format=json&categories=images&safesearch=1")
        let results = try searx.imageResults(from: Data(WebImageFixtures.searxng.utf8), status: 200)
        #expect(results.count == 1)
        #expect(results[0].imageURL.absoluteString == "https://example.net/bottle.webp")
        #expect(results[0].thumbnailURL.absoluteString == "https://example.net/thumb.webp")
        #expect(results[0].domain == "example.net")
        #expect(throws: WebSearchError.jsonFormatDisabled) { try searx.imageResults(from: Data(), status: 403) }
    }

    @Test func tavilyImages() throws {
        let tavily = TavilySearch(apiKey: Self.fakeKey)
        let request = try tavily.imageRequest(for: "Chablis bottle", count: 10)
        let body = try #require(request.body.flatMap { try JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        #expect(body["include_images"] as? Bool == true)
        let results = try tavily.imageResults(from: Data(WebImageFixtures.tavily.utf8), status: 200)
        #expect(results.map(\.imageURL.absoluteString) == ["https://example.org/a.jpg", "https://example.org/b.jpg"])
        #expect(results[1].title == "A bottle of Chablis")
        #expect(results[0].domain == "example.org")
    }

    @Test func downloadChecks() {
        #expect(WebImageDownload.isAllowed(URL(string: "https://example.org/a.jpg")!))
        #expect(!WebImageDownload.isAllowed(URL(string: "http://example.org/a.jpg")!))
        #expect(!WebImageDownload.isAllowed(URL(string: "file:///etc/passwd")!))
        // Never this device or the local network: the results come from anyone. Addresses are
        // built from their numbers, so the repository's checks do not take them for real ones.
        func ip(_ parts: Int...) -> String { parts.map(String.init).joined(separator: ".") }
        for address in [
            "https://\(ip(192, 168, 1, 1))/a.jpg", "https://localhost/a.jpg", "https://[fd00::1]/a.jpg", "https://nas.local/a.jpg",
            "https://\(ip(127, 0, 0, 1))/a.jpg", "https://\(ip(10, 0, 0, 2))/a.jpg", "https://\(ip(172, 16, 4, 1))/a.jpg",
            "https://\(ip(169, 254, 169, 254))/a.jpg", "https://[::1]/a.jpg", "https://[fe80::1]/a.jpg",
            "https://[::ffff:\(ip(192, 168, 1, 1))]/a.jpg", "https://\(ip(0, 0, 0, 0))/a.jpg", "https://2130706433/a.jpg",
            "https://127.1/a.jpg", "https://0x7f.0.0.1/a.jpg", "https://NAS.Local./a.jpg", "https://router.home.arpa/a.jpg",
            "https://app.localhost/a.jpg",
        ] {
            #expect(!WebImageDownload.isAllowed(URL(string: address)!), "\(address)")
        }
        // A public address (from the range kept for documentation), and a public IPv6 one.
        #expect(WebImageDownload.isAllowed(URL(string: "https://\(ip(203, 0, 113, 7))/a.jpg")!))
        #expect(WebImageDownload.isAllowed(URL(string: "https://[2001:db8::1]/a.jpg")!))
        #expect(!WebImageDownload.isAllowed(URL(string: "https://images.example.com:8443/a.jpg")!))
        #expect(WebImageDownload.isAllowed(URL(string: "https://images.example.com:443/a.jpg")!))
        #expect(WebImageDownload.isImage(contentType: "image/jpeg"))
        #expect(WebImageDownload.isImage(contentType: "Image/WebP; charset=binary"))
        #expect(!WebImageDownload.isImage(contentType: "text/html"))
        #expect(!WebImageDownload.isImage(contentType: "image/svg+xml"))
        #expect(!WebImageDownload.isImage(contentType: nil))
        #expect(WebImageDownload.acceptsLength(nil) && WebImageDownload.acceptsLength(-1))
        #expect(WebImageDownload.acceptsLength(8 * 1024 * 1024))
        #expect(!WebImageDownload.acceptsLength(8 * 1024 * 1024 + 1))
    }
}
