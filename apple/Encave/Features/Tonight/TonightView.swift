import CaveKit
import SwiftUI

/// What to open: bottles grouped by where they are in their drinking window.
struct TonightView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        NavigationStack {
            let sections = model.cellar.groupsByMaturity(year: model.currentYear)
            Group {
                if sections.isEmpty {
                    ContentUnavailableView(
                        "Nothing to suggest yet",
                        systemImage: "moon.stars",
                        description: Text("Add bottles with a drinking window to see what is ready tonight.")
                    )
                } else {
                    List {
                        ForEach(sections) { section in
                            Section {
                                ForEach(section.groups) { group in
                                    NavigationLink(value: group.wine.id) {
                                        BottleGroupRow(group: group)
                                    }
                                }
                            } header: {
                                MaturityLabel(maturity: section.maturity)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Tonight")
            .navigationDestination(for: UUID.self) { WineDetailView(wineID: $0) }
        }
    }
}
