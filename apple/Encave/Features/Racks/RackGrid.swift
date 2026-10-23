import CaveKit
import SwiftUI

/// What a tap on a place does on the rack screen, for VoiceOver's hints and actions.
enum RackMode: Equatable {
    /// A preview or a picker: no hints.
    case none, browsing, moving, auditing
}

/// An action on the bottle of a place, from VoiceOver's actions.
enum RackCellQuickAction {
    case move, drink, winePage
}

/// How the rack screen reacts to its places beyond a tap.
struct RackInteraction {
    var mode: RackMode = .none
    /// Move, Drink, Wine Page on a place holding one bottle, when browsing.
    var perform: ((RackCellQuickAction, Bottle) -> Void)?
    /// While checking: the bottles of a place, to check them one by one.
    var checkOneByOne: ((RackCell) -> Void)?
}

/// Draws a rack as it looks in real life:
/// - grid: square cells with row letters and column numbers,
/// - honeycomb: touching round holes, every other row offset by half; on shelves (a wine
///   fridge), each shelf's layers nest into each other in quincunx above a shelf; two deep,
///   each shelf shows its front bottles, then its back ones (necks first when head-to-tail),
/// - shelves: rows of bottles on a shelf, with how many are behind,
/// - bins: diamond bins showing a small pile of bottles and a count.
struct RackGrid: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    var cellSize: CGFloat = 52
    /// Slots chosen in a picker, shown with a check mark.
    var selected: Set<Slot> = []
    var highlighted: Slot?
    /// The bottle shown in the card under the rack.
    var selectedPlace: RackPlace?
    /// A wine chosen in the key: its bottles stand out, the others fade.
    var focusWine: UUID?
    var showsHeaders = true
    var onTap: (RackPlace, [Bottle]) -> Void = { _, _ in }
    /// A bottle dragged onto a place (iPad, Mac): move it there.
    var onDrop: ((UUID, RackPlace) -> Bool)?
    var interaction = RackInteraction()
    /// The smallest size of the fridge's captions (shelf numbers, front and back, zones),
    /// growing with Dynamic Type.
    @ScaledMetric(relativeTo: .caption2) private var captionFloor: CGFloat = 11

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

    /// A continuous honeycomb, drawn inside its frame.
    private var isHoneycombFrame: Bool { rack.kind == .staggered && !rack.hasShelfLayers }

    var body: some View {
        let bottlesByCell = model.cellar.bottlesByCell(in: rack)
        let selectedByCell = Dictionary(grouping: selected.filter { $0.rackID == rack.id }, by: \.cell)
        Group {
            if rack.hasShelfLayers {
                layeredShelves(bottlesByCell, selectedByCell)
            } else {
                VStack(alignment: .leading, spacing: rowSpacing) {
                    if showsHeaders, rack.kind == .grid || rack.kind == .shelves {
                        columnHeader
                            .padding(.bottom, rack.kind == .shelves ? -rowSpacing + spacing : 0)
                    }
                    ForEach(0..<rack.rows, id: \.self) { row in
                        HStack(spacing: 0) {
                            if showsHeaders { rowHeader(row) }
                            cells(ofRow: row, bottlesByCell, selectedByCell)
                                .background(alignment: .bottom) {
                                    if rack.kind == .shelves { ShelfBar(height: max(4, cellSize * 0.09)).offset(y: cellSize * 0.2) }
                                }
                        }
                    }
                }
            }
        }
        .padding(isHoneycombFrame ? cellSize * 0.18 : 0)
        .background {
            if isHoneycombFrame || rack.kind == .grid {
                RoundedRectangle(cornerRadius: cellSize * 0.22, style: .continuous)
                    .fill(Color.secondary.opacity(rack.kind == .staggered ? 0.08 : 0.04))
                    .padding(.leading, showsHeaders ? headerWidth : 0)
                    .padding(-cellSize * 0.08)
            }
        }
    }

    /// The widest layer with its half-place nesting, and half a place more when the back
    /// band of a head-to-tail fridge moves right.
    private var bandsWidth: CGFloat {
        (CGFloat(rack.maxColumns) + 0.5 + CGFloat(rack.bandShift(depth: 1))) * (size + spacing)
    }

    private var headerWidth: CGFloat { max(18, cellSize * 0.42, rack.hasShelfLayers ? captionFloor * 1.7 : 0) }

    /// The places of one row, offset by half a place when the row nests into the one below.
    /// `depth` draws only the front or the back places of a fridge drawn in bands; head-to-tail,
    /// the back ones move half a place more, each neck between two bases.
    private func cells(ofRow row: Int, depth: Int? = nil, _ bottlesByCell: [RackCell: [Bottle]],
                       _ selectedByCell: [RackCell: [Slot]]) -> some View {
        HStack(spacing: spacing) {
            ForEach(0..<rack.columns(inRow: row), id: \.self) { column in
                let place = RackPlace(cell: RackCell(rackID: rack.id, row: row, column: column), depth: depth)
                let bottles = (bottlesByCell[place.cell] ?? []).filter { $0.slot.map(place.contains) ?? false }
                RackCellView(
                    rack: rack, place: place, bottles: bottles,
                    size: size, selectedCount: selectedByCell[place.cell]?.filter(place.contains).count ?? 0,
                    isHighlighted: highlighted.map(place.contains) == true || selectedPlace == place,
                    focusWine: focusWine, onTap: onTap, onDrop: onDrop, interaction: interaction
                )
            }
        }
        .padding(.leading, ((rack.isOffset(row: row) ? 0.5 : 0) + CGFloat(rack.bandShift(depth: depth))) * (size + spacing))
    }

    /// A fridge with bottles in layers: each shelf's layers nest into each other in
    /// quincunx, above the shelf; shelves are numbered from the top.
    private func layeredShelves(_ bottlesByCell: [RackCell: [Bottle]], _ selectedByCell: [RackCell: [Slot]]) -> some View {
        let bar = max(4, cellSize * 0.09)
        return VStack(alignment: .leading, spacing: cellSize * Self.shelfGap) {
            ForEach(Array(rack.shelfRows.enumerated()), id: \.offset) { shelf, rows in
                if rack.zoneCount > 1, shelf == 0 || rack.zone(ofShelf: shelf) != rack.zone(ofShelf: shelf - 1) {
                    zoneDivider(rack.zone(ofShelf: shelf))
                }
                HStack(spacing: 0) {
                    if showsHeaders {
                        Text(verbatim: "\(shelf + 1)")
                            .font(.system(size: max(captionFloor, cellSize * 0.24), weight: .semibold).monospacedDigit())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(width: headerWidth)
                            .accessibilityHidden(true)
                    }
                    Group {
                        if rack.hasDepthBands {
                            bands(shelf: shelf, rows: rows, bottlesByCell, selectedByCell)
                        } else {
                            VStack(alignment: .leading, spacing: rowSpacing) {
                                ForEach(rows, id: \.self) { row in cells(ofRow: row, bottlesByCell, selectedByCell) }
                            }
                        }
                    }
                    .padding(.bottom, bar + cellSize * 0.04)
                    .background(alignment: .bottom) { ShelfBar(height: bar) }
                }
            }
        }
    }

    /// Space between two shelves of layers, in places.
    static let shelfGap: CGFloat = 0.12

    // MARK: Front and back bands

    /// A shelf of a fridge two deep: its front bottles, then below them the same quincunx for
    /// the bottles behind, smaller and on a tray, each band under its caption. Every place of
    /// both bands is tapped directly.
    private func bands(shelf: Int, rows: Range<Int>, _ bottlesByCell: [RackCell: [Bottle]],
                       _ selectedByCell: [RackCell: [Slot]]) -> some View {
        let backSpacing = -(size * Self.backHeight + spacing) * 0.134
        return VStack(alignment: .leading, spacing: 0) {
            bandCaption(0, shelf: shelf)
            VStack(alignment: .leading, spacing: rowSpacing) {
                ForEach(rows, id: \.self) { row in cells(ofRow: row, depth: 0, bottlesByCell, selectedByCell) }
            }
            bandCaption(1, shelf: shelf)
                .padding(.top, cellSize * Self.bandGap)
            VStack(alignment: .leading, spacing: backSpacing) {
                ForEach(rows, id: \.self) { row in cells(ofRow: row, depth: 1, bottlesByCell, selectedByCell) }
            }
            .padding(.vertical, cellSize * Self.backPadding)
            .background {
                // The back band on a tray, so it reads apart from the front one.
                RoundedRectangle(cornerRadius: cellSize * 0.14, style: .continuous)
                    .fill(Color.secondary.opacity(0.07))
                    .padding(.horizontal, -cellSize * 0.04)
            }
        }
    }

    /// "Front · Bases facing you" or "Back · Necks facing you" (bases when not head-to-tail),
    /// a heading for VoiceOver that names the shelf.
    private func bandCaption(_ depth: Int, shelf: Int) -> some View {
        let caption: Text = if depth == 0 {
            Text("**Front** · Bases facing you")
        } else if rack.isBackReversed {
            Text("**Back** · Necks facing you")
        } else {
            Text("**Back** · Bases facing you")
        }
        // Never below the caption floor: with large text it wraps, then truncates.
        return caption
            .font(.system(size: max(captionFloor, cellSize * 0.2)))
            .foregroundStyle(.secondary)
            .lineLimit(2)
            .truncationMode(.tail)
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: bandsWidth, alignment: .leading)
            .frame(minHeight: max(Self.bandCaptionMinHeight, cellSize * Self.bandCaptionHeight), alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Shelf \(shelf + 1)") + Text(verbatim: ", ") + caption)
            .accessibilityAddTraits(.isHeader)
    }

    /// The back places, as a share of the front ones' height (their width stays, so each back
    /// place sits right under the front one of its cell).
    static let backHeight: CGFloat = 0.78
    /// A band's caption, in places, and at least in points.
    static let bandCaptionHeight: CGFloat = 0.3
    static let bandCaptionMinHeight: CGFloat = 13
    /// Space above the back band's caption, and around the back places on their tray, in places.
    static let bandGap: CGFloat = 0.06
    static let backPadding: CGFloat = 0.04

    /// Height of a shelf's two bands beyond its front layers, in places (captions apart).
    static func backBandHeight(layers: Int) -> CGFloat {
        let n = CGFloat(layers)
        return n * backHeight - (n - 1) * (backHeight + 0.04) * 0.134 + 2 * backPadding + bandGap
    }

    /// The name of a temperature zone over a line; the zones below the first one start
    /// under a thicker divider.
    private func zoneDivider(_ zone: Int) -> some View {
        HStack(spacing: cellSize * 0.12) {
            Text(verbatim: rack.zoneName(zone))
                .font(.system(size: max(captionFloor, cellSize * 0.2), weight: .semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize()
            Capsule()
                .fill(Color.secondary.opacity(zone == 0 ? 0.25 : 0.55))
                .frame(height: zone == 0 ? 1 : max(3, cellSize * 0.07))
        }
        // As wide as the shelves (the line would otherwise take all the width offered).
        .frame(width: bandsWidth, alignment: .leading)
        .frame(minHeight: max(Self.zoneLabelMinHeight, cellSize * Self.zoneLabelHeight), alignment: .leading)
        .padding(.leading, showsHeaders ? headerWidth : 0)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    /// Height of a zone's name, in places, and at least in points.
    static let zoneLabelHeight: CGFloat = 0.3
    static let zoneLabelMinHeight: CGFloat = 12

    /// Shelves are numbered from the top; other racks letter their rows.
    private func rowHeader(_ row: Int) -> some View {
        Text(verbatim: rack.kind == .shelves ? "\(row + 1)" : Rack.rowLetters(row))
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
    /// Largest cell when fitting: big enough to read names on an iPad or a Mac,
    /// small enough that a little rack does not look like a toy.
    static let maxFittedCell: CGFloat = 112

    /// The cell size at which the whole rack fits in a space, headers included.
    static func fittingCellSize(for rack: Rack, in space: CGSize, showsHeaders: Bool = true) -> CGFloat {
        let columns = CGFloat(rack.maxColumns), rows = CGFloat(rack.rows)
        var width: CGFloat, height: CGFloat
        /// Points taken whatever the cell size (zone names).
        var reserved: CGFloat = 0
        switch rack.kind {
        case .grid:
            width = columns * 1.08
            height = rows * 1.08 + (showsHeaders ? 0.4 : 0)
        case .staggered where rack.hasShelfLayers:
            // Each shelf: its first layer, the others nesting at 0.87 of a place, the shelf
            // bar, and a gap to the next shelf.
            width = (columns + 0.5 + CGFloat(rack.bandShift(depth: 1))) * 1.04
            height = rack.shelfRows.reduce(CGFloat(0)) { $0 + 1 + CGFloat($1.count - 1) * 0.87 + 0.15 }
                + CGFloat(max(0, rack.shelfRows.count - 1)) * shelfGap
            if rack.hasDepthBands {
                // Each shelf also has its back band and two captions.
                let shelves = CGFloat(rack.shelfRows.count)
                height += rack.shelfRows.reduce(CGFloat(0)) { $0 + backBandHeight(layers: $1.count) }
                    + shelves * 2 * bandCaptionHeight
                reserved += shelves * 2 * bandCaptionMinHeight
            }
            if rack.zoneCount > 1 {
                // Each zone's name: a share of a place, but never below its smallest height.
                let zones = CGFloat(rack.zoneCount)
                height += zones * (zoneLabelHeight + shelfGap)
                reserved += zones * zoneLabelMinHeight
            }
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
        let fit = min(space.width / width, max(0, space.height - reserved) / height)
        // From this size, grid and shelf cells also show the wine's name, so rows get taller.
        if fit >= RackCellView.nameSize, rack.kind == .grid || rack.kind == .shelves {
            let taller = height + rows * (RackCellView.nameHeightRatio - 1)
            let withNames = min(space.width / width, space.height / taller)
            return min(max(withNames >= RackCellView.nameSize ? withNames : min(fit, RackCellView.nameSize - 0.5), 26), Self.maxFittedCell)
        }
        return min(max(fit, 26), Self.maxFittedCell)
    }
}

/// A rack drawn as large as the space allows. Pinch to zoom; on the Mac, trackpad pinch
/// and the zoom buttons set `zoom`.
struct FittedRackGrid: View {
    let rack: Rack
    /// Mac: 1 fits the whole rack; more zooms in. iPhone and iPad zoom natively.
    var zoom: CGFloat = 1
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var selectedPlace: RackPlace?
    var focusWine: UUID?
    var onTap: (RackPlace, [Bottle]) -> Void = { _, _ in }
    var onDrop: ((UUID, RackPlace) -> Bool)?
    /// iPhone and iPad: a zoom step asked by a button.
    var zoomStep = RackZoomStep()
    var interaction = RackInteraction()

    static let padding: CGFloat = 16

    var body: some View {
        GeometryReader { proxy in
            let padding = Self.padding
            let space = CGSize(width: proxy.size.width - padding * 2, height: proxy.size.height - padding * 2)
            let base = RackGrid.fittingCellSize(for: rack, in: space)
            #if os(iOS)
            ZoomableRack(rack: rack, baseCellSize: base, selected: selected, highlighted: highlighted,
                         selectedPlace: selectedPlace, focusWine: focusWine, onTap: onTap, onDrop: onDrop,
                         zoomStep: zoomStep, interaction: interaction)
            #else
            if zoom <= 1, base > 26 {
                // The whole rack fits (its cells are not at the smallest size): no scroll
                // view, whose bars (always shown without a trackpad) would take room and
                // make the fitted rack scroll.
                RackGrid(rack: rack, cellSize: base * zoom, selected: selected, highlighted: highlighted,
                         selectedPlace: selectedPlace, focusWine: focusWine, onTap: onTap, onDrop: onDrop,
                         interaction: interaction)
                    .padding(padding)
                    .frame(width: proxy.size.width, height: proxy.size.height)
            } else {
                ScrollView([.horizontal, .vertical]) {
                    RackGrid(rack: rack, cellSize: base * zoom, selected: selected, highlighted: highlighted,
                             selectedPlace: selectedPlace, focusWine: focusWine, onTap: onTap, onDrop: onDrop,
                             interaction: interaction)
                        .padding(padding)
                        .frame(minWidth: proxy.size.width, minHeight: proxy.size.height)
                }
                .defaultScrollAnchor(.center)
            }
            #endif
        }
    }
}

/// A request to zoom in or out by `factor`; a new `id` makes it a new request.
struct RackZoomStep: Equatable {
    var id = 0
    var factor: CGFloat = 1

    func next(factor: CGFloat) -> RackZoomStep { RackZoomStep(id: id + 1, factor: factor) }
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

/// One cell: a bottle end (with what is behind it) or a whole bin; on a fridge drawn front
/// and back, one place of a cell, in front (a bottle's base) or behind (smaller: its neck when
/// head-to-tail, else its base).
struct RackCellView: View {
    @Environment(CellarModel.self) private var model
    let rack: Rack
    let place: RackPlace
    /// Bottles of the place, front first.
    let bottles: [Bottle]
    /// The width (and the height in front) of the place.
    let size: CGFloat
    var selectedCount = 0
    var isHighlighted = false
    var focusWine: UUID?
    var onTap: (RackPlace, [Bottle]) -> Void
    var onDrop: ((UUID, RackPlace) -> Bool)?
    var interaction = RackInteraction()
    @State private var isTargeted = false

    /// From this cell size the vintage is written on the bottle; from `nameSize`, the name below it.
    static let vintageSize: CGFloat = 34
    static let nameSize: CGFloat = 56
    /// Cells showing a name are this much taller than wide.
    static let nameHeightRatio: CGFloat = 1.3

    private func color(_ bottle: Bottle) -> WineColor? { model.cellar.wines[bottle.wineID]?.color }

    private var cell: RackCell { place.cell }
    /// A back place of a fridge drawn front and back.
    private var isBack: Bool { (place.depth ?? 0) > 0 }
    /// Bottles this place holds at most.
    private var capacity: Int { place.depth == nil ? rack.positionsPerCell : 1 }

    private var showsName: Bool { size >= Self.nameSize && (rack.kind == .grid || rack.kind == .shelves) }
    private var showsVintage: Bool { size >= Self.vintageSize && rack.kind != .bins }
    private var cellHeight: CGFloat {
        if isBack { return size * RackGrid.backHeight }
        return showsName ? size * Self.nameHeightRatio : size
    }

    /// With a wine chosen in the key: does this cell hold it?
    private var isFocused: Bool { focusWine.map { id in bottles.contains { $0.wineID == id } } ?? false }
    private var isDimmed: Bool { focusWine != nil && !isFocused }
    /// Checking a rack: some, not all, of this place's bottles were found.
    private var isPartlyFound: Bool {
        interaction.mode == .auditing && selectedCount > 0 && selectedCount < bottles.count
    }

    var body: some View {
        Button {
            onTap(place, bottles)
        } label: {
            Group {
                if rack.kind == .bins { bin } else if place.depth != nil { bandPlace } else { position }
            }
            .frame(width: size, height: cellHeight)
            .contentShape(hitShape)
            .opacity(isDimmed ? 0.25 : 1)
        }
        .buttonStyle(.plain)
        .pointerHighlight(hitShape)
        .modifier(DragAndDrop(bottle: capacity == 1 ? bottles.first : nil, place: place,
                              onDrop: onDrop, isTargeted: $isTargeted))
        .overlay {
            if isTargeted {
                RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                    .strokeBorder(Color.accentColor, lineWidth: 3)
                    .allowsHitTesting(false)
            }
        }
        .help(Text(verbatim: bottles.compactMap { model.cellar.wines[$0.wineID]?.displayName }.joined(separator: ", ")))
        .modifier(CheckMenu(isOn: canCheckOneByOne) { interaction.checkOneByOne?(cell) })
        .accessibilityLabel(accessibilityText)
        .accessibilityValue(checkValue ?? Text(verbatim: ""))
        .accessibilityHint(hintText ?? Text(verbatim: ""))
        .accessibilityActions { cellActions }
        // Back places: "rack.cell.1-0.back"; the others keep their identifier.
        .accessibilityIdentifier("rack.cell.\(cell.row)-\(cell.column)" + (isBack ? ".back" : ""))
        .accessibilityAddTraits(selectedCount > 0 ? .isSelected : [])
    }

    /// What a tap reaches: the drawn hole of a honeycomb or a fridge, the diamond of a bin
    /// (their rows nest into each other), else the whole cell.
    private var hitShape: AnyShape {
        switch rack.kind {
        case .staggered: AnyShape(Circle())
        case .bins: AnyShape(Diamond())
        case .grid, .shelves: AnyShape(Rectangle())
        }
    }

    /// While checking, a place holding several bottles can be checked bottle by bottle.
    private var canCheckOneByOne: Bool {
        interaction.mode == .auditing && bottles.count > 1 && interaction.checkOneByOne != nil
    }

    /// What a tap does here, for VoiceOver.
    private var hintText: Text? {
        switch interaction.mode {
        case .none:
            return nil
        case .browsing:
            return bottles.isEmpty ? Text("Adds a bottle here") : Text("Shows this bottle")
        case .moving:
            if bottles.count < capacity { return Text("Puts the bottle here") }
            return capacity == 1 ? Text("Swaps the two bottles") : nil
        case .auditing:
            return bottles.isEmpty ? nil : Text("Marks as found")
        }
    }

    /// VoiceOver actions: those of the bottle card, or checking bottle by bottle.
    @ViewBuilder
    private var cellActions: some View {
        if interaction.mode == .browsing, capacity == 1, let bottle = bottles.first,
           let perform = interaction.perform {
            Button("Move") { perform(.move, bottle) }
            Button("Drink") { perform(.drink, bottle) }
            Button("Wine Page") { perform(.winePage, bottle) }
        }
        if canCheckOneByOne {
            Button("Check One by One…") { interaction.checkOneByOne?(cell) }
        }
    }

    // MARK: Single position (grid, honeycomb, shelf)

    private var position: some View {
        let front = bottles.first { $0.slot?.depth == 0 }
        let shown = front ?? bottles.first
        let wine = shown.flatMap { model.cellar.wines[$0.wineID] }
        return VStack(spacing: size * 0.03) {
            bottleSquare
                .frame(width: size, height: size)
            if showsName {
                Text(verbatim: wine?.name ?? "")
                    .font(.system(size: max(9, size * 0.15), weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(width: size * 0.96)
                    .foregroundStyle(isFocused ? Color.accentColor : Color.primary)
            }
        }
        .background {
            if rack.kind == .grid {
                RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                    .fill(Color.secondary.opacity(0.08))
                    .overlay(
                        RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                            .strokeBorder(isFocused ? Color.accentColor : Color.secondary.opacity(0.18),
                                          lineWidth: isFocused ? 2 : 1)
                    )
            }
        }
    }

    /// The bottle end, with the vintage written on it when there is room.
    private var bottleSquare: some View {
        let front = bottles.first { $0.slot?.depth == 0 }
        let behind = bottles.filter { ($0.slot?.depth ?? 0) > 0 }
        return ZStack {
            if rack.kind == .staggered {
                Circle().fill(Color.secondary.opacity(0.1))
            }
            if let front {
                BottleEnd(color: color(front), size: size * 0.72, isHighlighted: isHighlighted || isFocused)
                if showsVintage { vintageLabel(front, diameter: size * 0.72) }
            } else if let back = behind.first {
                // Nothing in front: show the bottle behind, smaller and faded.
                BottleEnd(color: color(back), size: size * 0.52, isHighlighted: isHighlighted)
                    .opacity(0.55)
            } else {
                BottleEnd(color: nil, size: size * 0.72, isEmpty: true, isHighlighted: isHighlighted)
                if selectedCount == 0 {
                    Text(verbatim: rack.emptyCaption(for: cell))
                        .font(.system(size: max(9, size * 0.18), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            if isPartlyFound {
                partlyFoundBadge
            } else if selectedCount > 0 {
                selectionBadge(diameter: size * 0.72)
            }
        }
        .overlay(alignment: .bottom) {
            if rack.positionsPerCell > 1 { depthDots.offset(y: rack.kind == .shelves && !showsName ? size * 0.16 : size * 0.02) }
        }
    }

    // MARK: One place of a fridge drawn front and back

    /// In front, a bottle's base as large as a honeycomb place; behind, smaller: the neck and
    /// capsule of a bottle lying head-to-tail, or its base.
    private var bandPlace: some View {
        let bottle = bottles.first
        let diameter = isBack ? size * RackGrid.backHeight * 0.74 : size * 0.72
        return ZStack {
            let backdrop = isBack ? size * RackGrid.backHeight * 0.96 : size
            Circle().fill(Color.secondary.opacity(0.1))
                .frame(width: backdrop, height: backdrop)
            if let bottle {
                if isBack, rack.isBackReversed {
                    BottleNeck(color: color(bottle), size: diameter, isHighlighted: isHighlighted || isFocused)
                } else {
                    BottleEnd(color: color(bottle), size: diameter, isHighlighted: isHighlighted || isFocused)
                    if showsVintage, !isBack { vintageLabel(bottle, diameter: diameter) }
                }
            } else {
                BottleEnd(color: nil, size: diameter, isEmpty: true, isHighlighted: isHighlighted)
                if selectedCount == 0 {
                    // The place number: the shelf is on the side, the band in its caption.
                    Text(verbatim: rack.emptyCaption(for: cell))
                        .font(.system(size: max(9, diameter * 0.25), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .minimumScaleFactor(0.7)
                        .lineLimit(1)
                }
            }
            if selectedCount > 0 {
                selectionBadge(diameter: diameter)
            }
        }
        .frame(width: size, height: cellHeight)
    }

    /// The vintage on the bottle end ("2017", or "NV"), in a color readable on that wine's tint.
    private func vintageLabel(_ bottle: Bottle, diameter: CGFloat) -> some View {
        let wine = model.cellar.wines[bottle.wineID]
        let dark = wine?.color?.prefersDarkLabel ?? true
        return Group {
            if let vintage = wine?.vintage {
                Text(verbatim: "\(vintage)")
            } else {
                Text("NV")
            }
        }
        .font(.system(size: max(8, diameter * 0.27), weight: .bold).monospacedDigit())
        .minimumScaleFactor(0.5)
        .lineLimit(1)
        .frame(width: diameter * 0.82)
        .foregroundStyle(dark ? Color.black.opacity(0.78) : Color.white)
        .accessibilityHidden(true)
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
                        .strokeBorder(isHighlighted || isFocused ? Color.accentColor : Color.secondary.opacity(0.3),
                                      lineWidth: isHighlighted || isFocused ? 3 : 1.5)
                )
                .rotationEffect(.degrees(45))
                .scaleEffect(0.7)
            VStack(spacing: size * 0.03) {
                Pile(colors: bottles.map(color), size: size * 0.11)
                Text(verbatim: "\(bottles.count)/\(capacity)")
                    .font(.system(size: max(10, size * 0.13), weight: .semibold).monospacedDigit())
                    .foregroundStyle(isFull ? Color.primary : Color.secondary)
                if selectedCount > 0 {
                    // Checking: found of the bottles there; choosing places: how many are chosen.
                    Text(verbatim: interaction.mode == .auditing ? "✓ \(selectedCount)/\(bottles.count)" : "+\(selectedCount)")
                        .font(.system(size: max(10, size * 0.12), weight: .bold).monospacedDigit())
                        .foregroundStyle(Color.accentColor)
                }
            }
            .offset(y: size * 0.04)
        }
    }

    /// A chosen or found place: a ring around the bottle and, in its top corner, a white
    /// tick on the accent color ringed with the background, readable on any wine's color and
    /// clear of the vintage. Choosing several bottles of a cell shows "+2" instead.
    @ViewBuilder
    private func selectionBadge(diameter: CGFloat) -> some View {
        if capacity > 1 && interaction.mode != .auditing {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.25))
                    .overlay(Circle().strokeBorder(Color.accentColor, lineWidth: 2))
                    .frame(width: diameter * 1.03, height: diameter * 1.03)
                Text(verbatim: "+\(selectedCount)")
                    .font(.system(size: diameter * 0.33, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.accentColor)
            }
        } else {
            let badge = max(12, diameter * 0.42)
            Circle()
                .strokeBorder(Color.accentColor, lineWidth: max(2, diameter * 0.06))
                .frame(width: diameter * 1.06, height: diameter * 1.06)
                .overlay(alignment: .topTrailing) {
                    Image(systemName: "checkmark")
                        .font(.system(size: badge * 0.55, weight: .heavy))
                        .foregroundStyle(.white)
                        .frame(width: badge, height: badge)
                        .background(Color.accentColor, in: Circle())
                        .overlay(Circle().strokeBorder(.background, lineWidth: max(1.5, badge * 0.12)))
                        .offset(x: badge * 0.22, y: -badge * 0.22)
                }
                .accessibilityHidden(true)
        }
    }

    /// Some of the bottles found while checking: "8/10".
    private var partlyFoundBadge: some View {
        Text(verbatim: "\(selectedCount)/\(bottles.count)")
            .font(.system(size: max(9, size * 0.2), weight: .bold).monospacedDigit())
            .foregroundStyle(Color.accentColor)
            .padding(.horizontal, size * 0.06)
            .background(.regularMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(Color.accentColor, lineWidth: 1.5))
    }

    /// The wine, its style and maturity in words (in the drawing the style is only the
    /// bottle's color): "Château X 2015, Red, Ready".
    private func wineWords(_ bottle: Bottle) -> String? {
        model.cellar.wines[bottle.wineID].map { wine in
            let maturity = Maturity.of(wine, year: model.currentYear).shortName
            return [wine.displayName, wine.color?.name, maturity].compactMap { $0 }.joined(separator: ", ")
        }
    }

    /// What a bin holds, most bottles first: "2 Château X 2015, 1 Y", the first three wines.
    private var binContents: String? {
        var counts: [UUID: Int] = [:]
        for bottle in bottles { counts[bottle.wineID, default: 0] += 1 }
        let wines = counts.compactMap { id, count in model.cellar.wines[id].map { ($0.displayName, count) } }
            .sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0 < $1.0 }
        guard !wines.isEmpty else { return nil }
        var parts = wines.prefix(3).map { "\($0.1) \($0.0)" }
        if wines.count > 3 {
            let others = wines.count - 3
            parts.append(String(localized: "\(others) more"))
        }
        return parts.joined(separator: ", ")
    }

    /// While checking: has this place been found?
    private var checkValue: Text? {
        guard interaction.mode == .auditing, !bottles.isEmpty, !isPartlyFound else { return nil }
        return selectedCount > 0 ? Text("Found") : Text("Not found yet")
    }

    /// Place, wine, style, then state: "B3: front Château X, Red; back empty, shown".
    private var accessibilityText: Text {
        let place = self.place.slot.map(rack.displayLabel(for:)) ?? rack.displayLabel(for: cell)
        var label: String
        if rack.kind == .bins {
            label = String(localized: "\(place), \(bottles.count) of \(rack.positionsPerCell) bottles")
            if let contentsLabel = binContents { label = String(localized: "\(label): \(contentsLabel)") }
        } else if capacity == 1 {
            if let words = bottles.first.flatMap(wineWords) {
                label = "\(place), \(words)"
            } else {
                label = String(localized: "\(place), empty")
            }
        } else {
            let depthsLabel = self.depthsLabel
            label = String(localized: "\(place): \(depthsLabel)")
        }
        if isPartlyFound {
            let found = selectedCount, total = bottles.count
            label = String(localized: "\(label), \(found) of \(total) found")
        }
        if isHighlighted || isFocused {
            label = String(localized: "\(label), shown")
        } else if isDimmed {
            label = String(localized: "\(label), other wine")
        }
        return Text(verbatim: label)
    }

    /// Each place of the cell, front first: "front Château X, Red; back empty".
    private var depthsLabel: String {
        (0..<rack.positionsPerCell).map { depth -> String in
            let depthName = rack.depthName(depth)
            if let words = bottles.first(where: { $0.slot?.depth == depth }).flatMap(wineWords) {
                return "\(depthName) \(words)"
            }
            return String(localized: "\(depthName) empty")
        }
        .joined(separator: "; ")
    }
}

/// A diamond touching the middle of each side, as a bin is drawn.
private struct Diamond: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.midX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.midY))
            path.closeSubpath()
        }
    }
}

/// A context menu to check a place's bottles one by one, only while it applies.
private struct CheckMenu: ViewModifier {
    let isOn: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        if isOn {
            content.contextMenu {
                Button("Check One by One…", systemImage: "checklist", action: action)
            }
        } else {
            content
        }
    }
}

/// Drag a bottle (single places only) and drop bottles on any place, when the
/// rack screen handles drops.
private struct DragAndDrop: ViewModifier {
    let bottle: Bottle?
    let place: RackPlace
    let onDrop: ((UUID, RackPlace) -> Bool)?
    @Binding var isTargeted: Bool

    func body(content: Content) -> some View {
        if let onDrop {
            content
                .modifier(Draggable(bottle: bottle))
                .dropDestination(for: BottleDrag.self) { items, _ in
                    guard let id = items.first?.bottleID else { return false }
                    return onDrop(id, place)
                } isTargeted: { isTargeted = $0 }
        } else {
            content
        }
    }
}

private struct Draggable: ViewModifier {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle?

    func body(content: Content) -> some View {
        if let bottle {
            content.draggable(BottleDrag(bottleID: bottle.id, name: model.cellar.wines[bottle.wineID]?.displayName ?? ""))
        } else {
            content
        }
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
        let byCell = model.cellar.bottlesByCell(in: rack)
        if rack.hasShelfLayers {
            // Shelves of layers nesting into each other, each above a shelf.
            VStack(alignment: .leading, spacing: 3) {
                ForEach(Array(shownShelves.enumerated()), id: \.offset) { _, rows in
                    VStack(alignment: .leading, spacing: -1.5) {
                        ForEach(rows, id: \.self) { row in places(row, byCell) }
                    }
                    .padding(.bottom, 3)
                    .background(alignment: .bottom) {
                        Capsule().fill(Color.secondary.opacity(0.35)).frame(height: 2)
                    }
                }
            }
            .accessibilityHidden(true)
        } else {
            VStack(alignment: .leading, spacing: 3) {
                ForEach(0..<min(rack.rows, 6), id: \.self) { row in places(row, byCell) }
            }
            .accessibilityHidden(true)
        }
    }

    /// The shelves that fit in the card: up to eight layers.
    private var shownShelves: [Range<Int>] {
        var shown: [Range<Int>] = []
        for rows in rack.shelfRows where (shown.last?.upperBound ?? 0) + rows.count <= 8 { shown.append(rows) }
        return shown.isEmpty ? Array(rack.shelfRows.prefix(1)) : shown
    }

    private func places(_ row: Int, _ byCell: [RackCell: [Bottle]]) -> some View {
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
        .padding(.leading, rack.kind == .staggered && rack.isOffset(row: row) ? 6 : 0)
    }
}
