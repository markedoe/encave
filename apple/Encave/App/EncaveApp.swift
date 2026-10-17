import SwiftUI

@main
struct EncaveApp: App {
    @State private var model = CellarModel.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
        .defaultSize(width: 1100, height: 720)
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
