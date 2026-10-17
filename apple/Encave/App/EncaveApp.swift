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
        WindowGroup {
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
            Button("Remember a Wine…") { model.sheet = .discovery(discoveryID: nil) }
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

/// Mac: whether Encave shows "Tonight" in the menu bar.
enum MenuBarSetting {
    static let key = "menuBar.enabled"
}
