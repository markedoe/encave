import CaveKit
import SwiftUI

/// What you drank (tastings) and wines found elsewhere (discoveries), with insights.
struct JournalView: View {
    enum Page: Hashable { case tastings, discoveries }

    @Environment(CellarModel.self) private var model
    /// On the Mac the window shows Insights in its own column, and provides the navigation.
    var embedded = false
    @State private var query = ""

    var body: some View {
        if embedded {
            journal
        } else {
            NavigationStack { journal }
        }
    }

    private var journal: some View {
        @Bindable var model = model
        return List {
                Section {
                    Picker("Show", selection: $model.journalPage.animation()) {
                        Text("Tastings").tag(Page.tastings)
                        Text("Discoveries").tag(Page.discoveries)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)

                switch model.journalPage {
                case .tastings: TastingSections(query: query, showsInsights: !embedded)
                case .discoveries: DiscoverySections(query: query)
                }
            }
            .searchable(text: $query, prompt: Text("Wine, place, note or tag"))
            .navigationTitle("Journal")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        model.sheet = .discovery(discoveryID: nil)
                    } label: {
                        Label("Remember a Wine", systemImage: "plus")
                    }
                    .accessibilityIdentifier("journal.add")
                    .help(Text("Remember a wine tasted or seen elsewhere"))
                }
            }
    }
}

/// Bottles you have opened, grouped by month, with access to insights.
private struct TastingSections: View {
    @Environment(CellarModel.self) private var model
    let query: String
    var showsInsights = true

    var body: some View {
        let bottles = model.cellar.tastings(matching: query)
        let months = Dictionary(grouping: bottles) { bottle in
            Calendar.current.dateInterval(of: .month, for: bottle.tasting?.date ?? bottle.addedAt)?.start ?? .distantPast
        }
        if showsInsights {
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
        }
        if bottles.isEmpty {
            if model.cellar.consumedBottles.isEmpty {
                ContentUnavailableView(
                    "No tastings yet",
                    systemImage: "book.closed",
                    description: Text("Bottles you open and your notes will appear here.")
                )
                .listRowBackground(Color.clear)
            } else {
                ContentUnavailableView.search(text: query)
                    .listRowBackground(Color.clear)
            }
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
                    // The style in words, not only by the bottle's color.
                    if showsWine, let style = wine?.color?.name {
                        Text(style)
                    }
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
