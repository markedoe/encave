import CaveKit
import SwiftUI

/// A rack drawn as it looks. Tap a bottle for its actions, tap an empty
/// position to add a bottle there. "Move" lets you pick the destination.
struct RackDetailView: View {
    @Environment(CellarModel.self) private var model
    let rackID: UUID
    @State private var zoom: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1
    @State private var selectedBottle: Bottle?
    @State private var movingFrom: Slot?
    @State private var filterWine: UUID?

    var body: some View {
        if let rack = model.cellar.rack(rackID) {
            ScrollView([.horizontal, .vertical]) {
                RackGrid(
                    rack: rack, cellSize: 52 * min(2.2, max(0.6, zoom * pinch)),
                    selected: movingFrom.map { [$0] } ?? [],
                    highlighted: model.highlightedSlot,
                    onTap: { slot, bottle in tap(slot, bottle) }
                )
                .padding(24)
                .animation(.snappy, value: zoom)
            }
            .defaultScrollAnchor(.center)
            .gesture(
                MagnifyGesture()
                    .updating($pinch) { value, state, _ in state = value.magnification }
                    .onEnded { value in zoom = min(2.2, max(0.6, zoom * value.magnification)) }
            )
            .safeAreaInset(edge: .bottom) {
                if movingFrom != nil {
                    HStack {
                        Label("Tap the new position", systemImage: "hand.tap")
                            .font(.subheadline.weight(.medium))
                        Spacer()
                        Button("Cancel") { movingFrom = nil }
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
                    Button { zoom = max(0.6, zoom - 0.2) } label: { Label("Zoom Out", systemImage: "minus.magnifyingglass") }
                        .keyboardShortcut("-")
                    Button { zoom = min(2.2, zoom + 0.2) } label: { Label("Zoom In", systemImage: "plus.magnifyingglass") }
                        .keyboardShortcut("+")
                    Button { model.sheet = .editRack(rackID: rackID) } label: { Label("Edit Rack", systemImage: "slider.horizontal.3") }
                }
            }
            .sheet(item: $selectedBottle) { bottle in
                SlotBottleSheet(bottle: bottle) { action in
                    selectedBottle = nil
                    switch action {
                    case .drink: model.sheet = .drink(bottleID: bottle.id)
                    case .move: movingFrom = bottle.slot
                    case .details: model.selectedTab = .cellar
                    case .remove: model.markGone(bottle.id)
                    }
                }
                .presentationDetents([.height(320), .medium])
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

    private func tap(_ slot: Slot, _ bottle: Bottle?) {
        if let from = movingFrom {
            movingFrom = nil
            if from != slot { model.swap(from, slot) }
            return
        }
        if let bottle {
            selectedBottle = bottle
        } else {
            model.sheet = .addBottle(AddBottleContext(slot: slot))
        }
    }
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
                Text("\(bottles.count) of \(rack.capacity)")
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

enum SlotAction { case drink, move, details, remove }

/// Actions for the bottle at a position.
private struct SlotBottleSheet: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle
    let onAction: (SlotAction) -> Void

    var body: some View {
        let wine = model.cellar.wines[bottle.wineID]
        VStack(spacing: 16) {
            HStack(spacing: 14) {
                BottleEnd(color: wine?.color, size: 48)
                VStack(alignment: .leading, spacing: 3) {
                    Text(wine?.displayName ?? "").font(.title3.weight(.semibold)).fontDesign(.serif)
                    if let producer = wine?.producer {
                        Text(producer).foregroundStyle(.secondary)
                    }
                    if let location = model.cellar.locationLabel(for: bottle) {
                        Label(location, systemImage: "square.grid.3x3").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            if let wine {
                MaturityLabel(maturity: Maturity.of(wine, year: model.currentYear))
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 12) {
                Button { onAction(.drink) } label: { Label("Drink", systemImage: "wineglass").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
                Button { onAction(.move) } label: { Label("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
            }
            .controlSize(.large)
            Button("Given away or broken", role: .destructive) { onAction(.remove) }
                .font(.subheadline)
        }
        .padding(24)
        .macSheetFrame(width: 420, height: 300)
    }
}
