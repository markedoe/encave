#if os(macOS)
import CaveKit
import SwiftUI

/// What the Mac sidebar shows: a section, or one rack.
enum MacDestination: Hashable {
    case cellar, tonight, journal
    case rack(UUID)
}

/// The Mac window, built like Mail or Notes: a sidebar (library and racks), the list of
/// the chosen item in the middle, and its detail on the right. One split view, no nesting.
struct MacRoot: View {
    @Environment(CellarModel.self) private var model
    @State private var destination: MacDestination? = .cellar
    @State private var wine: UUID?
    @State private var bottle: UUID?
    @State private var columns: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            sidebar
                .navigationSplitViewColumnWidth(min: 190, ideal: 220, max: 300)
        } content: {
            middle
                .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 520)
        } detail: {
            NavigationStack { detail }
        }
        // Menu commands and "Show in Rack" set the selected tab: follow them.
        .onChange(of: model.selectedTab, initial: true) { _, tab in follow(tab) }
        .onChange(of: model.highlightedSlot) { _, slot in
            if let slot { destination = .rack(slot.rackID) }
        }
        .onChange(of: destination) { _, new in
            switch new {
            case .cellar: if model.selectedTab != .cellar { model.selectedTab = .cellar }
            case .tonight: if model.selectedTab != .tonight { model.selectedTab = .tonight }
            case .journal: if model.selectedTab != .journal { model.selectedTab = .journal }
            case .rack: if model.selectedTab != .racks { model.selectedTab = .racks }
            case nil: break
            }
        }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        List(selection: $destination) {
            Section("Library") {
                Label("Cellar", systemImage: "wineglass")
                    .badge(model.cellar.bottlesInCellar.count)
                    .tag(MacDestination.cellar)
                    .accessibilityIdentifier("section.cellar")
                Label("Tonight", systemImage: "moon.stars")
                    .badge(model.cellar.statistics(year: model.currentYear).byMaturity[.drinkSoon, default: 0])
                    .tag(MacDestination.tonight)
                    .accessibilityIdentifier("section.tonight")
                Label("Journal", systemImage: "book.closed")
                    .badge(model.cellar.shoppingListCount)
                    .tag(MacDestination.journal)
                    .accessibilityIdentifier("section.journal")
            }
            Section("Racks") {
                ForEach(Array(model.cellar.racks.enumerated()), id: \.element.id) { index, rack in
                    Label(rack.name, systemImage: rack.kind.systemImage)
                        .badge(model.cellar.occupancy(of: rack))
                        .tag(MacDestination.rack(rack.id))
                        .accessibilityIdentifier("rack.card.\(index)")
                        .contextMenu {
                            Button("Edit Rack", systemImage: "pencil") { model.sheet = .editRack(rackID: rack.id) }
                            Divider()
                            Button("Delete Rack", systemImage: "trash", role: .destructive) {
                                model.update { $0.removeRack(rack.id) }
                            }
                        }
                }
                .onMove { source, target in model.update { $0.moveRacks(fromOffsets: source, toOffset: target) } }
                Button {
                    model.sheet = .editRack(rackID: nil)
                } label: {
                    Label("Add a Rack", systemImage: "plus")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("racks.add")
            }
        }
        .listStyle(.sidebar)
    }

    // MARK: Middle column

    @ViewBuilder
    private var middle: some View {
        switch destination {
        case .cellar, nil:
            CellarList(selection: $wine)
        case .tonight:
            NavigationStack { TonightView(selection: $wine) }
        case .journal:
            NavigationStack { JournalView(embedded: true) }
        case .rack(let id):
            RackBottleList(rackID: id, selection: $bottle)
        }
    }

    // MARK: Detail

    @ViewBuilder
    private var detail: some View {
        switch destination {
        case .cellar, .tonight, nil:
            WinePane(wineID: wine)
        case .journal:
            InsightsView()
        case .rack(let id):
            if model.cellar.rack(id) != nil {
                RackDetailView(rackID: id)
            } else {
                ContentUnavailableView("Select a rack", systemImage: "square.grid.3x3")
            }
        }
    }

    private func follow(_ tab: AppTab) {
        switch tab {
        case .cellar: if destination != .cellar { destination = .cellar }
        case .tonight: if destination != .tonight { destination = .tonight }
        case .journal: if destination != .journal { destination = .journal }
        case .racks:
            if case .rack = destination { return }
            if let first = model.highlightedSlot?.rackID ?? model.cellar.racks.first?.id {
                destination = .rack(first)
            } else {
                model.sheet = .editRack(rackID: nil)
            }
        }
    }
}

