import CaveKit
import SwiftUI

/// Four sections: a tab bar on iPhone, a sidebar on iPad and Mac.
/// Sheets and the undo banner live here so every screen can use them.
struct RootView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    /// This window is counted among the windows in the foreground.
    @State private var isCounted = false

    var body: some View {
        @Bindable var model = model
        sections
            .overlay { ToastOverlay() }
        .sheet(item: $model.sheet) { sheet in
            SheetContent(sheet: sheet)
                .environment(model)
        }
        .sensoryFeedback(.success, trigger: model.successCount)
        // The monthly reminder reflects the cellar as it is now.
        .task(id: model.cellar) {
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            await Reminders.schedule(for: model.cellar)
            // Spotlight: an on-device index of the wines (not for the sample cellar of the screen tour).
            if !model.sync.isDemo { await SpotlightIndex.update(model.cellar) }
        }
        // Sync runs while a window is open in the foreground; iOS suspends it in the
        // background anyway. With several windows (iPad, Mac), it stops with the last one.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active:
                if !isCounted { isCounted = true; model.sceneDidBecomeActive() }
                model.sync.resume()
                ChangeNotifications.appBecameActive()
                Task { await Reminders.schedule(for: model.cellar) }
            case .background:
                if isCounted { isCounted = false; model.sceneDidEnterBackground() }
            default: break
            }
        }
        .onDisappear {
            if isCounted { isCounted = false; model.sceneDidEnterBackground() }
        }
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
        MacRoot()
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


private struct SheetContent: View {
    let sheet: AppSheet

    var body: some View {
        switch sheet {
        case .addBottle(let context):
            AddBottleView(context: context)
        case .drink(let bottleID):
            DrinkSheet(bottleID: bottleID)
        case .editWine(let wineID, let field):
            EditWineView(wineID: wineID, field: field)
        case .editTasting(let bottleID):
            TastingEditor(bottleID: bottleID)
        case .editRack(let rackID):
            RackEditor(rackID: rackID)
        case .moveBottle(let bottleID):
            MoveBottleView(bottleID: bottleID)
        case .wineImage(let wineID):
            WineImagePicker(wineID: wineID)
        case .discovery(let discoveryID):
            DiscoveryEditor(discoveryID: discoveryID)
        case .bottleDocuments(let bottleID, let importing):
            BottleDocumentsView(bottleID: bottleID, startsImporting: importing)
        case .settings:
            NavigationStack { SettingsView() }
        }
    }
}

#Preview {
    RootView().environment(CellarModel.preview())
}
