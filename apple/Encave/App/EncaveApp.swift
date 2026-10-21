import SwiftUI
import UserNotifications

@main
struct EncaveApp: App {
    @State private var model = CellarModel.live()
    private let notifications = NotificationRouter()
    #if os(macOS)
    @AppStorage(MenuBarSetting.key) private var showsMenuBarExtra = true
    #endif

    init() {
        UNUserNotificationCenter.current().delegate = notifications
    }

    var body: some Scene {
        WindowGroup(id: "main") {
            RootView()
                .environment(model)
                .onAppear { notifications.model = model }
        }
        #if os(macOS)
        // Sized to the screen: a fixed size larger than a small display put the window off screen.
        .defaultWindowPlacement { _, context in
            let screen = context.defaultDisplay.visibleRect.size
            return WindowPlacement(size: CGSize(width: min(1180, screen.width * 0.9),
                                                height: min(780, screen.height * 0.9)))
        }
        .windowToolbarStyle(.unified)
        #endif
        #if os(iOS)
        // iPhone and iPad: a round away from home in the background, when iOS allows
        // (requested by `SyncService.scheduleBackgroundSync`). The Mac syncs while open.
        .backgroundTask(.appRefresh(BackgroundSync.identifier)) {
            await BackgroundSync.run()
        }
        #endif
        .commands {
            CellarCommands(model: model)
        }
        #if os(macOS)
        Window("Encave Help", id: "help") {
            NavigationStack { HelpView() }
                .frame(minWidth: 520, minHeight: 560)
        }
        .defaultSize(width: 620, height: 720)
        Settings {
            NavigationStack {
                SettingsView()
            }
            .environment(model)
            .frame(minWidth: 480, minHeight: 520)
        }
        MenuBarExtra(isInserted: $showsMenuBarExtra) {
            CellarMenuBarContent()
                .environment(model)
        } label: {
            Label("Encave", systemImage: "wineglass")
        }
        .menuBarExtraStyle(.window)
        #endif
    }
}

/// Menu bar and keyboard shortcuts (Mac, and iPad with a keyboard).
struct CellarCommands: Commands {
    let model: CellarModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Bottle…") { model.sheet = .addBottle(AddBottleContext()) }
                .keyboardShortcut("n")
            Button("New Rack…") { model.sheet = .editRack(rackID: nil) }
                .keyboardShortcut("n", modifiers: [.command, .shift])
            Button("Note a Wine Tasted Elsewhere…") { model.sheet = .discovery(discoveryID: nil) }
                .keyboardShortcut("n", modifiers: [.command, .option])
        }
        #if os(macOS)
        CommandGroup(replacing: .help) {
            HelpMenuButton()
        }
        #endif
        CommandMenu("Cellar") {
            Button("Cellar") { model.selectedTab = .cellar }.keyboardShortcut("1")
            Button("Racks") { model.selectedTab = .racks }.keyboardShortcut("2")
            Button("Tonight") { model.selectedTab = .tonight }.keyboardShortcut("3")
            Button("Journal") { model.selectedTab = .journal }.keyboardShortcut("4")
            Divider()
            CellarTableToggle()
        }
    }
}

#if os(macOS)
/// Help → Encave Help: opens the help in its own window.
private struct HelpMenuButton: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Encave Help") { openWindow(id: "help") }
            .keyboardShortcut("?")
    }
}
#endif

/// Cellar › Show as Table (⌥⌘T): the same switch as in the Cellar toolbar.
private struct CellarTableToggle: View {
    @AppStorage(CellarLayout.key) private var showsTable = false

    var body: some View {
        Toggle("Show as Table", isOn: $showsTable)
            .keyboardShortcut("t", modifiers: [.command, .option])
    }
}

/// Mac: whether Encave shows "Tonight" in the menu bar.
enum MenuBarSetting {
    static let key = "menuBar.enabled"
}
