import Foundation
import Testing
@testable import CaveKit

/// The cellar list from shared vectors: filters, sorts, chips and saved searches. The Kotlin
/// core (CellarFilterVectorTest.kt) reads the same file, `fixtures/cellar-filter-vectors.json`.
@Suite struct CellarFilterVectorTests {
    struct Vectors: Decodable {
        struct BottleItem: Decodable {
            var added: String
            var reservedFor: String?
            var drunk: Bool?
        }
        struct WineItem: Decodable {
            var name: String
            var producer: String?
            var vintage: Int?
            var color: String?
            var region: String?
            var country: String?
            var grapes: [String]?
            var tags: [String]?
            var barcodes: [String]?
            var from: Int?
            var until: Int?
            var bottles: [BottleItem]
        }
        struct List: Decodable {
            var about: String?
            var sort: String?
            var query: String?
            var colors: [String]?
            var maturities: [String]?
            var tag: String?
            var grape: String?
            var reservation: String?
            var groups: [String]
        }
        struct Step: Decodable {
            var save: String?
            var delete: String?
            var query: String?
            var colors: [String]?
            var maturities: [String]?
            var tag: String?
            var grape: String?
            var reservation: String?
        }
        struct Saved: Decodable {
            var about: String
            var steps: [Step]
            var names: [String]?
            var lists: [String: [String]]
        }
        var year: Int
        var wines: [WineItem]
        var lists: [List]
        var tags: [String]
        var grapes: [String]
        struct ChipCase: Decodable {
            var about: String
            var tags: [[String]]
            var reservedFor: [String]
            var expectedTags: [String]
            var expectedReservations: [String]
        }
        var reservations: [String]
        var chipOrder: [ChipCase]
        var savedSearches: [Saved]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/cellar-filter-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func day(_ text: String) throws -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return try #require(formatter.date(from: text))
    }

    static func cellar(_ vectors: Vectors) throws -> Cellar {
        var cellar = Cellar()
        for item in vectors.wines {
            let wine = Wine(name: item.name, producer: item.producer, vintage: item.vintage,
                            color: item.color.flatMap(WineColor.init(rawValue:)), region: item.region, country: item.country,
                            grapes: item.grapes ?? [], drinkFrom: item.from, drinkUntil: item.until,
                            barcodes: item.barcodes, tags: item.tags)
            for bottleItem in item.bottles {
                let bottle = try #require(try cellar.addBottles(of: wine, addedAt: Self.day(bottleItem.added)).first)
                if let occasion = bottleItem.reservedFor { try cellar.reserve([bottle.id], for: occasion) }
                if bottleItem.drunk == true { try cellar.drink(bottle.id, tasting: Tasting(date: Self.day(bottleItem.added))) }
            }
        }
        return cellar
    }

    static func filter(query: String?, colors: [String]?, maturities: [String]?, tag: String?, grape: String?,
                       reservation: String?) -> CellarFilter {
        CellarFilter(query: query ?? "", colors: Set((colors ?? []).compactMap(WineColor.init(rawValue:))),
                     maturities: Set((maturities ?? []).compactMap(Maturity.init(rawValue:))),
                     tag: tag, reservation: reservation, grape: grape)
    }

    static func names(_ groups: [WineGroup]) -> [String] {
        groups.map { "\($0.wine.name) ×\($0.count)" }
    }

    @Test func filtersAndSorts() throws {
        let vectors = try Self.vectors()
        let cellar = try Self.cellar(vectors)
        #expect(vectors.lists.count > 20)
        for list in vectors.lists {
            let filter = Self.filter(query: list.query, colors: list.colors, maturities: list.maturities, tag: list.tag,
                                     grape: list.grape, reservation: list.reservation)
            let sort = try #require(CellarSort(rawValue: list.sort ?? "name"))
            let groups = cellar.groups(filter: filter, sort: sort, year: vectors.year)
            #expect(Self.names(groups) == list.groups, "\(list.about ?? list.query ?? list.sort ?? "")")
        }
    }

    @Test func chips() throws {
        let vectors = try Self.vectors()
        let cellar = try Self.cellar(vectors)
        #expect(cellar.wineTags == vectors.tags)
        #expect(cellar.wineGrapes == vectors.grapes)
        #expect(cellar.reservations == vectors.reservations)
    }

    @Test func chipOrder() throws {
        let vectors = try Self.vectors()
        #expect(!vectors.chipOrder.isEmpty)
        for item in vectors.chipOrder {
            var cellar = Cellar()
            #expect(!item.tags.isEmpty && !item.reservedFor.isEmpty)
            for (index, tags) in item.tags.enumerated() {
                try cellar.addBottles(of: Wine(name: "Wine \(index)", tags: tags))
            }
            let kept = try cellar.addBottles(of: Wine(name: "Kept"), quantity: item.reservedFor.count)
            for (bottle, occasion) in zip(kept, item.reservedFor) { try cellar.reserve([bottle.id], for: occasion) }
            #expect(cellar.wineTags == item.expectedTags, "\(item.about)")
            #expect(cellar.reservations == item.expectedReservations, "\(item.about)")
        }
    }

    @Test func savedSearches() throws {
        let vectors = try Self.vectors()
        for saved in vectors.savedSearches {
            var cellar = try Self.cellar(vectors)
            for step in saved.steps {
                if let name = step.save {
                    let filter = Self.filter(query: step.query, colors: step.colors, maturities: step.maturities, tag: step.tag,
                                             grape: step.grape, reservation: step.reservation)
                    cellar.saveSearch(name, filter: filter)
                } else if let name = step.delete {
                    let search = try #require(cellar.settings.savedSearches?.first { $0.name == name })
                    cellar.deleteSearch(search.id)
                }
            }
            let searches = cellar.settings.savedSearches
            #expect(searches?.map(\.name) == saved.names, "\(saved.about)")
            for search in searches ?? [] {
                // A saved search keeps its filters as typed, trimmed, in a stable order.
                #expect(search.query == search.query.trimmingCharacters(in: .whitespaces))
                #expect(search.colors.map(\.rawValue) == search.colors.map(\.rawValue).sorted())
                #expect(search.maturities.map(\.rawValue) == search.maturities.map(\.rawValue).sorted())
                let expected = try #require(saved.lists[search.name], "\(search.name)")
                let groups = cellar.groups(filter: search.filter, sort: .name, year: vectors.year)
                #expect(Self.names(groups) == expected, "\(search.name)")
            }
        }
    }
}
