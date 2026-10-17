import CaveKit
import SwiftUI

/// Bottles you have opened, grouped by month, with access to insights.
struct JournalView: View {
    @Environment(CellarModel.self) private var model
    @State private var query = ""

    var body: some View {
        NavigationStack {
            let bottles = model.cellar.consumedBottles.filter { bottle in
                guard !query.isEmpty, let wine = model.cellar.wines[bottle.wineID] else { return true }
                return wine.displayName.localizedCaseInsensitiveContains(query)
                    || (bottle.tasting?.note ?? "").localizedCaseInsensitiveContains(query)
            }
            let months = Dictionary(grouping: bottles) { bottle in
                Calendar.current.dateInterval(of: .month, for: bottle.tasting?.date ?? bottle.addedAt)?.start ?? .distantPast
            }
            List {
                Section {
                    NavigationLink {
                        InsightsView()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Insights").font(.headline)
                                Text("Your cellar in figures").font(.subheadline).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "chart.pie").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                if bottles.isEmpty {
                    ContentUnavailableView(
                        "No tastings yet",
                        systemImage: "book.closed",
                        description: Text("Bottles you open and your notes will appear here.")
                    )
                    .listRowBackground(Color.clear)
                }
                ForEach(months.keys.sorted(by: >), id: \.self) { month in
                    Section {
                        ForEach(months[month] ?? []) { bottle in
                            Button {
                                model.sheet = .editTasting(bottleID: bottle.id)
                            } label: {
                                TastingRow(bottle: bottle, showsWine: true)
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text(month, format: .dateTime.month(.wide).year())
                    }
                }
            }
            .searchable(text: $query, prompt: Text("Wine or note"))
            .navigationTitle("Journal")
        }
    }
}

struct TastingRow: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle
    var showsWine = true

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        let tasting = bottle.tasting
        HStack(alignment: .top, spacing: 12) {
            if showsWine {
                BottleEnd(color: wine?.color, size: 30)
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    if showsWine {
                        Text(wine?.displayName ?? String(localized: "Unknown wine"))
                            .font(.headline)
                    } else if let date = tasting?.date {
                        Text(date, format: .dateTime.day().month(.wide).year())
                            .font(.headline)
                    }
                    Spacer()
                    if showsWine, let date = tasting?.date {
                        Text(date, format: .dateTime.day().month(.abbreviated))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                if let rating = tasting?.rating {
                    RatingView(rating: rating)
                }
                if let note = tasting?.note, !note.isEmpty {
                    Text(note)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
                HStack(spacing: 10) {
                    if let occasion = tasting?.occasion {
                        Label(occasion, systemImage: "person.2")
                    }
                    if tasting?.isDetailed == true {
                        Label("Full note", systemImage: "text.alignleft")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .labelStyle(CompactLabelStyle())
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
