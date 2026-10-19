import CaveKit
import SwiftUI

/// iPhone and iPad: the list, and the selected wine beside it (or pushed on iPhone).
/// On the Mac, the window's own columns show `CellarList` and `WinePane` directly.
struct CellarView: View {
    @Environment(CellarModel.self) private var model
    @State private var selection: UUID?
    /// iPad: show the list next to the wine, also in portrait, instead of an empty page.
    @State private var columns: NavigationSplitViewVisibility = .all
    @AppStorage(CellarLayout.key) private var showsTable = false
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var isWide: Bool { sizeClass == .regular }
    #else
    private let isWide = true
    #endif

    var body: some View {
        Group {
            if showsTable && isWide {
                NavigationStack { CellarTable(selection: $selection) }
            } else {
                NavigationSplitView(columnVisibility: $columns) {
                    CellarList(selection: $selection)
                        // Wide enough on iPad for names, producers and the figures.
                        .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 460)
                } detail: {
                    NavigationStack { WinePane(wineID: selection) }
                }
                .navigationSplitViewStyle(.balanced)
            }
        }
        // iPad: open on the first wine rather than an empty page (on iPhone the list
        // stays in front).
        .onAppear {
            if isWide, selection == nil {
                selection = model.cellar.groups(filter: CellarFilter(), sort: .name, year: model.currentYear).first?.wine.id
            }
        }
        // Opened from Spotlight, Shortcuts or a notification.
        .onChange(of: model.openWineRequest, initial: true) { _, id in
            guard let id else { return }
            selection = id
            model.openWineRequest = nil
        }
    }
}

/// The selected wine, or a hint when none is selected.
struct WinePane: View {
    @Environment(CellarModel.self) private var model
    let wineID: UUID?

    var body: some View {
        if let wineID, model.cellar.wines[wineID] != nil {
            WineDetailView(wineID: wineID)
        } else {
            ContentUnavailableView("Select a wine", systemImage: "wineglass",
                                   description: Text("Its bottles, drinking window and tastings appear here."))
        }
    }
}

/// Bottles grouped by wine, with search, filters and sorting.
struct CellarList: View {
    @Environment(CellarModel.self) private var model
    @Binding var selection: UUID?
    @State private var filter = CellarFilter()
    @State private var sort: CellarSort = .name
    @State private var showsInsights = false
    /// Selecting several wines for one action.
    @State private var isSelecting = false
    @State private var picked: Set<UUID> = []
    @State private var confirmingDelete = false
    @State private var namingSearch = false
    @State private var searchName = ""
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var isWide: Bool { sizeClass == .regular }
    #else
    private let isWide = true
    #endif

