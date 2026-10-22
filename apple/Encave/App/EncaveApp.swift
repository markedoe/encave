import CaveKit
import SwiftUI
import UserNotifications

@main
struct EncaveApp: App {
    #if os(macOS)
    // First, before the model sets up this device: it tells whether the app ran before.
    private let menuBarKept: Void = MenuBarSetting.keepForExistingUsers()
    #endif
    @State private var model = CellarModel.live()
    private let notifications = NotificationRouter()
    #if os(macOS)
    @AppStorage(MenuBarSetting.key) private var showsMenuBarExtra = MenuBarSetting.defaultValue
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

/// Menu bar and keyboard shortcuts (Mac, and iPad with a keyboard). They act on the
/// window in front; on the Mac, with no window open, they open one first.
struct CellarCommands: Commands {
    let model: CellarModel
    @FocusedValue(\.sceneRouter) private var router
    @FocusedValue(\.selectedWineID) private var selectedWineID
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Bottle…") { act(.present(.addBottle(AddBottleContext()))) }
                .keyboardShortcut("n")
            Button("New Rack…") { act(.present(.editRack(rackID: nil))) }
                .keyboardShortcut("n", modifiers: [.command, .shift])
            Button("Note a Wine Tasted Elsewhere…") { act(.present(.discovery(discoveryID: nil))) }
                .keyboardShortcut("n", modifiers: [.command, .option])
            Divider()
            Button("New Window") { openWindow(id: "main") }
                .keyboardShortcut("n", modifiers: [.command, .control])
        }
        #if os(macOS)
        CommandGroup(replacing: .help) {
            HelpMenuButton()
        }
        #endif
        CommandMenu("Wine") {
            Button("Edit Wine…") { withWine { act(.present(.editWine(wineID: $0.id))) } }
                .keyboardShortcut("e")
                .disabled(selectedWineID == nil)
            Button("Drink a Bottle…") {
                withWine { wine in
                    if let bottle = bottleToOpen(of: wine.id) { act(.present(.drink(bottleID: bottle.id))) }
                }
            }
            .keyboardShortcut("d")
            .disabled(selectedWineID == nil)
            Button("Show in Rack") {
                withWine { wine in
                    guard let bottle = model.cellar.bottlesInCellar.first(where: { $0.wineID == wine.id && $0.slot != nil }) else { return }
                    if let router { router.showInRack(bottle, model: model) } else { model.showInRack(bottle) }
                }
            }
            .disabled(selectedWineID == nil)
            Button("Add Bottles…") { withWine { act(.present(.addBottle(AddBottleContext(wine: $0)))) } }
                .disabled(selectedWineID == nil)
            Divider()
            Button("Delete Wine") { withWine { model.deleteWine($0.id) } }
                .keyboardShortcut(.delete)
                .disabled(selectedWineID == nil)
        }
        CommandMenu("Cellar") {
            Button("Cellar") { act(.show(.cellar)) }.keyboardShortcut("1")
            Button("Racks") { act(.show(.racks)) }.keyboardShortcut("2")
            Button("Tonight") { act(.show(.tonight)) }.keyboardShortcut("3")
            Button("Journal") { act(.show(.journal)) }.keyboardShortcut("4")
            Divider()
            CellarTableToggle()
            Divider()
            // With the devices on the network and away from home, as in Devices.
            Button("Sync Now") { Task { await model.sync.syncEverywhere() } }
                .keyboardShortcut("r")
                .disabled(model.sync.otherDevices.isEmpty || model.sync.isDemo)
        }
    }

    /// In the window in front, or in the main window, brought forward or opened (Mac).
    private func act(_ action: SceneAction) {
        if let router {
            router.perform(action)
        } else {
            model.request(action)
            #if os(macOS)
            MainWindow.show(openWindow)
            #endif
        }
    }

    private func withWine(_ body: (Wine) -> Void) {
        guard let id = selectedWineID, let wine = model.cellar.wines[id] else { return }
        body(wine)
    }

    private func bottleToOpen(of wineID: UUID) -> Bottle? {
        let bottles = model.cellar.bottlesInCellar.filter { $0.wineID == wineID }
        return bottles.first { $0.reservedFor == nil } ?? bottles.first
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

/// Mac: whether Encave shows "Tonight" in the menu bar. Off until turned on in Settings
/// or in Tonight.
enum MenuBarSetting {
    static let key = "menuBar.enabled"
    static let defaultValue = false
    static let checkedKey = "menuBar.checkedExistingUser"

    /// Earlier versions showed it unless turned off: for someone who used one, it stays
    /// shown. Checked once; a new install keeps it off.
    static func keepForExistingUsers(_ defaults: UserDefaults = .standard) {
        guard !defaults.bool(forKey: checkedKey) else { return }
        defaults.set(true, forKey: checkedKey)
        // Set at the first launch of every earlier version (the device's sync identity, the
        // first use for backup reminders).
        let launchedBefore = defaults.object(forKey: "sync.deviceID") != nil || defaults.object(forKey: "backup.firstUse") != nil
        if launchedBefore, defaults.object(forKey: key) == nil { defaults.set(true, forKey: key) }
    }
}
