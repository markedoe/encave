import CaveKit
import SwiftUI

struct SettingsView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingErase = false

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "–"
        let build = info?["CFBundleVersion"] as? String ?? "–"
        return "\(short) (\(build))"
    }

    var body: some View {
        Form {
            Section {
                Label("Your cellar is stored only on this device.", systemImage: "lock.shield")
            } header: {
                Text("Privacy")
            } footer: {
                Text("Sync between your devices and with the people you share a cellar with will be end-to-end encrypted.")
            }

            Section("Test data") {
                Button("Load sample cellar") { model.loadSample() }
                Button("Erase all data", role: .destructive) { confirmingErase = true }
            }

            Section("About") {
                LabeledContent("Version", value: version)
                Link("Source code", destination: URL(string: "https://github.com/markedoe/encave")!)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        #if os(iOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        #endif
        .confirmationDialog("Erase all data?", isPresented: $confirmingErase, titleVisibility: .visible) {
            Button("Erase all data", role: .destructive) { model.eraseAll() }
        } message: {
            Text("This removes every bottle, rack and tasting from this device.")
        }
    }
}
