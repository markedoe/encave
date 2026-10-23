import CaveKit
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// Four sections: a tab bar on iPhone, a sidebar on iPad and Mac.
/// Sheets and the undo banner live here so every screen can use them.
struct RootView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    #if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
    #endif
    /// This window is counted among the windows in the foreground.
    @State private var isCounted = false
    /// This window's sheet, section and requests: its own, whatever other windows show.
    @State private var router = SceneRouter()

    var body: some View {
        sections
            .environment(router)
            .overlay { ToastOverlay() }
        .sheet(item: $router.sheet, onDismiss: { router.sheetDismissed() }) { sheet in
            SheetContent(sheet: sheet)
                .environment(model)
                .environment(router)
        }
        .focusedSceneValue(\.sceneRouter, router)
        // Requests from outside a window go to the one used last.
        .onAppear { model.sceneBecameActive(router) }
        #if os(macOS)
        .onChange(of: controlActiveState) { _, state in
            if state == .key { model.sceneBecameActive(router) }
        }
        #endif
        .sensoryFeedback(.success, trigger: model.successCount)
        .sensoryFeedback(.selection, trigger: model.selectionCount)
        // encave://pair?code=…: the pairing QR code scanned with the system camera. Opens
        // Join with the code filled in; the user still chooses and taps Join.
        .onOpenURL { url in
            guard let parsed = PairingCode.parse(qr: url.absoluteString), !model.sync.isDemo else { return }
            router.perform(.present(.joinDevice(code: parsed.code.digits, commitment: parsed.commitment)))
        }
        #if os(macOS)
        // A link goes to a window already open rather than a new one.
        .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
        #endif
        // The monthly reminder reflects the cellar as it is now.
        .task(id: model.cellar) {
            try? await Task.sleep(for: .seconds(3))
            // Not for a cellar that could not be loaded: that would remove the reminders.
            guard !Task.isCancelled, !model.loadFailed else { return }
            await Reminders.schedule(for: model.cellar)
        }
        // Sync runs while a window is open in the foreground; iOS suspends it in the
        // background anyway. With several windows (iPad, Mac), it stops with the last one.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active:
                // Launched in the background before the first unlock: the cellar loads now.
                model.reloadIfLoadFailed()
                if !isCounted { isCounted = true; model.sceneDidBecomeActive() }
                model.sceneBecameActive(router)
                model.sync.resume()
                ChangeNotifications.appBecameActive()
                if !model.loadFailed { Task { await Reminders.schedule(for: model.cellar) } }
            case .background:
                if isCounted { isCounted = false; model.sceneDidEnterBackground() }
            default: break
            }
        }
        #if os(macOS)
        // A Mac window stays active behind other apps: coming back to Encave clears the
        // notification of changes too.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            ChangeNotifications.appBecameActive()
        }
        #endif
        .onDisappear {
            if isCounted { isCounted = false; model.sceneDidEnterBackground() }
            model.sceneClosed(router)
        }
        .alert(
            "Something went wrong",
            isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
        ) {
            // The cellar file could not be read: the newest previous version can be put back.
            if model.offersRestoreAfterUnreadable {
                Button("Restore Previous Version") { model.openLatestVersion() }
            }
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
        TabView(selection: $router.selectedTab) {
            Tab("Cellar", systemImage: "wineglass", value: AppTab.cellar) { CellarView() }
            Tab("Racks", systemImage: "square.grid.3x3", value: AppTab.racks) { RacksView() }
            Tab("Tonight", systemImage: "moon.stars", value: AppTab.tonight) { TonightView() }
            Tab("Journal", systemImage: "book.closed", value: AppTab.journal) { JournalView() }
        }
        .tabViewStyle(.sidebarAdaptable)
        // iPad: Settings and Help at the bottom of the sidebar too (each section also has it
        // in its toolbar).
        .tabViewSidebarBottomBar {
            SettingsButton()
                .labelStyle(.titleAndIcon)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal)
                .accessibilityIdentifier("sidebar.settings")
        }
        #endif
    }
}


/// The sheet a window shows for its router (`RootView`, `WineWindow`).
struct SheetContent: View {
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
        case .restore(let source):
            NavigationStack { RestorePreviewView(source: source, inSheet: true) }
                .macSheetFrame(width: 520, height: 640)
        case .settings:
            NavigationStack { SettingsView() }
        case .joinDevice(let code, let commitment):
            JoinDeviceView(code: code, commitment: commitment)
        }
    }
}

#Preview {
    RootView().environment(CellarModel.preview())
}
