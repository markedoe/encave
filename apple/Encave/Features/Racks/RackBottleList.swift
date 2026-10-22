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
            let bottles = placed(in: rackID)
            let rows: [Int: [Bottle]] = Dictionary(grouping: bottles) { $0.slot?.row ?? 0 }
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
                ForEach(rows.keys.sorted(), id: \.self) { row in
                    Section {
                        ForEach(rows[row] ?? []) { bottle in
                            listRow(rack, bottle)
                        }
                    } header: {
                        Text(verbatim: header(rack, row: row))
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
    /// The rack's bottles in place order: row, then column, then front to back.
    private func placed(in rackID: UUID) -> [Bottle] {
        let inRack: [Bottle] = model.cellar.bottlesInCellar.filter { $0.slot?.rackID == rackID }
        return inRack.sorted { a, b in
            guard let x = a.slot, let y = b.slot else { return false }
            if x.row != y.row { return x.row < y.row }
            if x.column != y.column { return x.column < y.column }
            return x.depth < y.depth
        }
    }

    /// Shelves are named ("Shelf 2"), other rows lettered.
    private func header(_ rack: Rack, row: Int) -> String {
        guard rack.kind == .shelves else { return Rack.rowLetters(row) }
        let label = rack.displayLabel(for: RackCell(rackID: rack.id, row: row, column: 0))
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
                Button("Given away or broken", systemImage: "minus.circle", role: .destructive) {
                    model.markGone(bottle.id)
                }
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
            Text(verbatim: bottle.slot.map { rack.label(for: $0) } ?? "")
                .font(.callout.monospacedDigit().weight(.medium))
                .foregroundStyle(.secondary)
                .frame(width: 44, alignment: .leading)
            BottleEnd(color: wine?.color, size: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(wine?.displayName ?? String(localized: "Unknown wine"))
                    .lineLimit(1)
                let line = [wine?.color?.name, wine?.producer].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty {
                    Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(1)
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
