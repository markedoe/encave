import Foundation
import Testing
@testable import CaveKit

/// Bottles in the edge triangles of a diamond rack: kept by every version, counted, and moved
/// with their place when the triangles are turned on or off (the same tests on Android,
/// DiamondEdgeTest.kt).
@Suite struct DiamondEdgeTests {
    let id = UUID()

    func rack(_ edges: [DiamondEdge], rows: Int = 3, columns: Int = 3) -> Rack {
        Rack.diamond(id: id, name: "Diamonds", rows: rows, columns: columns, binCapacity: 10, edges: edges)
    }

    /// The rack as a version without triangles reads it: whole diamonds.
    func older(_ rack: Rack) -> Rack {
        var older = rack
        older.edgeTrianglesValue = nil
        older.triangleCapacityValue = nil
        return older
    }

    @Test func aTriangleHoldsFewerBottles() throws {
        let rack = rack([.top, .left])
        // The top triangles are the row after the three rows of diamonds.
        let top = RackCell(rackID: id, row: 3, column: 1)
        #expect(rack.triangleEdge(of: top) == .top)
        #expect(rack.positions(of: top) == 5)
        var cellar = Cellar()
        cellar.addRack(rack)
        let wine = Wine(name: "Clos")
        var slots: [Slot] = []
        while let slot = cellar.freeSlot(in: top) {
            slots.append(slot)
            _ = try cellar.addBottles(of: wine, quantity: 1, slots: [slot])
        }
        #expect(slots.map(\.depth) == [0, 1, 2, 3, 4])
        // 3 rows of 3 diamonds, 3 top triangles and 1 left one (row B of diamonds).
        #expect(rack.capacity == 9 * 10 + 4 * 5)
        #expect(cellar.capacity(of: rack) == rack.capacity)
    }

    /// An older version sees a whole bin and fills it: every version keeps those bottles where
    /// they are after the merge's repair, and counts them, so a count never reads more
    /// bottles than places.
    @Test func bottlesAnOlderVersionPutInATriangleStay() throws {
        let rack = rack([.top, .left])
        let wine = Wine(name: "Clos")
        let slots = (0..<8).map { Slot(rackID: id, row: 3, column: 2, depth: $0) }
        #expect(slots.allSatisfy(older(rack).hasPosition))
        for version in [rack, older(rack)] {
            var cellar = Cellar(wines: [wine], bottles: slots.map { Bottle(wineID: wine.id, slot: $0) }, racks: [version])
            CellarMerge.repair(&cellar, stamps: [:])
            #expect(Set(cellar.bottlesInCellar.compactMap(\.slot)) == Set(slots))
            #expect(cellar.displacedBottles(by: version).isEmpty)
        }
        let cellar = Cellar(wines: [wine], bottles: slots.map { Bottle(wineID: wine.id, slot: $0) }, racks: [rack])
        #expect(cellar.capacity(of: rack) == rack.capacity + 3)
        #expect(cellar.freeSlot(in: RackCell(rackID: id, row: 3, column: 2)) == nil)
        // The other way: a bottle this version puts anywhere is a place the older version keeps.
        #expect(rack.allSlots.allSatisfy(older(rack).contains))
    }

    /// Turning on the top and left moves no bottle: the triangles are new cells after those of
    /// the diamonds, so an edit made meanwhile on another device (even by an older version)
    /// never finds bottles moved under it.
    @Test func turningTrianglesOnMovesBottlesWithTheirDiamond() throws {
        let before = rack([])
        var cellar = Cellar()
        cellar.addRack(before)
        let wine = Wine(name: "Clos")
        let slots = [Slot(rackID: id, row: 0, column: 0), Slot(rackID: id, row: 1, column: 2, depth: 3),
                     Slot(rackID: id, row: 2, column: 1, depth: 9)]
        _ = try cellar.addBottles(of: wine, quantity: slots.count, slots: slots)
        let after = rack([.top, .left])
        let map = Rack.diamondCellMap(from: before, to: after)
        #expect(cellar.displacedBottles(by: after, cellMap: map).isEmpty)
        try cellar.updateRack(after, cellMap: map)
        #expect(Set(cellar.bottlesInCellar.compactMap(\.slot)) == Set(slots))
        let places = Set(cellar.bottlesInCellar.compactMap { $0.slot.flatMap { after.diamondPlace(of: $0.cell) } })
        #expect(places == [.diamond(row: 0, column: 0), .diamond(row: 1, column: 2), .diamond(row: 2, column: 1)])
        cellar.undoRackUpdate(back: before, rowMap: [:], cellMap: map, takenOut: [])
        #expect(Set(cellar.bottlesInCellar.compactMap(\.slot)) == Set(slots))
    }

