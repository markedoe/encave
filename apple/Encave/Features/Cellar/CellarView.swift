import CaveKit
import SwiftUI

/// iPhone and iPad: the list, and the selected wine beside it (or pushed on iPhone).
/// On the Mac, the window's own columns show `CellarList` and `WinePane` directly.
struct CellarView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @State private var selection: UUID?
    /// iPad: show the list next to the wine, also in portrait, instead of an empty page.
    @State private var columns: NavigationSplitViewVisibility = .all
    private var layout = CellarLayout.shared
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var isWide: Bool { sizeClass == .regular }
    #else
    private let isWide = true
    #endif

    var body: some View {
        Group {
            if layout.showsTable && isWide {
                NavigationStack { CellarTable(selection: $selection) }
            } else {
                NavigationSplitView(columnVisibility: $columns) {
                    CellarList(selection: $selection)
                        // Wide enough on iPad for names, producers and the figures.
                        .navigationSplitViewColumnWidth(min: 320, ideal: 380, max: 460)
                } detail: {
                    // A new stack for each wine: what was opened from the last one's details goes.
                    NavigationStack { WinePane(wineID: selection) }
                        .id(selection)
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
        .onChange(of: router?.openWineRequest, initial: true) { _, id in
            guard let id else { return }
            selection = id
            router?.openWineRequest = nil
        }
        // The Wine menu (iPad with a keyboard) acts on the selected wine.
        .focusedSceneValue(\.selectedWineID, selection)
        // Deleted: back to the list on iPhone, never an empty page.
        .replacingDeletedWine($selection, selectsFirst: isWide)
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
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @Binding var selection: UUID?
    @State private var showsInsights = false
    /// Selecting several wines for one action.
    @State private var isSelecting = false
    @State private var picked: Set<UUID> = []
    @State private var confirmingDelete = false
    @State private var reservingPicked = false
    @State private var occasion = ""
    @State private var namingSearch = false
    @State private var searchName = ""
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var isWide: Bool { sizeClass == .regular }
    #else
    private let isWide = true
    #endif

    /// Shared with the table, so switching between them keeps the search and the filters.
    private var filter: CellarFilter {
        get { model.cellarFilter }
        nonmutating set { model.cellarFilter = newValue }
    }
    private var filterBinding: Binding<CellarFilter> {
        Binding(get: { model.cellarFilter }, set: { model.cellarFilter = $0 })
    }
    private var sortBinding: Binding<CellarSort> {
        Binding(get: { model.cellarSort }, set: { model.cellarSort = $0 })
    }

    var body: some View {
        let groups = model.cellar.groups(filter: filter, sort: model.cellarSort, year: model.currentYear)
        Group {
            if isSelecting {
                CellarSelectionList(groups: groups, picked: $picked)
            } else {
                content(groups)
            }
        }
            .navigationTitle(isSelecting ? Text("Selected: \(picked.count)") : Text("Cellar"))
            .searchable(text: filterBinding.query, prompt: Text("Wine, producer, region, grape, vintage"))
            .toolbar {
                if isSelecting { selectionToolbar(groups) } else { toolbar }
            }
            .alert("Save This Search", isPresented: $namingSearch) {
                TextField("Name", text: $searchName)
                Button("Save") { model.update { $0.saveSearch(searchName, filter: model.cellarFilter) } }
                    .disabled(searchName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("It appears first among the filters, one tap away.")
            }
            .alert("Keep for an Occasion", isPresented: $reservingPicked) {
                TextField("Occasion", text: $occasion)
                Button("Keep Bottles") {
                    model.reserve(pickedBottles(groups).map(\.id), for: occasion)
                    endSelection()
                }
                .disabled(occasion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The bottles stay in their places but are no longer suggested for tonight.")
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
    private func content(_ groups: [WineGroup]) -> some View {
        let searching = !filter.query.trimmingCharacters(in: .whitespaces).isEmpty
        if model.bottlesInCellar.isEmpty && model.cellar.bottlesOnOrder.isEmpty {
            EmptyCellarView()
        } else {
            ScrollViewReader { proxy in
            List(selection: $selection) {
                if model.showsSampleBanner {
                    Section {
                        SampleBanner()
                    }
                }
                // The figures while browsing; the chips also while searching, so active
                // filters stay in sight and can be turned off.
                Section {
                    if !searching {
                        CellarStats(filter: filterBinding, onBottles: { showsInsights = true }, onWines: {
                            withAnimation(.snappy) {
                                filter = CellarFilter()
                                if let first = model.cellar.groups(filter: CellarFilter(), sort: model.cellarSort, year: model.currentYear).first {
                                    proxy.scrollTo(first.id, anchor: .top)
                                }
                            }
                        })
                    }
                    CellarFilterChips(filter: filterBinding)
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
                if !searching, !filter.isActive {
                    OrdersSection()
                }
                Section {
                    if groups.isEmpty, filter.isActive {
                        ContentUnavailableView {
                            Label("No matching bottles", systemImage: "line.3.horizontal.decrease.circle")
                        } description: {
                            Text("Try removing a filter.")
                        } actions: {
                            Button("Clear Filters") {
                                withAnimation(.snappy) { filter = CellarFilter(query: filter.query) }
                            }
                            .accessibilityIdentifier("cellar.clearFilters")
                        }
                        .listRowBackground(Color.clear)
                    } else if groups.isEmpty, searching {
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
                                        router?.sheet = .drink(bottleID: bottle.id)
                                    } label: {
                                        Label("Drink", systemImage: "wineglass")
                                    }
                                    .tint(.accentColor)
                                }
                            }
                            .swipeActions(edge: .trailing) {
                                Button {
                                    router?.sheet = .addBottle(AddBottleContext(wine: group.wine))
                                } label: {
                                    Label("Add", systemImage: "plus")
                                }
                                .tint(.blue)
                            }
                            .contextMenu { WineContextMenu(group: group, onSelect: { startSelection(with: group.wine.id) }) }
                    }
                } header: {
                    if filter.isActive || searching {
                        Text("\(groups.reduce(0) { $0 + $1.count }) bottles")
                    }
                }
            }
            // Rows slide into place when sorting or filtering, not at each letter typed.
            .animation(searching ? nil : .default, value: groups.map(\.id))
            #if os(macOS)
            // The Delete key, like in Finder; Undo puts it back.
            .onDeleteCommand {
                if let selection { model.deleteWine(selection) }
            }
            #endif
            .modifier(SyncRefresh())
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            if isWide {
                CellarLayoutPicker()
            }
            // Like Mail and Files: choose several wines to act on them at once.
            Button("Select") { startSelection() }
                .accessibilityIdentifier("cellar.select")
            Menu {
                Picker("Sort by", selection: sortBinding) {
                    Text("Name").tag(CellarSort.name)
                    Text("Vintage").tag(CellarSort.vintage)
                    Text("Ready first").tag(CellarSort.readiness)
                    Text("Region").tag(CellarSort.region)
                    Text("Recently added").tag(CellarSort.recentlyAdded)
                }
                if filter.isActive {
                    Button("Clear Filters", systemImage: "line.3.horizontal.decrease.circle") {
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
                Label("Sort and Filter", systemImage: filter.isActive ? "line.3.horizontal.decrease.circle.fill" : "arrow.up.arrow.down")
            }
            .accessibilityIdentifier("cellar.sort")
            Menu {
                #if os(iOS)
                Button("Scan a Label", systemImage: "camera.viewfinder") { router?.sheet = .addBottle(AddBottleContext(scan: true)) }
                #endif
                Button("Add Bottles", systemImage: "plus") { router?.sheet = .addBottle(AddBottleContext()) }
                Button("Note a Wine Tasted Elsewhere", systemImage: "mappin.and.ellipse") { router?.sheet = .discovery(discoveryID: nil) }
            } label: {
                Label("Add Bottle", systemImage: "plus")
            } primaryAction: {
                // On iPhone and iPad, the label scanner first (Cancel leaves the form to type).
                router?.sheet = .addBottle(AddBottleContext(scan: true))
            }
            .accessibilityIdentifier("cellar.add")
        }
        #if os(iOS)
        SettingsToolbarItem(identifier: "cellar.settings")
        #endif
    }
}

extension CellarList {
    @ToolbarContentBuilder
    func selectionToolbar(_ groups: [WineGroup]) -> some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Done") { endSelection() }
                .accessibilityIdentifier("cellar.select.done")
        }
        ToolbarItem(placement: Self.selectAllPlacement) {
            let all = Set(groups.map(\.wine.id))
            let allPicked = !all.isEmpty && all.isSubset(of: picked)
            Button(allPicked ? "Deselect All" : "Select All") {
                picked = allPicked ? [] : all
            }
            .disabled(all.isEmpty)
            .accessibilityIdentifier("cellar.select.all")
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
            Menu {
                Button("Keep for an Occasion…", systemImage: "gift") {
                    occasion = model.cellar.reservations.first ?? ""
                    reservingPicked = true
                }
                Button(role: .destructive) {
                    model.markGone(Set(pickedBottles(groups).map(\.id)))
                    endSelection()
                } label: {
                    Label {
                        Text("No Longer in Cellar")
                        Text("Given away, sold or broken")
                    } icon: {
                        Image(systemName: "minus.circle")
                    }
                }
                Divider()
                Button("Delete Wines…", systemImage: "trash", role: .destructive) {
                    confirmingDelete = true
                }
                .accessibilityIdentifier("cellar.select.delete")
            } label: {
                Label("More", systemImage: "ellipsis.circle")
            }
            .disabled(picked.isEmpty)
            .accessibilityIdentifier("cellar.select.more")
        }
    }

    #if os(iOS)
    static let actionsPlacement = ToolbarItemPlacement.bottomBar
    static let selectAllPlacement = ToolbarItemPlacement.topBarLeading
    #else
    static let actionsPlacement = ToolbarItemPlacement.primaryAction
    static let selectAllPlacement = ToolbarItemPlacement.navigation
    #endif

    /// The bottles shown for the picked wines (only those the filters keep).
    func pickedBottles(_ groups: [WineGroup]) -> [Bottle] {
        groups.filter { picked.contains($0.wine.id) }.flatMap(\.bottles)
    }

    /// Choosing several wines, from the Select button or a wine's menu (already ticked).
    func startSelection(with wineID: UUID? = nil) {
        picked = wineID.map { [$0] } ?? []
        withAnimation { isSelecting = true }
    }

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

/// Quick figures at the top of the cellar.
private struct CellarStats: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var filter: CellarFilter
    /// Bottles: the cellar in figures.
    var onBottles: () -> Void
    /// Wines: the whole list, without filters.
    var onWines: () -> Void

    var body: some View {
        let stats = model.overview.statistics
        let soonOnly = filter.maturities == [.drinkSoon] && filter.colors.isEmpty && filter.rackID == nil && filter.tag == nil
            && filter.reservation == nil && filter.grape == nil
        // Side by side, or one above the other at the accessibility text sizes so captions fit.
        let tiles = dynamicTypeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 10)) : AnyLayout(HStackLayout(spacing: 10))
        tiles {
            Button(action: onBottles) {
                StatTile(count: stats.bottleCount, caption: .count("bottles", stats.bottleCount), systemImage: "wineglass", opens: true)
            }
            .accessibilityHint(Text("Shows your cellar in figures."))
            .accessibilityIdentifier("cellar.stat.bottles")
            Button(action: onWines) {
                StatTile(count: stats.wineCount, caption: .count("wines", stats.wineCount), systemImage: "list.bullet")
            }
            .accessibilityHint(Text("Shows all your wines."))
            .accessibilityIdentifier("cellar.stat.wines")
            Button {
                withAnimation(.snappy) {
                    filter = soonOnly ? CellarFilter(query: filter.query) : CellarFilter(query: filter.query, maturities: [.drinkSoon])
                }
            } label: {
                StatTile(value: stats.byMaturity[.drinkSoon, default: 0].formatted(), caption: "to drink soon",
                         systemImage: "hourglass", isSelected: soonOnly)
            }
            .accessibilityHint(Text("Shows only the wines to drink soon."))
            .accessibilityIdentifier("cellar.stat.soon")
        }
        .buttonStyle(.plain)
    }
}

/// What the filter chips and the table's filter menu offer: only what the bottles in the
/// cellar have, plus any value that no bottle has left while it filters the list.
struct CellarFilterOptions {
    var colors: [WineColor]
    var maturities: [Maturity]
    var grapes: [String]
    var reservations: [String]
    var tags: [String]

    @MainActor
    init(model: CellarModel, filter: CellarFilter) {
        let overview = model.overview
        let stats = overview.statistics
        let present = Set(stats.byColor.compactMap(\.color))
        colors = WineColor.allCases.filter { present.contains($0) || filter.colors.contains($0) }
        maturities = [Maturity.drinkSoon, .ready, .young, .pastPeak].filter {
            stats.byMaturity[$0, default: 0] > 0 || filter.maturities.contains($0)
        }
        grapes = Self.keeping(filter.grape, in: overview.grapes)
        reservations = Self.keeping(filter.reservation, in: overview.reservations)
        tags = Self.keeping(filter.tag, in: overview.tags)
    }

    var isEmpty: Bool { colors.isEmpty && maturities.isEmpty && grapes.isEmpty && reservations.isEmpty && tags.isEmpty }

    /// The values, with the selected one added when no bottle has it any more.
    private static func keeping(_ selected: String?, in values: [String]) -> [String] {
        guard let selected, !values.contains(where: { $0.caseInsensitiveCompare(selected) == .orderedSame }) else { return values }
        return values + [selected]
    }
}

/// The filter chips: saved searches, styles, maturities, grapes, occasions and tags.
private struct CellarFilterChips: View {
    @Environment(CellarModel.self) private var model
    @Binding var filter: CellarFilter

    var body: some View {
        let saved = model.cellar.settings.savedSearches ?? []
        let options = CellarFilterOptions(model: model, filter: filter)
        if !saved.isEmpty || !options.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
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
                    if !saved.isEmpty, !options.colors.isEmpty || !options.maturities.isEmpty { Divider().frame(height: 24) }
                    ForEach(options.colors, id: \.self) { color in
                        ChoiceChip(title: color.title, isSelected: filter.colors.contains(color)) {
                            toggle(color, in: \.colors)
                        } leading: {
                            BottleEnd(color: color, size: 14)
                        }
                    }
                    if !options.colors.isEmpty, !options.maturities.isEmpty { Divider().frame(height: 24) }
                    ForEach(options.maturities, id: \.self) { maturity in
                        ChoiceChip(title: maturity.shortTitle, isSelected: filter.maturities.contains(maturity)) {
                            toggle(maturity, in: \.maturities)
                        } leading: {
                            Image(systemName: maturity.systemImage).foregroundStyle(maturity.tint)
                        }
                    }
                    if !options.grapes.isEmpty {
                        Divider().frame(height: 24)
                        ForEach(options.grapes, id: \.self) { grape in
                            let isOn = filter.grape?.caseInsensitiveCompare(grape) == .orderedSame
                            ChoiceChip(verbatim: grape, isSelected: isOn) {
                                withAnimation(.snappy) { filter.grape = isOn ? nil : grape }
                            } leading: {
                                Image(systemName: "laurel.leading")
                            }
                            .accessibilityHint(Text("Shows the wines made with this grape."))
                        }
                    }
                    if !options.reservations.isEmpty {
                        Divider().frame(height: 24)
                        ForEach(options.reservations, id: \.self) { name in
                            let isOn = filter.reservation?.caseInsensitiveCompare(name) == .orderedSame
                            ChoiceChip(verbatim: name, isSelected: isOn) {
                                withAnimation(.snappy) { filter.reservation = isOn ? nil : name }
                            } leading: {
                                Image(systemName: "gift")
                            }
                            .accessibilityHint(Text("Shows the bottles kept for this occasion."))
                        }
                    }
                    if !options.tags.isEmpty {
                        Divider().frame(height: 24)
                        ForEach(options.tags, id: \.self) { tag in
                            let isOn = filter.tag?.caseInsensitiveCompare(tag) == .orderedSame
                            ChoiceChip(verbatim: tag, isSelected: isOn) {
                                withAnimation(.snappy) { filter.tag = isOn ? nil : tag }
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

extension View {
    /// The selected wine was deleted (here, or on another device): on a wide layout the
    /// first wine of the list takes its place; otherwise the list comes back.
    func replacingDeletedWine(_ selection: Binding<UUID?>, selectsFirst: Bool) -> some View {
        modifier(DeletedWineSelection(selection: selection, selectsFirst: selectsFirst))
    }
}

private struct DeletedWineSelection: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Binding var selection: UUID?
    let selectsFirst: Bool

    func body(content: Content) -> some View {
        content.onChange(of: selection.map { model.cellar.wines[$0] == nil } ?? false) { _, gone in
            guard gone else { return }
            selection = selectsFirst
                ? model.cellar.groups(filter: model.cellarFilter, sort: model.cellarSort, year: model.currentYear).first?.wine.id
                : nil
        }
    }
}

/// Long-press menu shared by the cellar, tonight and pairing lists.
struct WineContextMenu: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let group: WineGroup
    /// The Cellar list: choose several wines, starting with this one.
    var onSelect: (() -> Void)?

    var body: some View {
        if let bottle = group.bottles.bottleToOpen {
            Button("Drink a Bottle", systemImage: "wineglass") { router?.sheet = .drink(bottleID: bottle.id) }
            if bottle.slot != nil {
                Button("Show in Rack", systemImage: "square.grid.3x3") { router?.showInRack(bottle, model: model) }
            }
            Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") {
                router?.sheet = .moveBottle(bottleID: bottle.id)
            }
        }
        Button("Add Bottles", systemImage: "plus") { router?.sheet = .addBottle(AddBottleContext(wine: group.wine)) }
        Button("Edit Wine", systemImage: "pencil") { router?.sheet = .editWine(wineID: group.wine.id) }
        if let onSelect {
            Button("Select", systemImage: "checkmark.circle", action: onSelect)
        }
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

/// Shown while records of the sample cellar remain: removes them, and only them, with Undo.
struct SampleBanner: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        HStack(spacing: 12) {
            Label("You are exploring the sample cellar", systemImage: "testtube.2")
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Remove Sample") { model.removeSample() }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .accessibilityIdentifier("cellar.removeSample")
        }
        .accessibilityElement(children: .contain)
    }
}

/// First launch: explain the app, offer to join the cellar of another device and the sample cellar.
struct EmptyCellarView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
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
                    router?.sheet = .addBottle(AddBottleContext())
                } label: {
                    Text("Add a Bottle").frame(minWidth: 200)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("empty.add")
                // Already using Encave on another device: bring its cellar here.
                if model.sync.otherDevices.isEmpty {
                    Button {
                        router?.sheet = .joinDevice(code: "", commitment: nil)
                    } label: {
                        Text("Join a Cellar from Another Device").frame(minWidth: 200)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .accessibilityIdentifier("empty.join")
                }
                // Only for a cellar with nothing at all in it, that no other device syncs with:
                // the sample replaces everything.
                if model.canLoadSample {
                    Button("Load sample cellar") { confirmingSample = true }
                        .accessibilityIdentifier("empty.sample")
                }
                // Every sample bottle was drunk: what remains of it can still go.
                if model.showsSampleBanner {
                    Button("Remove Sample") { model.removeSample() }
                }
                Button("Note a wine tasted elsewhere") { router?.sheet = .discovery(discoveryID: nil) }
                    .buttonStyle(.borderless)
            }
        }
        .confirmationDialog("Load the sample cellar?", isPresented: $confirmingSample, titleVisibility: .visible) {
            Button("Load sample cellar") { model.loadSample() }
        } message: {
            Text("Example wines, racks and tastings to try Encave. Remove them at any time with Remove Sample at the top of the Cellar.")
        }
    }
}

#Preview {
    CellarView().environment(CellarModel.preview())
}

/// Pull down to sync with the other devices, when there are any.
private struct SyncRefresh: ViewModifier {
    @Environment(CellarModel.self) private var model

    func body(content: Content) -> some View {
        if model.sync.otherDevices.isEmpty || model.sync.isDemo {
            content
        } else {
            content.refreshable { await model.sync.syncEverywhere() }
        }
    }
}
