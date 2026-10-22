import CaveKit
import SwiftUI

/// The bottles of one rack, place by place. Selecting one points to it in the drawing.
struct RackBottleList: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let rackID: UUID
    @Binding var selection: UUID?
    /// Opens a sheet. In a sheet itself, this closes the list first.
    var present: ((AppSheet) -> Void)?

    var body: some View {
        if let rack = model.cellar.rack(rackID) {
            let bottles = placed(in: rack)
            let groups: [Int: [Bottle]] = Dictionary(grouping: bottles) { group(rack, of: $0) }
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
                    #if os(macOS)
                    ContentUnavailableView("Empty", systemImage: rack.kind.systemImage,
                                           description: Text("Click a place in the rack to add a bottle there."))
                    #else
                    ContentUnavailableView("Empty", systemImage: rack.kind.systemImage,
                                           description: Text("Tap a place in the rack to add a bottle there."))
                    #endif
                }
                ForEach(groups.keys.sorted(), id: \.self) { key in
                    Section {
                        ForEach(groups[key] ?? []) { bottle in
                            listRow(rack, bottle)
                        }
                    } header: {
                        Text(verbatim: header(rack, group: key))
                    }
                }
            }
            .navigationTitle(rack.name)
            .onChange(of: selection) { _, id in
                if let id, let slot = model.cellar.bottles[id]?.slot { model.highlightedSlot = slot }
            }
        } else {
            ContentUnavailableView("Rack not found", systemImage: "questionmark")
        }
    }
}

extension RackBottleList {
    /// The rack's bottles in place order: row, then column, then front to back. On a
    /// fridge with shelf layers: shelf, then layer from the bottom, then column and depth.
    private func placed(in rack: Rack) -> [Bottle] {
        let inRack: [Bottle] = model.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        return inRack.sorted { a, b in
            guard let x = a.slot, let y = b.slot else { return false }
            let first = order(rack, x), second = order(rack, y)
            if first.group != second.group { return first.group < second.group }
            if first.layer != second.layer { return first.layer < second.layer }
            if x.column != y.column { return x.column < y.column }
            return x.depth < y.depth
        }
    }

    /// The section of a place (its shelf on a fridge with layers, else its row) and its
    /// layer on that shelf (0 is the bottom one).
    private func order(_ rack: Rack, _ slot: Slot) -> (group: Int, layer: Int) {
        if let place = rack.shelfPlace(ofRow: slot.row) { return (place.shelf, place.layer) }
        return (slot.row, 0)
    }

    private func group(_ rack: Rack, of bottle: Bottle) -> Int {
        bottle.slot.map { order(rack, $0).group } ?? 0
    }

    /// Shelves are named ("Shelf 2", with its zone when the fridge has several), other
    /// rows lettered.
    private func header(_ rack: Rack, group: Int) -> String {
        if rack.hasShelfLayers {
            let number = group + 1
            let shelf = String(localized: "Shelf \(number)")
            guard rack.zoneCount > 1 else { return shelf }
            return "\(shelf) · \(rack.zoneName(rack.zone(ofShelf: group)))"
        }
        guard rack.kind == .shelves else { return Rack.rowLetters(group) }
        let label = rack.displayLabel(for: RackCell(rackID: rack.id, row: group, column: 0))
        return label.components(separatedBy: ",").first ?? ""
    }

    private func listRow(_ rack: Rack, _ bottle: Bottle) -> some View {
        tappable(rack, bottle)
            .tag(bottle.id)
            .contextMenu {
                Button("Drink", systemImage: "wineglass") { open(.drink(bottleID: bottle.id)) }
                Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") {
                    open(.moveBottle(bottleID: bottle.id))
                }
                BottleDocumentButtons(bottle: bottle) { open($0) }
                Divider()
                NoLongerInCellarButton { model.markGone(bottle.id) }
            }
    }

    /// iPhone and iPad: rows are buttons, as list selection needs edit mode there.
    @ViewBuilder
    private func tappable(_ rack: Rack, _ bottle: Bottle) -> some View {
        #if os(iOS)
        Button { selection = bottle.id } label: {
            RackBottleRow(rack: rack, bottle: bottle).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("rack.list.row")
        #else
        RackBottleRow(rack: rack, bottle: bottle)
        #endif
    }

    private func open(_ sheet: AppSheet) {
        if let present { present(sheet) } else { router?.sheet = sheet }
    }
}

private struct RackBottleRow: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let bottle: Bottle

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        HStack(spacing: 10) {
            BottleEnd(color: wine?.color, size: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .lineLimit(1)
                // "B3, back · Red · Producer": the place as named everywhere else.
                let place: String? = bottle.slot.map { rack.displayLabel(for: $0) }
                let line = [place, wine?.color?.name, wine?.producer].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty {
                    Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            if let documents = bottle.attachments?.count {
                Spacer(minLength: 4)
                DocumentCount(count: documents)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
