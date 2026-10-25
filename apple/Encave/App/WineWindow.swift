import CaveKit
import SwiftUI

/// A wine in a window of its own (Mac, and iPad with several windows): its page, with its
/// own sheets and banners. Show in Rack and the sections go to the main window.
/// Nothing here reads @AppStorage: at the root of a window it redraws without end on the
/// Mac (see `DefaultsFlag`).
struct WineWindow: View {
    static let id = "wine"

    @Environment(CellarModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
    let wineID: UUID?
    /// This window's sheets and requests, apart from the other windows'.
    @State private var router = SceneRouter()

    var body: some View {
        NavigationStack {
            WinePane(wineID: wineID)
        }
        .environment(router)
        .overlay { ToastOverlay() }
        .sheet(item: $router.sheet, onDismiss: { router.sheetDismissed() }) { sheet in
            SheetContent(sheet: sheet)
                .environment(model)
                .environment(router)
        }
        // Delete Wine with several bottles asks here (the window then closes, below).
        .confirmsWineDeletion($router.confirmingWineDeletion)
        // The menus act on this window's wine.
        .focusedSceneValue(\.sceneRouter, router)
        .focusedSceneValue(\.selectedWineID, wineID)
        // Show in Rack, Cellar ⌘1…: shown in the main window, this one stays on its wine.
        .onChange(of: router.selectedTab) { _, tab in
            guard tab != .cellar else { return }
            router.selectedTab = .cellar
            showInMainWindow(.show(tab))
        }
        // ⌘1 included: this window's router is already on the Cellar.
        .onAppear {
            router.showsSectionElsewhere = { [model, openWindow] action in
                model.request(action)
                #if os(macOS)
                MainWindow.show(openWindow)
                #endif
            }
        }
        .onChange(of: router.openWineRequest) { _, id in
            guard let id else { return }
            router.openWineRequest = nil
            if id != wineID { openWindow(id: Self.id, value: id) }
        }
        // The wine was deleted (here, elsewhere or by sync): its window closes; the
        // banner's Undo stays in the main window.
        .onChange(of: isGone, initial: true) { _, gone in
            if gone { dismissWindow() }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }

    private var isGone: Bool {
        // A cellar that could not be read shows nothing yet: the window waits rather than closes.
        guard !model.loadFailed else { return false }
        return wineID.map { model.cellar.wines[$0] == nil } ?? true
    }

    private func showInMainWindow(_ action: SceneAction) {
        model.request(action)
        #if os(macOS)
        MainWindow.show(openWindow)
        #endif
    }
}

/// "Open in New Window" for a wine, where the device shows several windows.
struct OpenWineWindowButton: View {
    @Environment(\.openWindow) private var openWindow
    @Environment(\.supportsMultipleWindows) private var supportsMultipleWindows
    let wineID: UUID

    var body: some View {
        if supportsMultipleWindows {
            Button("Open in New Window", systemImage: "macwindow.badge.plus") {
                openWindow(id: WineWindow.id, value: wineID)
            }
        }
    }
}