    /// Turning a side off while its triangles hold bottles: refused until the person agrees,
    /// then they stay in the cellar without a place (never lost), with Undo.
    @Test func turningASideOffAsksFirstAndNeverLosesBottles() throws {
        let before = rack([.top, .left])
        var cellar = Cellar()
        cellar.addRack(before)
        let wine = Wine(name: "Clos")
        let inTriangle = Slot(rackID: id, row: 3, column: 1, depth: 2)
        let inLeft = Slot(rackID: id, row: 1, column: 3)
        let inDiamond = Slot(rackID: id, row: 2, column: 1)
        _ = try cellar.addBottles(of: wine, quantity: 3, slots: [inTriangle, inLeft, inDiamond])
        let after = rack([.left])
        let map = Rack.diamondCellMap(from: before, to: after)
        let displaced = cellar.displacedBottles(by: after, cellMap: map)
        #expect(displaced.compactMap(\.slot) == [inTriangle])
        #expect(throws: CellarError.self) { try cellar.updateRack(after, cellMap: map) }
        try cellar.updateRack(after, cellMap: map, takingOut: true)
        #expect(cellar.bottlesInCellar.count == 3)
        #expect(cellar.bottlesInCellar.filter { $0.slot == nil }.count == 1)
        let places = Set(cellar.bottlesInCellar.compactMap { $0.slot.flatMap { after.diamondPlace(of: $0.cell) } })
        #expect(places == [.triangle(.left, index: 0), .diamond(row: 2, column: 1)])
        #expect(Set(cellar.bottlesInCellar.compactMap(\.slot)) == [inLeft, inDiamond])
        cellar.undoRackUpdate(back: before, rowMap: [:], cellMap: map, takenOut: displaced)
        #expect(Set(cellar.bottlesInCellar.compactMap(\.slot)) == [inTriangle, inLeft, inDiamond])
    }

    /// An older version's own edit (a row added) leaves the key but not the shape: the newer
    /// version then shows whole diamonds and moves nothing.
    @Test func aRackReshapedByAnOlderVersionKeepsItsBottles() throws {
        var rack = rack([.top, .left])
        rack.rows += 1
        rack.rowColumns = (rack.rowColumns ?? []) + [3]
        #expect(rack.diamondLayout == nil)
        #expect(rack.positions(of: RackCell(rackID: id, row: 0, column: 0)) == 10)
        #expect(rack.capacity == rack.cellCount * 10)
        #expect(Rack.diamondCellMap(from: rack, to: rack).isEmpty)
    }

    @Test func theTrianglesAreEncodedOnlyWhenOn() throws {
        let plain = try JSONSerialization.jsonObject(with: JSONEncoder().encode(rack([]))) as? [String: Any]
        #expect(plain?["edgeTriangles"] == nil && plain?["triangleCapacity"] == nil && plain?["rowColumns"] == nil)
        let made = rack([.right, .top])
        let keys = try JSONSerialization.jsonObject(with: JSONEncoder().encode(made)) as? [String: Any]
        #expect(keys?["edgeTriangles"] as? [String] == ["right", "top"])
        #expect(keys?["triangleCapacity"] as? Int == 5)
        #expect(try JSONDecoder().decode(Rack.self, from: JSONEncoder().encode(made)) == made)
    }

    @Test func rowsOfDiamondsKeepTheirLookAndNames() {
        let plain = rack([]), topped = rack([.top, .left])
        #expect((0..<3).map(plain.isOffset(row:)) == (0..<3).map(topped.isOffset(row:)))
        #expect(topped.isOffset(row: 3))
        #expect(topped.diamondLayout?.drawingRows == [3, 0, 1, 2])
        #expect(topped.placeName(for: Slot(rackID: id, row: 1, column: 2)) == plain.placeName(for: Slot(rackID: id, row: 1, column: 2)))
    }

