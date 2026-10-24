import Foundation
import Testing
@testable import CaveKit

/// Merge conflicts from shared vectors, run both ways. The Kotlin core (MergeVectorTest.kt) reads
/// the same file, `fixtures/sync/merge-vectors.json`, whose "about" describes the format.
@Suite struct MergeVectorTests {
    struct Side: Decodable {
        var cellar: Cellar
        var state: SyncState
    }
    struct Expect: Decodable {
        var wines: [String: String]
        var bottles: [String: String]
        var racks: [String]
        var stamps: [String: Stamp]
        var clock: UInt64
    }
    struct Case: Decodable {
        var name: String
        var a: Side
        var b: Side
        var expect: Expect
    }
    struct Vectors: Decodable {
        var cases: [Case]
    }

    struct Compact: Equatable {
        var wines: [String: String]
        var bottles: [String: String]
        var racks: [String]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/sync/merge-vectors.json")
        return try JSONFileCellarStore.makeDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func compact(_ cellar: Cellar) -> Compact {
        var bottles: [String: String] = [:]
        for bottle in cellar.bottles.values {
            var text = bottle.status.rawValue
            if let slot = bottle.slot {
                let rack = cellar.racks.first { $0.id == slot.rackID }?.name ?? slot.rackID.uuidString.lowercased()
                text += " \(rack) \(slot.row),\(slot.column)"
            }
            bottles[bottle.id.uuidString.lowercased()] = text
        }
        var wines: [String: String] = [:]
        for wine in cellar.wines.values { wines[wine.id.uuidString.lowercased()] = wine.name }
        return Compact(wines: wines, bottles: bottles, racks: cellar.racks.map { "\($0.name) \($0.rows)x\($0.columns)" })
    }

    @Test func mergesGiveTheSharedResultBothWays() throws {
        for vector in try Self.vectors().cases {
            let expected = Compact(wines: vector.expect.wines, bottles: vector.expect.bottles, racks: vector.expect.racks)
            let onA = CellarMerge.merge(local: vector.a.cellar, vector.a.state, remote: vector.b.cellar, vector.b.state)
            let onB = CellarMerge.merge(local: vector.b.cellar, vector.b.state, remote: vector.a.cellar, vector.a.state)
            for (side, result) in [("A", onA), ("B", onB)] {
                #expect(Self.compact(result.cellar) == expected, "\(vector.name), on \(side)")
                #expect(result.stamps == vector.expect.stamps, "\(vector.name), stamps on \(side)")
                #expect(result.clock == vector.expect.clock, "\(vector.name), clock on \(side)")
            }
            #expect(onA.cellar == onB.cellar, "\(vector.name)")
        }
    }
}