    var body: some View {
        Group {
            if isSelecting {
                CellarSelectionList(groups: model.cellar.groups(filter: filter, sort: sort, year: model.currentYear),
                                    picked: $picked)
            } else {
                content
            }
        }
            .navigationTitle(isSelecting ? Text("Selected: \(picked.count)") : Text("Cellar"))
            .searchable(text: $filter.query, prompt: Text("Wine, producer, region, grape, vintage"))
            .toolbar {
                if isSelecting { selectionToolbar } else { toolbar }
            }
            .alert("Save This Search", isPresented: $namingSearch) {
                TextField("Name", text: $searchName)
                Button("Save") { model.update { $0.saveSearch(searchName, filter: filter) } }
                    .disabled(searchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("It appears first among the filters, one tap away.")
            }
            .confirmationDialog("Delete these wines?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete Wines", role: .destructive) {
                    model.deleteWines(picked)
                    endSelection()
                }
            } message: {
                Text("Their bottles and tastings are deleted too. You can undo right after.")
            }
            .sheet(isPresented: $showsInsights) {
                NavigationStack {
                    InsightsView()
                        .inlineTitle()
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { showsInsights = false }
                                    .accessibilityIdentifier("sheet.done")
                            }
                        }
                }
                .macSheetFrame(width: 640, height: 720)
            }
    }

    @ViewBuilder
    private var content: some View {
        let groups = model.cellar.groups(filter: filter, sort: sort, year: model.currentYear)
        if model.cellar.bottlesInCellar.isEmpty && model.cellar.bottlesOnOrder.isEmpty {
            EmptyCellarView()
        } else {
            ScrollViewReader { proxy in
            List(selection: $selection) {
                if filter.query.isEmpty {
                    Section {
                        CellarSummary(filter: $filter, onBottles: { showsInsights = true }, onWines: {
                            withAnimation(.snappy) {
                                filter = CellarFilter()
                                if let first = model.cellar.groups(filter: CellarFilter(), sort: sort, year: model.currentYear).first {
                                    proxy.scrollTo(first.id, anchor: .top)
                                }
                            }
                        })
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                            .listRowBackground(Color.clear)
                    }
                    if !filter.isActive {
                        OrdersSection()
                    }
                }
                Section {
                    if groups.isEmpty, filter.isActive || !filter.query.isEmpty {
                        ContentUnavailableView.search(text: filter.query)
                            .listRowBackground(Color.clear)
                    }
                    ForEach(groups) { group in
                        WineRow(group: group)
                            .accessibilityIdentifier("wine.row")
                            .tag(group.wine.id)
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                if let bottle = group.bottles.bottleToOpen {
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
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            if isWide {
                CellarLayoutPicker()
            }
            Menu {
                Button("Select Wines", systemImage: "checkmark.circle") {
                    picked = []
                    withAnimation { isSelecting = true }
                }
                .accessibilityIdentifier("cellar.select")
                Divider()
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
                if filter.isActive || !filter.query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button("Save This Search…", systemImage: "bookmark") {
                        searchName = filter.query.trimmingCharacters(in: .whitespaces)
                        namingSearch = true
                    }
                }
            } label: {
                Label("Sort", systemImage: filter.isActive ? "line.3.horizontal.decrease.circle.fill" : "arrow.up.arrow.down")
            }
            .accessibilityIdentifier("cellar.sort")
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

extension CellarList {
    @ToolbarContentBuilder
    var selectionToolbar: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Done") { endSelection() }
                .accessibilityIdentifier("cellar.select.done")
        }
        ToolbarItemGroup(placement: Self.actionsPlacement) {
            Menu {
                ForEach(model.cellar.racks) { rack in
                    Button(rack.name) {
                        model.place(picked, inRack: rack.id)
                        endSelection()
                    }
                }
            } label: {
                Label("Place in a Rack", systemImage: "square.grid.3x3")
            }
            .disabled(picked.isEmpty || model.cellar.racks.isEmpty)
            Button {
                model.unplace(picked)
                endSelection()
            } label: {
                Label("Take Out of Racks", systemImage: "tray.and.arrow.up")
            }
            .disabled(picked.isEmpty)
            Button(role: .destructive) {
                confirmingDelete = true
            } label: {
                Label("Delete", systemImage: "trash")
            }
            .disabled(picked.isEmpty)
        }
    }

    #if os(iOS)
    static let actionsPlacement = ToolbarItemPlacement.bottomBar
    #else
    static let actionsPlacement = ToolbarItemPlacement.primaryAction
    #endif

    func endSelection() {
        withAnimation { isSelecting = false }
        picked = []
    }
}

/// The cellar's wines with check boxes, to act on several at once.
private struct CellarSelectionList: View {
    let groups: [WineGroup]
    @Binding var picked: Set<UUID>

    var body: some View {
        List(groups, selection: $picked) { group in
            WineRow(group: group)
                .tag(group.wine.id)
                .accessibilityIdentifier("wine.select.row")
        }
        #if os(iOS)
        .environment(\.editMode, .constant(.active))
        #endif
    }
}

/// Quick figures and filter chips at the top of the cellar.
private struct CellarSummary: View {
    @Environment(CellarModel.self) private var model
    @Binding var filter: CellarFilter
    /// Bottles: the cellar in figures.
    var onBottles: () -> Void
    /// Wines: the whole list, without filters.
    var onWines: () -> Void

    var body: some View {
        let stats = model.cellar.statistics(year: model.currentYear)
        let soonOnly = filter.maturities == [.drinkSoon] && filter.colors.isEmpty && filter.rackID == nil && filter.tag == nil
            && filter.reservation == nil
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Button(action: onBottles) {
                    StatTile(value: "\(stats.bottleCount)", caption: "bottles", systemImage: "wineglass", opens: true)
                }
                .accessibilityHint(Text("Shows your cellar in figures."))
                .accessibilityIdentifier("cellar.stat.bottles")
                Button(action: onWines) {
                    StatTile(value: "\(stats.wineCount)", caption: "wines", systemImage: "list.bullet")
                }
                .accessibilityHint(Text("Shows all your wines."))
                .accessibilityIdentifier("cellar.stat.wines")
                Button {
                    withAnimation(.snappy) {
                        filter = soonOnly ? CellarFilter(query: filter.query) : CellarFilter(query: filter.query, maturities: [.drinkSoon])
                    }
                } label: {
                    StatTile(value: "\(stats.byMaturity[.drinkSoon, default: 0])", caption: "to drink soon",
                             systemImage: "hourglass", isSelected: soonOnly)
                }
                .accessibilityHint(Text("Shows only the wines to drink soon."))
                .accessibilityIdentifier("cellar.stat.soon")
            }
            .buttonStyle(.plain)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    let saved = model.cellar.settings.savedSearches ?? []
                    ForEach(saved) { search in
                        let isOn = filter == search.filter
                        ChoiceChip(verbatim: search.name, isSelected: isOn) {
                            withAnimation(.snappy) { filter = isOn ? CellarFilter() : search.filter }
                        } leading: {
                            Image(systemName: isOn ? "bookmark.fill" : "bookmark")
                        }
                        .contextMenu {
                            Button("Delete Search", systemImage: "trash", role: .destructive) {
                                model.update { $0.deleteSearch(search.id) }
                            }
                        }
                        .accessibilityHint(Text("Applies this saved search."))
                        .accessibilityAction(named: Text("Delete Search")) {
                            model.update { $0.deleteSearch(search.id) }
                        }
                    }
                    // Only the styles and maturities of bottles in the cellar get a chip; one
                    // no bottle has left stays while it filters the list.
                    let present = Set(stats.byColor.compactMap(\.color))
                    let colors = WineColor.allCases.filter { present.contains($0) || filter.colors.contains($0) }
                    let maturities = [Maturity.drinkSoon, .ready, .young, .pastPeak].filter {
                        stats.byMaturity[$0, default: 0] > 0 || filter.maturities.contains($0)
                    }
                    if !saved.isEmpty, !colors.isEmpty || !maturities.isEmpty { Divider().frame(height: 24) }
                    ForEach(colors, id: \.self) { color in
                        ChoiceChip(title: color.title, isSelected: filter.colors.contains(color)) {
                            toggle(color, in: \.colors)
                        } leading: {
                            BottleEnd(color: color, size: 14)
                        }
                    }
                    if !colors.isEmpty, !maturities.isEmpty { Divider().frame(height: 24) }
                    ForEach(maturities, id: \.self) { maturity in
                        ChoiceChip(title: maturity.shortTitle, isSelected: filter.maturities.contains(maturity)) {
                            toggle(maturity, in: \.maturities)
                        } leading: {
                            Image(systemName: maturity.systemImage).foregroundStyle(maturity.tint)
                        }
                    }
                    let reservations = model.cellar.reservations
                    if !reservations.isEmpty {
                        Divider().frame(height: 24)
                        ForEach(reservations, id: \.self) { name in
                            ChoiceChip(verbatim: name, isSelected: filter.reservation == name) {
                                withAnimation(.snappy) { filter.reservation = filter.reservation == name ? nil : name }
                            } leading: {
                                Image(systemName: "gift")
                            }
                            .accessibilityHint(Text("Shows the bottles kept for this occasion."))
                        }
                    }
                    let tags = model.cellar.wineTags
                    if !tags.isEmpty {
                        Divider().frame(height: 24)
                        ForEach(tags, id: \.self) { tag in
                            ChoiceChip(verbatim: tag, isSelected: filter.tag == tag) {
                                withAnimation(.snappy) { filter.tag = filter.tag == tag ? nil : tag }
                            } leading: {
                                Image(systemName: "tag")
                            }
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
        if let bottle = group.bottles.bottleToOpen {
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

/// Bottles bought but not delivered yet, the soonest first. Swipe or use the
/// menu to mark them received; they then join the cellar without a place.
struct OrdersSection: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        let orders = model.cellar.orderGroups()
        if !orders.isEmpty {
            Section {
                ForEach(orders) { group in
                    WineRow(group: group)
                        .tag(group.wine.id)
                        // Its own scroll identity: the same wine may also have a row below.
                        .id("order-\(group.wine.id)")
                        .accessibilityIdentifier("order.row")
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button {
                                model.receive(group.bottles.map(\.id))
                            } label: {
                                Label("Received", systemImage: "shippingbox")
                            }
                            .tint(.accentColor)
                        }
                        .contextMenu {
                            Button("Mark Received", systemImage: "shippingbox") {
                                model.receive(group.bottles.map(\.id))
                            }
                        }
                        .accessibilityAction(named: Text("Mark Received")) {
                            model.receive(group.bottles.map(\.id))
                        }
                }
            } header: {
                Text("On order")
            }
        }
    }
}

/// First launch: explain the app and offer the sample cellar.
struct EmptyCellarView: View {
    @Environment(CellarModel.self) private var model
    @State private var confirmingSample = false

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
            // A column on every platform: on the Mac, actions are laid out in
            // a row and would be cut off in the narrow content column.
            VStack(spacing: 12) {
                Button {
                    model.sheet = .addBottle(AddBottleContext())
                } label: {
                    Text("Add a Bottle").frame(minWidth: 200)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                Button("Remember a wine tasted elsewhere") { model.sheet = .discovery(discoveryID: nil) }
                // Only for a cellar with nothing at all in it, that no other device syncs with:
                // the sample replaces everything.
                if model.canLoadSample {
                    Button("Load sample cellar") { confirmingSample = true }
                }
            }
        }
        .confirmationDialog("Load the sample cellar?", isPresented: $confirmingSample, titleVisibility: .visible) {
            Button("Load sample cellar") { model.loadSample() }
        } message: {
            Text("Example wines, racks and tastings to try Encave. Erase them later with Erase all data.")
        }
    }
}

#Preview {
    CellarView().environment(CellarModel.preview())
}
