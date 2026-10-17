import CaveKit
import SwiftUI

/// Draws a rack as it looks in real life:
/// - grid: square cells with row letters and column numbers,
/// - honeycomb: touching round holes, every other row offset by half,
/// - shelves: rows of bottles on a shelf, with how many are behind,
/// - bins: diamond bins showing a small pile of bottles and a count.
struct RackGrid: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    var cellSize: CGFloat = 52
    /// Slots chosen in a picker, shown with a check mark.
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var showsHeaders = true
    var onTap: (RackCell, [Bottle]) -> Void = { _, _ in }

    private var size: CGFloat { rack.kind == .bins ? cellSize * 1.7 : cellSize }
    private var spacing: CGFloat {
        switch rack.kind {
        case .grid: cellSize * 0.08
        case .staggered: cellSize * 0.04
        case .shelves: cellSize * 0.1
        case .bins: 0
        }
    }

    /// Vertical distance between rows: honeycomb and diamond rows nest into each other.
    private var rowSpacing: CGFloat {
        switch rack.kind {
        case .grid: spacing
        case .staggered: -(size + spacing) * 0.134
        case .shelves: cellSize * 0.42
        case .bins: -size / 2 + cellSize * 0.06
        }
    }

    private var offsetsOddRows: Bool { rack.kind == .staggered || rack.kind == .bins }

    var body: some View {
        let bottlesByCell = model.cellar.bottlesByCell(in: rack)
        let selectedByCell = Dictionary(grouping: selected.filter { $0.rackID == rack.id }, by: \.cell)
        VStack(alignment: .leading, spacing: rowSpacing) {
            if showsHeaders, rack.kind == .grid || rack.kind == .shelves {
                columnHeader
                    .padding(.bottom, rack.kind == .shelves ? -rowSpacing + spacing : 0)
            }
            ForEach(0..<rack.rows, id: \.self) { row in
                HStack(spacing: 0) {
                    if showsHeaders { rowHeader(row) }
                    HStack(spacing: spacing) {
                        ForEach(0..<rack.columns(inRow: row), id: \.self) { column in
                            let cell = RackCell(rackID: rack.id, row: row, column: column)
                            RackCellView(
                                rack: rack, cell: cell, bottles: bottlesByCell[cell] ?? [],
                                size: size, selectedCount: selectedByCell[cell]?.count ?? 0,
                                isHighlighted: highlighted?.cell == cell, onTap: onTap
                            )
                        }
                    }
                    .padding(.leading, offsetsOddRows && !row.isMultiple(of: 2) ? (size + spacing) / 2 : 0)
                    .background(alignment: .bottom) {
                        if rack.kind == .shelves { ShelfBar(height: max(4, cellSize * 0.09)).offset(y: cellSize * 0.2) }
                    }
                }
            }
        }
        .padding(rack.kind == .staggered ? cellSize * 0.18 : 0)
        .background {
            if rack.kind == .staggered || rack.kind == .grid {
                RoundedRectangle(cornerRadius: cellSize * 0.22, style: .continuous)
                    .fill(Color.secondary.opacity(rack.kind == .staggered ? 0.08 : 0.04))
                    .padding(.leading, showsHeaders ? headerWidth : 0)
                    .padding(-cellSize * 0.08)
            }
        }
    }

    private var headerWidth: CGFloat { max(18, cellSize * 0.42) }

    private func rowHeader(_ row: Int) -> some View {
        Text(verbatim: Rack.rowLetters(row))
            .font(.system(size: max(10, cellSize * 0.24), weight: .semibold).monospacedDigit())
            .foregroundStyle(.secondary)
            .frame(width: headerWidth)
            .accessibilityHidden(true)
    }

    private var columnHeader: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: headerWidth, height: 1)
            HStack(spacing: spacing) {
                ForEach(0..<rack.maxColumns, id: \.self) { column in
                    Text(verbatim: "\(column + 1)")
                        .font(.system(size: max(10, cellSize * 0.22), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(width: size)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

extension RackGrid {
    /// The cell size at which the whole rack fits in a space, headers included.
    static func fittingCellSize(for rack: Rack, in space: CGSize, showsHeaders: Bool = true) -> CGFloat {
        let columns = CGFloat(rack.maxColumns), rows = CGFloat(rack.rows)
        var width: CGFloat, height: CGFloat
        switch rack.kind {
        case .grid:
            width = columns * 1.08
            height = rows * 1.08 + (showsHeaders ? 0.4 : 0)
        case .staggered:
            width = (columns + 0.5) * 1.04 + 0.4
            height = rows * 0.9 + 0.4
        case .shelves:
            width = columns * 1.1
            height = rows * 1.45 + (showsHeaders ? 0.4 : 0)
        case .bins:
            width = (columns + 0.5) * 1.7
            height = 1.7 + (rows - 1) * 0.92
        }
        if showsHeaders { width += 0.5 }
        let fit = min(space.width / width, space.height / height)
        return min(max(fit, 26), 80)
    }
}

/// A rack drawn as large as the space allows, scrollable when zoomed in.
struct FittedRackGrid: View {
    let rack: Rack
    /// 1 fits the whole rack; more zooms in.
    var zoom: CGFloat = 1
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var onTap: (RackCell, [Bottle]) -> Void = { _, _ in }

    var body: some View {
        GeometryReader { proxy in
            let padding: CGFloat = 16
            let space = CGSize(width: proxy.size.width - padding * 2, height: proxy.size.height - padding * 2)
            let base = RackGrid.fittingCellSize(for: rack, in: space)
            ScrollView([.horizontal, .vertical]) {
                RackGrid(rack: rack, cellSize: base * zoom, selected: selected, highlighted: highlighted, onTap: onTap)
                    .padding(padding)
                    .frame(minWidth: proxy.size.width, minHeight: proxy.size.height)
            }
            .defaultScrollAnchor(.center)
        }
    }
}

/// A shelf under a row of bottles.
private struct ShelfBar: View {
    let height: CGFloat

    var body: some View {
        Capsule()
            .fill(Color.secondary.opacity(0.28))
            .frame(height: height)
            .padding(.horizontal, -height)
    }
}

/// One cell: a bottle end (with what is behind it) or a whole bin.
struct RackCellView: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let cell: RackCell
    /// Bottles in the cell, front first.
    let bottles: [Bottle]
    let size: CGFloat
    var selectedCount = 0
    var isHighlighted = false
    var onTap: (RackCell, [Bottle]) -> Void

    private func color(_ bottle: Bottle) -> WineColor? { model.cellar.wines[bottle.wineID]?.color }

    var body: some View {
        Button {
            onTap(cell, bottles)
        } label: {
            Group {
                if rack.kind == .bins { bin } else { position }
            }
            .frame(width: size, height: size)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityText)
        .accessibilityIdentifier("rack.cell.\(cell.row)-\(cell.column)")
        .accessibilityAddTraits(selectedCount > 0 ? .isSelected : [])
    }

    // MARK: Single position (grid, honeycomb, shelf)

    private var position: some View {
        let front = bottles.first { $0.slot?.depth == 0 }
        let behind = bottles.filter { ($0.slot?.depth ?? 0) > 0 }
        return ZStack {
            switch rack.kind {
            case .grid:
                RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                    .fill(Color.secondary.opacity(0.08))
                    .overlay(
                        RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                            .strokeBorder(Color.secondary.opacity(0.18), lineWidth: 1)
                    )
            case .staggered:
                Circle().fill(Color.secondary.opacity(0.1))
            default:
                EmptyView()
            }
            if let front {
                BottleEnd(color: color(front), size: size * 0.72, isHighlighted: isHighlighted)
            } else if let back = behind.first {
                // Nothing in front: show the bottle behind, smaller and faded.
                BottleEnd(color: color(back), size: size * 0.52, isHighlighted: isHighlighted)
                    .opacity(0.55)
            } else {
                BottleEnd(color: nil, size: size * 0.72, isEmpty: true, isHighlighted: isHighlighted)
                if selectedCount == 0 {
                    Text(verbatim: rack.label(for: cell))
                        .font(.system(size: max(9, size * 0.18), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            if selectedCount > 0 { selectionBadge }
        }
        .overlay(alignment: .bottom) {
            if rack.positionsPerCell > 1 { depthDots.offset(y: rack.kind == .shelves ? size * 0.16 : -size * 0.02) }
        }
    }

    /// One dot per place in the cell, front first: filled when a bottle is there.
    private var depthDots: some View {
        HStack(spacing: max(2, size * 0.05)) {
            ForEach(0..<rack.positionsPerCell, id: \.self) { depth in
                let bottle = bottles.first { $0.slot?.depth == depth }
                Circle()
                    .fill(bottle.map { color($0)?.tint ?? .gray } ?? Color.clear)
                    .overlay(Circle().strokeBorder(Color.secondary.opacity(0.6), lineWidth: 0.75))
                    .frame(width: max(4, size * 0.1), height: max(4, size * 0.1))
            }
        }
        .accessibilityHidden(true)
    }

    // MARK: Bin

    private var bin: some View {
        let capacity = rack.positionsPerCell
        let isFull = bottles.count >= capacity
        return ZStack {
            RoundedRectangle(cornerRadius: size * 0.08, style: .continuous)
                .fill(Color.secondary.opacity(0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.08, style: .continuous)
                        .strokeBorder(isHighlighted ? Color.accentColor : Color.secondary.opacity(0.3),
                                      lineWidth: isHighlighted ? 3 : 1.5)
                )
                .rotationEffect(.degrees(45))
                .scaleEffect(0.7)
            VStack(spacing: size * 0.03) {
                Pile(colors: bottles.map(color), size: size * 0.11)
                Text(verbatim: "\(bottles.count)/\(capacity)")
                    .font(.system(size: max(10, size * 0.13), weight: .semibold).monospacedDigit())
                    .foregroundStyle(isFull ? Color.primary : Color.secondary)
                if selectedCount > 0 {
                    Text(verbatim: "+\(selectedCount)")
                        .font(.system(size: max(10, size * 0.12), weight: .bold).monospacedDigit())
                        .foregroundStyle(Color.accentColor)
                }
            }
            .offset(y: size * 0.04)
        }
    }

    private var selectionBadge: some View {
        ZStack {
            Circle()
                .fill(Color.accentColor.opacity(0.25))
                .overlay(Circle().strokeBorder(Color.accentColor, lineWidth: 2))
                .frame(width: size * 0.74, height: size * 0.74)
            if rack.positionsPerCell > 1 {
                Text(verbatim: "+\(selectedCount)")
                    .font(.system(size: size * 0.24, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.accentColor)
            } else {
                Image(systemName: "checkmark")
                    .font(.system(size: size * 0.25, weight: .bold))
                    .foregroundStyle(Color.accentColor)
            }
        }
    }

    private var accessibilityText: Text {
        let place = rack.displayLabel(for: cell)
        if rack.kind == .bins {
            return Text("\(place), \(bottles.count) of \(rack.positionsPerCell) bottles")
        }
        let names = bottles.compactMap { model.cellar.wines[$0.wineID]?.displayName }
        if names.isEmpty { return Text("\(place), empty") }
        return Text(verbatim: "\(place), \(names.joined(separator: ", "))")
    }
}

/// Bottles stacked in a bin: up to ten drawn as a pyramid, bottom row first.
private struct Pile: View {
    let colors: [WineColor?]
    let size: CGFloat

    var body: some View {
        let shown = Array(colors.prefix(10))
        // Rows from the bottom: 4, 3, 2, 1.
        let widths = [4, 3, 2, 1]
        var rows: [[WineColor?]] = []
        var index = 0
        for width in widths where index < shown.count {
            let end = min(shown.count, index + width)
            rows.append(Array(shown[index..<end]))
            index = end
        }
        return VStack(spacing: -size * 0.12) {
            ForEach(Array(rows.enumerated().reversed()), id: \.offset) { _, row in
                HStack(spacing: 1) {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, color in
                        BottleEnd(color: color, size: size)
                    }
                }
            }
        }
        .frame(minHeight: size * 3.2, alignment: .bottom)
        .accessibilityHidden(true)
    }
}

/// Small preview of a rack's fill, for cards.
struct RackThumbnail: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack

    var body: some View {
        let rows = min(rack.rows, 6)
        let byCell = model.cellar.bottlesByCell(in: rack)
        VStack(alignment: .leading, spacing: 3) {
            ForEach(0..<rows, id: \.self) { row in
                HStack(spacing: 3) {
                    ForEach(0..<min(rack.columns(inRow: row), 10), id: \.self) { column in
                        let bottles = byCell[RackCell(rackID: rack.id, row: row, column: column)] ?? []
                        let color = bottles.first.flatMap { model.cellar.wines[$0.wineID]?.color }
                        Group {
                            if rack.kind == .bins {
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(bottles.isEmpty ? Color.secondary.opacity(0.15) : (color?.tint ?? .gray))
                                    .rotationEffect(.degrees(45))
                                    .scaleEffect(0.75)
                            } else {
                                Circle()
                                    .fill(bottles.isEmpty ? Color.secondary.opacity(0.15) : (color?.tint ?? .gray))
                            }
                        }
                        .frame(width: 9, height: 9)
                    }
                }
                .padding(.leading, rack.kind == .staggered && !row.isMultiple(of: 2) ? 6 : 0)
            }
        }
        .accessibilityHidden(true)
    }
}
