import CaveKit
import SwiftUI

/// Pick a dish, see which of your bottles go with it and why.
struct FoodPairingView: View {
    @Environment(CellarModel.self) private var model
    @State private var dish: DishCategory = .redMeat

    var body: some View {
        let suggestions = model.cellar.pairings(for: dish, year: model.currentYear)
        List {
            Section {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 10) {
                    ForEach(DishCategory.allCases, id: \.self) { category in
                        Button {
                            withAnimation(.snappy) { dish = category }
                        } label: {
                            VStack(spacing: 6) {
                                Image(systemName: category.systemImage)
                                    .font(.title2)
                                Text(category.title)
                                    .font(.caption.weight(.medium))
                                    .lineLimit(2)
                                    .multilineTextAlignment(.center)
                            }
                            .frame(maxWidth: .infinity, minHeight: 72)
                            .background(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(dish == category ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.08))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(dish == category ? Color.accentColor : .clear, lineWidth: 1.5)
                            )
                            .foregroundStyle(dish == category ? Color.accentColor : Color.primary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(dish == category ? .isSelected : [])
                    }
                }
                .padding(.vertical, 6)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            Section {
                if suggestions.isEmpty {
                    Text("No bottle in your cellar matches this dish yet.")
                        .foregroundStyle(.secondary)
                }
                ForEach(suggestions) { suggestion in
                    NavigationLink {
                        WineDetailView(wineID: suggestion.group.wine.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            WineRow(group: suggestion.group)
                            Text(reasonText(suggestion.reasons))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("From your cellar")
            }
        }
        .navigationTitle("Food pairing")
        .sensoryFeedback(.selection, trigger: dish)
    }

    private func reasonText(_ reasons: [PairingReason]) -> String {
        reasons.map { reason in
            switch reason {
            case .style: String(localized: "classic style for this dish")
            case .grape: String(localized: "suited grape")
            case .region: String(localized: "region known for it")
            case .readyToDrink: String(localized: "ready to drink")
            }
        }.formatted(.list(type: .and))
    }
}

extension DishCategory {
    var title: LocalizedStringKey {
        switch self {
        case .redMeat: "Red meat"
        case .poultry: "Poultry"
        case .pork: "Pork"
        case .game: "Game"
        case .fish: "Fish"
        case .shellfish: "Shellfish"
        case .cheese: "Cheese"
        case .spicy: "Spicy"
        case .vegetarian: "Vegetarian"
        case .pasta: "Pasta, pizza"
        case .dessert: "Dessert"
        case .aperitif: "Aperitif"
        }
    }

    var systemImage: String {
        switch self {
        case .redMeat: "flame"
        case .poultry: "bird"
        case .pork: "fork.knife"
        case .game: "hare"
        case .fish: "fish"
        case .shellfish: "water.waves"
        case .cheese: "triangle.lefthalf.filled"
        case .spicy: "thermometer.sun"
        case .vegetarian: "leaf"
        case .pasta: "takeoutbag.and.cup.and.straw"
        case .dessert: "birthday.cake"
        case .aperitif: "party.popper"
        }
    }
}
