import CaveKit
import SwiftUI

struct WineDetailView: View {
    @Environment(CellarModel.self) private var model
    let wineID: UUID

    var body: some View {
        if let wine = model.cellar.wines[wineID] {
            let bottles = model.cellar.bottles(of: wineID)
            let tastings = model.cellar.tastings(of: wineID)
            let maturity = Maturity.of(wine, year: model.currentYear)
            List {
                Section {
                    WineHeader(wine: wine, count: bottles.count)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section {
                    HStack(spacing: 12) {
                        Button {
                            if let bottle = bottles.first { model.sheet = .drink(bottleID: bottle.id) }
                        } label: {
                            ActionLabel("Drink", systemImage: "wineglass").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("action.wine.drink")
                        .disabled(bottles.isEmpty)
                        Button {
                            model.sheet = .addBottle(AddBottleContext(wine: wine))
                        } label: {
                            ActionLabel("Add", systemImage: "plus").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("action.wine.add")
                    }
                    .controlSize(.large)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }

                Section("Drinking window") {
                    MaturityLabel(maturity: maturity)
                        .font(.headline)
                    if let from = wine.drinkFrom, let until = wine.drinkUntil {
                        MaturityGauge(from: from, until: until, year: model.currentYear, maturity: maturity)
                            .padding(.vertical, 6)
                    } else {
                        Button("Set a drinking window") { model.sheet = .editWine(wineID: wineID) }
                    }
                }

                if let advice = ServingAdvice.advice(for: wine, year: model.currentYear) {
                    Section("Serving") {
                        LabeledContent {
                            Text(verbatim: temperatureRange(advice)).monospacedDigit()
                        } label: {
                            Label("Temperature", systemImage: "thermometer.medium")
                        }
                        LabeledContent {
                            if let minutes = advice.decantMinutes {
                                Text("\(minutes) min")
                            } else {
                                Text("Not needed")
                            }
                        } label: {
                            Label("Decanting", systemImage: "drop")
                        }
                    }
                }

                Section("Details") {
                    if let color = wine.color {
                        LabeledContent("Style") {
                            HStack(spacing: 6) {
                                BottleEnd(color: color, size: 16)
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
                    if let notes = wine.notes, !notes.isEmpty {
                        Text(notes)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    ForEach(bottles) { bottle in
                        BottleRow(bottle: bottle)
                    }
                } header: {
                    Text("\(bottles.count) bottles in the cellar")
                }

                let discoveries = model.cellar.discoveries(of: wineID)
                if !discoveries.isEmpty {
                    Section("Discoveries") {
                        ForEach(discoveries) { discovery in
                            Button {
                                model.sheet = .discovery(discoveryID: discovery.id)
                            } label: {
                                DiscoveryRow(discovery: discovery, showsWine: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !tastings.isEmpty {
                    Section("Tastings") {
                        ForEach(tastings) { bottle in
                            Button {
                                model.sheet = .editTasting(bottleID: bottle.id)
                            } label: {
                                TastingRow(bottle: bottle, showsWine: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle(wine.displayName)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Edit Wine", systemImage: "pencil") { model.sheet = .editWine(wineID: wineID) }
                        Button("Add Bottles", systemImage: "plus") { model.sheet = .addBottle(AddBottleContext(wine: wine)) }
                        Divider()
                        Button("Delete Wine", systemImage: "trash", role: .destructive) { model.deleteWine(wineID) }
                    } label: {
                        Label("More", systemImage: "ellipsis.circle")
                    }
                }
            }
        } else {
            ContentUnavailableView("Wine not found", systemImage: "questionmark")
        }
    }

    /// "16 – 18 °C", or Fahrenheit where the locale uses it.
    private func temperatureRange(_ advice: ServingAdvice) -> String {
        let style = Measurement<UnitTemperature>.FormatStyle(
            width: .narrow, usage: .weather, numberFormatStyle: .number.precision(.fractionLength(0))
        )
        let low = Measurement(value: Double(advice.minTemperature), unit: UnitTemperature.celsius)
        let high = Measurement(value: Double(advice.maxTemperature), unit: UnitTemperature.celsius)
        return "\(low.formatted(style)) – \(high.formatted(style))"
    }

    @ViewBuilder
    private func optionalRow(_ title: LocalizedStringKey, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            LabeledContent(title, value: value)
        }
    }
}

/// Large header: bottle end, name in serif, vintage and origin.
private struct WineHeader: View {
    let wine: Wine
    let count: Int

    var body: some View {
        VStack(spacing: 12) {
            if PhotoStore.exists(wine.photoID) {
                WinePhoto(photoID: wine.photoID)
                    .frame(maxWidth: 360)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding(.top, 4)
                    .accessibilityHidden(true)
            } else {
                BottleEnd(color: wine.color, size: 72)
                    .padding(.top, 8)
            }
            Text(wine.name)
                .font(.largeTitle.weight(.semibold))
                .fontDesign(.serif)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .minimumScaleFactor(0.7)
            HStack(spacing: 8) {
                if let vintage = wine.vintage {
                    Text(verbatim: "\(vintage)")
                        .font(.title3.monospacedDigit())
                }
                if let color = wine.color {
                    Text(color.title)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(color.tint.opacity(0.18), in: Capsule())
                }
            }
            if let producer = wine.producer {
                Text(producer)
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
            let origin = [wine.appellation, wine.region, wine.country].compactMap { $0 }.joined(separator: " · ")
            if !origin.isEmpty {
                Text(origin)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .padding(.horizontal)
        .background(
            LinearGradient(
                colors: [(wine.color?.tint ?? .gray).opacity(0.22), .clear],
                startPoint: .top, endPoint: .bottom
            ),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .accessibilityElement(children: .combine)
    }
}

/// One bottle in the cellar with its location and quick actions.
struct BottleRow: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: bottle.slot == nil ? "shippingbox" : "square.grid.3x3.topleft.filled")
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.locationLabel(for: bottle) ?? String(localized: "No location"))
                    .font(.body)
                Text(details)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if bottle.slot != nil {
                Button {
                    model.showInRack(bottle)
                } label: {
                    Image(systemName: "scope")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Show in Rack"))
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                model.sheet = .drink(bottleID: bottle.id)
            } label: {
                Label("Drink", systemImage: "wineglass")
            }
            .tint(.accentColor)
        }
        .swipeActions(edge: .trailing) {
            Button {
                model.markGone(bottle.id)
            } label: {
                Label("Remove", systemImage: "minus.circle")
            }
            .tint(.red)
            Button {
                model.sheet = .moveBottle(bottleID: bottle.id)
            } label: {
                Label("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right")
            }
            .tint(.indigo)
        }
        .contextMenu {
            Button("Drink", systemImage: "wineglass") { model.sheet = .drink(bottleID: bottle.id) }
            Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") { model.sheet = .moveBottle(bottleID: bottle.id) }
            if bottle.slot != nil {
                Button("Show in Rack", systemImage: "scope") { model.showInRack(bottle) }
            }
            Divider()
            Button("Given away or broken", systemImage: "minus.circle", role: .destructive) { model.markGone(bottle.id) }
        }
    }

    private var details: String {
        var parts: [String] = []
        if let price = bottle.purchasePrice {
            parts.append(price.formatted(.currency(code: bottle.currency ?? model.cellar.settings.currency)))
        }
        if let date = bottle.purchaseDate {
            parts.append(date.formatted(date: .abbreviated, time: .omitted))
        }
        if let place = bottle.purchasePlace { parts.append(place) }
        if bottle.sizeML != 750 { parts.append(BottleSize.label(for: bottle.sizeML)) }
        return parts.isEmpty ? String(localized: "Added \(bottle.addedAt.formatted(date: .abbreviated, time: .omitted))") : parts.joined(separator: " · ")
    }
}

/// Common bottle sizes.
enum BottleSize {
    static let all = [375, 750, 1500, 3000]

    static func label(for ml: Int) -> String {
        switch ml {
        case 375: String(localized: "Half bottle")
        case 750: String(localized: "Bottle")
        case 1500: String(localized: "Magnum")
        case 3000: String(localized: "Double magnum")
        default: "\(ml) ml"
        }
    }
}
