import Foundation
import Testing
@testable import CaveKit

/// Search by words and a stable cellar order. The Kotlin core (SearchTest.kt) reads the
/// same vectors, in `fixtures/search` at the top of the repository.
@Suite struct SearchTests {
    struct Vectors: Decodable {
        struct WineFields: Decodable {
            var name: String
            var producer: String?
            var vintage: Int?
            var appellation: String?
            var region: String?
            var country: String?
            var grapes: [String]?
            var barcodes: [String]?
            var tags: [String]?
        }
        struct Search: Decodable {
            var query: String
            var matches: [String]
        }
        struct Suggestion: Decodable {
            var query: String
            var names: [String]
        }
        var wines: [WineFields]
        var searches: [Search]
        var suggestions: [Suggestion]
    }

    static func vectors() throws -> (wines: [Wine], searches: [Vectors.Search], suggestions: [Vectors.Suggestion]) {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/search/wine-matching.json")
        let vectors = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
        let wines = vectors.wines.map {
            Wine(name: $0.name, producer: $0.producer, vintage: $0.vintage, region: $0.region, country: $0.country,
                 appellation: $0.appellation, grapes: $0.grapes ?? [], barcodes: $0.barcodes, tags: $0.tags)
        }
        return (wines, vectors.searches, vectors.suggestions)
    }

    @Test func everyWordIsFoundInSomeField() throws {
        let (wines, searches, _) = try Self.vectors()
        #expect(!searches.isEmpty)
        for search in searches {
            let found = wines.filter { $0.matches(search.query) }.sorted { Cellar.nameOrder($0, $1) }.map(\.name)
            #expect(found == search.matches, "\(search.query)")
        }
    }

    @Test func suggestionsComeInNameOrder() throws {
        let (wines, _, suggestions) = try Self.vectors()
        var cellar = Cellar()
        for wine in wines { try cellar.addBottles(of: wine) }
        #expect(!suggestions.isEmpty)
        for suggestion in suggestions {
            #expect(cellar.wineSuggestions(matching: suggestion.query).map(\.name) == suggestion.names, "\(suggestion.query)")
        }
    }

    @Test func cellarListJournalAndDiscoveriesSearchByWords() throws {
        var cellar = Cellar()
        let palmer = Wine(name: "Château Palmer", vintage: 2015, appellation: "Margaux")
        let guigal = Wine(name: "La Landonne", producer: "E. Guigal", grapes: ["Syrah"])
        try cellar.addBottles(of: palmer, quantity: 2)
        try cellar.addBottles(of: guigal)
        #expect(cellar.groups(matching: "margaux 2015").map(\.wine.id) == [palmer.id])
        #expect(cellar.groups(matching: "margaux 2016").isEmpty)
        #expect(cellar.wineSuggestions(matching: "guigal syrah").map(\.id) == [guigal.id])

        // A tasting: the words may be in the wine or in the note.
        let bottle = try #require(cellar.bottles(of: palmer.id).first)
        try cellar.drink(bottle.id, tasting: Tasting(note: "Silky, long finish", occasion: "Birthday"))
        #expect(cellar.tastings(matching: "palmer silky").map(\.id) == [bottle.id])
        #expect(cellar.tastings(matching: "birthday finish").map(\.id) == [bottle.id])
        #expect(cellar.tastings(matching: "palmer tannic").isEmpty)

        // A wine tasted elsewhere: the wine, the place, the note and the tags.
        let found = Wine(name: "Rully", producer: "Domaine Jacqueson")
        let discovery = cellar.addDiscovery(Discovery(wineID: found.id, place: "Bistro, Lisbon", note: "Mineral"), wine: found)
        #expect(cellar.discoveries(filter: DiscoveryFilter(query: "rully lisbon")).map(\.id) == [discovery.id])
        #expect(cellar.discoveries(filter: DiscoveryFilter(query: "jacqueson mineral")).map(\.id) == [discovery.id])
        #expect(cellar.discoveries(filter: DiscoveryFilter(query: "rully paris")).isEmpty)
    }

    /// Wines alike (same name, vintage and window) always come in the same order, whatever
    /// order the records are stored in: every sort is a total order.
    @Test func everySortIsATotalOrder() throws {
        var cellar = Cellar()
        for index in 0..<6 {
            let producer = index < 2 ? nil : (index.isMultiple(of: 2) ? "Domaine B" : "Domaine A")
            try cellar.addBottles(of: Wine(name: "Bourgogne", producer: producer, vintage: 2020, color: .red),
                                  addedAt: Date(timeIntervalSince1970: 1_700_000_000))
        }
        let sorts: [CellarSort] = [.name, .vintage, .readiness, .region, .recentlyAdded]
        for sort in sorts {
            let groups = cellar.groups(filter: CellarFilter(), sort: sort, year: 2026)
            #expect(groups.count == 6)
            // The same order from the records in reverse.
            var reversed = Cellar()
            for group in groups.reversed() {
                try reversed.addBottles(of: group.wine, addedAt: Date(timeIntervalSince1970: 1_700_000_000))
            }
            #expect(reversed.groups(filter: CellarFilter(), sort: sort, year: 2026).map(\.wine.id) == groups.map(\.wine.id),
                    "\(sort)")
        }
        // Producer, then id.
        let names = cellar.groups(filter: CellarFilter(), sort: .readiness, year: 2026).map { $0.wine.producer ?? "" }
        #expect(names == ["", "", "Domaine A", "Domaine A", "Domaine B", "Domaine B"])
    }
}
