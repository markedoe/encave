import CaveKit
import SwiftUI

/// What to open: a suggestion for tonight, food pairing, then bottles by maturity.
struct TonightView: View {
    @Environment(CellarModel.self) private var model
    @State private var pickIndex = 0

    var body: some View {
        NavigationStack {
            let sections = model.cellar.groupsByMaturity(year: model.currentYear)
            let candidates = sections.filter { $0.maturity == .drinkSoon || $0.maturity == .ready }.flatMap(\.groups)
            Group {
                if sections.isEmpty {
                    ContentUnavailableView(
                        "Nothing to suggest yet",
                        systemImage: "moon.stars",
                        description: Text("Add bottles with a drinking window to see what is ready tonight.")
                    )
                } else {
                    List {
                        if !candidates.isEmpty {
                            Section {
                                TonightPick(group: candidates[pickIndex % candidates.count]) {
                                    withAnimation(.snappy) { pickIndex += 1 }
                                }
                                .listRowInsets(EdgeInsets())
                                .listRowBackground(Color.clear)
                            }
                        }
                        Section {
                            NavigationLink {
                                FoodPairingView()
                            } label: {
                                Label {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Food pairing").font(.headline)
                                        Text("Find the right bottle for your dish").font(.subheadline).foregroundStyle(.secondary)
                                    }
                                } icon: {
                                    Image(systemName: "fork.knife").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                        ForEach(sections) { section in
                            Section {
                                ForEach(section.groups) { group in
                                    NavigationLink(value: group.wine.id) {
                                        WineRow(group: group)
                                    }
                                    .contextMenu { WineContextMenu(group: group) }
                                }
                            } header: {
                                MaturityLabel(maturity: section.maturity)
                                    .font(.subheadline.weight(.semibold))
                                    .textCase(nil)
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

/// Hero card with tonight's suggestion.
private struct TonightPick: View {
    @Environment(CellarModel.self) private var model
    let group: WineGroup
    let next: () -> Void

    var body: some View {
        let wine = group.wine
        VStack(alignment: .leading, spacing: 14) {
            Text("Tonight, open…")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 16) {
                BottleEnd(color: wine.color, size: 64)
                VStack(alignment: .leading, spacing: 4) {
                    Text(wine.displayName)
                        .font(.title2.weight(.semibold))
                        .fontDesign(.serif)
                        .lineLimit(2)
                    if let producer = wine.producer {
                        Text(producer).foregroundStyle(.secondary)
                    }
                    MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                        .font(.footnote.weight(.medium))
                }
            }
            if let location = group.bottles.first.flatMap({ model.locationLabel(for: $0) }) {
                Label(location, systemImage: "square.grid.3x3")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button {
                    if let bottle = group.bottles.first { model.sheet = .drink(bottleID: bottle.id) }
                } label: {
                    Label("Drink", systemImage: "wineglass").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("action.tonight.drink")
                Button(action: next) {
                    Label("Another idea", systemImage: "shuffle").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("action.tonight.next")
            }
            .controlSize(.large)
        }
        .padding(20)
        .background(
            LinearGradient(colors: [(wine.color?.tint ?? .gray).opacity(0.25), Color.secondary.opacity(0.06)],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .padding(.vertical, 4)
        .id(group.id)
        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
    }
}
