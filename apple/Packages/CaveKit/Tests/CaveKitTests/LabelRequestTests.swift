import Foundation
import Testing
@testable import CaveKit

/// The AI request asks where the label is only with the studio look on (as `LabelRequestTest.kt`).
struct LabelRequestTests {
    private func required(_ request: LabelRequest) -> [String] { request.schema["required"] as? [String] ?? [] }
    private func properties(_ request: LabelRequest) -> [String: Any] { request.schema["properties"] as? [String: Any] ?? [:] }

    @Test func withTheStudioLookItAsksForTheLabelsPlace() {
        let request = LabelRequest(findsLabel: true)
        #expect(required(request).contains("label_box"))
        #expect(required(request).contains("label_angle"))
        #expect(properties(request)["label_box"] != nil)
        #expect(properties(request)["label_angle"] != nil)
        #expect(request.prompt.contains("label_box"))
        #expect(request.prompt.contains("uphill to the right"))
        // Every property is required, as strict schemas want.
        #expect(Set(required(request)) == Set(properties(request).keys))
    }

    @Test func withoutItTheRequestIsAsBefore() {
        let request = LabelRequest(findsLabel: false)
        #expect(!required(request).contains("label_box"))
        #expect(!required(request).contains("label_angle"))
        #expect(properties(request)["label_box"] == nil)
        #expect(!request.prompt.contains("label_"))
        #expect(Set(required(request)) == Set(properties(request).keys))
        #expect(request.prompt.hasPrefix("This is a photo of a wine label."))
        #expect(request.prompt.hasSuffix("Do not invent a producer or vintage that is not visible."))
    }

    @Test func theSchemaIsValidJSON() throws {
        for findsLabel in [true, false] {
            let data = try JSONSerialization.data(withJSONObject: LabelRequest(findsLabel: findsLabel).schema)
            #expect(!data.isEmpty)
        }
    }
}
