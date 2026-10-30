import Foundation
import Testing
@testable import CaveKit

/// The aroma game (`fixtures/aroma-game-vectors.json`, as `AromaGameTest.kt`), and where its games are kept.
@Suite struct AromaGameTests {
    let vectors: [String: Any]

    init() throws { vectors = try SharedFixture.object("aroma-game-vectors.json") }

    private func rows(_ key: String) -> [[String: Any]] { vectors[key] as? [[String: Any]] ?? [] }
    private func levels(_ value: Any?) -> [String: Int] {
        ((value as? [String: Any]) ?? [:]).compactMapValues { ($0 as? NSNumber)?.intValue }
    }

    @Test func scoreVectors() {
        #expect(!rows("scores").isEmpty)
        for row in rows("scores") {
            let note = row["note"] as? String ?? ""
            let score = AromaGame.score(guesses: row["guesses"] as? [String] ?? [], structure: levels(row["structure"]),
                                        expected: row["expected"] as? [String] ?? [], expectedStructure: levels(row["expectedStructure"]))
            #expect(score.points == (row["points"] as? NSNumber)?.intValue, "\(note)")
            #expect(score.maxPoints == (row["maxPoints"] as? NSNumber)?.intValue, "\(note)")
            #expect(score.accuracy == (row["accuracy"] as? NSNumber)?.intValue, "\(note)")
            #expect(score.exact == row["exact"] as? [String], "\(note)")
            #expect(score.family.map { [$0.guess, $0.expected] } == row["family"] as? [[String]], "\(note)")
            #expect(score.missed == row["missed"] as? [String], "\(note)")
            #expect(score.wrong == row["wrong"] as? [String], "\(note)")
        }
    }

    @Test func levelVectors() {
        for case let pair as [Any] in vectors["levels"] as? [Any] ?? [] {
            #expect(Palate.Level.of(points: (pair[0] as? NSNumber)?.intValue ?? -1).rawValue == pair[1] as? String)
        }
    }

    @Test func palateVectors() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "UTC"))
        func noon(_ day: String) throws -> Date {
            let parts = day.split(separator: "-").compactMap { Int($0) }
            return try #require(calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)))
        }
        #expect(!rows("palate").isEmpty)
        for row in rows("palate") {
            let note = row["note"] as? String ?? ""
            let records = try (row["games"] as? [[String: Any]] ?? []).map { game in
                AromaGameRecord(wineID: UUID(), date: try noon(game["day"] as? String ?? ""), guesses: game["guesses"] as? [String] ?? [],
                                structure: levels(game["structure"]), expected: game["expected"] as? [String] ?? [],
                                expectedStructure: levels(game["expectedStructure"]))
            }
            let palate = Palate(records: records, today: try noon(row["today"] as? String ?? ""), calendar: calendar)
            let result = try #require(row["result"] as? [String: Any])
            #expect(palate.points == (result["points"] as? NSNumber)?.intValue, "\(note)")
            #expect(palate.level.rawValue == result["level"] as? String, "\(note)")
            #expect(palate.accuracy == (result["accuracy"] as? NSNumber)?.intValue, "\(note)")
            #expect(palate.streak == (result["streak"] as? NSNumber)?.intValue, "\(note)")
            #expect(palate.bestFamily == result["best"] as? String, "\(note)")
            #expect(palate.weakestFamily == result["weakest"] as? String, "\(note)")
            #expect(palate.games == (result["games"] as? NSNumber)?.intValue, "\(note)")
        }
    }

    /// Games stay in this device's defaults: never in the cellar, its sync payload or exports.
    @Test func gamesStayOnTheDevice() throws {
        let defaults = try #require(UserDefaults(suiteName: "aroma-game-tests-\(UUID().uuidString)"))
        let store = AromaGameStore(defaults: defaults)
        let wine = Wine(name: "Clos", color: .red)
        // Whole seconds: games are kept with ISO 8601 dates.
        let record = AromaGameRecord(wineID: wine.id, date: Date(timeIntervalSinceReferenceDate: 800_000_000), guesses: ["fruit.redCherry"], structure: [:],
                                     expected: ["fruit.redCherry"], expectedStructure: [:])
        store.add(record)
        #expect(store.load() == [record])
        var cellar = Cellar()
        cellar.wines[wine.id] = wine
        let encoded = String(data: try JSONEncoder().encode(cellar), encoding: .utf8) ?? ""
        let backup = String(data: try CellarExport.json(cellar), encoding: .utf8) ?? ""
        let csv = CellarExport.csv(cellar)
        for text in [encoded, backup, csv] {
            #expect(!text.contains(record.id.uuidString) && !text.contains("aromaGame") && !text.contains("guesses"))
        }
        store.removeAll()
        #expect(store.load().isEmpty)
    }
}
