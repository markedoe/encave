import CaveKit
import SwiftUI

/// Four sections: a tab bar on iPhone, a sidebar on iPad and Mac.
/// Sheets and the undo banner live here so every screen can use them.
struct RootView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        @Bindable var model = model
        sections
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

    @ViewBuilder
    private var sections: some View {
        #if os(macOS)
        MacSidebarRoot()
        #else
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            Tab("Cellar", systemImage: "wineglass", value: AppTab.cellar) { CellarView() }
            Tab("Racks", systemImage: "square.grid.3x3", value: AppTab.racks) { RacksView() }
            Tab("Tonight", systemImage: "moon.stars", value: AppTab.tonight) { TonightView() }
            Tab("Journal", systemImage: "book.closed", value: AppTab.journal) { JournalView() }
        }
        .tabViewStyle(.sidebarAdaptable)
        #endif
    }
}

#if os(macOS)
/// The Mac window: one sidebar with the four sections, the section on the right.
private struct MacSidebarRoot: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        let selection = Binding<AppTab?>(
            get: { model.selectedTab },
            set: { if let tab = $0 { model.selectedTab = tab } }
        )
        NavigationSplitView {
            List(selection: selection) {
                Section {
                    Label("Cellar", systemImage: "wineglass")
                        .badge(model.cellar.bottlesInCellar.count)
                        .tag(AppTab.cellar)
                        .accessibilityIdentifier("section.cellar")
                    Label("Racks", systemImage: "square.grid.3x3")
                        .badge(model.cellar.racks.count)
                        .tag(AppTab.racks)
                        .accessibilityIdentifier("section.racks")
                    Label("Tonight", systemImage: "moon.stars")
                        .badge(model.cellar.statistics(year: model.currentYear).byMaturity[.drinkSoon, default: 0])
                        .tag(AppTab.tonight)
                        .accessibilityIdentifier("section.tonight")
                    Label("Journal", systemImage: "book.closed")
                        .badge(model.cellar.shoppingListCount)
                        .tag(AppTab.journal)
                        .accessibilityIdentifier("section.journal")
                }
            }
            .navigationSplitViewColumnWidth(min: 180, ideal: 200, max: 260)
        } detail: {
            switch model.selectedTab {
            case .cellar: CellarView()
            case .racks: RacksView()
            case .tonight: TonightView()
            case .journal: JournalView()
            }
        }
    }
}
#endif

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
