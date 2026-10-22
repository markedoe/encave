import CaveKit
import Charts
import SwiftUI

/// The cellar in figures and charts.
struct InsightsView: View {
    @Environment(CellarModel.self) private var model
    /// One country bar, taller with larger text so its label is not clipped.
    @ScaledMetric(relativeTo: .body) private var countryRowHeight: CGFloat = 34

    var body: some View {
        let stats = model.cellar.statistics(year: model.currentYear)
        if stats.bottleCount == 0, stats.consumedCount == 0, model.cellar.discoveries.isEmpty {
            ContentUnavailableView("No figures yet", systemImage: "chart.pie",
                                   description: Text("Add bottles to see insights."))
                .navigationTitle("Insights")
        } else {
            figures(stats)
        }
    }

    private func figures(_ stats: CellarStatistics) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                YearInWineCard()
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                    StatTile(count: stats.bottleCount, caption: .count("bottles in the cellar", stats.bottleCount), systemImage: "wineglass")
                    StatTile(count: stats.wineCount, caption: .count("different wines", stats.wineCount), systemImage: "list.bullet")
                    StatTile(count: stats.consumedCount, caption: .count("bottles opened", stats.consumedCount), systemImage: "book.closed")
                    StatTile(value: stats.averageRating.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "–",
                             caption: "average rating", systemImage: "star")
                    ForEach(stats.valueByCurrency.keys.sorted(), id: \.self) { currency in
                        StatTile(value: (stats.valueByCurrency[currency] ?? 0).formatted(.currency(code: currency).precision(.fractionLength(0))),
                                 caption: "purchase value", systemImage: "banknote")
                    }
                    ForEach(stats.estimatedValueByCurrency.keys.sorted(), id: \.self) { currency in
                        let pricedCount = stats.pricedBottleCount, bottleCount = stats.bottleCount
                        StatTile(value: (stats.estimatedValueByCurrency[currency] ?? 0).formatted(.currency(code: currency).precision(.fractionLength(0))),
                                 caption: "estimated value today, \(pricedCount) of \(bottleCount) bottles", systemImage: "chart.line.uptrend.xyaxis")
                    }
                }

                DrinkDownCard()

                if !stats.byColor.isEmpty {
                    ChartCard(title: "By style") {
                        Chart(stats.byColor) { slice in
                            SectorMark(angle: .value("Bottles", slice.count), innerRadius: .ratio(0.6), angularInset: 1.5)
                                .cornerRadius(4)
                                .foregroundStyle(slice.color?.tint ?? Color.secondary.opacity(0.3))
                                .accessibilityLabel(slice.color.map { Text($0.title) } ?? Text("Unknown"))
                                .accessibilityValue(Text("\(slice.count) bottles"))
                        }
                        .frame(height: 200)
                        FlowLayout(spacing: 12) {
                            ForEach(stats.byColor) { slice in
                                HStack(spacing: 5) {
                                    BottleEnd(color: slice.color, size: 12)
                                    if let color = slice.color { Text(color.title) } else { Text("Unknown") }
                                    Text(verbatim: slice.count.formatted()).foregroundStyle(.secondary).monospacedDigit()
                                }
                                .font(.footnote)
                                // The chart above already reads each style to VoiceOver.
                                .accessibilityHidden(true)
                            }
                        }
                    }
                }

                let maturities: [(maturity: Maturity, count: Int)] = Maturity.allCases.sorted().compactMap { maturity in
                    guard let count = stats.byMaturity[maturity], count > 0 else { return nil }
                    return (maturity, count)
                }
                if !maturities.isEmpty {
                    ChartCard(title: "By maturity") {
                        let largest = Double(maturities.map(\.count).max() ?? 1)
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(maturities, id: \.maturity) { item in
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        // The icon and the words, not only the color.
                                        MaturityLabel(maturity: item.maturity)
                                        Spacer()
                                        Text(verbatim: "\(item.count)").monospacedDigit().foregroundStyle(.secondary)
                                    }
                                    .font(.subheadline)
                                    ProgressView(value: Double(item.count), total: largest)
                                        .tint(item.maturity.tint)
                                        .accessibilityHidden(true)
                                }
                                .accessibilityElement(children: .combine)
                            }
                        }
                    }
                }

                if !stats.byCountry.isEmpty {
                    ChartCard(title: "By country") {
                        Chart(stats.byCountry.prefix(8)) { slice in
                            BarMark(x: .value("Bottles", slice.count), y: .value("Country", slice.label))
                                .foregroundStyle(Color.accentColor.gradient)
                                .cornerRadius(4)
                                .annotation(position: .trailing) {
                                    Text(verbatim: slice.count.formatted()).font(.caption).foregroundStyle(.secondary)
                                }
                                .accessibilityLabel(Text(verbatim: slice.label))
                                .accessibilityValue(Text("\(slice.count) bottles"))
                        }
                        .chartXAxis(.hidden)
                        .frame(height: CGFloat(min(8, stats.byCountry.count)) * countryRowHeight + 10)
                    }
                }

                if !stats.byRegion.isEmpty {
                    ChartCard(title: "By region") {
                        Chart(stats.byRegion.prefix(8)) { slice in
                            BarMark(x: .value("Bottles", slice.count), y: .value("Region", slice.label))
                                .foregroundStyle(Color.accentColor.gradient)
                                .cornerRadius(4)
                                .annotation(position: .trailing) {
                                    Text(verbatim: "\(slice.count)").font(.caption).foregroundStyle(.secondary)
                                }
                        }
                        .chartXAxis(.hidden)
                        .frame(height: CGFloat(min(8, stats.byRegion.count)) * 34 + 10)
                    }
                }

                if !stats.byVintageDecade.isEmpty {
                    ChartCard(title: "By vintage") {
                        Chart(stats.byVintageDecade) { slice in
                            BarMark(x: .value("Decade", Self.decadeName(slice.label)), y: .value("Bottles", slice.count))
                                .foregroundStyle(Color.accentColor.gradient)
                                .cornerRadius(4)
                                .accessibilityLabel(Text(verbatim: Self.decadeName(slice.label)))
                                .accessibilityValue(Text("\(slice.count) bottles"))
                        }
                        .frame(height: 180)
                    }
                }

                ChartCard(title: "Bottles opened, last 12 months") {
                    Chart(stats.consumptionByMonth) { month in
                        BarMark(x: .value("Month", month.month, unit: .month), y: .value("Bottles", month.count))
                            .foregroundStyle(Color.accentColor.gradient)
                            .cornerRadius(3)
                            .accessibilityLabel(Text(month.month, format: .dateTime.month(.wide).year()))
                            .accessibilityValue(Text("\(month.count) bottles"))
                    }
                    .chartXAxis {
                        AxisMarks(values: .stride(by: .month, count: 2)) { _ in
                            AxisValueLabel(format: .dateTime.month(.abbreviated))
                        }
                    }
                    .frame(height: 180)
                }

                if !stats.topGrapes.isEmpty {
                    ChartCard(title: "Grapes") {
                        FlowLayout(spacing: 8) {
                            ForEach(stats.topGrapes) { grape in
                                Text(verbatim: "\(grape.label) · \(grape.count)")
                                    .font(.subheadline)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(Color.secondary.opacity(0.1), in: Capsule())
                            }
                        }
                    }
                }
            }
            .padding()
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Insights")
    }

    /// "2010s" and "NV" from the statistics, in the user's language.
    static func decadeName(_ label: String) -> String {
        if label == "NV" { return String(localized: "NV") }
        if label.hasSuffix("s"), let decade = Int(label.dropLast()) {
            return String(localized: "\(decade)s", comment: "A decade of vintages, such as 2010s")
        }
        return label
    }
}

