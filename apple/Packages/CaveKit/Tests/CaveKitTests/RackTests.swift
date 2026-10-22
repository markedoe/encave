import Foundation
import Testing
@testable import CaveKit

@Suite struct RackTests {
    @Test func capacityPerKind() {
        #expect(Rack(name: "Grid", rows: 6, columns: 8).capacity == 48)
        #expect(Rack(name: "Deep", rows: 6, columns: 8, depth: 2).capacity == 96)
        // Honeycomb: rows of 8 and 7 alternate.
        #expect(Rack(name: "Honeycomb", kind: .staggered, rows: 4, columns: 8).capacity == 30)
        #expect(Rack(name: "Fridge", kind: .shelves, rows: 4, columns: 6, depth: 2).capacity == 48)
        #expect(Rack(name: "Bins", kind: .bins, rows: 2, columns: 3, binCapacity: 10).capacity == 60)
        #expect(Rack(name: "Triangle", rows: 3, columns: 3, rowColumns: [1, 2, 3]).capacity == 6)
    }

    @Test func honeycombOffsetRowsHoldOneFewer() {
        let rack = Rack(name: "H", kind: .staggered, rows: 3, columns: 5, depth: 2)
        #expect(rack.columns(inRow: 0) == 5)
        #expect(rack.columns(inRow: 1) == 4)
        #expect(rack.positionsPerCell == 2, "a deep honeycomb cell holds bottles one behind the other")
        #expect(!rack.contains(Slot(rackID: rack.id, row: 1, column: 4)))
    }

    @Test func labelsRoundTripWithDepth() {
        let grid = Rack(name: "G", rows: 30, columns: 5)
        #expect(grid.label(for: Slot(rackID: grid.id, row: 1, column: 2)) == "B3")
        let fridge = Rack(name: "F", kind: .shelves, rows: 4, columns: 6, depth: 2)
        let back = Slot(rackID: fridge.id, row: 1, column: 2, depth: 1)
        #expect(fridge.label(for: back) == "B3-2")
        #expect(fridge.slot(forLabel: "B3-2") == back)
        #expect(fridge.slot(forLabel: "b3") == Slot(rackID: fridge.id, row: 1, column: 2))
        #expect(fridge.slot(forLabel: "B3-3") == nil)
    }

    @Test func binsFillOneBinBeforeTheNext() throws {
        var cellar = Cellar()
        let rack = Rack(name: "Bins", kind: .bins, rows: 1, columns: 2, binCapacity: 3)
        cellar.addRack(rack)
        let added = try cellar.addBottles(of: Wine(name: "W"), quantity: 4,
                                          slots: Array(cellar.freeSlots(in: rack).prefix(4)))
        let cells = added.compactMap(\.slot?.cell)
        #expect(cells.filter { $0.column == 0 }.count == 3)
        #expect(cellar.bottles(in: RackCell(rackID: rack.id, row: 0, column: 1)).count == 1)
        #expect(cellar.freeSlot(in: RackCell(rackID: rack.id, row: 0, column: 0)) == nil)
        #expect(cellar.freeSlot(in: RackCell(rackID: rack.id, row: 0, column: 1))?.depth == 1)
    }

    @Test func sampleCellarShowsEveryKindOfRack() {
        let cellar = SampleData.cellar(year: 2026)
        let racks = cellar.racks
        #expect(Set(racks.map(\.kind)) == Set(RackKind.allCases))
        #expect(racks.contains { $0.depth == 2 && $0.kind == .grid })
        #expect(racks.contains { $0.rowColumns != nil })
        #expect(racks.contains { $0.hasShelfLayers }, "a fridge with bottles in layers")
        for rack in racks {
            #expect(cellar.occupancy(of: rack) > 0, "\(rack.name) has bottles")
        }
        #expect(cellar.bottlesInCellar.contains { ($0.slot?.depth ?? 0) > 0 }, "a bottle sits behind another")
    }

    @Test func frontIsFilledBeforeBack() {
        let rack = Rack(name: "F", kind: .shelves, rows: 1, columns: 3, depth: 2)
        #expect(rack.allSlots.prefix(3).allSatisfy { $0.depth == 0 })
    }

