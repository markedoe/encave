import CaveKit
import SwiftUI

/// Mac and iPad: show the cellar as a list or as a sortable table. Remembered.
enum CellarLayout {
    static let key = "cellar.table"
}

/// The cellar as a sortable table, one row per wine, with the selected wine in an
/// inspector. For large cellars on the Mac and the iPad, like a spreadsheet.
struct CellarTable: View {
    @Environment(CellarModel.self) private var model
    /// The wine in the inspector: the selected row when only one is selected.
    @Binding var selection: UUID?
    /// The selected rows (⌘-click and ⇧-click select several).
    @State private var picked: Set<UUID> = []
    @State private var confirmingDelete: Set<UUID> = []
    @State private var sortOrder = [KeyPathComparator(\CellarTableRow.name)]
    @State private var showsInspector = true
    /// Columns shown, their order and widths, remembered per window.
    @SceneStorage("cellar.table.columns") private var columns = TableColumnCustomization<CellarTableRow>()

    /// Shared with the list, so switching between them keeps the search and the filters.
    private var filter: Binding<CellarFilter> {
        Binding(get: { model.cellarFilter }, set: { model.cellarFilter = $0 })
    }

    var body: some View {
        let rows = model.cellar.groups(filter: model.cellarFilter, sort: .name, year: model.currentYear)
            .map { CellarTableRow(group: $0, model: model) }
            .sorted(using: sortOrder)
        table(rows)
            .onChange(of: picked) { _, ids in
                selection = ids.count == 1 ? ids.first : nil
            }
            .onChange(of: selection, initial: true) { _, id in
                // Opened from elsewhere (Spotlight, a notification), or replaced after a deletion.
                if let id, picked != [id] { picked = [id] }
            }
            .modifier(TableCommands(picked: $picked, confirmingDelete: $confirmingDelete))
            .overlay { emptyState(rows.isEmpty) }
            .searchable(text: filter.query, prompt: Text("Wine, producer, region, grape, vintage"))
            .navigationTitle("Cellar")
            .inspector(isPresented: $showsInspector) {
                // Its own stack, new for each wine: what is opened from the wine's details
                // stays in the inspector instead of covering the table.
                NavigationStack { WinePane(wineID: selection) }
                    .id(selection)
                    .inspectorColumnWidth(min: 300, ideal: 360, max: 480)
            }
            .toolbar { toolbar }
    }

    private func table(_ rows: [CellarTableRow]) -> some View {
        Table(rows, selection: $picked, sortOrder: $sortOrder, columnCustomization: $columns) {
            TableColumn("Wine", value: \.name) { row in
                HStack(spacing: 8) {
                    BottleEnd(color: row.wine.color, size: 18)
                    Text(row.name).fontWeight(.medium)
                }
                .accessibilityElement(children: .combine)
            }
            .width(min: 160, ideal: 220)
            .customizationID("wine")
            .disabledCustomizationBehavior(.visibility)
            TableColumn("Vintage", value: \.vintage) { row in
                if let vintage = row.wine.vintage {
                    Text(verbatim: "\(vintage)").monospacedDigit()
                } else {
                    Text("Non-vintage").foregroundStyle(.secondary)
                }
            }
            .width(min: 60, ideal: 80)
            .customizationID("vintage")
            TableColumn("Producer", value: \.producer)
                .width(min: 100, ideal: 160)
                .customizationID("producer")
            TableColumn("Style", value: \.styleOrder) { row in
                if let color = row.wine.color { Text(color.title) }
            }
            .width(min: 60, ideal: 90)
            .customizationID("style")
            TableColumn("Region", value: \.region)
                .width(min: 90, ideal: 140)
                .customizationID("region")
            TableColumn("When to drink", value: \.maturity) { row in
                if row.maturity != .unknown { MaturityLabel(maturity: row.maturity) }
            }
            .width(min: 110, ideal: 130)
            .customizationID("maturity")
            TableColumn("Bottles", value: \.count) { row in
                Text(verbatim: "\(row.count)").monospacedDigit()
            }
            .width(min: 50, ideal: 60)
            .customizationID("bottles")
            TableColumn("Location", value: \.location) { row in
                Text(row.location).foregroundStyle(.secondary)
            }
            .width(min: 120, ideal: 200)
            .customizationID("location")
            // Hidden at first; shown from the column header's menu.
            TableColumn("Tags", value: \.tags) { row in
                Text(verbatim: row.tags).foregroundStyle(.secondary)
            }
            .width(min: 80, ideal: 120)
            .customizationID("tags")
            .defaultVisibility(.hidden)
            TableColumn("Purchase value", value: \.value) { row in
                Text(verbatim: row.valueText).monospacedDigit()
            }
            .width(min: 70, ideal: 90)
            .customizationID("value")
            .defaultVisibility(.hidden)
        }
        .contextMenu(forSelectionType: UUID.self) { ids in
            menu(for: ids, rows: rows)
        } primaryAction: { ids in
            if let id = ids.first {
                picked = [id]
                selection = id
                showsInspector = true
            }
        }
    }