private struct ChartCard<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            content()
        }
        .padding(16)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// How many bottles a month to open so none passes its window, and when windows end.
private struct DrinkDownCard: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        let month = CellarYear.calendar.component(.month, from: .now)
        let plan = model.cellar.drinkDownPlan(year: model.currentYear, month: month)
        if !plan.isEmpty {
            ChartCard(title: "Drink-down plan") {
                // "1.4", "3", "12": one decimal only for small paces that are not whole.
                let pace = plan.bottlesPerMonth
                let rounded = (pace * 10).rounded() / 10
                let paceLabel = rounded.formatted(.number.precision(.fractionLength(rounded < 10 && rounded != rounded.rounded() ? 1 : 0)))
                VStack(alignment: .leading, spacing: 4) {
                    Group {
                        if rounded < 0.75 {
                            Text("Less than one bottle a month")
                        } else if rounded <= 1 {
                            Text("About one bottle a month")
                        } else {
                            Text("About \(paceLabel) bottles a month")
                        }
                    }
                    .font(.title3.weight(.semibold))
                    Text("to open every bottle before the end of its drinking window.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                if plan.pastPeak > 0 {
                    Label {
                        Text("Past their peak: \(plan.pastPeak)")
                    } icon: {
                        Image(systemName: Maturity.pastPeak.systemImage).foregroundStyle(Maturity.pastPeak.tint)
                    }
                    .font(.subheadline)
                }
                if plan.years.count > 1 || plan.years.first?.leaving ?? 0 > 0 {
                    Text("Windows ending each year")
                        .font(.subheadline.weight(.medium))
                    Chart(plan.years) { item in
                        BarMark(x: .value("Year", String(item.year)), y: .value("Bottles", item.leaving))
                            .foregroundStyle(Color.accentColor.gradient)
                            .cornerRadius(3)
                            .accessibilityLabel(Text(verbatim: String(item.year)))
                            .accessibilityValue(Text("\(item.leaving) bottles"))
                    }
                    .frame(height: 160)
                    .accessibilityIdentifier("insights.drinkdown")
                }
                if plan.withoutWindow > 0 {
                    Text("Bottles without a drinking window are left out: \(plan.withoutWindow)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// "Your year in wine": what was opened in a year, with a choice of year.
private struct YearInWineCard: View {
    @Environment(CellarModel.self) private var model
    @State private var year: Int?
    /// Drawn once per year and recap, not at every rendering of the card.
    @State private var image: Image?

    private struct ImageKey: Equatable {
        var year: Int
        var recap: YearInWine
    }

    var body: some View {
        let years = availableYears
        // A chosen year with nothing left in it falls back to the latest one.
        let shown = year.flatMap { years.contains($0) ? $0 : nil } ?? years.first ?? model.currentYear
        let recap = model.cellar.yearInWine(shown)
        if !years.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Label {
                        Text("Your year in wine").font(.headline)
                    } icon: {
                        Image(systemName: "sparkles").foregroundStyle(Color.accentColor)
                    }
                    Spacer()
                    if years.count > 1 {
                        Picker("Year", selection: Binding(get: { shown }, set: { year = $0 })) {
                            ForEach(years, id: \.self) { Text(verbatim: String($0)).tag($0) }
                        }
                        .pickerStyle(.menu)
                        .labelsHidden()
                    } else {
                        Text(verbatim: String(shown)).foregroundStyle(.secondary)
                    }
                    if let image {
                        ShareLink(item: image, preview: SharePreview(Text("Your year in wine"), image: image)) {
                            Label("Share", systemImage: "square.and.arrow.up")
                                .labelStyle(.iconOnly)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityHint(Text("Shares this summary as an image. You choose where."))
                    }
                }
                YearInWineFacts(recap: recap)
                    .accessibilityElement(children: .combine)
            }
            .padding(16)
            .background {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(Color.accentColor.opacity(0.1))
            }
            .accessibilityIdentifier("insights.year")
            .task(id: ImageKey(year: shown, recap: recap)) {
                image = shareImage(recap: recap, year: shown)
            }
        }
    }

    /// The summary drawn as an image, in the light appearance, to share.
    private func shareImage(recap: YearInWine, year: Int) -> Image? {
        let yearLabel = String(year)
        let card = VStack(alignment: .leading, spacing: 14) {
            Label {
                Text("Your year in wine \(yearLabel)").font(.headline)
            } icon: {
                Image(systemName: "sparkles").foregroundStyle(Color.accentColor)
            }
            YearInWineFacts(recap: recap)
            Text("Made with Encave").font(.caption2).foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(width: 360, alignment: .leading)
        .background(Color.accentColor.opacity(0.1))
        .background(.background)
        .environment(\.colorScheme, .light)
        .dynamicTypeSize(.large)
        let renderer = ImageRenderer(content: card)
        renderer.scale = 3
        return renderer.cgImage.map { Image(decorative: $0, scale: 3) }
    }

    /// Years with tastings, from the cellar or elsewhere, most recent first.
    private var availableYears: [Int] {
        let dates = model.cellar.consumedBottles.compactMap { $0.tasting?.date } + model.cellar.discoveries.values.map(\.date)
        return Set(dates.map(CellarYear.of)).sorted(by: >)
    }

}

/// The figures and facts of a year in wine, on screen and in the shared image.
private struct YearInWineFacts: View {
    let recap: YearInWine

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 18) {
                figure(recap.bottlesOpened, caption: .count("bottles opened", recap.bottlesOpened))
                figure(recap.differentWines, caption: .count("different wines", recap.differentWines))
                if recap.discoveries > 0 {
                    figure(recap.discoveries, caption: .count("tasted elsewhere", recap.discoveries))
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                if let favorite = recap.favorite {
                    let starsLabel = String(localized: "\(favorite.rating) stars")
                    fact("star.fill", Text("Favorite: \(favorite.name), \(starsLabel)"))
                }
                if let regionName = recap.topRegion {
                    fact("map", Text("Most opened region: \(regionName)"))
                }
                if let styleName = recap.topColor?.name {
                    fact("wineglass", Text("Most opened style: \(styleName)"))
                }
                if let occasion = recap.topOccasion {
                    let occasionName = Occasion.displayName(occasion)
                    fact("person.2", Text("Favorite occasion: \(occasionName)"))
                }
                if let month = recap.busiestMonth {
                    let monthName = CellarYear.calendar.standaloneMonthSymbols[month - 1]
                    fact("calendar", Text("Busiest month: \(monthName)"))
                }
            }
            .font(.subheadline)
        }
    }

    private func figure(_ value: Int, caption: CountCaption) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: value.formatted()).font(.title.weight(.semibold).monospacedDigit())
            Text(caption.key).font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func fact(_ systemImage: String, _ text: Text) -> some View {
        Label { text } icon: {
            Image(systemName: systemImage).foregroundStyle(Color.accentColor)
        }
    }
}
