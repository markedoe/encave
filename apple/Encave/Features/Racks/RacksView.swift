import CaveKit
import SwiftUI

struct RacksView: View {
    @Environment(CellarModel.self) private var model

    var body: some View {
        NavigationStack {
            Group {
                if model.cellar.racks.isEmpty {
                    ContentUnavailableView {
                        Label("No racks yet", systemImage: "square.grid.3x3")
                    } description: {
                        Text("Describe your racks to see exactly where each bottle is.")
                    } actions: {
                        Button("Load sample cellar") { model.loadSample() }
                    }
                } else {
                    List(model.cellar.racks) { rack in
                        NavigationLink(value: rack.id) {
                            let used = rack.capacity - model.cellar.freeSlots(in: rack).count
                            LabeledContent {
                                Text("\(used) of \(rack.capacity)")
                            } label: {
                                Label(rack.name, systemImage: "square.grid.3x3")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Racks")
            .navigationDestination(for: UUID.self) { RackDetailView(rackID: $0) }
        }
    }
}

struct RackDetailView: View {
    @Environment(CellarModel.self) private var model
    let rackID: UUID

    var body: some View {
        if let rack = model.cellar.racks.first(where: { $0.id == rackID }) {
            ScrollView([.horizontal, .vertical]) {
                Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                    ForEach(0..<rack.rows, id: \.self) { row in
                        GridRow {
                            ForEach(0..<rack.columns, id: \.self) { column in
                                SlotCell(rack: rack, slot: Slot(rackID: rack.id, row: row, column: column))
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(rack.name)
        } else {
            ContentUnavailableView("Rack not found", systemImage: "questionmark")
        }
    }
}

/// One position in a rack. At least 44 points so it is easy to tap.
struct SlotCell: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let slot: Slot

    var body: some View {
        let bottle = model.cellar.bottle(at: slot)
        let wine = bottle.flatMap { model.cellar.wines[$0.wineID] }
        let label = rack.label(row: slot.row, column: slot.column)

        VStack(spacing: 2) {
            // Bottle end seen from the front: filled when a bottle is there.
            Circle()
                .fill(wine.map { $0.color?.tint ?? Color.secondary } ?? Color.clear)
                .overlay(Circle().strokeBorder(Color.secondary.opacity(wine == nil ? 0.5 : 0.25), lineWidth: 1))
                .frame(width: 26, height: 26)
            Text(verbatim: label)
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .frame(width: 48, height: 48)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement()
        .accessibilityLabel(wine.map { Text("\(label), \($0.displayName)") } ?? Text("\(label), empty"))
    }
}
