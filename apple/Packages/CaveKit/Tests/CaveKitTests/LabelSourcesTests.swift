import Foundation
import Testing
@testable import CaveKit

/// The details marked "Estimated by AI", from `fixtures/ai-label-answer-vectors.json`, read
/// the same way by `LabelSourcesTest.kt`.
@Suite struct LabelSourcesTests {
    @Test func answers() throws {
        let vectors = try SharedFixture.object("ai-label-answer-vectors.json")
        let answers = try #require(vectors["answers"] as? [[String: Any]])
        #expect(!answers.isEmpty)
        for row in answers {
            let answer = try #require(row["answer"] as? String)
            let expected = Set(try #require(row["estimated"] as? [String]))
            #expect(LabelSources.estimated(inAnswer: answer) == expected, "\(row["note"] ?? "")")
        }
    }

    /// Every detail has a source in the schema, required for strict schemas, and the prompt asks for it.
    @Test func schemaAsksForEverySource() throws {
        for findsLabel in [false, true] {
            let request = LabelRequest(findsLabel: findsLabel)
            let required = try #require(request.schema["required"] as? [String])
            #expect(required.contains("sources"))
            let properties = try #require(request.schema["properties"] as? [String: Any])
            let sources = try #require(properties["sources"] as? [String: Any])
            let sourcesRequired = try #require(sources["required"] as? [String])
            let sourcesProperties = try #require(sources["properties"] as? [String: Any])
            #expect(Set(sourcesRequired) == Set(LabelRequest.sourceKeys))
            #expect(Set(sourcesProperties.keys) == Set(LabelRequest.sourceKeys))
            #expect(sources["additionalProperties"] as? Bool == false)
            #expect(request.prompt.contains("\"knowledge\""))
            #expect(request.prompt.contains("when you are not sure"))
            // Still a valid JSON schema.
            #expect(JSONSerialization.isValidJSONObject(request.schema))
        }
    }
}
