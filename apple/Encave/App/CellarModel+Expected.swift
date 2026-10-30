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