    @Test func reducingDepthWithBottlesBehindIsRefused() throws {
        var cellar = Cellar()
        var rack = Rack(name: "F", kind: .shelves, rows: 2, columns: 2, depth: 2)
        cellar.addRack(rack)
        _ = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 0, column: 0, depth: 1)])
        rack.depth = 1
        #expect(throws: CellarError.rackNotEmpty) { try cellar.updateRack(rack) }
    }

    /// Shared with the Kotlin RackTest: a fridge of five shelves of two layers.
    @Test func duplicatingAShelfMovesTheBottlesBelowIt() throws {
        var cellar = Cellar()
        let fridge = Rack(name: "F", kind: .staggered, rows: 10, columns: 6, depth: 2, shelfLayers: [2, 2, 2, 2, 2])
        cellar.addRack(fridge)
        // Shelf 3, bottom layer (its last row), place 3, back.
        let bottle = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: fridge.id, row: 5, column: 2, depth: 1)])[0]
        // Shelf 1 duplicated: a new shelf 2, the old shelves 2 to 5 one lower.
        let edited = Rack(id: fridge.id, name: "F", kind: .staggered, rows: 12, columns: 6, depth: 2,
                          shelfLayers: [2, 2, 2, 2, 2, 2])
        let map = Rack.shelfRowMap(from: fridge, to: edited, origins: [0, nil, 1, 2, 3, 4])
        #expect(map[5] == .some(7))
        #expect(cellar.displacedBottles(by: edited, rowMap: map).isEmpty)
        try cellar.updateRack(edited, rowMap: map)
        let slot = try #require(cellar.bottles[bottle.id]?.slot)
        #expect(slot == Slot(rackID: fridge.id, row: 7, column: 2, depth: 1))
        #expect(edited.shelfPlace(ofRow: slot.row)?.shelf == 3)
        #expect(edited.shelfPlace(ofRow: slot.row)?.layer == 0)
    }

    @Test func removingAShelfOrLayerDisplacesItsBottles() throws {
        var cellar = Cellar()
        let fridge = Rack(name: "F", kind: .staggered, rows: 6, columns: 6, depth: 2, shelfLayers: [2, 2, 2])
        cellar.addRack(fridge)
        let bottles = try cellar.addBottles(of: Wine(name: "W"), quantity: 3, slots: [
            Slot(rackID: fridge.id, row: 2, column: 0),  // shelf 2, top layer
            Slot(rackID: fridge.id, row: 3, column: 1),  // shelf 2, bottom layer
            Slot(rackID: fridge.id, row: 5, column: 4),  // shelf 3, bottom layer
        ])
        // Shelf 2 removed, shelf 3 down to one layer of 4.
        let edited = Rack(id: fridge.id, name: "F", kind: .staggered, rows: 3, columns: 6, depth: 2,
                          rowColumns: [5, 6, 4], shelfLayers: [2, 1])
        let map = Rack.shelfRowMap(from: fridge, to: edited, origins: [0, 2])
        #expect(map[2] == .some(nil))
        #expect(map[4] == .some(nil), "the top layer of shelf 3 is gone")
        #expect(cellar.displacedBottles(by: edited, rowMap: map).map(\.id) == bottles.map(\.id),
                "the third bottle's place 5 no longer fits a layer of 4")
        #expect(throws: CellarError.rackNotEmpty) { try cellar.updateRack(edited, rowMap: map) }
        try cellar.updateRack(edited, rowMap: map, takingOut: true)
        #expect(bottles.allSatisfy { cellar.bottles[$0.id]?.slot == nil && cellar.bottles[$0.id]?.status == .inCellar })
        #expect(cellar.racks[0].rows == 3)
    }

    @Test func shrinkingCanTakeBottlesOut() throws {
        var cellar = Cellar()
        var rack = Rack(name: "G", rows: 3, columns: 3)
        cellar.addRack(rack)
        let kept = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 0, column: 0)])[0]
        let out = try cellar.addBottles(of: Wine(name: "W"), slots: [Slot(rackID: rack.id, row: 2, column: 1)])[0]
        rack.rows = 2
        #expect(cellar.displacedBottles(by: rack).map(\.id) == [out.id])
        try cellar.updateRack(rack, takingOut: true)
        #expect(cellar.bottles[out.id]?.slot == nil)
        #expect(cellar.bottles[kept.id]?.slot == Slot(rackID: rack.id, row: 0, column: 0))
    }

    @Test func oldRacksAndSlotsStillDecode() throws {
        let rackID = UUID(), wineID = UUID(), bottleID = UUID()
        let json = """
        {"schemaVersion":2,"racks":[{"id":"\(rackID)","name":"Old","rows":2,"columns":3,"layout":"diamond"}],
         "wines":["\(wineID)",{"id":"\(wineID)","name":"W","grapes":[]}],
         "bottles":["\(bottleID)",{"id":"\(bottleID)","wineID":"\(wineID)","status":"inCellar",
                    "addedAt":700000000,"slot":{"rackID":"\(rackID)","row":1,"column":2}}]}
        """
        let cellar = try JSONDecoder().decode(Cellar.self, from: Data(json.utf8))
        #expect(cellar.racks[0].kind == .grid)
        #expect(cellar.racks[0].capacity == 6)
        #expect(cellar.bottles[bottleID]?.slot == Slot(rackID: rackID, row: 1, column: 2, depth: 0))
    }

    @Test func newRacksRoundTrip() throws {
        let rack = Rack(name: "T", kind: .bins, rows: 2, columns: 2, binCapacity: 8, rowColumns: [1, 2])
        let data = try JSONEncoder().encode(rack)
        #expect(try JSONDecoder().decode(Rack.self, from: data) == rack)
    }

    @Test func csvRoundTripKeepsBinAndBackPositions() throws {
        let original = SampleData.cellar(year: 2026)
        var target = Cellar(racks: original.racks)
        try target.importCSV(CellarExport.csv(original))
        #expect(Set(target.bottlesInCellar.compactMap(\.slot)) == Set(original.bottlesInCellar.compactMap(\.slot)))
    }

    @Test func headToTailBacksOnlyOnTwoDeepFridgesWithLayers() throws {
        let fridge = Rack(name: "F", kind: .staggered, rows: 2, columns: 4, depth: 2, shelfLayers: [2, 2], backReversed: true)
        #expect(fridge.hasDepthBands && fridge.isBackReversed && fridge.backReversed == true)
        // Nowhere else: one deep, a continuous honeycomb, a grid; false is left out.
        #expect(Rack(name: "1", kind: .staggered, rows: 2, columns: 4, shelfLayers: [2], backReversed: true).backReversed == nil)
        #expect(Rack(name: "H", kind: .staggered, rows: 2, columns: 4, depth: 2, backReversed: true).backReversed == nil)
        #expect(Rack(name: "G", rows: 2, columns: 4, depth: 2, backReversed: true).backReversed == nil)
        #expect(Rack(name: "F", kind: .staggered, rows: 2, columns: 4, depth: 2, shelfLayers: [2], backReversed: false)
            .backReversed == nil)

        let data = try JSONEncoder().encode(fridge)
        #expect(String(decoding: data, as: UTF8.self).contains("\"backReversed\":true"))
        #expect(try JSONDecoder().decode(Rack.self, from: data) == fridge)
        var plain = fridge
        plain.backReversed = nil
        #expect(!String(decoding: try JSONEncoder().encode(plain), as: UTF8.self).contains("backReversed"))

        // Older racks have no flag; a flag where it does not apply is dropped when read.
        let id = UUID()
        let old = #"{"id":"\#(id)","name":"Old","kind":"staggered","rows":2,"columns":4,"depth":2,"shelfLayers":[2]}"#
        #expect(try JSONDecoder().decode(Rack.self, from: Data(old.utf8)).backReversed == nil)
        let grid = #"{"id":"\#(id)","name":"G","kind":"grid","rows":2,"columns":4,"depth":2,"backReversed":true}"#
        let decoded = try JSONDecoder().decode(Rack.self, from: Data(grid.utf8))
        #expect(decoded.backReversed == nil && !decoded.isBackReversed)
    }

    @Test func sampleHasAHeadToTailFridgeWithBottlesFrontAndBack() {
        let cellar = SampleData.cellar(year: 2026)
        let fridge = cellar.racks.first { $0.isBackReversed }
        #expect(fridge?.shelfLayers?.allSatisfy { $0 == 2 } == true)
        #expect(fridge.map { [$0.columns(inRow: 0), $0.columns(inRow: 1)] } == [3, 4])
        let depths = Set(cellar.bottlesInCellar.filter { $0.slot?.rackID == fridge?.id }.compactMap(\.slot?.depth))
        #expect(depths == [0, 1])
    }
}
