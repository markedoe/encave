import CaveKit
import SwiftUI

/// A rack drawn as it looks. Tap a bottle or a bin for what is inside, tap an empty
/// place to add a bottle there. "Move" lets you pick the destination.
struct RackDetailView: View {
    @Environment(CellarModel.self) private var model
    let rackID: UUID
    @State private var zoom: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1
    @State private var openCell: OpenCell?
    @State private var moving: Bottle?

    var body: some View {
        if let rack = model.cellar.rack(rackID) {
            FittedRackGrid(
                rack: rack, zoom: min(3, max(0.6, zoom * pinch)),
                selected: moving?.slot.map { [$0] } ?? [],
                highlighted: model.highlightedSlot,
                onTap: { cell, bottles in tap(rack, cell, bottles) }
            )
            .animation(.snappy, value: zoom)
            .gesture(
                MagnifyGesture()
                    .updating($pinch) { value, state, _ in state = value.magnification }
                    .onEnded { value in zoom = min(3, max(0.6, zoom * value.magnification)) }
            )
            .safeAreaInset(edge: .bottom) {
                if let moving {
                    let displayName = model.cellar.wines[moving.wineID]?.displayName ?? ""
                    HStack {
                        Label("Tap where to put \(displayName)", systemImage: "hand.tap")
                            .font(.subheadline.weight(.medium))
                            .lineLimit(2)
                        Spacer()
                        Button("Cancel") { self.moving = nil }
                        .accessibilityIdentifier("sheet.cancel")
                    }
                    .padding()
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding()
                } else {
                    RackLegend(rack: rack)
                }
            }
            .navigationTitle(rack.name)
            .inlineTitle()
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { zoom = max(0.6, zoom - 0.25) } label: { Label("Zoom Out", systemImage: "minus.magnifyingglass") }
                        .keyboardShortcut("-")
                        .disabled(zoom <= 0.6)
                    Button { zoom = min(3, zoom + 0.25) } label: { Label("Zoom In", systemImage: "plus.magnifyingglass") }
                        .keyboardShortcut("+")
                        .disabled(zoom >= 3)
                    Button { model.sheet = .editRack(rackID: rackID) } label: { Label("Edit Rack", systemImage: "slider.horizontal.3") }
                }
            }
            .sheet(item: $openCell) { open in
                CellSheet(rack: rack, cell: open.cell) { action in
                    openCell = nil
                    switch action {
                    case .drink(let bottle): model.sheet = .drink(bottleID: bottle.id)
                    case .move(let bottle): moving = bottle
                    case .remove(let bottle): model.markGone(bottle.id)
                    case .add(let slot): model.sheet = .addBottle(AddBottleContext(slot: slot))
                    }
                }
                .presentationDetents([.medium, .large])
                .solidSheetBackground()
            }
            .task(id: model.highlightedSlot) {
                guard model.highlightedSlot?.rackID == rackID else { return }
                try? await Task.sleep(for: .seconds(4))
                withAnimation { model.highlightedSlot = nil }
            }
        } else {
            ContentUnavailableView("Rack not found", systemImage: "questionmark")
        }
    }

    private func tap(_ rack: Rack, _ cell: RackCell, _ bottles: [Bottle]) {
        if let bottle = moving {
            moving = nil
            guard let from = bottle.slot, from.cell != cell else { return }
            if let free = model.cellar.freeSlot(in: cell) {
                model.move(bottle.id, to: free)
            } else if rack.positionsPerCell == 1 {
                model.swap(from, Slot(rackID: cell.rackID, row: cell.row, column: cell.column))
            } else {
                model.showToast(String(localized: "This place is full."))
            }
            return
        }
        if bottles.isEmpty, let free = model.cellar.freeSlot(in: cell) {
            model.sheet = .addBottle(AddBottleContext(slot: free))
        } else {
            openCell = OpenCell(cell: cell)
        }
    }
}

private struct OpenCell: Identifiable {
    let cell: RackCell
    var id: String { "\(cell.row)-\(cell.column)" }
}

/// Counts per wine style under the rack.
private struct RackLegend: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack

    var body: some View {
        let bottles = model.cellar.bottlesInCellar.filter { $0.slot?.rackID == rack.id }
        let counts = Dictionary(grouping: bottles.compactMap { model.cellar.wines[$0.wineID]?.color }, by: { $0 }).mapValues(\.count)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                Label {
                    Text("\(bottles.count) of \(rack.capacity) bottles")
                } icon: {
                    Image(systemName: rack.kind.systemImage)
                }
                .font(.subheadline.weight(.semibold).monospacedDigit())
                ForEach(WineColor.allCases.filter { counts[$0] != nil }, id: \.self) { color in
                    HStack(spacing: 5) {
                        BottleEnd(color: color, size: 14)
                        Text(color.title)
                        Text(verbatim: "\(counts[color] ?? 0)").monospacedDigit().foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(.bar)
    }
}

enum CellAction {
    case drink(Bottle), move(Bottle), remove(Bottle), add(Slot)
}

/// What is in one place of a rack: one bottle, front and back, or a whole bin.
private struct CellSheet: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rack: Rack
    let cell: RackCell
    let onAction: (CellAction) -> Void

    var body: some View {
        let bottles = model.cellar.bottles(in: cell)
        let free = model.cellar.freeSlot(in: cell)
        NavigationStack {
            List {
                if bottles.isEmpty {
                    ContentUnavailableView("Empty", systemImage: rack.kind.systemImage)
                        .listRowBackground(Color.clear)
                }
                Section {
                    ForEach(bottles) { bottle in
                        BottleInCellRow(rack: rack, bottle: bottle, onAction: onAction)
                    }
                } header: {
                    if rack.positionsPerCell > 1 {
                        Text("\(bottles.count) of \(rack.positionsPerCell) bottles")
                    }
                }
                if let free {
                    Section {
                        Button {
                            onAction(.add(free))
                        } label: {
                            if rack.kind == .bins {
                                Label("Add a Bottle to This Bin", systemImage: "plus.circle.fill")
                            } else {
                                Label("Add a Bottle Here", systemImage: "plus.circle.fill")
                            }
                        }
                    }
                }
            }
            .navigationTitle(rack.displayLabel(for: cell))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
                }
            }
        }
        .macSheetFrame(width: 440, height: 460)
    }
}

/// A bottle in a cell, with its actions.
private struct BottleInCellRow: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let bottle: Bottle
    let onAction: (CellAction) -> Void

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                BottleEnd(color: wine?.color, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(wine?.displayName ?? String(localized: "Unknown wine"))
                        .font(.headline)
                    HStack(spacing: 6) {
                        if let producer = wine?.producer { Text(producer) }
                        if rack.kind != .bins, rack.positionsPerCell > 1, let slot = bottle.slot {
                            Text(verbatim: "· \(rack.depthName(slot.depth))")
                        }
                    }
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    if let wine {
                        MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                            .font(.caption)
                    }
                }
            }
            HStack(spacing: 10) {
                Button { onAction(.drink(bottle)) } label: { ActionLabel("Drink", systemImage: "wineglass") }
                    .buttonStyle(.borderedProminent)
                Button { onAction(.move(bottle)) } label: { ActionLabel("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right") }
                    .buttonStyle(.bordered)
                Spacer()
                Menu {
                    Button("Given away or broken", systemImage: "minus.circle", role: .destructive) { onAction(.remove(bottle)) }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                        .labelStyle(.iconOnly)
                }
                .fixedSize()
            }
            .controlSize(.regular)
        }
        .padding(.vertical, 4)
    }
}