    /// Edited at the same time on two devices: one turns the top on, the other (an older
    /// version too) renames the rack and wins. No bottle was moved, so the merge keeps them all.
    @Test func aSideTurnedOnWhileAnotherDeviceEditsMovesNothing() throws {
        let before = rack([])
        var cellar = Cellar()
        cellar.addRack(before)
        let slots = (0..<3).flatMap { row in (0..<3).map { Slot(rackID: id, row: row, column: $0) } }
        _ = try cellar.addBottles(of: Wine(name: "Clos"), quantity: slots.count, slots: slots)
        let after = rack([.top, .left])
        try cellar.updateRack(after, cellMap: Rack.diamondCellMap(from: before, to: after))
        // The other device's rack wins: renamed, without triangles.
        var renamed = before
        renamed.name = "Renamed"
        var merged = cellar
        merged.racks = [renamed]
        CellarMerge.repair(&merged, stamps: [:])
        #expect(Set(merged.bottlesInCellar.compactMap(\.slot)) == Set(slots))
    }

    /// Two devices turn on different sides at once: whichever wins, no bottle moves.
    @Test func twoSidesTurnedOnAtOnceMoveNothing() throws {
        let before = rack([])
        let slots = (0..<3).flatMap { row in (0..<3).map { Slot(rackID: id, row: row, column: $0) } }
        for (mine, theirs) in [([DiamondEdge.top], [DiamondEdge.left]), ([.left], [.top])] {
            var cellar = Cellar()
            cellar.addRack(before)
            _ = try cellar.addBottles(of: Wine(name: "Clos"), quantity: slots.count, slots: slots)
            let after = rack(mine)
            try cellar.updateRack(after, cellMap: Rack.diamondCellMap(from: before, to: after))
            var merged = cellar
            merged.racks = [rack(theirs)]
            CellarMerge.repair(&merged, stamps: [:])
            #expect(Set(merged.bottlesInCellar.compactMap(\.slot)) == Set(slots))
        }
    }

    /// Saving keeps sides of a newer version, and the order of the rows of triangles.
    @Test func editsKeepUnknownSidesAndTheOrderOfRows() {
        var original = rack([.bottom, .top, .left])
        original.edgeTrianglesValue = (original.edgeTrianglesValue ?? []) + ["corners"]
        #expect(original.diamondLayout?.appendedRows == [.bottom, .top])
        let order = Rack.orderedEdges([.top, .bottom, .left], keepingOrderOf: original)
        #expect(order == [.bottom, .top, .left])
        let saved = rack(order).keepingEdgeValues(from: original)
        #expect(saved.edgeTrianglesValue == ["bottom", "top", "left", "corners"])
        #expect(saved.diamondLayout == original.diamondLayout)
        #expect(saved.diamondLayout?.appendedRows == [.bottom, .top])
        // Reshaped by an older version, unchanged here: kept as read.
        var stale = original
        stale.rows += 1
        stale.rowColumns = (0..<stale.rows).map { _ in 3 }
        var renamed = Rack(id: id, name: "R", kind: .bins, rows: stale.rows, columns: 3, binCapacity: 10, binStyle: .diamond)
        renamed = renamed.keepingEdgeValues(from: stale)
        #expect(renamed.edgeTrianglesValue == stale.edgeTrianglesValue)
    }

    /// Bins without a type whose stale key would fit once picked as diamond bins: saving them
    /// does not turn whole bins into triangles.
    @Test func aStaleKeyIsNotRevivedBySaving() {
        var crates = Rack(id: id, name: "Bins", kind: .bins, rows: 2, columns: 4, binCapacity: 10)
        crates.edgeTrianglesValue = ["top"]
        #expect(crates.storageType == .crates && crates.diamondLayout == nil)
        var picked = crates
        picked.binStyle = .diamond
        #expect(picked.diamondLayout != nil)
        // The editor saves the shape it showed: whole bins, without the key.
        picked.edgeTrianglesValue = nil
        #expect(picked.keepingEdgeValues(from: crates).diamondLayout == nil)
    }
}
