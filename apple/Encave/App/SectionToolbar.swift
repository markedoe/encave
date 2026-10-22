import SwiftUI

#if os(iOS)
/// iPhone and iPad: Settings (and Help, its first row) from every section, at the top left.
/// On the Mac, they are in the app menu and the Help menu.
struct SettingsToolbarItem: ToolbarContent {
    /// For the screen tour, which opens Settings from the Cellar.
    var identifier = "section.settings"

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            SettingsButton()
                .accessibilityIdentifier(identifier)
        }
    }
}

/// Opens Settings in this window.
struct SettingsButton: View {
    @Environment(SceneRouter.self) private var router: SceneRouter?

    var body: some View {
        Button {
            router?.sheet = .settings
        } label: {
            // With a badge when sync needs attention.
            SettingsButtonLabel()
        }
    }
}
#endif

#if os(macOS)
/// Mac Tonight: turns the list of bottles to open now in the menu bar on or off, where it
/// is useful (the same switch as in Settings).
struct MenuBarToggle: View {
    @AppStorage(MenuBarSetting.key) private var showsMenuBarExtra = MenuBarSetting.defaultValue

    var body: some View {
        Toggle(isOn: $showsMenuBarExtra) {
            Label("Show Tonight in the Menu Bar", systemImage: "menubar.arrow.up.rectangle")
        }
        .help(Text("A glass in the menu bar lists the bottles to open now; click one to open it."))
    }
}
#endif