/// The bottles of one rack, place by place. Selecting one points to it in the drawing.
struct RackBottleList: View {
    @Environment(CellarModel.self) private var model
    let rackID: UUID
    @Binding var selection: UUID?

    var body: some View {
        if let rack = model.cellar.rack(rackID) {
            let bottles = model.cellar.bottlesInCellar
                .filter { $0.slot?.rackID == rackID }
                .sorted { ($0.slot?.row ?? 0, $0.slot?.column ?? 0, $0.slot?.depth ?? 0)
                    < ($1.slot?.row ?? 0, $1.slot?.column ?? 0, $1.slot?.depth ?? 0) }
            let rows = Dictionary(grouping: bottles) { $0.slot?.row ?? 0 }
            List(selection: $selection) {
                Section {
                    LabeledContent {
                        Text("\(bottles.count) of \(rack.capacity) bottles").monospacedDigit()
                    } label: {
                        Label(rack.kind.title, systemImage: rack.kind.systemImage)
                    }
                    ProgressView(value: Double(bottles.count), total: Double(max(1, rack.capacity)))
                        .tint(Color.accentColor)
                }
                if bottles.isEmpty {
                    ContentUnavailableView("Empty", systemImage: rack.kind.systemImage,
                                           description: Text("Click a place in the rack to add a bottle there."))
                }
                ForEach(rows.keys.sorted(), id: \.self) { row in
                    Section {
                        ForEach(rows[row] ?? []) { bottle in
                            RackBottleRow(rack: rack, bottle: bottle)
                                .tag(bottle.id)
                                .contextMenu {
                                    Button("Drink", systemImage: "wineglass") { model.sheet = .drink(bottleID: bottle.id) }
                                    Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") {
                                        model.sheet = .moveBottle(bottleID: bottle.id)
                                    }
                                    Divider()
                                    Button("Given away or broken", systemImage: "minus.circle", role: .destructive) {
                                        model.markGone(bottle.id)
                                    }
                                }
                        }
                    } header: {
                        Text(verbatim: rack.kind == .shelves
                             ? rack.displayLabel(for: RackCell(rackID: rack.id, row: row, column: 0)).components(separatedBy: ",").first ?? ""
                             : Rack.rowLetters(row))
                    }
                }
            }
            .navigationTitle(rack.name)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        model.sheet = .editRack(rackID: rackID)
                    } label: {
                        Label("Edit Rack", systemImage: "slider.horizontal.3")
                    }
                }
            }
            .onChange(of: selection) { _, id in
                if let id, let slot = model.cellar.bottles[id]?.slot { model.highlightedSlot = slot }
            }
        } else {
            ContentUnavailableView("Rack not found", systemImage: "questionmark")
        }
    }
}

private struct RackBottleRow: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let bottle: Bottle

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        HStack(spacing: 10) {
            Text(verbatim: bottle.slot.map { rack.label(for: $0) } ?? "")
                .font(.callout.monospacedDigit().weight(.medium))
                .foregroundStyle(.secondary)
                .frame(width: 44, alignment: .leading)
            BottleEnd(color: wine?.color, size: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .lineLimit(1)
                if let producer = wine?.producer {
                    Text(producer).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
#endif
