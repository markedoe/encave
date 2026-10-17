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
    @AppStorage(CellarLayout.key) private var showsTable = false

    var body: some View {
        Group {
            if destination == .cellar && showsTable {
                // The table needs the width: sidebar, then the table with the wine
                // in an inspector.
                NavigationSplitView(columnVisibility: $columns) {
                    sidebar
                        .navigationSplitViewColumnWidth(min: 190, ideal: 220, max: 300)
                } detail: {
                    NavigationStack { CellarTable(selection: $wine) }
                }
            } else {
                NavigationSplitView(columnVisibility: $columns) {
                    sidebar
                        .navigationSplitViewColumnWidth(min: 190, ideal: 220, max: 300)
                } content: {
                    middle
                        .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 520)
                } detail: {
                    NavigationStack { detail }
                }
            }
        }
        // Like Mail: open on the first wine rather than an empty page.
        .onAppear {
            if wine == nil {
                wine = model.cellar.groups(filter: CellarFilter(), sort: .name, year: model.currentYear).first?.wine.id
            }
        }
        // Menu commands and "Show in Rack" set the selected tab: follow them.
        .onChange(of: model.selectedTab, initial: true) { _, tab in follow(tab) }
        .onChange(of: model.openWineRequest, initial: true) { _, id in
            guard let id else { return }
            destination = .cellar
            wine = id
            model.openWineRequest = nil
        }
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

#endif
