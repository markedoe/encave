import CaveKit
import SwiftUI

/// The diary: bottles opened from the cellar and wines tasted elsewhere, newest first,
/// grouped by month, with filters and insights.
struct JournalView: View {
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

    /// The chosen tag while some wine tasted elsewhere still has it and the filter shows them:
    /// otherwise its chip is gone and the filter could not be cleared.
    private var activeTag: String? {
        guard model.journalFilter.showsElsewhere else { return nil }
        return model.journalTag.flatMap { model.cellar.discoveryTags.contains($0) ? $0 : nil }
    }

    private var journal: some View {
        let entries = model.cellar.diary(filter: model.journalFilter, query: query, tag: activeTag)
        let months = Dictionary(grouping: entries) { entry in
            Calendar.current.dateInterval(of: .month, for: entry.date)?.start ?? .distantPast
        }
        let isEmpty = model.cellar.consumedBottles.isEmpty && model.cellar.discoveries.isEmpty
        return List {
            if !embedded {
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
            if isEmpty {
                ContentUnavailableView {
                    Label("Nothing here yet", systemImage: "book.closed")
                } description: {
                    Text("Open a bottle from your cellar or note a wine tasted elsewhere.")
                } actions: {
                    AddTastingMenu {
                        Text("Add a Tasting").frame(minWidth: 200)
                    }
                    .menuStyle(.button)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .accessibilityIdentifier("journal.empty.add")
                }
                .listRowBackground(Color.clear)
            } else {
                Section {
                    DiaryFilterBar(activeTag: activeTag)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
                if entries.isEmpty {
                    if query.trimmingCharacters(in: .whitespaces).isEmpty {
                        FilterEmptyState(filter: model.journalFilter)
                            .listRowBackground(Color.clear)
                    } else {
                        ContentUnavailableView.search(text: query)
                            .listRowBackground(Color.clear)
                    }
                }
                ForEach(months.keys.sorted(by: >), id: \.self) { month in
                    Section {
                        ForEach(months[month] ?? []) { entry in
                            DiaryEntryRow(entry: entry)
                        }
                    } header: {
                        // A heading on its own: "Octobre 2026" in French, not "octobre 2026".
                        Text(month, format: Date.FormatStyle(capitalizationContext: .beginningOfSentence).month(.wide).year())
                    }
                }
            }
        }
        .searchable(text: $query, prompt: Text("Wine, place, note or tag"))
        .navigationTitle("Journal")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                AddTastingMenu {
                    Label("Add a Tasting", systemImage: "plus")
                }
                .accessibilityIdentifier("journal.add")
                .help(Text("Note a bottle opened from your cellar or a wine tasted elsewhere"))
            }
            #if os(iOS)
            SettingsToolbarItem()
            #endif
        }
    }
}

/// "+" in the Journal: first where the wine was drunk, since only a bottle from the
/// cellar leaves it.
struct AddTastingMenu<MenuLabel: View>: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @ViewBuilder var label: () -> MenuLabel

    var body: some View {
        Menu {
            Button {
                router?.sheet = .drink(bottleID: nil)
            } label: {
                Label("A Bottle from My Cellar", systemImage: "house")
                // Says why it is off, rather than only dimming it.
                if model.bottlesInCellar.isEmpty {
                    Text("Your cellar has no bottle to open.")
                } else {
                    Text("It leaves your cellar.")
                }
            }
            .disabled(model.bottlesInCellar.isEmpty)
            .accessibilityIdentifier("journal.add.cellar")
            Button {
                router?.sheet = .discovery(discoveryID: nil)
            } label: {
                Label("Somewhere Else", systemImage: "mappin.and.ellipse")
                Text("Restaurant, friends, a fair… Your cellar does not change.")
            }
            .accessibilityIdentifier("journal.add.elsewhere")
        } label: {
            label()
        }
    }
}

/// All · From my cellar · Tasted elsewhere · To buy, then the tags of wines tasted elsewhere.
private struct DiaryFilterBar: View {
    @Environment(CellarModel.self) private var model
    let activeTag: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ChoiceChip(title: "All", isSelected: model.journalFilter == .all) {
                    select(.all)
                } leading: {
                    Image(systemName: "book.closed")
                }
                // One choice of four, read as such ("1 of 4"), not four separate buttons.
                .accessibilityValue(Text("\(1) of \(4)"))
                .accessibilityIdentifier("journal.filter.all")
                ChoiceChip(title: "From my cellar", isSelected: model.journalFilter == .cellar) {
                    select(.cellar)
                } leading: {
                    Image(systemName: "house")
                }
                .accessibilityValue(Text("\(2) of \(4)"))
                .accessibilityIdentifier("journal.filter.cellar")
                ChoiceChip(title: "Tasted elsewhere", isSelected: model.journalFilter == .elsewhere) {
                    select(.elsewhere)
                } leading: {
                    Image(systemName: "mappin.and.ellipse")
                }
                .accessibilityValue(Text("\(3) of \(4)"))
                .accessibilityIdentifier("journal.filter.elsewhere")
                ChoiceChip(title: "To buy", isSelected: model.journalFilter == .toBuy) {
                    select(.toBuy)
                } leading: {
                    Image(systemName: "cart")
                    Text(verbatim: "\(model.cellar.shoppingListCount)")
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                .accessibilityValue(Text("\(4) of \(4)"))
                .accessibilityIdentifier("journal.filter.toBuy")
                if model.journalFilter.showsElsewhere {
                    ForEach(model.cellar.discoveryTags, id: \.self) { name in
                        ChoiceChip(verbatim: name, isSelected: activeTag == name) {
                            withAnimation(.snappy) { model.journalTag = activeTag == name ? nil : name }
                        } leading: {
                            Image(systemName: "number").font(.caption.weight(.semibold))
                        }
                    }
                }
            }
            .padding(.horizontal, 2)
        }
    }

    private func select(_ filter: DiaryFilter) {
        withAnimation(.snappy) { model.journalFilter = filter }
    }
}

