import Foundation
import Testing
@testable import CaveKit

/// Storage layouts from shared vectors. The Kotlin core (RackVectorTest.kt) reads the same
/// file, `fixtures/rack-vectors.json`, so the two platforms cannot drift. The French names
/// ("fr") are the apps' wording: the Android app checks them (RackVectorLabelsTest.kt).
@Suite struct RackVectorTests {
    struct Vectors: Decodable {
        struct Place: Decodable {
            var row: Int
            var column: Int
            var depth: Int
            var label: String
            var en: String
            var fr: String
            var emptyCaption: String
            var bandShift: Double
        }
        struct Layout: Decodable {
            var about: String
            var kind: String
            var rows: Int
            var columns: Int
            var depth: Int?
            var binCapacity: Int?
            var rowColumns: [Int]?
            var shelfLayers: [Int]?
            var backReversed: Bool?
            var storageType: String
            var storageCount: Int
            var columnsInRow: [Int]
            var fillOrderStart: [[Int]]
            var places: [Place]
        }
        var racks: [Layout]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/rack-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    static func rack(_ layout: Vectors.Layout) throws -> Rack {
        let kind = try #require(RackKind(rawValue: layout.kind), "\(layout.kind)")
        return Rack(name: "Rack", kind: kind, rows: layout.rows, columns: layout.columns, depth: layout.depth ?? 1,
                    binCapacity: layout.binCapacity ?? 12, rowColumns: layout.rowColumns, shelfLayers: layout.shelfLayers,
                    backReversed: layout.backReversed)
    }

    @Test func cellsPerRowAndFillOrder() throws {
        let layouts = try Self.vectors().racks
        #expect(!layouts.isEmpty)
        for layout in layouts {
            let rack = try Self.rack(layout)
            #expect((0..<rack.rows).map { rack.columns(inRow: $0) } == layout.columnsInRow, "\(layout.about)")
            #expect(!layout.fillOrderStart.isEmpty, "\(layout.about)")
            let start = rack.allSlots.prefix(layout.fillOrderStart.count).map { [$0.row, $0.column, $0.depth] }
            #expect(start == layout.fillOrderStart, "\(layout.about)")
        }
    }

    @Test func storageTypeAndCount() throws {
        for layout in try Self.vectors().racks {
            let rack = try Self.rack(layout)
            #expect(rack.storageType == StorageType(rawValue: layout.storageType), "\(layout.about)")
            #expect(rack.storageCount == layout.storageCount, "\(layout.about)")
        }
    }

    @Test func placesAreNamedTheSame() throws {
        var count = 0
        for layout in try Self.vectors().racks {
            let rack = try Self.rack(layout)
            for place in layout.places {
                let slot = Slot(rackID: rack.id, row: place.row, column: place.column, depth: place.depth)
                let about = "\(layout.about): \(place.label)"
                #expect(rack.contains(slot), "\(about)")
                #expect(rack.label(for: slot) == place.label, "\(about)")
                #expect(rack.placeName(for: slot) == place.en, "\(about)")
                #expect(rack.emptyCaption(for: slot.cell) == place.emptyCaption, "\(about)")
                #expect(rack.bandShift(depth: place.depth) == place.bandShift, "\(about)")
                count += 1
            }
        }
        #expect(count > 0)
    }
}
