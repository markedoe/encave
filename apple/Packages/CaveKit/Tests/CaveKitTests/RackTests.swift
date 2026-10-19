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
}
