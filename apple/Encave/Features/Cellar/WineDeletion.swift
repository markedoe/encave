import CaveKit
import SwiftUI

// One rule for deleting, on every screen and on Android (CaveKit `DeletionRule`): a wine
// with at most one bottle goes at once, with an Undo banner and the window's Undo; a wine
// with several bottles, or several wines, is confirmed first in an alert that names it and
// says how many bottles go with it.

extension CellarModel {
    /// Deletes these wines by the rule: at once with Undo, or after `router` asks (from the
    /// alert `confirmsWineDeletion` shows at the root of its window).
    func deleteFollowingRule(wines wineIDs: Set<UUID>, router: SceneRouter?) {
        let ids = wineIDs.filter { cellar.wines[$0] != nil }
        guard !ids.isEmpty else { return }
        switch cellar.deletionGuard(ofWines: ids) {
        case .undo:
            if let id = ids.first { deleteWine(id) }
        case .confirm:
            router?.confirmingWineDeletion = ids
        }
    }
}

/// Asks before deleting wines, naming the wine (or how many) and how many bottles and
/// tastings go with them; the deletion then shows an Undo banner.
struct WineDeletionConfirmation: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Binding var wineIDs: Set<UUID>
    var onDelete: () -> Void = {}

    func body(content: Content) -> some View {
        let ids = wineIDs.filter { model.cellar.wines[$0] != nil }
        let bottles = model.cellar.bottleRecordCount(ofWines: ids)
        let single = ids.count == 1 ? ids.first.flatMap { model.cellar.wines[$0] } : nil
        content.alert(title(single: single, count: ids.count, bottles: bottles), isPresented: Binding(
            get: { !ids.isEmpty }, set: { if !$0 { wineIDs = [] } }
        )) {
            Button(role: .destructive) {
                if let single { model.deleteWine(single.id) } else { model.deleteWines(ids) }
                wineIDs = []
                onDelete()
            } label: {
                if single != nil {
                    Text("Delete Wine")
                } else if bottles > 0 {
                    Text("Delete Bottles")
                } else {
                    Text("Delete Wines")
                }
            }
            .accessibilityIdentifier("wine.delete.confirm")
            Button("Cancel", role: .cancel) { wineIDs = [] }
        } message: {
            if single == nil, bottles > 0 {
                Text("Their bottles and tastings are deleted too. You can undo right after.")
            } else {
                Text("\(bottles) bottles and their tastings are deleted too. You can undo right after.")
            }
        }
    }

    /// Several wines: counted in bottles first, "Delete 15 bottles (2 wines)?", as on Android.
    private func title(single: Wine?, count: Int, bottles: Int) -> Text {
        if let single {
            let wineName = single.displayName
            return Text("Delete “\(wineName)”?")
        }
        guard bottles > 0 else { return Text("Delete \(count) wines?") }
        let bottlesText = String(localized: "\(bottles) bottles")
        let winesText = String(localized: "\(count) wines")
        // A format key: the parentheses would confuse apple/tools/l10n.py's reading of an interpolation.
        return Text(verbatim: String(format: String(localized: "Delete %@ (%@)?"), bottlesText, winesText))
    }
}

extension View {
    /// Asks before deleting the wines `wineIDs` holds (empty: nothing asked).
    func confirmsWineDeletion(_ wineIDs: Binding<Set<UUID>>, onDelete: @escaping () -> Void = {}) -> some View {
        modifier(WineDeletionConfirmation(wineIDs: wineIDs, onDelete: onDelete))
    }
}
