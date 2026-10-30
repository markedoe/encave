import Foundation
import Testing
@testable import CaveKit

/// The expected profile: how it is read (`fixtures/expected-profile-vectors.json`), the
/// bundled profiles (`fixtures/expected-profiles.json`), as `ExpectedProfileTest.kt`.
@Suite struct ExpectedProfileTests {
    let vectors: [String: Any]

    init() throws { vectors = try SharedFixture.object("expected-profile-vectors.json") }

    private func rows(_ key: String) -> [[String: Any]] { vectors[key] as? [[String: Any]] ?? [] }

    private func json(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) }

    @Test func readVectors() throws {
        #expect(!rows("read").isEmpty)
        for row in rows("read") {
            let profile = try JSONDecoder().decode(ExpectedProfile.self, from: try json(try #require(row["input"])))
            let written = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(profile)) as? NSDictionary)
            #expect(written == (try #require(row["output"] as? NSDictionary)), "\(row["note"] ?? "")")
        }
    }

    @Test func lookupVectors() throws {
        #expect(rows("lookup").count > 10)
        for row in rows("lookup") {
            var object = try #require(row["wine"] as? [String: Any])
            object["id"] = UUID().uuidString
            let wine = try JSONDecoder().decode(Wine.self, from: try json(object))
            #expect(ExpectedProfiles.entry(for: wine)?.id == row["profile"] as? String, "\(row["note"] ?? "")")
        }
    }

    @Test func comparisonVectors() throws {
        for row in rows("comparison") {
            let comparison = AromaComparison(expected: row["expected"] as? [String] ?? [], found: row["found"] as? [String] ?? [])
            #expect(comparison.matched == row["matched"] as? [String])
            #expect(comparison.missed == row["missed"] as? [String])
            #expect(comparison.extra == row["extra"] as? [String])
        }
    }

    /// The embedded text is the shared file, and every id in it is one both apps know.
    @Test func bundledProfilesAreTheFixture() throws {
        let fixture = try String(contentsOf: SharedFixture.url("expected-profiles.json"), encoding: .utf8)
        #expect(ExpectedProfiles.json.trimmingCharacters(in: .newlines) == fixture.trimmingCharacters(in: .newlines))
        let entries = ExpectedProfiles.entries
        #expect(entries.count >= 40)
        #expect(Set(entries.map(\.id)).count == entries.count)
        let aromas = Set(Vocabulary.aromaIDs), structure = Set(Vocabulary.structure.map(\.id))
        let dishes = Set(DishCategory.allCases.map(\.rawValue)), colors = Set(WineColor.allCases.map(\.rawValue))
        for entry in entries {
            #expect(!entry.aromas.isEmpty && entry.aromas.allSatisfy(aromas.contains), "\(entry.id)")
            #expect(Set(entry.structure.keys).isSubset(of: structure) && entry.structure.values.allSatisfy { (1...5).contains($0) }, "\(entry.id)")
            #expect(entry.pairings.allSatisfy(dishes.contains), "\(entry.id)")
            #expect(Set(entry.colors ?? []).isSubset(of: colors), "\(entry.id)")
            #expect(entry.servingMin <= entry.servingMax && entry.decantMinutes >= 0, "\(entry.id)")
            #expect(!(entry.note["en"] ?? "").isEmpty && !(entry.note["fr"] ?? "").isEmpty, "\(entry.id)")
            // Names are listed as they are compared.
            for name in (entry.appellations ?? []) + (entry.grapes ?? []).flatMap({ $0 }) {
                #expect(ExpectedProfiles.normalized(name) == name, "\(entry.id): \(name)")
            }
        }
        for color in WineColor.allCases {
            #expect(ExpectedProfiles.entry(for: Wine(name: "X", color: color)) != nil, "\(color)")
        }
    }

    @Test func wineRoundTripAndOlderVersions() throws {
        var wine = Wine(name: "Clos", vintage: 2019, color: .red, grapes: ["Pinot Noir"])
        wine.expectedProfile = ExpectedProfile(source: ExpectedProfile.ai, aromas: ["fruit.redCherry", "earth.forestFloor"],
                                               structure: ["acidity": 4, "tannin": 2], servingMin: 15, servingMax: 17,
                                               decantMinutes: 0, pairings: ["poultry"], text: "Silky.", language: "en",
                                               model: "claude-opus-5-5", date: Date(timeIntervalSinceReferenceDate: 800_000_000))
        let data = try JSONEncoder().encode(wine)
        #expect(try JSONDecoder().decode(Wine.self, from: data) == wine)
        // A version without the field keeps it as an unknown field and writes it back unchanged.
        let old = try JSONDecoder().decode(OlderWine.self, from: data)
        #expect(old.unknownFields.values["expectedProfile"] != nil)
        let back = try JSONDecoder().decode(Wine.self, from: JSONEncoder().encode(old))
        #expect(back.expectedProfile == wine.expectedProfile)
        // No profile: the key is left out, so files of earlier wines stay as they were.
        let plain = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(Wine(name: "X"))) as? [String: Any])
        #expect(plain["expectedProfile"] == nil)
    }

    @Test func storedProfileWinsOverBundled() {
        var wine = Wine(name: "X", color: .red, grapes: ["Merlot"])
        #expect(wine.expectedOrBundledProfile?.bundledID == "merlot")
        #expect(wine.expectedOrBundledProfile?.source == ExpectedProfile.bundled)
        wine.expectedProfile = ExpectedProfile(source: ExpectedProfile.ai, aromas: ["fruit.plum"])
        #expect(wine.expectedOrBundledProfile?.source == ExpectedProfile.ai)
        // An empty stored profile shows the bundled one.
        wine.expectedProfile = ExpectedProfile(source: ExpectedProfile.ai)
        #expect(wine.expectedOrBundledProfile?.bundledID == "merlot")
        #expect(ExpectedProfiles.note(id: "merlot", language: "fr")?.isEmpty == false)
        #expect(ExpectedProfiles.note(id: "merlot", language: "de") == ExpectedProfiles.note(id: "merlot", language: "en"))
    }

    @Test func mergeKeepsTheProfile() throws {
        var cellar = Cellar()
        var source = Wine(name: "A")
        source.expectedProfile = ExpectedProfile(source: ExpectedProfile.ai, aromas: ["fruit.plum"])
        let target = Wine(name: "A")
        cellar.wines = [target.id: target, source.id: source]
        try cellar.mergeWine(source.id, into: target.id)
        #expect(cellar.wines[target.id]?.expectedProfile?.aromas == ["fruit.plum"])
    }
}

