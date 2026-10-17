import CaveKit
import SwiftUI

struct CellarView: View {
    @Environment(CellarModel.self) private var model
    @State private var query = ""
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            let groups = model.cellar.groups(matching: query)
            Group {
                if model.cellar.bottlesInCellar.isEmpty {
                    EmptyCellarView()
                } else if groups.isEmpty {
                    ContentUnavailableView.search(text: query)
                } else {
                    List(groups) { group in
                        NavigationLink(value: group.wine.id) {
                            BottleGroupRow(group: group)
                        }
                    }
                }
            }
            .navigationTitle("Cellar")
            .navigationDestination(for: UUID.self) { WineDetailView(wineID: $0) }
            .searchable(text: $query, prompt: Text("Wine, producer, region, vintage"))
            .toolbar { settingsButton }
            .sheet(isPresented: $showingSettings) {
                NavigationStack { SettingsView() }
            }
        }
    }

    @ToolbarContentBuilder
    private var settingsButton: some ToolbarContent {
        #if os(iOS)
        ToolbarItem(placement: .topBarTrailing) {
            Button { showingSettings = true } label: {
                Label("Settings", systemImage: "gearshape")
            }
        }
        #else
        ToolbarItem { EmptyView() }
        #endif
    }
}

/// First launch: explain the app and offer the sample cellar.
struct EmptyCellarView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        ContentUnavailableView {
            Label("Your cellar is empty", systemImage: "wineglass")
        } description: {
            Text("Add the bottles you keep at home to know what you have, where it is and when to open it.")
        } actions: {
            Button("Load sample cellar") { model.loadSample() }
                .buttonStyle(.borderedProminent)
        }
    }
}

#Preview {
    CellarView().environment(CellarModel.preview())
}