    @ViewBuilder
    private func menu(for ids: Set<UUID>, rows: [CellarTableRow]) -> some View {
        if ids.count > 1 {
            SeveralWinesMenu(wineIDs: ids) { confirmingDelete = ids }
        } else if let row = rows.first(where: { ids.contains($0.id) }) {
            WineContextMenu(group: row.group)
        }
    }

    @ViewBuilder
    private func emptyState(_ isEmpty: Bool) -> some View {
        if isEmpty {
            if model.cellarFilter.isActive {
                ContentUnavailableView {
                    Label("No matching bottles", systemImage: "line.3.horizontal.decrease.circle")
                } description: {
                    Text("Try removing a filter.")
                } actions: {
                    Button("Clear Filters") { model.cellarFilter = CellarFilter(query: model.cellarFilter.query) }
                }
            } else if model.cellarFilter.query.trimmingCharacters(in: .whitespaces).isEmpty {
                ContentUnavailableView("No bottles", systemImage: "wineglass")
            } else {
                ContentUnavailableView.search(text: model.cellarFilter.query)
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            CellarLayoutPicker()
            CellarFilterMenu(filter: filter)
            Button {
                model.sheet = .addBottle(AddBottleContext())
            } label: {
                Label("Add Bottle", systemImage: "plus")
            }
            Button {
                showsInspector.toggle()
            } label: {
                Label("Show Wine", systemImage: "sidebar.trailing")
            }
            .help(Text("Show or hide the selected wine"))
        }
        #if os(iOS)
        // iPad: Settings is reached from the Cellar, list or table.
        ToolbarItem(placement: .topBarLeading) {
            Button {
                model.sheet = .settings
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
        }
        #endif
    }
}

/// Deleting the selected rows: the Delete key on the Mac, like the context menu, deletes
/// one wine at once (with Undo) and several after a confirmation.
private struct TableCommands: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Binding var picked: Set<UUID>
    @Binding var confirmingDelete: Set<UUID>

    func body(content: Content) -> some View {
        content
            #if os(macOS)
            .onDeleteCommand {
                if picked.count == 1, let id = picked.first {
                    model.deleteWine(id)
                } else if picked.count > 1 {
                    confirmingDelete = picked
                }
            }
            #endif
            .confirmationDialog("Delete these wines?", isPresented: Binding(
                get: { !confirmingDelete.isEmpty }, set: { if !$0 { confirmingDelete = [] } }
            ), titleVisibility: .visible) {
                Button("Delete Wines", role: .destructive) {
                    model.deleteWines(confirmingDelete)
                    picked = []
                    confirmingDelete = []
                }
            } message: {
                Text("Their bottles and tastings are deleted too. You can undo right after.")
            }
    }
}

/// The filters of the cellar list, as a menu: the table has no room for the chips.
private struct CellarFilterMenu: View {
    @Environment(CellarModel.self) private var model
    @Binding var filter: CellarFilter

