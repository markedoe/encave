import CaveKit
import Foundation

extension CellarModel {
    /// Keeps what to expect with the wine (nil: back to the bundled profile), with Undo,
    /// which puts back only what this change replaced.
    func setExpectedProfile(_ profile: ExpectedProfile?, for wineID: UUID) {
        guard var wine = cellar.wines[wineID] else { return }
        let before = wine.expectedProfile
        guard before != profile else { return }
        wine.expectedProfile = profile
        guard update({ try $0.updateWine(wine) }) else { return }
        let message = profile == nil ? String(localized: "Back to the typical profile") : String(localized: "What to expect saved")
        showToast(message, lasting: .afterChanges, undo: { [weak self] in
            guard let self, var live = self.cellar.wines[wineID], live.expectedProfile == profile else { return }
            live.expectedProfile = before
            _ = self.update { try $0.updateWine(live) }
        })
    }
}

/// "Complete New Wines from the Web": on this device only, off by default.
enum WebCompletionSettings {
    static let automaticKey = "ai.webCompletion.automatic"

    static var isAutomatic: Bool { UserDefaults.standard.bool(forKey: automaticKey) }

    /// A model may search the web: a connection allows it and has a model in the list.
    static var isAvailable: Bool { !AISettings.load().webSearchOrder.isEmpty }
}

extension CellarModel {
    /// Completes a saved wine from the web (`AIService.completeFromWeb`): only its empty
    /// details, what to expect and the pages cited, with Undo. Returns what to tell the
    /// person when nothing was completed; nothing for an automatic completion.
    @discardableResult
    func completeFromWeb(_ wineID: UUID, automatic: Bool = false) async -> String? {
        guard let wine = cellar.wines[wineID] else { return nil }
        do {
            guard let found = try await AIService.completeFromWeb(WineIdentity(wine), language: AIService.language) else {
                return automatic ? nil : String(localized: "The answer from the AI service could not be read.")
            }
            guard let live = cellar.wines[wineID] else { return nil }
            let completed = found.completion.completing(live, sources: found.sources, date: Date())
            guard completed != live else { return automatic ? nil : String(localized: "The AI found nothing to add") }
            guard update({ try $0.updateWine(completed) }) else { return nil }
            showToast(String(localized: "Completed from the web"), lasting: .afterChanges, undo: { [weak self] in
                guard let self, var current = self.cellar.wines[wineID] else { return }
                func restore<Value: Equatable>(_ field: WritableKeyPath<Wine, Value>) {
                    if current[keyPath: field] == completed[keyPath: field] { current[keyPath: field] = live[keyPath: field] }
                }
                restore(\.producer); restore(\.appellation); restore(\.region); restore(\.country); restore(\.classification)
                restore(\.grapes); restore(\.abv); restore(\.drinkFrom); restore(\.drinkUntil)
                restore(\.expectedProfile); restore(\.webSources); restore(\.aiCompletedAt)
                _ = self.update { try $0.updateWine(current) }
            })
            return nil
        } catch is CancellationError {
            return nil
        } catch {
            return automatic ? nil : error.localizedDescription
        }
    }
}
