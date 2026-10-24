import CaveKit
import Observation
import SwiftUI

/// Something to show in a window: asked by a screen of that window, a menu command, or
/// from outside it (Spotlight, Shortcuts, a notification, the Mac menu bar).
enum SceneAction {
    case openWine(UUID)
    case show(AppTab)
    case present(AppSheet)
}

/// What one window shows: its sheet, its section and the wine it should open. Each window
/// (iPad and Mac can have several) has its own, so a sheet opened in one never appears in,
/// or closes, another. Created by `RootView` and passed down in the environment.
@MainActor
@Observable
final class SceneRouter {
    /// The sheet shown above this window (add, drink, edit...).
    var sheet: AppSheet?
    /// The section shown, so screens can jump between them.
    var selectedTab: AppTab = .cellar
    /// A wine for the Cellar to select, then cleared.
    var openWineRequest: UUID?
    /// Wines to delete once the alert of this window confirms it (`deleteFollowingRule`).
    var confirmingWineDeletion: Set<UUID> = []
    /// Asked while a sheet was open: done once it closes, so a form being filled in is
    /// never closed to show something else.
    @ObservationIgnored private var deferred: SceneAction?
    /// A window without sections (a wine's window) shows them in the main window instead,
    /// the Cellar too: setting `selectedTab` to the tab it already has would do nothing.
    @ObservationIgnored var showsSectionElsewhere: ((AppTab) -> Void)?

    func perform(_ action: SceneAction) {
        if sheet != nil {
            deferred = action
            return
        }
        switch action {
        case .openWine(let id):
            selectedTab = .cellar
            openWineRequest = id
        case .show(let tab):
            if let showsSectionElsewhere { showsSectionElsewhere(tab) } else { selectedTab = tab }
        case .present(let sheet):
            self.sheet = sheet
        }
    }

    /// The sheet of this window closed: what was asked meanwhile happens now.
    func sheetDismissed() {
        guard sheet == nil, let action = deferred else { return }
        deferred = nil
        perform(action)
    }

    /// Racks, with the bottle's place highlighted.
    func showInRack(_ bottle: Bottle, model: CellarModel) {
        guard let slot = bottle.slot else { return }
        model.highlightedSlot = slot
        selectedTab = .racks
    }
}

extension FocusedValues {
    /// The router of the window in front, for the menu commands.
    @Entry var sceneRouter: SceneRouter?
    /// The wine selected in the Cellar of the window in front, for the Wine menu.
    @Entry var selectedWineID: UUID?
}
