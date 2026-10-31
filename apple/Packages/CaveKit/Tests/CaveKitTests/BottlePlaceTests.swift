import Foundation
import Testing
@testable import CaveKit

/// The order bottles are listed in and the bottle Put Away places, from
/// `fixtures/bottle-place-vectors.json` (the Android core reads them too).
struct BottlePlaceTests {
    struct Vectors: Decodable {
        struct RackItem: Decodable {
            var id: String; var kind: String; var rows: Int; var columns: Int; var depth: Int?; var shelfLayers: [Int]?
        }
        struct BottleItem: Decodable {
            var id: String; var rack: String?; var row: Int?; var column: Int?; var depth: Int?
            var addedAt: String; var reservedFor: String?
        }
        struct Case: Decodable {
            var about: String; var racks: [RackItem]; var bottles: [BottleItem]; var placeOrder: [String]; var putAway: String?
            var openOn: String?
        }
        var cases: [Case]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/bottle-place-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    @Test func orderAndPutAwayMatchTheSharedVectors() throws {
        let vectors = try Self.vectors()
        #expect(!vectors.cases.isEmpty)
        let dates = ISO8601DateFormatter()
        for vector in vectors.cases {
            let wine = Wine(name: "Wine")
            let racks = try vector.racks.map { item in
                Rack(id: try #require(UUID(uuidString: item.id)), name: item.id,
                     kind: try #require(RackKind(rawValue: item.kind)), rows: item.rows, columns: item.columns,
                     depth: item.depth ?? 1, shelfLayers: item.shelfLayers)
            }
            let bottles = try vector.bottles.map { item in
                var bottle = Bottle(id: try #require(UUID(uuidString: item.id)), wineID: wine.id,
                                    addedAt: try #require(dates.date(from: item.addedAt)))
                if let rack = item.rack {
                    bottle.slot = Slot(rackID: try #require(UUID(uuidString: rack)), row: item.row ?? 0,
                                       column: item.column ?? 0, depth: item.depth ?? 0)
                }
                bottle.reservedFor = item.reservedFor
                return bottle
            }
            // Whatever the order the bottles come in.
            for order in [bottles, bottles.reversed()] {
                let cellar = Cellar(wines: [wine], bottles: order, racks: racks)
                let listed = cellar.inPlaceOrder(cellar.bottles(of: wine.id)).map { $0.id.uuidString.lowercased() }
                #expect(listed == vector.placeOrder.map { $0.lowercased() }, "\(vector.about)")
                #expect(cellar.bottles(of: wine.id).bottleToPutAway?.id.uuidString.lowercased() == vector.putAway?.lowercased(),
                        "\(vector.about)")
                #expect(cellar.bottles(of: wine.id).hasBottleToPutAway == (vector.putAway != nil), "\(vector.about)")
                let opened = cellar.bottles(of: wine.id).bottleToPutAway.flatMap { cellar.storageToOpen(for: $0) }
                #expect(opened?.id.uuidString.lowercased() == vector.openOn?.lowercased(), "\(vector.about)")
            }
        }
    }

    @Test func bottlesNotInStorageAreTheOnesWithoutAPosition() throws {
        let rack = Rack(name: "Rack", rows: 2, columns: 2)
        var cellar = Cellar(racks: [rack])
        let wine = Wine(name: "Wine")
        let added = try cellar.addBottles(of: wine, quantity: 3)
        try cellar.move(added[0].id, to: Slot(rackID: rack.id, row: 0, column: 0))
        #expect(Set(cellar.bottlesNotInStorage.map(\.id)) == Set(added.dropFirst().map(\.id)))
    }
}
