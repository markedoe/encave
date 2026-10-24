import Foundation
import Testing
@testable import CaveKit

/// Finding the wine a new bottle belongs to, from shared vectors. The Kotlin core
/// (WineLookupVectorTest.kt) reads the same file, `fixtures/wine-lookup-vectors.json`.
@Suite struct WineLookupVectorTests {
    struct Vectors: Decodable {
        struct WineItem: Decodable {
            var id: UUID
            var name: String
            var producer: String?
            var vintage: Int?
            var barcodes: [String]?
            var inCellar: Int
            var drunk: Int?
        }
        struct Lookup: Decodable {
            var about: String?
            var code: String
            var wines: [String]
        }
        struct Draft: Decodable {
            var about: String?
            var id: UUID?
            var name: String
            var producer: String?
            var vintage: Int?
            var match: UUID?
        }
        var wines: [WineItem]
        var barcodes: [Lookup]
        var duplicates: [Draft]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/wine-lookup-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func cellar(_ vectors: Vectors) throws -> Cellar {
        var cellar = Cellar()
        let added = Date(timeIntervalSince1970: 1_767_225_600) // 2026-01-01 UTC
        for item in vectors.wines {
            let wine = Wine(id: item.id, name: item.name, producer: item.producer, vintage: item.vintage, barcodes: item.barcodes)
            let drunk = item.drunk ?? 0
            let bottles = try cellar.addBottles(of: wine, quantity: item.inCellar + drunk, addedAt: added)
            for bottle in bottles.prefix(drunk) { try cellar.drink(bottle.id, tasting: Tasting(date: added)) }
        }
        return cellar
    }

    @Test func byBarcode() throws {
        let vectors = try Self.vectors()
        let cellar = try Self.cellar(vectors)
        #expect(!vectors.wines.isEmpty && !vectors.barcodes.isEmpty)
        for lookup in vectors.barcodes {
            let found = cellar.wines(barcode: lookup.code)
            #expect(found.map(\.displayName) == lookup.wines, "\(lookup.about ?? lookup.code)")
            #expect(cellar.wine(barcode: lookup.code)?.id == found.first?.id)
        }
    }

    @Test func byNameProducerAndVintage() throws {
        let vectors = try Self.vectors()
        let cellar = try Self.cellar(vectors)
        #expect(!vectors.wines.isEmpty && !vectors.duplicates.isEmpty)
        for draft in vectors.duplicates {
            let wine = Wine(id: draft.id ?? UUID(), name: draft.name, producer: draft.producer, vintage: draft.vintage)
            #expect(cellar.wine(matching: wine)?.id == draft.match, "\(draft.about ?? draft.name)")
        }
    }
}
