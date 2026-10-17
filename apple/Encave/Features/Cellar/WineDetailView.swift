import CaveKit
import SwiftUI

struct WineDetailView: View {
    @Environment(CellarModel.self) private var model
    let wineID: UUID

    var body: some View {
        if let wine = model.cellar.wines[wineID] {
            let bottles = model.cellar.bottlesInCellar.filter { $0.wineID == wineID }
            Form {
                Section {
                    if let color = wine.color {
                        LabeledContent("Style") {
                            HStack(spacing: 6) {
                                WineColorDot(color: color)
                                Text(color.title)
                            }
                        }
                    }
                    optionalRow("Producer", wine.producer)
                    optionalRow("Appellation", wine.appellation)
                    optionalRow("Region", wine.region)
                    optionalRow("Country", wine.country)
                    if !wine.grapes.isEmpty {
                        LabeledContent("Grapes", value: wine.grapes.formatted(.list(type: .and)))
                    }
                }

                Section("Drinking window") {
                    MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                    if let from = wine.drinkFrom, let until = wine.drinkUntil {
                        LabeledContent("From – until") {
                            Text(verbatim: "\(from) – \(until)")
                        }
                    }
                }

                Section {
                    ForEach(bottles) { bottle in
                        Label(
                            model.cellar.locationLabel(for: bottle) ?? String(localized: "Not placed in a rack"),
                            systemImage: bottle.slot == nil ? "tray" : "square.grid.3x3"
                        )
                    }
                } header: {
                    Text("\(bottles.count) bottles")
                }
            }
            .formStyle(.grouped)
            .navigationTitle(wine.displayName)
        } else {
            ContentUnavailableView("Wine not found", systemImage: "questionmark")
        }
    }

    @ViewBuilder
    private func optionalRow(_ title: LocalizedStringKey, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            LabeledContent(title, value: value)
        }
    }
}
