import SwiftUI

/// Four sections: a tab bar on iPhone, a sidebar on iPad and Mac.
struct RootView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        TabView {
            Tab("Cellar", systemImage: "wineglass") { CellarView() }
            Tab("Racks", systemImage: "square.grid.3x3") { RacksView() }
            Tab("Tonight", systemImage: "moon.stars") { TonightView() }
            Tab("Journal", systemImage: "book.closed") { JournalView() }
        }
        .tabViewStyle(.sidebarAdaptable)
        .alert(
            "Something went wrong",
            isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

#Preview {
    RootView().environment(CellarModel.preview())
}
