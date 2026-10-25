import Foundation
import Testing
@testable import CaveKit

/// Rows of their own (`rack.rowDepths`, `rack.rowBackReversed`) from shared vectors. The
/// Kotlin core (StorageRowVectorTest.kt) reads the same file,
/// `fixtures/storage-row-vectors.json`, so both platforms read, edit and make such racks alike.
@Suite struct StorageRowVectorTests {
    static func vectors() throws -> [String: Any] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/storage-row-vectors.json")
        return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
    }

    static func cases(_ key: String) throws -> [[String: Any]] {
        try #require(try vectors()[key] as? [[String: Any]])
    }

    static func rack(_ object: Any?) throws -> Rack {
        let json = try JSONSerialization.data(withJSONObject: try #require(object))
        return try JSONDecoder().decode(Rack.self, from: json)
    }

    static func slots(_ object: Any?, in rack: Rack) throws -> [Slot] {
        try #require(object as? [[Int]]).map { Slot(rackID: rack.id, row: $0[0], column: $0[1], depth: $0[2]) }
    }

    /// An array of the vectors, or nil for `null`.
    static func optional<T>(_ value: Any?, as type: T.Type) -> T? { value is NSNull ? nil : value as? T }

    @Test func racksAreReadTheSameEverywhere() throws {
        let cases = try Self.cases("wire")
        #expect(!cases.isEmpty)
        for vector in cases {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector["rack"])
            #expect(rack.depth == vector["depth"] as? Int, "\(about)")
            #expect(rack.rowDepths == Self.optional(vector["rowDepths"], as: [Int].self), "\(about)")
            #expect(rack.rowBackReversed == Self.optional(vector["rowBackReversed"], as: [Bool].self), "\(about)")
            #expect(rack.backReversed == Self.optional(vector["backReversed"], as: Bool.self), "\(about)")
            #expect((0..<rack.rows).map(rack.depth(ofRow:)) == vector["depthOfRow"] as? [Int], "\(about)")
            #expect((0..<rack.rows).map(rack.isBackReversed(row:)) == vector["headToTailOfRow"] as? [Bool], "\(about)")
            #expect(rack.capacity == vector["capacity"] as? Int, "\(about)")
            #expect(rack.allSlots.count == rack.capacity, "\(about)")
            #expect(rack.allSlots.allSatisfy(rack.contains), "\(about)")

            // Written back as read, and read again the same.
            let written = try JSONEncoder().encode(rack)
            let keys = try #require(JSONSerialization.jsonObject(with: written) as? [String: Any])
            #expect((keys["rowDepths"] != nil) == (rack.rowDepths != nil), "\(about)")
            #expect((keys["rowBackReversed"] != nil) == (rack.rowBackReversed != nil), "\(about)")
            #expect(try JSONDecoder().decode(Rack.self, from: written) == rack, "\(about)")
        }
    }

    /// A version that does not know the per-row fields keeps them as unknown keys and reads
    /// `depth` and `backReversed`: it still shows every place there is.
    @Test func olderVersionsStillShowEveryPlace() throws {
        for vector in try Self.cases("wire") {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector["rack"])
            var keys = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(rack)) as? [String: Any])
            keys["rowDepths"] = nil
            keys["rowBackReversed"] = nil
            let older = try Self.rack(keys)
            #expect(rack.allSlots.allSatisfy { older.contains($0) }, "\(about)")
            #expect(older.isBackReversed == (0..<rack.rows).contains(where: rack.isBackReversed(row:)), "\(about)")
        }
        // The fields this version does not know are kept, and so are these by a reader that
        // does not know them (as the older version does with its unknown keys).
        let id = UUID()
        let future = #"{"id":"\#(id)","name":"R","kind":"grid","rows":2,"columns":2,"depth":2,"rowDepths":[2,1],"rowFuture":[1,2]}"#
        let read = try JSONDecoder().decode(Rack.self, from: Data(future.utf8))
        let again = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(read)) as? [String: Any])
        #expect(again["rowFuture"] as? [Int] == [1, 2])
        #expect(again["rowDepths"] as? [Int] == [2, 1])
    }

    @Test func bottlesLeftWithoutAPlaceWhenARowShrinks() throws {
        for vector in try Self.cases("displaced") {
            let about = vector["about"] as? String ?? ""
            let before = try Self.rack(vector["before"]), after = try Self.rack(vector["after"])
            var cellar = Cellar()
            cellar.addRack(before)
            let slots = try Self.slots(vector["slots"], in: before)
            #expect(slots.allSatisfy(before.contains), "\(about)")
            _ = try cellar.addBottles(of: Wine(name: "W"), quantity: slots.count, slots: slots)
            let displaced = cellar.displacedBottles(by: after).compactMap(\.slot)
            #expect(displaced == (try Self.slots(vector["displaced"], in: before)), "\(about)")
            // Saving asks first; agreed, the displaced bottles are taken out, the others stay.
            if !displaced.isEmpty { #expect(throws: CellarError.self) { try cellar.updateRack(after) } }
            try cellar.updateRack(after, takingOut: true)
            let kept = cellar.bottlesInCellar.compactMap(\.slot)
            #expect(kept.count == slots.count - displaced.count, "\(about)")
            #expect(kept.allSatisfy(after.contains), "\(about)")
        }
    }

    @Test func theWizardMakesRowsOfTheirOwn() throws {
        for vector in try Self.cases("wizard") {
            let about = vector["about"] as? String ?? ""
            let settings = try #require(vector["settings"] as? [String: Any])
            let type = try #require(StorageType(rawValue: settings["type"] as? String ?? ""))
            var wizard = StorageSettings(type: type)
            if let layout = settings["layout"] as? String { wizard.layout = try #require(RackLayout(rawValue: layout)) }
            if let perBin = settings["bottlesPerBin"] as? Int { wizard.bottlesPerBin = perBin }
            if let bins = settings["bins"] as? Int { wizard.bins = bins }
            let rows = try #require(settings["rows"] as? [[String: Any]])
            wizard.rowSettings = rows.map {
                StorageRowSettings(positions: $0["positions"] as? Int ?? 1, depth: $0["depth"] as? Int ?? 1,
                                   headToTail: $0["headToTail"] as? Bool ?? false, layers: $0["layers"] as? Int ?? 1)
            }
            let made = wizard.rack(name: "Rack")
            let expected = try #require(vector["rack"] as? [String: Any])
            #expect(made.kind.rawValue == expected["kind"] as? String, "\(about)")
            #expect(made.rows == expected["rows"] as? Int, "\(about)")
            #expect(made.columns == expected["columns"] as? Int, "\(about)")
            #expect(made.depth == expected["depth"] as? Int, "\(about)")
            #expect(made.rowColumns == Self.optional(expected["rowColumns"], as: [Int].self), "\(about)")
            #expect(made.rowDepths == Self.optional(expected["rowDepths"], as: [Int].self), "\(about)")
            #expect(made.shelfLayers == Self.optional(expected["shelfLayers"], as: [Int].self), "\(about)")
            #expect(made.backReversed == Self.optional(expected["backReversed"], as: Bool.self), "\(about)")
            #expect(made.rowBackReversed == Self.optional(expected["rowBackReversed"], as: [Bool].self), "\(about)")
            #expect(made.binStyleValue == Self.optional(expected["binStyle"], as: String.self), "\(about)")
            #expect(made.capacity == vector["capacity"] as? Int, "\(about)")
            // Described again, the same rack comes back.
            #expect(StorageSettings.sameShape(StorageSettings(rack: made).rack(name: "Rack"), made), "\(about)")
        }
    }

    @Test func rowsStartAsTheUniformSettingsAndFollowTheirNumber() {
        var fridge = StorageSettings(type: .wineFridge)
        let uniform = fridge.rack(name: "F")
        fridge.setRowsDiffer(true)
        #expect(fridge.rowSettings?.count == fridge.shelves)
        #expect(StorageSettings.sameShape(fridge.rack(name: "F"), uniform))
        #expect(fridge.rowSettings?.first == StorageRowSettings(positions: 6, depth: 2, headToTail: true, layers: 2))
        fridge.rowSettings?[1].depth = 1
        fridge.rowCount = 7
        #expect(fridge.rowSettings?.count == 7 && fridge.rowSettings?.last?.depth == 2)
        fridge.rowCount = 2
        #expect(fridge.rowSettings?.count == 2 && fridge.rowSettings?.last?.depth == 1)
        fridge.setRowsDiffer(false)
        #expect(fridge.rowSettings == nil)

        var honeycomb = StorageSettings(type: .rack)
        honeycomb.layout = .honeycomb
        honeycomb.rows = 3
        honeycomb.columns = 5
        honeycomb.setRowsDiffer(true)
        #expect(honeycomb.rowSettings?.map(\.positions) == [5, 4, 5])

        var crates = StorageSettings(type: .crates)
        crates.setRowsDiffer(true)
        #expect(crates.rowSettings == nil && !crates.offersRowSettings)
    }
}
