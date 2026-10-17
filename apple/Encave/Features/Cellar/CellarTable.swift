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
    @Binding var selection: UUID?
    @State private var query = ""
    @State private var sortOrder = [KeyPathComparator(\CellarTableRow.name)]
    @State private var showsInspector = true

    var body: some View {
        let rows = model.cellar.groups(filter: CellarFilter(query: query), sort: .name, year: model.currentYear)
            .map { CellarTableRow(group: $0, model: model) }
            .sorted(using: sortOrder)
        Table(rows, selection: $selection, sortOrder: $sortOrder) {
            TableColumn("Wine", value: \.name) { row in
                HStack(spacing: 8) {
                    BottleEnd(color: row.wine.color, size: 18)
                    Text(row.name).fontWeight(.medium)
                }
                .accessibilityElement(children: .combine)
            }
            .width(min: 160, ideal: 220)
            TableColumn("Vintage", value: \.vintage) { row in
                if let vintage = row.wine.vintage {
                    Text(verbatim: "\(vintage)").monospacedDigit()
                } else {
                    Text("Non-vintage").foregroundStyle(.secondary)
                }
            }
            .width(min: 60, ideal: 80)
            TableColumn("Producer", value: \.producer)
                .width(min: 100, ideal: 160)
            TableColumn("Style", value: \.styleOrder) { row in
                if let color = row.wine.color { Text(color.title) }
            }
            .width(min: 60, ideal: 90)
            TableColumn("Region", value: \.region)
                .width(min: 90, ideal: 140)
            TableColumn("When to drink", value: \.maturity) { row in
                if row.maturity != .unknown { MaturityLabel(maturity: row.maturity) }
            }
            .width(min: 110, ideal: 130)
            TableColumn("Bottles", value: \.count) { row in
                Text(verbatim: "\(row.count)").monospacedDigit()
            }
            .width(min: 50, ideal: 60)
            TableColumn("Location", value: \.location) { row in
                Text(row.location).foregroundStyle(.secondary)
            }
            .width(min: 120, ideal: 200)
        }
        .contextMenu(forSelectionType: UUID.self) { ids in
            if let id = ids.first, let group = rows.first(where: { $0.id == id }).map(\.group) {
                WineContextMenu(group: group)
            }
        } primaryAction: { ids in
            if let id = ids.first {
                selection = id
                showsInspector = true
            }
        }
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
            WinePane(wineID: selection)
                .inspectorColumnWidth(min: 300, ideal: 360, max: 480)
        }
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                CellarLayoutPicker()
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
        }
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
