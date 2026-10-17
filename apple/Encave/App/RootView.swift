import CaveKit
import SwiftUI

/// Four sections: a tab bar on iPhone, a sidebar on iPad and Mac.
/// Sheets and the undo banner live here so every screen can use them.
struct RootView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            Tab("Cellar", systemImage: "wineglass", value: AppTab.cellar) { CellarView() }
            Tab("Racks", systemImage: "square.grid.3x3", value: AppTab.racks) { RacksView() }
            Tab("Tonight", systemImage: "moon.stars", value: AppTab.tonight) { TonightView() }
            Tab("Journal", systemImage: "book.closed", value: AppTab.journal) { JournalView() }
        }
        .tabViewStyle(.sidebarAdaptable)
        .overlay { ToastOverlay() }
        .sheet(item: $model.sheet) { sheet in
            SheetContent(sheet: sheet)
                .environment(model)
        }
        .sensoryFeedback(.success, trigger: model.successCount)
        .alert(
            "Something went wrong",
            isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

private struct SheetContent: View {
    let sheet: AppSheet

    var body: some View {
        switch sheet {
        case .addBottle(let context):
            AddBottleView(context: context)
        case .drink(let bottleID):
            DrinkSheet(bottleID: bottleID)
        case .editWine(let wineID):
            EditWineView(wineID: wineID)
        case .editTasting(let bottleID):
            TastingEditor(bottleID: bottleID)
        case .editRack(let rackID):
            RackEditor(rackID: rackID)
        case .moveBottle(let bottleID):
            MoveBottleView(bottleID: bottleID)
        case .discovery(let discoveryID):
            DiscoveryEditor(discoveryID: discoveryID)
        case .settings:
            NavigationStack { SettingsView() }
        }
    }
}

#Preview {
    RootView().environment(CellarModel.preview())
}
