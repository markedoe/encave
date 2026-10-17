import CaveKit
import SwiftUI

/// The bottles of one rack, place by place. Selecting one points to it in the drawing.
struct RackBottleList: View {
    @Environment(CellarModel.self) private var model
    let rackID: UUID
    @Binding var selection: UUID?
    /// Opens a sheet. In a sheet itself, this closes the list first.
    var present: ((AppSheet) -> Void)?

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
                            RackBottleRow(rack: rack, bottle: bottle)
                                .tag(bottle.id)
                                .contextMenu {
                                    Button("Drink", systemImage: "wineglass") { open(.drink(bottleID: bottle.id)) }
                                    Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") {
                                        open(.moveBottle(bottleID: bottle.id))
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
            .onChange(of: selection) { _, id in
                if let id, let slot = model.cellar.bottles[id]?.slot { model.highlightedSlot = slot }
            }
        } else {
            ContentUnavailableView("Rack not found", systemImage: "questionmark")
        }
    }
}

extension RackBottleList {
    private func open(_ sheet: AppSheet) {
        if let present { present(sheet) } else { model.sheet = sheet }
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
