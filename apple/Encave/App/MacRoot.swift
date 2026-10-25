#if os(macOS)
import AppKit
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
    @Environment(SceneRouter.self) private var router
    @State private var destination: MacDestination? = .cellar
    @State private var wine: UUID?
    /// Tonight's own selection, so it never shows the wine last chosen in the Cellar.
    @State private var tonightWine: UUID?
    @State private var bottle: UUID?
    @State private var columns: NavigationSplitViewVisibility = .all
    @State private var deletingRackID: UUID?
    /// No accent chosen in System Settings (Multicolor): the app's own accent applies.
    @State private var usesAppAccent = SystemAccent.isMulticolor
    private var layout = DefaultsFlag.cellarTable

    var body: some View {
        Group {
            if destination == .cellar && layout.isOn {
                // The table needs the width: sidebar, then the table with the wine
                // in an inspector.
                NavigationSplitView(columnVisibility: $columns) {
                    sidebar
                        .navigationSplitViewColumnWidth(min: 190, ideal: 240, max: 300)
                } detail: {
                    NavigationStack { CellarTable(selection: $wine) }
                }
            } else {
                NavigationSplitView(columnVisibility: $columns) {
                    sidebar
                        .navigationSplitViewColumnWidth(min: 190, ideal: 240, max: 300)
                } content: {
                    middle
                        .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 520)
                } detail: {
                    // A new stack for each wine: what was opened from the last one's details goes.
                    NavigationStack { detail }
                        .id(destination == .tonight ? tonightWine : wine)
                }
            }
        }
        // Like Mail: open on the first wine rather than an empty page.
        .onAppear {
            if wine == nil {
                wine = model.cellar.groups(filter: CellarFilter(), sort: .name, year: model.currentYear).first?.wine.id
            }
        }
        // Menu commands and "Show in Storage" set the selected tab: follow them.
        .onChange(of: router.selectedTab, initial: true) { _, tab in follow(tab) }
        .onChange(of: router.openWineRequest, initial: true) { _, id in
            guard let id else { return }
            destination = .cellar
            wine = id
            router.openWineRequest = nil
        }
        // The Wine menu acts on the wine selected in this window.
        .focusedSceneValue(\.selectedWineID, destination == .cellar ? wine : destination == .tonight ? tonightWine : nil)
        // The selected wine was deleted: the first of the Cellar takes its place.
        .replacingDeletedWine($wine, selectsFirst: destination == .cellar)
        .replacingDeletedWine($tonightWine, selectsFirst: false)
        .onChange(of: router.highlightedSlot) { _, slot in
            if let slot { destination = .rack(slot.rackID) }
        }
        .onChange(of: destination) { _, new in
            if new == .tonight, tonightWine == nil { tonightWine = todaysSuggestion }
            switch new {
            case .cellar: if router.selectedTab != .cellar { router.selectedTab = .cellar }
            case .tonight: if router.selectedTab != .tonight { router.selectedTab = .tonight }
            case .journal: if router.selectedTab != .journal { router.selectedTab = .journal }
            case .rack: if router.selectedTab != .racks { router.selectedTab = .racks }
            case nil: break
            }
        }
    }

    // MARK: Sidebar

    private var sidebar: some View {
        List(selection: $destination) {
            Section("Library") {
                SidebarLabel(title: Text("Cellar"), systemImage: "wineglass")
                    .badge(model.bottlesInCellar.count)
                    .tag(MacDestination.cellar)
                    .accessibilityIdentifier("section.cellar")
                SidebarLabel(title: Text("Tonight"), systemImage: "moon.stars")
                    .badge(model.overview.statistics.byMaturity[.drinkSoon, default: 0])
                    .tag(MacDestination.tonight)
                    .accessibilityIdentifier("section.tonight")
                SidebarLabel(title: Text("Journal"), systemImage: "book.closed")
                    .badge(model.cellar.shoppingListCount)
                    .tag(MacDestination.journal)
                    .accessibilityIdentifier("section.journal")
            }
            Section("Storage") {
                ForEach(Array(model.cellar.racks.enumerated()), id: \.element.id) { index, rack in
                    SidebarLabel(title: Text(rack.name), systemImage: rack.systemImage)
                        .help(rack.name)
                        .badge(model.cellar.occupancy(of: rack))
                        .tag(MacDestination.rack(rack.id))
                        .accessibilityIdentifier("rack.card.\(index)")
                        // A wine or a bottle dropped here: one bottle to the first free position.
                        .storageDropTarget(rack.id)
                        .contextMenu {
                            Button("Edit Storage", systemImage: "pencil") { router.sheet = .editRack(rackID: rack.id) }
                            Divider()
                            Button("Delete Storage…", systemImage: "trash", role: .destructive) {
                                deletingRackID = rack.id
                            }
                        }
                }
                .onMove { source, target in model.update { $0.moveRacks(fromOffsets: source, toOffset: target) } }
                Button {
                    router.sheet = .editRack(rackID: nil)
                } label: {
                    Label("Add Storage", systemImage: "plus")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("racks.add")
            }
        }
        .listStyle(.sidebar)
        // The selected row: white on a darker rose in dark mode, where white on the accent
        // is 3.4:1 (design token "mac-sidebar-selection"); the accent in light mode. Only
        // with Multicolor: an accent chosen in System Settings colors the selection as
        // it colors the row icons.
        .tint(usesAppAccent ? Color.macSidebarSelection : nil)
        .onReceive(NotificationCenter.default.publisher(for: NSColor.systemColorsDidChangeNotification)) { _ in
            usesAppAccent = SystemAccent.isMulticolor
        }
        .confirmsRackDeletion($deletingRackID)
        .safeAreaInset(edge: .bottom) { SyncAttentionFooter() }
    }

    // MARK: Middle column

    @ViewBuilder
    private var middle: some View {
        switch destination {
        case .cellar, nil:
            CellarList(selection: $wine)
        case .tonight:
            NavigationStack { TonightView(selection: $tonightWine) }
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
        case .cellar, nil:
            WinePane(wineID: wine)
        case .tonight:
            WinePane(wineID: tonightWine)
        case .journal:
            InsightsView()
        case .rack(let id):
            if model.cellar.rack(id) != nil {
                RackDetailView(rackID: id)
            } else {
                ContentUnavailableView("Select storage", systemImage: "square.grid.3x3")
            }
        }
    }

    /// The wine Tonight suggests first today.
    private var todaysSuggestion: UUID? {
        // The same wine as Tonight's card, including the one it keeps for the day.
        let day = Cellar.tonightDay(.now)
        // Read, not @AppStorage: at the root of the window that redraws it without end (DefaultsFlag).
        let kept = TonightView.savedFirstPick(on: day)
        return model.cellar.tonightSuggestion(year: model.currentYear, day: day, keeping: kept)?.wine.id
    }

    private func follow(_ tab: AppTab) {
        switch tab {
        case .cellar: if destination != .cellar { destination = .cellar }
        case .tonight: if destination != .tonight { destination = .tonight }
        case .journal: if destination != .journal { destination = .journal }
        case .racks:
            if case .rack = destination { return }
            if let first = router.highlightedSlot?.rackID ?? model.cellar.racks.first?.id {
                destination = .rack(first)
            } else {
                router.sheet = .editRack(rackID: nil)
            }
        }
    }
}

/// The accent chosen in System Settings › Appearance.
enum SystemAccent {
    /// Multicolor (no "AppleAccentColor" set): each app shows its own accent.
    static var isMulticolor: Bool {
        UserDefaults.standard.object(forKey: "AppleAccentColor") == nil
    }
}

/// A sidebar row whose symbol keeps the accent: the sidebar's tint is for the selection only.
private struct SidebarLabel: View {
    let title: Text
    let systemImage: String

    var body: some View {
        Label {
            title
        } icon: {
            Image(systemName: systemImage).tint(Color.accentColor)
        }
    }
}

#endif