/// A wine as the previous version reads it: every key but "expectedProfile" known, the
/// others kept as unknown fields and written back unchanged (docs/SYNC.md §1).
private struct OlderWine: Codable {
    var known: [String: JSONValue]
    var unknownFields: UnknownFields

    private static let knownKeys: Set<String> = [
        "id", "name", "producer", "vintage", "color", "region", "country", "appellation", "grapes", "drinkFrom", "drinkUntil",
        "notes", "photoID", "barcodes", "tags", "extraPhotoIDs", "currentPrice", "currentPriceCurrency", "currentPriceDate",
        "coverPhotoID", "attachments", "aiCompletedAt", "labelFrame", "abv", "classification", "lieuDit", "dosage", "sweetness",
        "grapeShares", "ageing", "farming", "farmingCertified", "closure", "criticScores",
    ]

    init(from decoder: Decoder) throws {
        let values = try [String: JSONValue](from: decoder)
        known = values.filter { Self.knownKeys.contains($0.key) }
        unknownFields = UnknownFields(values.filter { !Self.knownKeys.contains($0.key) })
    }

    func encode(to encoder: Encoder) throws {
        var all = known
        for (key, value) in unknownFields.values where all[key] == nil { all[key] = value }
        try all.encode(to: encoder)
    }
}

/// The AI's "expected" answer and what is sent for it, as `ExpectedRequestTest.kt`.
@Suite struct ExpectedRequestTests {
    @Test func answerVectors() throws {
        let vectors = try SharedFixture.object("expected-profile-vectors.json")
        let answers = try #require(vectors["answers"] as? [[String: Any]])
        #expect(!answers.isEmpty)
        for row in answers {
            let profile = ExpectedRequest.profile(fromAnswer: try #require(row["answer"] as? String), source: "ai", model: "m",
                                                  language: "fr", date: Date(timeIntervalSinceReferenceDate: 0))
            guard let expected = row["profile"] as? NSDictionary else {
                #expect(profile == nil, "\(row["note"] ?? "")")
                continue
            }
            var written = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(try #require(profile))) as? [String: Any])
            written["date"] = nil
            #expect(NSDictionary(dictionary: written) == expected, "\(row["note"] ?? "")")
        }
    }

    /// From the identity alone: producer, name, appellation, vintage, colour and grapes, nothing else.
    @Test func identityRequestSendsOnlyTheIdentity() throws {
        var wine = Wine(name: "Les Galets", producer: "Domaine Martin", vintage: 2021, color: .white, region: "Bourgogne",
                        country: "France", appellation: "Chablis", grapes: ["Chardonnay"], notes: "Secret note", photoID: "sha256-x")
        wine.currentPrice = 42
        wine.currentPriceCurrency = "EUR"
        wine.tags = ["Gift from Anna"]
        let prompt = ExpectedRequest.prompt(for: WineIdentity(wine), language: "fr")
        for sent in ["Producer: Domaine Martin", "Name: Les Galets", "Appellation: Chablis", "Vintage: 2021", "Colour: white",
                     "Grapes: Chardonnay", "in French"] {
            #expect(prompt.contains(sent), "\(sent)")
        }
        for kept in ["Secret note", "42", "EUR", "Gift", "sha256", "Bourgogne", "France"] {
            #expect(!prompt.contains(kept), "\(kept)")
        }
        let schema = ExpectedRequest.answerSchema
        #expect(schema["required"] as? [String] == ["expected"])
        #expect(JSONSerialization.isValidJSONObject(schema))
    }

    @Test func labelRequestAsksWhatToExpectWithALanguage() throws {
        let plain = LabelRequest(findsLabel: false)
        #expect(!(plain.schema["required"] as? [String] ?? []).contains("expected"))
        let asking = LabelRequest(findsLabel: true, expectedLanguage: "en")
        let required = try #require(asking.schema["required"] as? [String])
        let properties = try #require(asking.schema["properties"] as? [String: Any])
        #expect(required.contains("expected") && properties["expected"] != nil)
        #expect(Set(required) == Set(properties.keys))
        #expect(asking.prompt.contains("- expected:") && asking.prompt.contains("in English"))
        #expect(asking.prompt.hasSuffix("Do not invent a producer or vintage that is not visible."))
    }
}
