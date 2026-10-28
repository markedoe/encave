import CaveKit
import Observation
import SwiftUI

/// Something to show in a window: asked by a screen of that window, a menu command, or
/// from outside it (Spotlight, Shortcuts, a notification, the Mac menu bar).
enum SceneAction {
    case openWine(UUID)
    /// From a tasting in the Journal: the wine on the Journal's stack (iPhone, iPad),
    /// so Back returns there and a wine with no bottle left opens too; the Cellar on the Mac.
    case openWineFromJournal(UUID)
    case show(AppTab)
    /// Storage, with this position highlighted ("Show in Storage").
    case showInStorage(Slot)
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
    /// A wine for the Journal to push, then cleared.
    var journalWineRequest: UUID?
    /// Wines to delete once the alert of this window confirms it (`deleteFollowingRule`).
    var confirmingWineDeletion: Set<UUID> = []
    /// The Cellar's search, filters and sort, shared by its list and its table; each
    /// window has its own.
    var cellarFilter = CellarFilter()
    var cellarSort: CellarSort = .name
    /// Position to highlight when jumping to a storage ("Show in Storage"), in this window.
    var highlightedSlot: Slot?
    /// Asked while a sheet was open: done once it closes, so a form being filled in is
    /// never closed to show something else.
    @ObservationIgnored private var deferred: SceneAction?
    /// A window without sections (a wine's window) shows them in the main window instead,
    /// the Cellar too: setting `selectedTab` to the tab it already has would do nothing.
    /// Told `.show` and `.showInStorage`.
    @ObservationIgnored var showsSectionElsewhere: ((SceneAction) -> Void)?

    func perform(_ action: SceneAction) {
        if sheet != nil {
            deferred = action
            return
        }
        switch action {
        case .openWine(let id):
            selectedTab = .cellar
            openWineRequest = id
        case .openWineFromJournal(let id):
            #if os(iOS)
            selectedTab = .journal
            journalWineRequest = id
            #else
            perform(.openWine(id))
            #endif
        case .show(let tab):
            if let showsSectionElsewhere { showsSectionElsewhere(action) } else { selectedTab = tab }
        case .showInStorage(let slot):
            if let showsSectionElsewhere {
                showsSectionElsewhere(action)
            } else {
                highlightedSlot = slot
                selectedTab = .racks
            }
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
    func showInRack(_ bottle: Bottle) {
        guard let slot = bottle.slot else { return }
        perform(.showInStorage(slot))
    }
}

extension FocusedValues {
    /// The router of the window in front, for the menu commands.
    @Entry var sceneRouter: SceneRouter?
    /// The wine selected in the Cellar of the window in front, for the Wine menu.
    @Entry var selectedWineID: UUID?
}
