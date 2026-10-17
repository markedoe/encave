import CaveKit
import SwiftUI

struct CellarView: View {
    @Environment(CellarModel.self) private var model
    @State private var filter = CellarFilter()
    @State private var sort: CellarSort = .name
    @State private var selection: UUID?

    var body: some View {
        #if os(macOS)
        // The window already has a sidebar: the list and the wine sit side by side.
        HSplitView {
            NavigationStack {
                list
            }
            .frame(minWidth: 280, idealWidth: 380, maxWidth: 560)
            NavigationStack { detail }
                .frame(minWidth: 360, maxWidth: .infinity)
        }
        #else
        NavigationSplitView {
            list
        } detail: {
            detail
        }
        .navigationSplitViewStyle(.balanced)
        #endif
    }

    private var list: some View {
        content
            .navigationTitle("Cellar")
            .searchable(text: $filter.query, prompt: Text("Wine, producer, region, grape, vintage"))
            .toolbar { toolbar }
    }

    @ViewBuilder
    private var detail: some View {
        if let selection, model.cellar.wines[selection] != nil {
            #if os(macOS)
            WineDetailView(wineID: selection)
            #else
            NavigationStack { WineDetailView(wineID: selection) }
            #endif
        } else {
            ContentUnavailableView("Select a wine", systemImage: "wineglass",
                                   description: Text("Its bottles, drinking window and tastings appear here."))
        }
    }

    @ViewBuilder
    private var content: some View {
        let groups = model.cellar.groups(filter: filter, sort: sort, year: model.currentYear)
        if model.cellar.bottlesInCellar.isEmpty {
            EmptyCellarView()
        } else {
            List(selection: $selection) {
                if filter.query.isEmpty {
                    Section {
                        CellarSummary(filter: $filter)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                            .listRowBackground(Color.clear)
                    }
                }
                Section {
                    if groups.isEmpty {
                        ContentUnavailableView.search(text: filter.query)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(groups) { group in
                        WineRow(group: group)
                            .accessibilityIdentifier("wine.row")
                            .tag(group.wine.id)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                if let bottle = group.bottles.first {
                                    Button {
                                        model.sheet = .drink(bottleID: bottle.id)
                                    } label: {
                                        Label("Drink", systemImage: "wineglass")
                                    }
                                    .tint(.accentColor)
                                }
                            }
                            .swipeActions(edge: .trailing) {
                                Button {
                                    model.sheet = .addBottle(AddBottleContext(wine: group.wine))
                                } label: {
                                    Label("Add", systemImage: "plus")
                                }
                                .tint(.blue)
                            }
                            .contextMenu { WineContextMenu(group: group) }
                    }
                } header: {
                    if filter.isActive || !filter.query.isEmpty {
                        Text("\(groups.reduce(0) { $0 + $1.count }) bottles")
                    }
                }
            }
            .animation(.default, value: groups.map(\.id))
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            Menu {
                Picker("Sort by", selection: $sort) {
                    Text("Name").tag(CellarSort.name)
                    Text("Vintage").tag(CellarSort.vintage)
                    Text("Ready first").tag(CellarSort.readiness)
                    Text("Region").tag(CellarSort.region)
                    Text("Recently added").tag(CellarSort.recentlyAdded)
                }
                if filter.isActive {
                    Button("Clear filters", systemImage: "line.3.horizontal.decrease.circle") {
                        filter = CellarFilter(query: filter.query)
                    }
                }
            } label: {
                Label("Sort", systemImage: filter.isActive ? "line.3.horizontal.decrease.circle.fill" : "arrow.up.arrow.down")
            }
            Menu {
                Button("Add Bottles", systemImage: "plus") { model.sheet = .addBottle(AddBottleContext()) }
                Button("Remember a Wine", systemImage: "sparkles") { model.sheet = .discovery(discoveryID: nil) }
            } label: {
                Label("Add Bottle", systemImage: "plus")
            } primaryAction: {
                model.sheet = .addBottle(AddBottleContext())
            }
            .accessibilityIdentifier("cellar.add")
        }
        #if os(iOS)
        ToolbarItem(placement: .topBarLeading) {
            Button {
                model.sheet = .settings
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            .accessibilityIdentifier("cellar.settings")
        }
        #endif
    }
}

/// Quick figures and filter chips at the top of the cellar.
private struct CellarSummary: View {
    @Environment(CellarModel.self) private var model
    @Binding var filter: CellarFilter

    var body: some View {
        let stats = model.cellar.statistics(year: model.currentYear)
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                StatTile(value: "\(stats.bottleCount)", caption: "bottles", systemImage: "wineglass")
                StatTile(value: "\(stats.wineCount)", caption: "wines", systemImage: "list.bullet")
                StatTile(value: "\(stats.byMaturity[.drinkSoon, default: 0])", caption: "to drink soon", systemImage: "hourglass")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(WineColor.allCases, id: \.self) { color in
                        ChoiceChip(title: color.title, isSelected: filter.colors.contains(color)) {
                            toggle(color, in: \.colors)
                        } leading: {
                            BottleEnd(color: color, size: 14)
                        }
                    }
                    Divider().frame(height: 24)
                    ForEach([Maturity.drinkSoon, .ready, .young, .pastPeak], id: \.self) { maturity in
                        ChoiceChip(title: maturity.shortTitle, isSelected: filter.maturities.contains(maturity)) {
                            toggle(maturity, in: \.maturities)
                        } leading: {
                            Image(systemName: maturity.systemImage).foregroundStyle(maturity.tint)
                        }
                    }
                }
                .padding(.horizontal, 2)
            }
        }
    }

    private func toggle<T: Hashable>(_ value: T, in keyPath: WritableKeyPath<CellarFilter, Set<T>>) {
        withAnimation(.snappy) {
            if filter[keyPath: keyPath].contains(value) {
                filter[keyPath: keyPath].remove(value)
            } else {
                filter[keyPath: keyPath].insert(value)
            }
        }
    }
}

/// Long-press menu shared by the cellar, tonight and pairing lists.
struct WineContextMenu: View {
    @Environment(CellarModel.self) private var model
    let group: WineGroup

    var body: some View {
        if let bottle = group.bottles.first {
            Button("Drink a Bottle", systemImage: "wineglass") { model.sheet = .drink(bottleID: bottle.id) }
            if bottle.slot != nil {
                Button("Show in Rack", systemImage: "square.grid.3x3") { model.showInRack(bottle) }
            }
            Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") {
                model.sheet = .moveBottle(bottleID: bottle.id)
            }
        }
        Button("Add Bottles", systemImage: "plus") { model.sheet = .addBottle(AddBottleContext(wine: group.wine)) }
        Button("Edit Wine", systemImage: "pencil") { model.sheet = .editWine(wineID: group.wine.id) }
        Divider()
        Button("Delete Wine", systemImage: "trash", role: .destructive) { model.deleteWine(group.wine.id) }
    }
}

/// First launch: explain the app and offer the sample cellar.
struct EmptyCellarView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        ContentUnavailableView {
            Label {
                Text("Your cellar is empty")
            } icon: {
                BottleEnd(color: .red, size: 56)
            }
        } description: {
            Text("Add the bottles you keep at home to know what you have, where it is and when to open it.")
        } actions: {
            Button {
                model.sheet = .addBottle(AddBottleContext())
            } label: {
                Text("Add a Bottle").frame(minWidth: 200)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button("Remember a wine tasted elsewhere") { model.sheet = .discovery(discoveryID: nil) }
            Button("Load sample cellar") { model.loadSample() }
        }
    }
}

#Preview {
    CellarView().environment(CellarModel.preview())
}