/// What to do when a filter shows nothing yet.
private struct FilterEmptyState: View {
    let filter: DiaryFilter

    var body: some View {
        switch filter {
        case .all, .cellar:
            ContentUnavailableView(
                "No bottles opened yet",
                systemImage: "house",
                description: Text("Bottles you open from your cellar and your notes appear here.")
            )
        case .elsewhere:
            ContentUnavailableView(
                "No wines tasted elsewhere yet",
                systemImage: "mappin.and.ellipse",
                description: Text("Liked a wine at friends’ or in a restaurant? Tap + and choose Somewhere Else to remember it, add notes and tags, and put it on your shopping list.")
            )
        case .toBuy:
            ContentUnavailableView(
                "Your shopping list is empty",
                systemImage: "cart",
                description: Text("Turn on To buy for a wine tasted elsewhere to find it here.")
            )
        }
    }
}

/// One entry of the diary, from the cellar or tasted elsewhere, opening its note.
private struct DiaryEntryRow: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let entry: DiaryEntry

    var body: some View {
        switch entry {
        case .tasting(let bottle):
            Button {
                router?.sheet = .editTasting(bottleID: bottle.id)
            } label: {
                TastingRow(bottle: bottle, showsWine: true, showsSource: true)
            }
            .buttonStyle(.plain)
            .modifier(OpenedBottleActions(bottle: bottle))
            .accessibilityIdentifier("journal.row.cellar")
        case .elsewhere(let discovery):
            Button {
                router?.sheet = .discovery(discoveryID: discovery.id)
            } label: {
                DiscoveryRow(discovery: discovery, showsSource: true)
            }
            .buttonStyle(.plain)
            .modifier(ElsewhereActions(discovery: discovery))
            .accessibilityIdentifier("journal.row.elsewhere")
        }
    }
}

/// Swipe actions and context menu of a bottle opened from the cellar: its wine, the
/// note, more bottles of it, or back in the cellar if it was logged by mistake (with Undo).
struct OpenedBottleActions: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let bottle: Bottle

    func body(content: Content) -> some View {
        let wine = model.cellar.wines[bottle.wineID]
        content
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                if let wine {
                    Button {
                        router?.sheet = .addBottle(AddBottleContext(wine: wine))
                    } label: {
                        Label("Add Bottles", systemImage: "plus")
                    }
                    .tint(.accentColor)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) {
                    model.putBack(bottle.id)
                } label: {
                    Label("Put Back in the Cellar", systemImage: "arrow.uturn.backward")
                }
            }
            .contextMenu {
                if let wine {
                    Button("Show Wine", systemImage: "wineglass") { model.open(wine: wine.id) }
                }
                Button("Edit Note", systemImage: "pencil") { router?.sheet = .editTasting(bottleID: bottle.id) }
                if let wine {
                    Button("Add Bottles", systemImage: "plus") { router?.sheet = .addBottle(AddBottleContext(wine: wine)) }
                }
                Divider()
                Button("Put Back in the Cellar", systemImage: "arrow.uturn.backward", role: .destructive) {
                    model.putBack(bottle.id)
                }
            }
    }
}

struct TastingRow: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle
    var showsWine = true
    /// In the Journal: says in words that the bottle came from the cellar.
    var showsSource = false

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        let tasting = bottle.tasting
        let row = HStack(alignment: .top, spacing: 12) {
            if showsWine {
                BottleEnd(color: wine?.color, size: 30)
            }
            VStack(alignment: .leading, spacing: 4) {
                if showsWine {
                    JournalRowHeader(title: Text(wine?.displayName ?? String(localized: "Unknown wine")),
                                     date: tasting?.date ?? bottle.addedAt)
                } else if let date = tasting?.date {
                    Text(date, format: .dateTime.day().month(.wide).year())
                        .font(.headline)
                }
                if showsSource {
                    Label("From my cellar", systemImage: "house")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .labelStyle(CompactLabelStyle())
                }
                // The same order as a wine tasted elsewhere: details, rating, note.
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
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .labelStyle(CompactLabelStyle())
                if let rating = tasting?.rating {
                    RatingView(rating: rating)
                }
                if let note = tasting?.note, !note.isEmpty {
                    Text(note)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                }
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        if showsSource {
            row
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(accessibilityText(wine: wine))
        } else {
            row.accessibilityElement(children: .combine)
        }
    }

    /// "From my cellar, Château X 2015, 4 stars, March 3, …".
    private func accessibilityText(wine: Wine?) -> String {
        let tasting = bottle.tasting
        var parts = [String(localized: "From my cellar"), wine?.displayName ?? String(localized: "Unknown wine")]
        // The style in words, as shown under the note.
        if let style = wine?.color?.name { parts.append(style) }
        if let rating = tasting?.rating { parts.append(String(localized: "\(rating) stars")) }
        parts.append((tasting?.date ?? bottle.addedAt).formatted(.dateTime.day().month(.wide)))
        if let occasion = tasting?.occasion { parts.append(occasion) }
        if let note = tasting?.note, !note.isEmpty { parts.append(note) }
        if tasting?.isDetailed == true { parts.append(String(localized: "Full note")) }
        return parts.joined(separator: ", ")
    }
}

/// The first line of a Journal row: the wine, and the date at the top right; the date goes
/// under the wine at the accessibility text sizes so neither is squeezed.
struct JournalRowHeader: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: Text
    let date: Date

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
        layout {
            title.font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(date, format: .dateTime.day().month(.abbreviated))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize()
        }
    }
}
