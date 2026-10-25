import Foundation
import Testing
@testable import CaveKit

@Suite struct EveningRequestTests {
    /// The vectors shared with the Android core, in `fixtures/evening-request-vectors.json`.
    struct Vectors: Decodable {
        struct Case: Decodable {
            var about: String
            var occasion: String
            var bottlesWanted: Int?
            var onlyReady: Bool
            var language: EveningRequest.Language
            var year: Int
            var omitted: Int
            var wines: [EveningRequest.Wine]
            var text: String
        }
        var cases: [Case]
    }

    static func vectors() throws -> Vectors {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .deletingLastPathComponent().appendingPathComponent("fixtures/evening-request-vectors.json")
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: file))
    }

    @Test func textMatchesTheSharedVectors() throws {
        let vectors = try Self.vectors()
        #expect(vectors.cases.count >= 5)
        for vector in vectors.cases {
            let request = EveningRequest(occasion: vector.occasion, bottlesWanted: vector.bottlesWanted,
                                         onlyReady: vector.onlyReady, language: vector.language, year: vector.year,
                                         wines: vector.wines, omitted: vector.omitted)
            #expect(request.text == vector.text, "\(vector.about)")
        }
    }

    @Test func languageFollowsTheCode() {
        #expect(EveningRequest.Language(code: "fr") == .fr)
        #expect(EveningRequest.Language(code: "fr-CA") == .fr)
        #expect(EveningRequest.Language(code: "en") == .en)
        #expect(EveningRequest.Language(code: "de") == .en)
        #expect(EveningRequest.Language(code: nil) == .en)
    }

    private func cellar() throws -> Cellar {
        var cellar = Cellar()
        try cellar.addBottles(of: Wine(name: "Young", vintage: 2024, color: .red, drinkFrom: 2030, drinkUntil: 2040), quantity: 2)
        try cellar.addBottles(of: Wine(name: "Ready", producer: "Dom. B", vintage: 2020, color: .red, region: "Loire",
                                       drinkFrom: 2022, drinkUntil: 2030),
                              purchase: PurchaseInfo(price: 42, place: "Wine shop", storageNote: "Kitchen"))
        let soon = try cellar.addBottles(of: Wine(name: "Soon", producer: "Dom. A", vintage: 2018, color: .white,
                                                  appellation: "Vouvray", drinkFrom: 2020, drinkUntil: 2026, notes: "Secret note"),
                                          quantity: 2)
        try cellar.reserve([soon[0].id], for: "Christmas")
        return cellar
    }

    @Test func readyBottlesComeMostUrgentFirstWithoutThoseKeptForAnOccasion() throws {
        let request = try cellar().eveningRequest(occasion: "Roast chicken", bottlesWanted: 2, language: .en, year: 2026)
        #expect(request.wines.map(\.name) == ["Soon", "Ready"])
        #expect(request.wines.map(\.bottles) == [1, 1])
        #expect(request.wines[1].appellation == "Loire", "the region stands in for a missing appellation")
        let text = request.text
        #expect(text.contains("Occasion: Roast chicken"))
        #expect(text.contains("2. Ready 2020 | Dom. B | red | Loire | 2022-2030 | ready | 1 | Kitchen"))
        for secret in ["42", "Wine shop", "Secret note", "Christmas"] {
            #expect(!text.contains(secret), "\(secret) must not be in the request")
        }
    }

    @Test func everyBottleIsListedWhenNotOnlyReady() throws {
        let request = try cellar().eveningRequest(onlyReady: false, language: .fr, year: 2026)
        #expect(request.wines.map(\.name) == ["Ready", "Soon", "Young"])
        #expect(request.wines.map(\.bottles) == [1, 2, 2])
        #expect(!request.text.contains("ne contient que"))
        #expect(request.text.contains("Ma cave en 2026 : 3 vins, 5 bouteilles."))
    }

    @Test func aLimitLeavesWinesOut() throws {
        let request = try cellar().eveningRequest(onlyReady: false, language: .en, year: 2026, limit: 1)
        #expect(request.wines.count == 1 && request.omitted == 2)
        #expect(request.text.contains("My cellar in 2026: 1 wine, 1 bottle (2 more wines left out)."))
    }
}
