import CaveKit
import SwiftUI

/// Draws a rack: every position with its bottle end. Grid racks are square
/// cells; diamond racks offset every other row like X-shaped bins.
struct RackGrid: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    var cellSize: CGFloat = 52
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var onTap: (Slot, Bottle?) -> Void = { _, _ in }

    var body: some View {
        let spacing = cellSize * 0.12
        VStack(alignment: .leading, spacing: rack.layout == .diamond ? -cellSize * 0.18 : spacing) {
            ForEach(0..<rack.rows, id: \.self) { row in
                HStack(spacing: spacing) {
                    ForEach(0..<rack.columns, id: \.self) { column in
                        let slot = Slot(rackID: rack.id, row: row, column: column)
                        SlotCell(
                            rack: rack, slot: slot, size: cellSize,
                            isSelected: selected.contains(slot), isHighlighted: highlighted == slot,
                            onTap: onTap
                        )
                    }
                }
                .offset(x: rack.layout == .diamond && row.isMultiple(of: 2) == false ? (cellSize + spacing) / 2 : 0)
            }
        }
        .padding(.trailing, rack.layout == .diamond ? cellSize / 2 : 0)
    }
}

/// One position: a bottle end in the wine's color, or a dashed empty circle.
/// At least 44 points so it is easy to tap.
struct SlotCell: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let slot: Slot
    let size: CGFloat
    var isSelected = false
    var isHighlighted = false
    var onTap: (Slot, Bottle?) -> Void

    var body: some View {
        let bottle = model.cellar.bottle(at: slot)
        let wine = bottle.flatMap { model.cellar.wines[$0.wineID] }
        let label = rack.label(for: slot)
        Button {
            onTap(slot, bottle)
        } label: {
            ZStack {
                if rack.layout == .diamond {
                    RoundedRectangle(cornerRadius: size * 0.12, style: .continuous)
                        .fill(Color.secondary.opacity(0.07))
                        .rotationEffect(.degrees(45))
                        .scaleEffect(0.74)
                } else {
                    RoundedRectangle(cornerRadius: size * 0.2, style: .continuous)
                        .fill(Color.secondary.opacity(0.07))
                }
                BottleEnd(color: wine?.color, size: size * 0.62, isEmpty: bottle == nil, isHighlighted: isHighlighted)
                if isSelected {
                    Circle()
                        .fill(Color.accentColor.opacity(0.25))
                        .overlay(Circle().strokeBorder(Color.accentColor, lineWidth: 2))
                        .frame(width: size * 0.7, height: size * 0.7)
                    Image(systemName: "checkmark")
                        .font(.system(size: size * 0.25, weight: .bold))
                        .foregroundStyle(Color.accentColor)
                } else if bottle == nil {
                    Text(verbatim: label)
                        .font(.system(size: max(9, size * 0.18), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: max(44, size), height: max(44, size))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(wine.map { Text("\(label), \($0.displayName)") } ?? Text("\(label), empty"))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Small preview of a rack's fill, for cards.
struct RackThumbnail: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack

    var body: some View {
        let rows = min(rack.rows, 6), columns = min(rack.columns, 10)
        Grid(horizontalSpacing: 3, verticalSpacing: 3) {
            ForEach(0..<rows, id: \.self) { row in
                GridRow {
                    ForEach(0..<columns, id: \.self) { column in
                        let wine = model.cellar.bottle(at: Slot(rackID: rack.id, row: row, column: column))
                            .flatMap { model.cellar.wines[$0.wineID] }
                        Circle()
                            .fill(wine.map { $0.color?.tint ?? .gray } ?? Color.secondary.opacity(0.15))
                            .frame(width: 9, height: 9)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}
