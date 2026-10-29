import Foundation
import Testing
@testable import CaveKit

/// Diamond racks with edge triangles (`rack.edgeTriangles`, `rack.triangleCapacity`) from shared
/// vectors. The Kotlin core (DiamondEdgeVectorTest.kt) reads the same file,
/// `fixtures/diamond-edge-vectors.json`, so both platforms read, name and edit them alike.
@Suite struct DiamondEdgeVectorTests {
    static func vectors() throws -> [String: Any] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/diamond-edge-vectors.json")
        return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
    }

    static func cases(_ key: String) throws -> [[String: Any]] {
        try #require(try vectors()[key] as? [[String: Any]])
    }

    static func rack(_ object: Any?) throws -> Rack {
        let json = try JSONSerialization.data(withJSONObject: try #require(object))
        return try JSONDecoder().decode(Rack.self, from: json)
    }

    static func keys(_ rack: Rack) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(rack)) as? [String: Any])
    }

    /// The rack as a version that does not know edge triangles holds it: the two keys among
    /// its unknown fields (written back unchanged), so it reads every cell as a whole diamond.
    static func older(_ rack: Rack) throws -> Rack {
        var keys = try keys(rack)
        var unknown: [String: JSONValue] = [:]
        for key in ["edgeTriangles", "triangleCapacity"] {
            if let value = keys.removeValue(forKey: key) {
                unknown[key] = try JSONDecoder().decode(JSONValue.self, from: JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed))
            }
        }
        var older = try Self.rack(keys)
        older.edgeTrianglesValue = nil
        older.triangleCapacityValue = nil
        older.unknownFields = UnknownFields(unknown)
        return older
    }

    /// "B3" for a diamond, "top 2" for a triangle.
    static func name(_ place: DiamondPlace?, row: Int, column: Int) -> String {
        switch place {
        case let .diamond(row, column)?: "\(Rack.rowLetters(row))\(column + 1)"
        case let .triangle(edge, index)?: "\(edge.rawValue) \(index + 1)"
        case nil: "\(Rack.rowLetters(row))\(column + 1)"
        }
    }

    @Test func racksAreReadTheSameEverywhere() throws {
        let cases = try Self.cases("wire")
        #expect(cases.count >= 5)
        for vector in cases {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector["rack"])
            let layout = rack.diamondLayout
            #expect(DiamondEdge.allCases.filter { layout?.edges.contains($0) == true }.map(\.rawValue)
                    == vector["edges"] as? [String], "\(about)")
            #expect(layout?.rows == vector["diamondRows"] as? Int, "\(about)")
            #expect(layout?.columns == vector["diamondColumns"] as? Int, "\(about)")
            #expect((0..<rack.rows).map(rack.columns(inRow:)) == vector["columnsInRow"] as? [Int], "\(about)")
            #expect((0..<rack.rows).map(rack.isOffset(row:)) == vector["offsetRows"] as? [Bool], "\(about)")
            if layout != nil { #expect(rack.triangleCapacity == vector["triangleCapacity"] as? Int, "\(about)") }
            #expect(rack.capacity == vector["capacity"] as? Int, "\(about)")
            #expect(rack.allSlots.count == rack.capacity, "\(about)")
            #expect(rack.allSlots.allSatisfy(rack.hasPosition), "\(about)")
            let start = rack.allSlots.prefix(6).map { [$0.row, $0.column, $0.depth] }
            #expect(start == vector["fillOrderStart"] as? [[Int]], "\(about)")
            for place in try #require(vector["places"] as? [[String: Any]]) {
                let row = place["row"] as? Int ?? -1, column = place["column"] as? Int ?? -1
                let cell = RackCell(rackID: rack.id, row: row, column: column)
                #expect(rack.contains(cell), "\(about)")
                #expect(Self.name(rack.diamondPlace(of: cell), row: row, column: column) == place["place"] as? String, "\(about)")
                #expect(rack.positions(of: cell) == place["positions"] as? Int, "\(about)")
                let slot = Slot(rackID: rack.id, row: row, column: column)
                #expect(rack.label(for: slot) == place["label"] as? String, "\(about)")
                #expect(rack.placeName(for: slot) == place["en"] as? String, "\(about)")
                if let triangle = rack.diamondPlace(of: cell), let layout {
                    // Each place has one cell, and back.
                    let back = try #require(layout.cell(of: triangle))
                    #expect(back.row == row && back.column == column, "\(about)")
                }
            }

            // Written back as read, and read again the same.
            let keys = try Self.keys(rack)
            let raw = try #require(vector["rack"] as? [String: Any])
            #expect(keys["edgeTriangles"] as? [String] == raw["edgeTriangles"] as? [String], "\(about)")
            #expect(keys["triangleCapacity"] as? Int == raw["triangleCapacity"] as? Int, "\(about)")
            #expect(try JSONDecoder().decode(Rack.self, from: JSONEncoder().encode(rack)) == rack, "\(about)")
        }
    }

    /// A version that does not know the triangles keeps both keys and judges the same places:
    /// every place this version offers is one it keeps, and the reverse holds within a bin.
    @Test func olderVersionsKeepTheKeysAndTheSamePlaces() throws {
        for vector in try Self.cases("wire") {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector["rack"])
            let older = try Self.older(rack)
            #expect(older.diamondLayout == nil, "\(about)")
            #expect(older.cells == rack.cells, "\(about)")
            #expect(rack.allSlots.allSatisfy(older.contains), "\(about)")
            #expect(older.allSlots.allSatisfy(rack.contains), "\(about)")
            // It writes them back unchanged after its own edit (a rename): its known keys, then
            // the unknown ones as read. Read again, the triangles are the same.
            var written = try Self.keys(older)
            written["name"] = "Renamed"
            for (key, value) in older.unknownFields.values {
                written[key] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value), options: .fragmentsAllowed)
            }
            let back = try Self.rack(written)
            #expect(back.diamondLayout == rack.diamondLayout, "\(about)")
            #expect(back.triangleCapacityValue == rack.triangleCapacityValue, "\(about)")
        }
    }

    @Test func placesEveryVersionKeeps() throws {
        for vector in try Self.cases("kept") {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector["rack"])
            let place = try #require(vector["slot"] as? [Int])
            let slot = Slot(rackID: rack.id, row: place[0], column: place[1], depth: place[2])
            #expect(rack.contains(slot) == vector["contains"] as? Bool, "\(about)")
            #expect(try Self.older(rack).contains(slot) == vector["contains"] as? Bool, "\(about)")
            #expect(rack.hasPosition(slot) == vector["hasPosition"] as? Bool, "\(about)")
            #expect(rack.label(for: slot) == vector["label"] as? String, "\(about)")
            #expect(rack.placeName(for: slot) == vector["en"] as? String, "\(about)")
        }
    }

    @Test func editsMoveBottlesWithTheirDiamond() throws {
        for vector in try Self.cases("moves") {
            let about = vector["about"] as? String ?? ""
            let before = try Self.rack(vector["before"]), after = try Self.rack(vector["after"])
            let map = Rack.diamondCellMap(from: before, to: after)
            for move in try #require(vector["cells"] as? [[String: Any]]) {
                let from = try #require(move["from"] as? [Int])
                let cell = RackCell(rackID: before.id, row: from[0], column: from[1])
                let to = (move["to"] as? [Int]).map { RackCell(rackID: before.id, row: $0[0], column: $0[1]) }
                let moved = Rack.moved(Slot(rackID: before.id, row: from[0], column: from[1], depth: 0), rowMap: [:], cellMap: map)
                #expect(moved?.cell == to, "\(about) \(from)")
                #expect(to.map(after.contains) ?? true, "\(about)")
                _ = cell
            }
        }
    }

    @Test func theWizardMakesTriangles() throws {
        for vector in try Self.cases("wizard") {
            let about = vector["about"] as? String ?? ""
            let settings = try #require(vector["settings"] as? [String: Any])
            var wizard = StorageSettings(type: .rack)
            wizard.layout = .diamond
            wizard.rows = settings["rows"] as? Int ?? 1
            wizard.columns = settings["columns"] as? Int ?? 1
            wizard.bottlesPerBin = settings["bottlesPerBin"] as? Int ?? 10
            wizard.diamondEdges = Set((settings["edges"] as? [String] ?? []).compactMap(DiamondEdge.init(rawValue:)))
            wizard.bottlesPerTriangle = settings["bottlesPerTriangle"] as? Int
            let made = wizard.rack(name: "Rack")
            var keys = try Self.keys(made)
            keys["id"] = nil
            keys["name"] = nil
            let expected = try #require(vector["rack"] as? [String: Any])
            #expect(NSDictionary(dictionary: keys) == NSDictionary(dictionary: expected), "\(about)")
            // Described again, the same rack comes back.
            let again = StorageSettings(rack: made)
            #expect(again.diamondEdges == wizard.diamondEdges, "\(about)")
            #expect(again.rowSettings == nil, "\(about)")
            #expect(StorageSettings.sameShape(again.rack(name: "Rack"), made), "\(about)")
        }
    }
}
