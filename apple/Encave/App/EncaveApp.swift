import SwiftUI

@main
struct EncaveApp: App {
    @State private var model = CellarModel.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
        #if os(macOS)
        Settings {
            SettingsView()
                .environment(model)
                .frame(minWidth: 420, minHeight: 320)
        }
        #endif
    }
}
