import Foundation
import Testing
@testable import CaveKit

/// The storage type of bins from shared vectors. The Kotlin core (StorageTypeVectorTest.kt)
/// reads the same file, `fixtures/storage-type-vectors.json`.
@Suite struct StorageTypeVectorTests {
    static func root() throws -> [String: Any] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/storage-type-vectors.json")
        return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
    }

    static func cases() throws -> [[String: Any]] { try #require(root()["cases"] as? [[String: Any]]) }

    static func rack(_ vector: [String: Any]) throws -> Rack {
        var rack: Rack
        if let wizard = vector["wizard"] as? [String: Any] {
            let type = try #require(StorageType(rawValue: wizard["type"] as? String ?? ""))
            var settings = StorageSettings(type: type)
            if let layout = wizard["layout"] as? String { settings.layout = try #require(RackLayout(rawValue: layout)) }
            if let rows = wizard["rows"] as? Int { settings.rows = rows }
            if let columns = wizard["columns"] as? Int { settings.columns = columns }
            if let bins = wizard["bins"] as? Int { settings.bins = bins }
            if let perBin = wizard["bottlesPerBin"] as? Int { settings.bottlesPerBin = perBin }
            rack = settings.rack(name: "Bins")
        } else {
            let record = try #require(vector["record"])
            rack = try JSONDecoder().decode(Rack.self, from: JSONSerialization.data(withJSONObject: record))
        }
        if let edit = vector["edit"] as? [String: Any], let columns = edit["columns"] as? Int {
            rack.columns = columns
        }
        return rack
    }

    @Test func storageTypeCountAndBinStyle() throws {
        let cases = try Self.cases()
        #expect(cases.count >= 5)
        for vector in cases {
            let about = vector["about"] as? String ?? ""
            let rack = try Self.rack(vector)
            #expect(rack.storageType.rawValue == vector["storageType"] as? String, "\(about)")
            #expect(rack.storageCount == vector["storageCount"] as? Int, "\(about)")
            let encoded = try #require(
                JSONSerialization.jsonObject(with: JSONEncoder().encode(rack)) as? [String: Any])
            #expect(encoded["binStyle"] as? String == vector["binStyle"] as? String, "\(about)")
            // Read back, the rack is the same.
            let again = try JSONDecoder().decode(Rack.self, from: JSONEncoder().encode(rack))
            #expect(again == rack, "\(about)")
            #expect(again.unknownFields.isEmpty, "\(about)")
        }
    }

    @Test func editorSettingsKeepTheType() throws {
        for vector in try Self.cases() {
            let rack = try Self.rack(vector)
            #expect(StorageSettings(rack: rack).type == rack.storageType, "\(vector["about"] as? String ?? "")")
        }
    }

    /// The bins editor, as `RackEditor` saves: a rename, a type picked, a count of bins or rows and columns.
    @Test func editorKeepsOrSetsTheType() throws {
        let cases = try #require(Self.root()["editorCases"] as? [[String: Any]])
        #expect(cases.count >= 8)
        for vector in cases {
            let about = vector["about"] as? String ?? ""
            let record = try #require(vector["record"])
            let rack = try JSONDecoder().decode(Rack.self, from: JSONSerialization.data(withJSONObject: record))
            let edit = vector["edit"] as? [String: Any] ?? [:]
            let chosen: BinStyle? = switch edit["choose"] as? String {
            case "rack": .diamond
            case "crates": .crates
            default: nil
            }
            let reshaped = edit["rows"] != nil || edit["columns"] != nil
            let shape = Rack(id: rack.id, name: rack.name, kind: .bins, rows: edit["rows"] as? Int ?? rack.rows,
                             columns: edit["columns"] as? Int ?? rack.columns, binCapacity: rack.binCapacity,
                             rowColumns: reshaped ? nil : rack.rowColumns)
            let saved = rack.savingBins(name: edit["name"] as? String ?? rack.name, binCapacity: rack.binCapacity,
                                        chosen: chosen, bins: edit["bins"] as? Int ?? rack.cellCount, shape: shape)
            #expect(saved.storageType.rawValue == vector["storageType"] as? String, "\(about)")
            #expect(saved.binStyleValue == vector["binStyle"] as? String, "\(about)")
            #expect((0..<saved.rows).map { saved.columns(inRow: $0) } == vector["columnsInRow"] as? [Int], "\(about)")
            #expect(rack.maxEditableBins == vector["maxEditableBins"] as? Int, "\(about)")
            #expect(saved.id == rack.id && saved.name == edit["name"] as? String ?? rack.name, "\(about)")
        }
    }
}
