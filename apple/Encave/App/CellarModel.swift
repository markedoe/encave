import CaveKit
import Foundation
import Observation

/// Holds the cellar for the UI and persists every change.
@MainActor
@Observable
final class CellarModel {
    private(set) var cellar: Cellar
    var errorMessage: String?
    private let store: any CellarStore

    init(store: any CellarStore) {
        self.store = store
        do {
            cellar = try store.load()
        } catch {
            cellar = Cellar()
            errorMessage = String(localized: "Your cellar could not be loaded.")
        }
    }

    static func live() -> CellarModel {
        if let store = try? JSONFileCellarStore.defaultStore() {
            return CellarModel(store: store)
        }
        return CellarModel(store: InMemoryCellarStore())
    }

    static func preview() -> CellarModel {
        CellarModel(store: InMemoryCellarStore(SampleData.cellar(labels: .localized)))
    }

    var currentYear: Int { Calendar.current.component(.year, from: .now) }

    /// Applies a change and saves it. On failure nothing changes.
    func update(_ change: (inout Cellar) throws -> Void) {
        var copy = cellar
        do {
            try change(&copy)
            try store.save(copy)
            cellar = copy
        } catch {
            errorMessage = String(localized: "The change could not be saved.")
        }
    }

    func loadSample() { update { $0 = SampleData.cellar(labels: .localized) } }
    func eraseAll() { update { $0 = Cellar() } }
}

extension SampleData.Labels {
    static var localized: Self {
        var labels = Self()
        labels.mainRack = String(localized: "Main rack")
        labels.wineFridge = String(localized: "Wine fridge")
        labels.tastingNote = String(localized: "Silky, red fruit, long finish.")
        return labels
    }
}