    var body: some View {
        let options = CellarFilterOptions(model: model, filter: filter)
        let saved = model.cellar.settings.savedSearches ?? []
        Menu {
            if !saved.isEmpty {
                Section("Saved searches") {
                    ForEach(saved) { search in
                        Toggle(isOn: Binding(get: { filter == search.filter },
                                             set: { filter = $0 ? search.filter : CellarFilter() })) {
                            Text(verbatim: search.name)
                        }
                    }
                }
            }
            if !options.colors.isEmpty {
                Section("Style") {
                    ForEach(options.colors, id: \.self) { color in
                        Toggle(isOn: contains(color, in: \.colors)) { Text(color.title) }
                    }
                }
            }
            if !options.maturities.isEmpty {
                Section("When to drink") {
                    ForEach(options.maturities, id: \.self) { maturity in
                        Toggle(isOn: contains(maturity, in: \.maturities)) {
                            Label(maturity.shortTitle, systemImage: maturity.systemImage)
                        }
                    }
                }
            }
            choice("Grape", systemImage: "laurel.leading", values: options.grapes, selected: \.grape)
            choice("Occasion", systemImage: "gift", values: options.reservations, selected: \.reservation)
            choice("Tag", systemImage: "tag", values: options.tags, selected: \.tag)
            if filter.isActive {
                Divider()
                Button("Clear Filters", systemImage: "xmark.circle") { filter = CellarFilter(query: filter.query) }
            }
        } label: {
            Label("Filter", systemImage: filter.isActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
        .help(Text("Show only some wines"))
        .accessibilityIdentifier("table.filter")
    }

    private func contains<T: Hashable>(_ value: T, in keyPath: WritableKeyPath<CellarFilter, Set<T>>) -> Binding<Bool> {
        Binding(get: { filter[keyPath: keyPath].contains(value) }, set: { isOn in
            if isOn { filter[keyPath: keyPath].insert(value) } else { filter[keyPath: keyPath].remove(value) }
        })
    }

    /// One value at most (a grape, an occasion, a tag), in a submenu.
    @ViewBuilder
    private func choice(_ title: LocalizedStringKey, systemImage: String, values: [String],
                        selected: WritableKeyPath<CellarFilter, String?>) -> some View {
        if !values.isEmpty {
            Menu {
                ForEach(values, id: \.self) { value in
                    Toggle(isOn: Binding(get: { filter[keyPath: selected]?.caseInsensitiveCompare(value) == .orderedSame },
                                         set: { filter[keyPath: selected] = $0 ? value : nil })) {
                        Text(verbatim: value)
                    }
                }
            } label: {
                Label(title, systemImage: systemImage)
            }
        }
    }
}

/// Right-click on several rows of the table: what can be done to them at once.
private struct SeveralWinesMenu: View {
    @Environment(CellarModel.self) private var model
    let wineIDs: Set<UUID>
    let onDelete: () -> Void

    var body: some View {
        if !model.cellar.racks.isEmpty {
            Menu("Place in a Rack", systemImage: "square.grid.3x3") {
                ForEach(model.cellar.racks) { rack in
                    Button(rack.name) { model.place(wineIDs, inRack: rack.id) }
                }
            }
        }
        Button("Take Out of Racks", systemImage: "tray.and.arrow.up") { model.unplace(wineIDs) }
        Divider()
        Button("Delete Wines…", systemImage: "trash", role: .destructive, action: onDelete)
    }
}

/// One wine in the table, with values that sort.
struct CellarTableRow: Identifiable, Sendable {
    let group: WineGroup
    let name: String
    let vintage: Int
    let producer: String
    let styleOrder: Int
    let region: String
    let maturity: Maturity
    let count: Int
    let location: String
    let tags: String
    /// Sum of purchase prices, for sorting; `valueText` shows it per currency.
    let value: Decimal
    let valueText: String

    var id: UUID { group.wine.id }
    var wine: Wine { group.wine }

    @MainActor
    init(group: WineGroup, model: CellarModel) {
        self.group = group
        let wine = group.wine
        name = wine.name
        // Non-vintage after every year when sorting by vintage.
        vintage = wine.vintage ?? Int.max
        producer = wine.producer ?? ""
        styleOrder = wine.color.flatMap { WineColor.allCases.firstIndex(of: $0) } ?? Int.max
        region = [wine.appellation ?? wine.region, wine.country].compactMap { $0 }.joined(separator: ", ")
        maturity = Maturity.of(wine, year: model.currentYear)
        count = group.count
        tags = (wine.tags ?? []).joined(separator: ", ")
        var totals: [String: Decimal] = [:]
        for bottle in group.bottles {
            guard let price = bottle.purchasePrice else { continue }
            totals[bottle.currency ?? model.cellar.settings.currency, default: 0] += price
        }
        value = totals.values.reduce(0, +)
        valueText = totals.keys.sorted().map { code in
            (totals[code] ?? 0).formatted(.currency(code: code).precision(.fractionLength(0)))
        }.joined(separator: " + ")
        location = WineRow.places(group.bottles.compactMap { model.locationLabel(for: $0) }) ?? ""
    }
}

/// List or table, in the Cellar toolbar on the Mac and the iPad.
struct CellarLayoutPicker: View {
    @AppStorage(CellarLayout.key) private var showsTable = false

    var body: some View {
        Picker("View", selection: $showsTable) {
            Label("List", systemImage: "list.bullet").tag(false)
            Label("Table", systemImage: "tablecells").tag(true)
        }
        .pickerStyle(.segmented)
        .labelStyle(.iconOnly)
        .help(Text("Show the cellar as a list or a table"))
        .accessibilityIdentifier("cellar.layout")
    }
}
