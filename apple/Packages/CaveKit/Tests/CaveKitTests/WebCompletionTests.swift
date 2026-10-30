import Foundation
import Testing
@testable import CaveKit

/// Completing a saved wine from the web (`fixtures/web-completion-vectors.json`, as `WebCompletionTest.kt`).
@Suite struct WebCompletionTests {
    @Test func vectors() throws {
        let vectors = try SharedFixture.object("web-completion-vectors.json")
        let cases = try #require(vectors["cases"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        let date = Date(timeIntervalSinceReferenceDate: 800_000_000)
        for row in cases {
            let note = row["note"] as? String ?? ""
            var object = try #require(row["wine"] as? [String: Any])
            object["id"] = UUID().uuidString
            let wine = try JSONDecoder().decode(Wine.self, from: JSONSerialization.data(withJSONObject: object))
            let completion = WebCompletion.parse(answer: try #require(row["answer"] as? String), model: "m", language: "en", date: date)
            guard let result = row["result"] as? [String: Any] else {
                #expect(completion == nil, "\(note)")
                continue
            }
            let sources = ((row["sources"] as? [[String: Any]]) ?? []).map { WineWebSource(url: $0["url"] as? String ?? "", title: $0["title"] as? String) }
            let completed = try #require(completion).completing(wine, sources: sources, date: date)
            #expect((completed != wine) == (row["changed"] as? Bool), "\(note)")
            #expect((completed.aiCompletedAt == date) == (row["changed"] as? Bool), "\(note)")
            #expect(completed.producer == result["producer"] as? String, "\(note)")
            #expect(completed.appellation == result["appellation"] as? String, "\(note)")
            #expect(completed.region == result["region"] as? String, "\(note)")
            #expect(completed.country == result["country"] as? String, "\(note)")
            #expect(completed.grapes == result["grapes"] as? [String], "\(note)")
            #expect(completed.classification == result["classification"] as? String, "\(note)")
            #expect(completed.abv.map { NSDecimalNumber(decimal: $0).doubleValue } == (result["abv"] as? NSNumber)?.doubleValue, "\(note)")
            #expect(completed.drinkFrom == (result["drinkFrom"] as? NSNumber)?.intValue, "\(note)")
            #expect(completed.drinkUntil == (result["drinkUntil"] as? NSNumber)?.intValue, "\(note)")
            #expect(completed.expectedProfile?.aromas == result["expectedAromas"] as? [String], "\(note)")
            #expect(completed.expectedProfile?.source == result["expectedSource"] as? String, "\(note)")
            #expect(completed.webSources?.map(\.url) == result["webSources"] as? [String], "\(note)")
        }
    }

    /// Only the identity is sent, and the request asks what to expect too.
    @Test func promptSendsOnlyTheIdentity() throws {
        var wine = Wine(name: "Les Galets", producer: "Domaine Martin", vintage: 2021, color: .white, region: "Bourgogne",
                        appellation: "Chablis", grapes: ["Chardonnay"], notes: "Secret note")
        wine.currentPrice = 42
        let prompt = WebCompletion.prompt(for: WineIdentity(wine), language: "en")
        #expect(prompt.contains("Name: Les Galets") && prompt.contains("- expected:"))
        #expect(!prompt.contains("Secret") && !prompt.contains("42") && !prompt.contains("Bourgogne"))
        #expect((WebCompletion.schema["required"] as? [String])?.contains("expected") == true)
        #expect(JSONSerialization.isValidJSONObject(WebCompletion.schema))
    }

    @Test func webSourcesRoundTripAndOnlyWebModelsSearch() throws {
        var wine = Wine(name: "X")
        wine.webSources = [WineWebSource(url: "https://a.example", title: "A")]
        #expect(try JSONDecoder().decode(Wine.self, from: JSONEncoder().encode(wine)) == wine)
        wine.webSources = []
        #expect(wine.webSources == nil)
        let anthropic = AIConnection(provider: .anthropic, allowsWebSearch: true), ollama = AIConnection(provider: .ollama, allowsWebSearch: true)
        var configuration = AIConfiguration(connections: [ollama, anthropic])
        configuration.addModel("gemma", connection: ollama.id)
        configuration.addModel("claude-opus-5-5", connection: anthropic.id)
        #expect(configuration.webSearchOrder.map(\.model) == ["claude-opus-5-5"])
    }
}
