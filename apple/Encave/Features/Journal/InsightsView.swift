import CaveKit
import Charts
import SwiftUI

/// The cellar in figures and charts.
struct InsightsView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        let stats = model.cellar.statistics(year: model.currentYear)
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                    StatTile(value: "\(stats.bottleCount)", caption: "bottles in the cellar", systemImage: "wineglass")
                    StatTile(value: "\(stats.wineCount)", caption: "different wines", systemImage: "list.bullet")
                    StatTile(value: "\(stats.consumedCount)", caption: "bottles opened", systemImage: "book.closed")
                    StatTile(value: stats.averageRating.map { $0.formatted(.number.precision(.fractionLength(1))) } ?? "–",
                             caption: "average rating", systemImage: "star")
                    ForEach(stats.valueByCurrency.keys.sorted(), id: \.self) { currency in
                        StatTile(value: (stats.valueByCurrency[currency] ?? 0).formatted(.currency(code: currency).precision(.fractionLength(0))),
                                 caption: "purchase value", systemImage: "banknote")
                    }
                }

                if !stats.byColor.isEmpty {
                    ChartCard(title: "By style") {
                        Chart(stats.byColor) { slice in
                            SectorMark(angle: .value("Bottles", slice.count), innerRadius: .ratio(0.6), angularInset: 1.5)
                                .cornerRadius(4)
                                .foregroundStyle(slice.color?.tint ?? .gray)
                        }
                        .frame(height: 200)
                        FlowLayout(spacing: 12) {
                            ForEach(stats.byColor) { slice in
                                HStack(spacing: 5) {
                                    BottleEnd(color: slice.color, size: 12)
                                    if let color = slice.color { Text(color.title) } else { Text("Unknown") }
                                    Text(verbatim: "\(slice.count)").foregroundStyle(.secondary).monospacedDigit()
                                }
                                .font(.footnote)
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
                                    Text(verbatim: "\(slice.count)").font(.caption).foregroundStyle(.secondary)
                                }
                        }
                        .chartXAxis(.hidden)
                        .frame(height: CGFloat(min(8, stats.byCountry.count)) * 34 + 10)
                    }
                }

                if !stats.byVintageDecade.isEmpty {
                    ChartCard(title: "By vintage") {
                        Chart(stats.byVintageDecade) { slice in
                            BarMark(x: .value("Decade", slice.label), y: .value("Bottles", slice.count))
                                .foregroundStyle(Color.accentColor.gradient)
                                .cornerRadius(4)
                        }
                        .frame(height: 180)
                    }
                }

                ChartCard(title: "Bottles opened, last 12 months") {
                    Chart(stats.consumptionByMonth) { month in
                        BarMark(x: .value("Month", month.month, unit: .month), y: .value("Bottles", month.count))
                            .foregroundStyle(Color.accentColor.gradient)
                            .cornerRadius(3)
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
