import CaveKit
import SwiftUI

/// Mac and iPad: show the cellar as a list or as a sortable table. Remembered.
enum CellarLayout {
    static let key = "cellar.table"
}

/// The cellar as a sortable table, one row per wine, with the selected wine in an
/// inspector. For large cellars on the Mac and the iPad, like a spreadsheet. Several
/// wines can be selected at once to place, take out or delete them together.
struct CellarTable: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    /// The wine shown in the inspector: the selected one, when only one is.
    @Binding var selection: UUID?
    /// The selected rows.
    @State private var picked: Set<UUID> = []
    @State private var query = ""
    @State private var sortOrder = [KeyPathComparator(\CellarTableRow.name)]
    @State private var showsInspector = true
    /// Columns shown, their order and widths, remembered per window.
    @SceneStorage("cellar.table.columns") private var columns = TableColumnCustomization<CellarTableRow>()

    var body: some View {
        let rows = model.cellar.groups(filter: CellarFilter(query: query), sort: .name, year: model.currentYear)
            .map { CellarTableRow(group: $0, model: model) }
            .sorted(using: sortOrder)
        table(rows)
        .onChange(of: picked) { _, new in
            selection = new.count == 1 ? new.first : nil
        }
        // A wine chosen elsewhere (opened from Spotlight, the first one at launch).
        .onChange(of: selection, initial: true) { _, id in
            if let id, picked != Set([id]) { picked = Set([id]) }
        }
        #if os(macOS)
        // The Delete key, like in Finder; Undo puts them back.
        .onDeleteCommand {
            if picked.count == 1, let id = picked.first {
                model.deleteWine(id)
            } else if !picked.isEmpty {
                model.deleteWines(picked)
            }
        }
        #endif
        .overlay {
            if rows.isEmpty {
                if query.isEmpty {
                    ContentUnavailableView("No bottles", systemImage: "wineglass")
                } else {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
        .searchable(text: $query, prompt: Text("Wine, producer, region, grape, vintage"))
        .navigationTitle("Cellar")
        .inspector(isPresented: $showsInspector) {
            // Its own stack, new for each wine: what is opened from the wine's details
            // stays in the inspector instead of covering the table.
            NavigationStack { WinePane(wineID: selection) }
                .id(selection)
                .inspectorColumnWidth(min: 300, ideal: 360, max: 480)
        }
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                CellarLayoutPicker()
                Button {
                    router?.sheet = .addBottle(AddBottleContext())
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
            SettingsToolbarItem(identifier: "cellar.settings")
            #endif
        }
    }

    /// The table and its row menus, apart from the rest: one expression would be too slow
    /// to type-check.
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
            if ids.count == 1, let id = ids.first, let group = rows.first(where: { $0.id == id }).map(\.group) {
                WineContextMenu(group: group)
            } else if ids.count > 1 {
                SeveralWinesMenu(wineIDs: ids)
            }
        } primaryAction: { ids in
            if ids.count == 1, let id = ids.first {
                picked = Set([id])
                showsInspector = true
            }
        }
    }
}

/// The menu of several selected wines: what the Cellar list does with its selection.
private struct SeveralWinesMenu: View {
    @Environment(CellarModel.self) private var model
    let wineIDs: Set<UUID>

    var body: some View {
        Menu("Place in a Rack", systemImage: "square.grid.3x3") {
            ForEach(model.cellar.racks) { rack in
                Button(rack.name) { model.place(wineIDs, inRack: rack.id) }
            }
        }
        .disabled(model.cellar.racks.isEmpty)
        Button("Take Out of Racks", systemImage: "tray.and.arrow.up") { model.unplace(wineIDs) }
        Divider()
        Button("Delete Wines", systemImage: "trash", role: .destructive) { model.deleteWines(wineIDs) }
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
        let labels = group.bottles.compactMap { model.locationLabel(for: $0) }
        if let first = labels.first {
            location = labels.count > 1 ? String(localized: "\(first) and \(labels.count - 1) more") : first
        } else {
            location = ""
        }
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
