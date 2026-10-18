import Foundation
import Testing
@testable import CaveKit

/// Answers in each provider's format. The Android tests (WebSearchTest.kt) use the same fixtures.
enum WebSearchFixtures {
    static let brave = #"""
    {"type":"search","query":{"original":"Domaine Example Chablis 1er Cru 2019 wine"},
     "web":{"type":"search","results":[
      {"title":"Domaine Example <strong>Chablis</strong> 1er Cru 2019","url":"https://www.example.com/chablis-2019",
       "description":"A <strong>Chardonnay</strong> from Burgundy &amp; best from 2023 to 2031. It&#39;s &quot;mineral&quot;.",
       "meta_url":{"hostname":"www.example.com"}},
      {"title":"No address","description":"skipped"},
      {"title":"Script","url":"javascript:alert(1)","description":"skipped"},
      {"title":"","url":"http://shop.example.org/p?id=1","description":"  Two\n lines  "}
     ]}}
    """#

    static let braveNoWeb = #"{"type":"search","query":{"original":"zzz"}}"#

    static let searxng = #"""
    {"query":"Domaine Example Chablis 1er Cru 2019 wine","number_of_results":0,
     "results":[
      {"title":"Chablis 1er Cru 2019 – Domaine Example","url":"https://example.net/wine","content":"Region: Burgundy. Grape: Chardonnay.","engine":"duckduckgo"},
      {"title":"Bad","url":"ftp://example.net/file","content":"skipped"}
     ],"answers":[],"suggestions":[],"unresponsive_engines":[]}
    """#

    static let tavily = #"""
    {"query":"Domaine Example Chablis 1er Cru 2019 wine","answer":null,"images":[],
     "results":[
      {"title":"Domaine Example","url":"https://example.org/domaine","content":"Family estate in Chablis, France.","score":0.91}
     ],"response_time":1.2}
    """#
}

@Suite struct WebSearchTests {
    static let fakeKey = "TEST-KEY-NOT-REAL"

    // MARK: Query

    @Test func queryHasProducerNameAppellationAndVintage() {
        let wine = Wine(name: "1er Cru Montmains", producer: "Domaine Example", vintage: 2019,
                        region: "Burgundy", country: "France", appellation: "Chablis", grapes: ["Chardonnay"],
                        notes: "Bought at the shop", tags: ["gift"])
        #expect(WebSearchQuery.terms(for: wine) == "Domaine Example 1er Cru Montmains Chablis 2019 wine")
    }

    @Test func queryDoesNotRepeatParts() {
        #expect(WebSearchQuery.terms(producer: "Château Margaux", name: "Château Margaux", appellation: "Margaux",
                                     vintage: 2015) == "Château Margaux 2015 wine")
        #expect(WebSearchQuery.terms(producer: "domaine example", name: "Domaine Example Chablis", appellation: "chablis",
                                     vintage: nil) == "Domaine Example Chablis wine")
        #expect(WebSearchQuery.terms(producer: "Maison Example Frères", name: "Maison Example", appellation: nil,
                                     vintage: nil, wineWord: "vin") == "Maison Example Frères vin")
        #expect(WebSearchQuery.terms(producer: "  ", name: "  Cuvée   X ", appellation: "", vintage: 0) == "Cuvée X wine")
        #expect(WebSearchQuery.terms(producer: nil, name: "", appellation: nil, vintage: nil) == "")
    }

    @Test func queryIsPercentEncoded() throws {
        let request = try BraveSearch(apiKey: Self.fakeKey).request(for: "Moët & Chandon+1 2012", count: 50)
        #expect(request.url.absoluteString
            == "https://api.search.brave.com/res/v1/web/search?q=Mo%C3%ABt%20%26%20Chandon%2B1%202012&count=20")
    }

    // MARK: Brave

    @Test func braveRequest() throws {
        let request = try BraveSearch(apiKey: Self.fakeKey).request(for: "Chablis 2019 wine", count: 10)
        #expect(request.method == "GET")
        #expect(request.url.absoluteString == "https://api.search.brave.com/res/v1/web/search?q=Chablis%202019%20wine&count=10")
        #expect(request.headers["X-Subscription-Token"] == Self.fakeKey)
        #expect(request.headers["Accept"] == "application/json")
        #expect(request.body == nil)
    }

    @Test func braveResults() throws {
        let results = try BraveSearch(apiKey: Self.fakeKey).results(from: Data(WebSearchFixtures.brave.utf8), status: 200)
        #expect(results.count == 2)
        #expect(results[0].title == "Domaine Example Chablis 1er Cru 2019")
        #expect(results[0].snippet == "A Chardonnay from Burgundy & best from 2023 to 2031. It's \"mineral\".")
        #expect(results[0].domain == "example.com")
        #expect(results[1].title == "shop.example.org")
        #expect(results[1].snippet == "Two lines")
        #expect(results[1].domain == "shop.example.org")
        #expect(try BraveSearch(apiKey: Self.fakeKey).results(from: Data(WebSearchFixtures.braveNoWeb.utf8), status: 200).isEmpty)
    }

    @Test func braveErrors() {
        let brave = BraveSearch(apiKey: Self.fakeKey)
        #expect(throws: WebSearchError.unauthorized) { try brave.results(from: Data("{}".utf8), status: 401) }
        #expect(throws: WebSearchError.unauthorized) { try brave.results(from: Data("{}".utf8), status: 403) }
        #expect(throws: WebSearchError.rateLimited) { try brave.results(from: Data("{}".utf8), status: 429) }
        #expect(throws: WebSearchError.server(status: 500)) { try brave.results(from: Data(), status: 500) }
        #expect(throws: WebSearchError.invalidResponse) { try brave.results(from: Data("<html>".utf8), status: 200) }
    }

    // MARK: SearXNG

    @Test func searxngRequest() throws {
        for address in ["http://nas.local:8080", "http://nas.local:8080/", " http://nas.local:8080/search?q=x "] {
            let request = try SearXNGSearch(serverURL: address).request(for: "Chablis 2019", count: 10)
            #expect(request.url.absoluteString == "http://nas.local:8080/search?q=Chablis%202019&format=json")
            #expect(request.method == "GET")
        }
        let path = try SearXNGSearch(serverURL: "https://search.example.org/searx/").request(for: "a", count: 5)
        #expect(path.url.absoluteString == "https://search.example.org/searx/search?q=a&format=json")
        #expect(throws: WebSearchError.invalidServerURL) { try SearXNGSearch(serverURL: "nas.local:8080") }
        #expect(throws: WebSearchError.invalidServerURL) { try SearXNGSearch(serverURL: "") }
        #expect(throws: WebSearchError.invalidServerURL) { try SearXNGSearch(serverURL: "ftp://nas.local") }
    }

    @Test func searxngResults() throws {
        let searx = try SearXNGSearch(serverURL: "http://nas.local:8080")
        let results = try searx.results(from: Data(WebSearchFixtures.searxng.utf8), status: 200)
        #expect(results.map(\.title) == ["Chablis 1er Cru 2019 – Domaine Example"])
        #expect(results[0].snippet == "Region: Burgundy. Grape: Chardonnay.")
        #expect(results[0].domain == "example.net")
        #expect(throws: WebSearchError.jsonFormatDisabled) { try searx.results(from: Data(), status: 403) }
    }

    // MARK: Tavily

    @Test func tavilyRequest() throws {
        let request = try TavilySearch(apiKey: Self.fakeKey).request(for: "Chablis 2019", count: 8)
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://api.tavily.com/search")
        #expect(request.headers["Authorization"] == "Bearer \(Self.fakeKey)")
        let body = try #require(request.body.flatMap { try JSONSerialization.jsonObject(with: $0) as? [String: Any] })
        #expect(body["query"] as? String == "Chablis 2019")
        #expect(body["max_results"] as? Int == 8)
        #expect(body.keys.sorted() == ["max_results", "query", "search_depth"])
    }

    @Test func tavilyResults() throws {
        let results = try TavilySearch(apiKey: Self.fakeKey).results(from: Data(WebSearchFixtures.tavily.utf8), status: 200)
        #expect(results == [WebSearchResult(title: "Domaine Example", url: URL(string: "https://example.org/domaine")!,
                                            snippet: "Family estate in Chablis, France.")])
    }

    @Test func servicesNeedTheirKeyOrServer() throws {
        #expect(throws: WebSearchError.missingKey) { try WebSearchService.brave.provider(apiKey: " ", serverURL: "") }
        #expect(throws: WebSearchError.missingKey) { try WebSearchService.tavily.provider(apiKey: nil, serverURL: "") }
        #expect(throws: WebSearchError.invalidServerURL) { try WebSearchService.searxng.provider(apiKey: nil, serverURL: "") }
        #expect(try WebSearchService.brave.provider(apiKey: Self.fakeKey, serverURL: "") is BraveSearch)
        #expect(try WebSearchService.searxng.provider(apiKey: nil, serverURL: "http://nas.local:8080") is SearXNGSearch)
        #expect(WebSearchService.allCases.filter(\.requiresKey) == [.brave, .tavily])
    }

    @Test func entities() {
        #expect(WebSearchText.plain("<b>Saint-&Eacute;milion</b> &#233;t&#xE9; &hellip; &unknown; & co") == "Saint-&Eacute;milion été … &unknown; & co")
    }

    // MARK: Suggested details

    @Test func promptListsResultsWithShortSnippets() throws {
        let results = try BraveSearch(apiKey: Self.fakeKey).results(from: Data(WebSearchFixtures.brave.utf8), status: 200)
        let prompt = WebSearchSuggestions.prompt(terms: "Chablis 2019 wine", results: results, limit: 1, snippetLength: 10)
        #expect(prompt.contains("this wine: Chablis 2019 wine."))
        #expect(prompt.contains("1. Domaine Example Chablis 1er Cru 2019 (example.com)\nA Chardonn…"))
        #expect(!prompt.contains("shop.example.org"))
    }

    @Test func cleanedDropsImpossibleValues() {
        let proposed = WineDetails(producer: " Domaine  Example ", region: String(repeating: "x", count: 81),
                                   appellation: "Chablis Premier Cru", country: "France",
                                   grapes: ["Chardonnay", "chardonnay", ""], drinkFrom: 2017, drinkUntil: 2030)
        let cleaned = WebSearchSuggestions.cleaned(proposed, vintage: 2019, currentYear: 2026)
        #expect(cleaned.producer == "Domaine Example")
        #expect(cleaned.region == "")
        #expect(cleaned.grapes == ["Chardonnay"])
        #expect(cleaned.drinkFrom == nil && cleaned.drinkUntil == nil)
        let window = WebSearchSuggestions.cleaned(WineDetails(drinkFrom: 2023, drinkUntil: 2031), vintage: 2019, currentYear: 2026)
        #expect(window.drinkFrom == 2023 && window.drinkUntil == 2031)
        let backwards = WebSearchSuggestions.cleaned(WineDetails(drinkFrom: 2031, drinkUntil: 2023), vintage: nil, currentYear: 2026)
        #expect(backwards.drinkFrom == nil)
        let zero = WebSearchSuggestions.cleaned(WineDetails(drinkFrom: 0, drinkUntil: 0), vintage: nil, currentYear: 2026)
        #expect(zero.drinkFrom == nil)
    }

    @Test func suggestionsListOnlyNewValues() {
        let current = WineDetails(producer: "Domaine Example", region: "", appellation: "chablis", country: "France",
                                  grapes: ["Chardonnay"], drinkFrom: 2021, drinkUntil: 2027)
        let proposed = WineDetails(producer: "Domaine Exemple", region: "Burgundy", appellation: "Chablis", country: "",
                                   grapes: ["chardonnay"], drinkFrom: 2023, drinkUntil: 2031)
        let suggestions = WebSearchSuggestions.suggestions(current: current, proposed: proposed)
        #expect(suggestions.map(\.field) == [.producer, .region, .drinkingWindow])
        #expect(suggestions[0].current == "Domaine Example" && suggestions[0].proposed == "Domaine Exemple")
        #expect(suggestions[1].current == "")
        #expect(suggestions[2].current == "2021 – 2027" && suggestions[2].proposed == "2023 – 2031")
    }

    @Test func applyingOnlyAcceptedFields() {
        var wine = Wine(name: "Chablis", producer: "Old", vintage: 2019, notes: "kept")
        let proposed = WineDetails(producer: "New", region: "Burgundy", grapes: ["Chardonnay"], drinkFrom: 2023, drinkUntil: 2031)
        wine.apply(proposed, fields: [.region, .drinkingWindow])
        #expect(wine.producer == "Old")
        #expect(wine.region == "Burgundy")
        #expect(wine.grapes.isEmpty)
        #expect(wine.drinkFrom == 2023 && wine.drinkUntil == 2031)
        #expect(wine.notes == "kept")
        var details = WineDetails(Wine(name: "W", producer: "Old"))
        details.take(.producer, from: proposed)
        #expect(details.producer == "New")
    }
}
