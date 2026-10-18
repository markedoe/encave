import CaveKit
import SwiftUI

/// What to open: a suggestion for tonight, food pairing, then bottles by maturity.
/// On iPhone and iPad it pushes the wine; on the Mac it sets `selection`, shown in the next column.
struct TonightView: View {
    @Environment(CellarModel.self) private var model
    var selection: Binding<UUID?>?
    @State private var pickIndex = 0

    var body: some View {
        if let selection {
            list(selection: selection)
                .navigationTitle("Tonight")
                .navigationDestination(for: FoodPairingRoute.self) { _ in FoodPairingView() }
                .navigationDestination(for: AskCellarRoute.self) { _ in AskCellarView(selection: selection) }
        } else {
            NavigationStack {
                list(selection: nil)
                    .navigationTitle("Tonight")
                    .navigationDestination(for: UUID.self) { WineDetailView(wineID: $0) }
                    .navigationDestination(for: FoodPairingRoute.self) { _ in FoodPairingView() }
                    .navigationDestination(for: AskCellarRoute.self) { _ in AskCellarView() }
            }
        }
    }

    @ViewBuilder
    private func list(selection: Binding<UUID?>?) -> some View {
        let sections = model.cellar.groupsByMaturity(year: model.currentYear)
        let candidates = sections.filter { $0.maturity == .drinkSoon || $0.maturity == .ready }.flatMap(\.groups)
        if sections.isEmpty {
            ContentUnavailableView(
                "Nothing to suggest yet",
                systemImage: "moon.stars",
                description: Text("Add bottles with a drinking window to see what is ready tonight.")
            )
        } else if let selection {
            List(selection: selection) { rows(sections: sections, candidates: candidates, linking: false) }
        } else {
            List { rows(sections: sections, candidates: candidates, linking: true) }
        }
    }

    @ViewBuilder
    private func rows(sections: [MaturitySection], candidates: [WineGroup], linking: Bool) -> some View {
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
                    NavigationLink(value: FoodPairingRoute()) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Food pairing").font(.headline)
                                Text("Find the right bottle for your dish").font(.subheadline).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "fork.knife").foregroundStyle(Color.accentColor)
                        }
                    }
                    if CellarAssistant.isAvailable {
                        NavigationLink(value: AskCellarRoute()) {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Ask your cellar").font(.headline)
                                    Text("A question, answered with your bottles, on this device").font(.subheadline).foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "sparkles").foregroundStyle(Color.accentColor)
                            }
                        }
                        .accessibilityIdentifier("tonight.ask")
                    }
                }
                ForEach(sections) { section in
                    Section {
                        ForEach(section.groups) { group in
                            Group {
                                if linking {
                                    NavigationLink(value: group.wine.id) { WineRow(group: group) }
                                } else {
                                    WineRow(group: group).tag(group.wine.id)
                                }
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

/// Navigation value for the food pairing page.
struct FoodPairingRoute: Hashable {}

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
            if let location = group.bottles.bottleToOpen.flatMap({ model.locationLabel(for: $0) }) {
                Label(location, systemImage: "square.grid.3x3")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button {
                    if let bottle = group.bottles.bottleToOpen { model.sheet = .drink(bottleID: bottle.id) }
                } label: {
                    ActionLabel("Drink", systemImage: "wineglass").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("action.tonight.drink")
                Button(action: next) {
                    ActionLabel("Another idea", systemImage: "shuffle").frame(maxWidth: .infinity)
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
        .id("pick-\(group.id)")
        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
    }
}
