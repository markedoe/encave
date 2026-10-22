import Foundation
import Testing

/// French wording of the app's strings that the review asked to change, read from the app's
/// catalog (apple/Encave/Resources/Localizable.xcstrings), which `apple/tools/l10n.py` writes.
@Suite struct AppStringsTests {
    static func french() throws -> [String: String] {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent() // CaveKitTests
            .deletingLastPathComponent().deletingLastPathComponent() // CaveKit
            .deletingLastPathComponent().deletingLastPathComponent() // apple
            .appendingPathComponent("Encave/Resources/Localizable.xcstrings")
        let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        let strings = catalog?["strings"] as? [String: [String: Any]] ?? [:]
        return strings.compactMapValues { entry in
            let fr = (entry["localizations"] as? [String: Any])?["fr"] as? [String: Any]
            return (fr?["stringUnit"] as? [String: Any])?["value"] as? String
        }
    }

    @Test func discardingANoteSaysAbandonNotDelete() throws {
        let fr = try Self.french()
        #expect(fr["Discard this note?"] == "Abandonner les modifications\u{202F}?")
        #expect(fr["Discard"] == "Abandonner")
    }

    @Test func newStringsAreTranslated() throws {
        let fr = try Self.french()
        #expect(fr["Opens the wine."] == "Ouvre le vin.")
        #expect(fr["Not placed in a rack"]?.isEmpty == false)
        #expect(fr["%lld of %lld"]?.isEmpty == false)
    }
}
