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
    /// Move, Drink, Show Wine on a place holding one bottle, when browsing.
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
    /// Drawn only (the overview of a rack that scrolls): no buttons, identifiers or
    /// accessibility elements, so neither VoiceOver nor UI tests find its places twice.
    var isPicture = false
    /// The width the fridge's band captions may take, when wider than the shelves (a small
    /// preview, where they would be cut).
    var captionWidth: CGFloat?
    /// The smallest size of the fridge's captions (shelf numbers, front and back, zones),
    /// growing with Dynamic Type.
    @ScaledMetric(relativeTo: .caption2) private var captionFloor: CGFloat = 11
    /// The smallest size of the row letters and column numbers, growing with Dynamic Type
    /// up to a size that still leaves room for the places.
    @ScaledMetric(relativeTo: .caption2) private var scaledHeaderFloor: CGFloat = 10
    private var headerFloor: CGFloat { min(scaledHeaderFloor, 18) }

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

    /// A diamond rack with edge triangles: where they are.
    private var diamondLayout: DiamondLayout? { rack.diamondLayout }

    /// The side of a row of triangles (top or bottom), nil for a row of diamonds.
    private func triangleRow(_ row: Int) -> DiamondEdge? {
        guard let layout = diamondLayout, case let .triangle(edge, _) = layout.place(row: row, column: 0),
              edge == .top || edge == .bottom else { return nil }
        return edge
    }

    /// How far right a row starts, in places: half a place on offset rows, unless a left
    /// triangle (half a place wide) fills that notch.
    private func leadingShift(ofRow row: Int, depth: Int?) -> CGFloat {
        if let layout = diamondLayout, let first = layout.drawingColumns(row: row).first,
           case .triangle(.left, _) = layout.place(row: row, column: first) {
            return 0
        }
        return (rack.isOffset(row: row) ? 0.5 : 0) + CGFloat(rack.bandShift(row: row, depth: depth))
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
                    // A diamond rack with edge triangles draws its rows of triangles above and below
                    // the rows of diamonds, whatever their numbers.
                    ForEach(diamondLayout?.drawingRows ?? Array(0..<rack.rows), id: \.self) { row in
                        HStack(spacing: 0) {
                            if showsHeaders { rowHeader(row) }
                            cells(ofRow: row, bottlesByCell, selectedByCell)
                                .background(alignment: .bottom) {
                                    if rack.kind == .shelves { ShelfBar(height: max(4, cellSize * 0.09)).offset(y: cellSize * 0.2) }
                                }
                        }
                        // A row of edge triangles fills the notches along the first or last row of
                        // diamonds: only its half next to the diamonds takes room.
                        .padding(.top, triangleRow(row) == .top ? -size / 2 : 0)
                        .padding(.bottom, triangleRow(row) == .bottom ? -size / 2 : 0)
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
        (CGFloat(rack.maxColumns) + 0.5 + CGFloat(rack.maxBandShift)) * (size + spacing)
    }

    private var headerWidth: CGFloat {
        max(18, cellSize * 0.42, headerFloor * 1.6, rack.hasShelfLayers ? captionFloor * 1.7 : 0)
    }

    /// The places of one row, offset by half a place when the row nests into the one below.
    /// `depth` draws only the front or the back places of a fridge drawn in bands; head-to-tail,
    /// the back ones move half a place more, each neck between two bases.
    private func cells(ofRow row: Int, depth: Int? = nil, _ bottlesByCell: [RackCell: [Bottle]],
                       _ selectedByCell: [RackCell: [Slot]]) -> some View {
        HStack(spacing: spacing) {
            ForEach(diamondLayout?.drawingColumns(row: row) ?? Array(0..<rack.columns(inRow: row)), id: \.self) { column in
                let place = RackPlace(cell: RackCell(rackID: rack.id, row: row, column: column), depth: depth)
                let bottles = (bottlesByCell[place.cell] ?? []).filter { $0.slot.map(place.contains) ?? false }
                RackCellView(
                    rack: rack, place: place, bottles: bottles,
                    size: size, selectedCount: selectedByCell[place.cell]?.filter(place.contains).count ?? 0,
                    isHighlighted: highlighted.map(place.contains) == true || selectedPlace == place,
                    focusWine: focusWine, onTap: onTap, onDrop: onDrop, interaction: interaction,
                    isPicture: isPicture
                )
            }
        }
        .padding(.leading, leadingShift(ofRow: row, depth: depth) * (size + spacing))
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
                        if rack.hasDepthBands(shelf: shelf) {
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
        } else if rack.isBackReversed(shelf: shelf) {
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
            .frame(width: max(bandsWidth, captionWidth ?? 0), alignment: .leading)
            .frame(minHeight: max(Self.bandCaptionMinHeight, cellSize * Self.bandCaptionHeight), alignment: .leading)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Shelf \(shelf + 1)") + Text(verbatim: ", ") + caption)
            .accessibilityAddTraits(.isHeader)
    }

    /// The back places, as a share of the front ones' height (their width stays, so each back
    /// place sits right under the front one of its cell).
    static let backHeight: CGFloat = RackViewport.backPlaceHeight
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
        Text(verbatim: rowHeaderText(row))
            .font(.system(size: max(headerFloor, cellSize * 0.24), weight: .semibold).monospacedDigit())
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .frame(width: headerWidth)
            .accessibilityHidden(true)
    }

    /// "3" for a shelf, "B" for a row; a diamond rack with triangles letters its rows of
    /// diamonds only.
    private func rowHeaderText(_ row: Int) -> String {
        if rack.kind == .shelves { return "\(row + 1)" }
        return triangleRow(row) == nil ? Rack.rowLetters(row) : ""
    }

    private var columnHeader: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: headerWidth, height: 1)
            HStack(spacing: spacing) {
                ForEach(0..<rack.maxColumns, id: \.self) { column in
                    Text(verbatim: "\(column + 1)")
                        .font(.system(size: max(headerFloor, cellSize * 0.22), weight: .medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
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

    /// The cell size at which the whole rack fits in a space, headers included, and at least `floor`.
    static func fittingCellSize(for rack: Rack, in space: CGSize, showsHeaders: Bool = true, floor: CGFloat = 26) -> CGFloat {
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
            width = (columns + 0.5 + CGFloat(rack.maxBandShift)) * 1.04
            height = rack.shelfRows.reduce(CGFloat(0)) { $0 + 1 + CGFloat($1.count - 1) * 0.87 + 0.15 }
                + CGFloat(max(0, rack.shelfRows.count - 1)) * shelfGap
            if rack.hasDepthBands {
                // Each shelf two deep also has its back band and two captions.
                let banded = rack.shelfRows.indices.filter(rack.hasDepthBands(shelf:))
                let shelves = CGFloat(banded.count)
                height += banded.reduce(CGFloat(0)) { $0 + backBandHeight(layers: rack.shelfRows[$1].count) }
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
            return min(max(withNames >= RackCellView.nameSize ? withNames : min(fit, RackCellView.nameSize - 0.5), floor), Self.maxFittedCell)
        }
        return min(max(fit, floor), Self.maxFittedCell)
    }
}

/// A rack drawn as large as the space allows, but never with places under 44 pt: a larger
/// rack is drawn at that size, scrolls both ways under an overview of the whole rack, and
/// zooms in from there. Pinch to zoom; on the Mac, trackpad pinch and the zoom buttons set `zoom`.
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
    /// Mac: told the smallest zoom that still changes the drawing (`RackZoom.minimum`), for Zoom Out.
    var onMinimumZoom: ((CGFloat) -> Void)?

    @State private var tracker = RackViewportTracker()
    #if os(macOS)
    @State private var position = ScrollPosition()
    #endif

    static let padding: CGFloat = 16
    /// Height of the overview above a rack that scrolls.
    static let overviewHeight: CGFloat = 56
    /// Narrower than this (a tall wine fridge), the overview cannot be read: it is left out.
    static let overviewMinWidth: CGFloat = 48
    @State private var overviewReadable = true

    var body: some View {
        GeometryReader { proxy in
            let padding = Self.padding
            let space = CGSize(width: proxy.size.width - padding * 2, height: proxy.size.height - padding * 2)
            let fitted = RackGrid.fittingCellSize(for: rack, in: space, floor: 0)
            let minimum = RackViewport.minimumCellSize(for: rack)
            let nearFit = RackViewport.minimumCellSize(for: rack, target: RackViewport.nearFitTarget)
            let scrolls = RackViewport.scrolls(fitted: fitted, minimum: minimum, nearFit: nearFit)
            let base = RackViewport.cellSize(fitted: fitted, minimum: minimum, nearFit: nearFit)
            VStack(spacing: 0) {
                if scrolls {
                    RackOverview(rack: rack, tracker: tracker, selected: selected, highlighted: highlighted,
                                 focusWine: focusWine) { overviewReadable = $0 }
                        .frame(height: overviewReadable ? Self.overviewHeight : 0)
                        .opacity(overviewReadable ? 1 : 0)
                        .padding(.horizontal, padding)
                        .padding(.vertical, overviewReadable ? 6 : 0)
                    if overviewReadable { Divider() }
                }
                drawing(base: base, scrolls: scrolls)
            }
            #if os(macOS)
            .onChange(of: RackZoom.minimum(base: base, minimum: minimum), initial: true) { _, value in
                onMinimumZoom?(value)
            }
            #endif
        }
    }

    @ViewBuilder
    private func drawing(base: CGFloat, scrolls: Bool) -> some View {
        #if os(iOS)
        ZoomableRack(rack: rack, baseCellSize: base, selected: selected, highlighted: highlighted,
                     selectedPlace: selectedPlace, focusWine: focusWine, onTap: onTap, onDrop: onDrop,
                     zoomStep: zoomStep, interaction: interaction, tracker: tracker, alignsTop: true)
        #else
        let minimum = RackViewport.minimumCellSize(for: rack)
        let cellSize = RackZoom.cellSize(base: base, minimum: minimum, zoom: zoom)
        GeometryReader { proxy in
            if !scrolls, cellSize <= base {
                // The whole rack fits: no scroll view, whose bars (always shown without a
                // trackpad) would take room and make the fitted rack scroll.
                grid(cellSize)
                    .padding(Self.padding)
                    // Right under the navigation bar, not centered in the space.
                    .frame(width: proxy.size.width, height: proxy.size.height, alignment: .top)
            } else {
                ScrollView([.horizontal, .vertical]) {
                    grid(cellSize)
                        .padding(Self.padding)
                        // Right under the navigation bar (under the overview of a rack that scrolls).
                        .frame(minWidth: proxy.size.width, minHeight: proxy.size.height, alignment: .top)
                }
                .scrollPosition($position)
                .defaultScrollAnchor(scrolls ? .topLeading : .center)
                .onScrollGeometryChange(for: RackViewportTracker.Geometry.self) { geometry in
                    RackViewportTracker.Geometry(content: geometry.contentSize, view: geometry.containerSize,
                                                 offset: geometry.contentOffset)
                } action: { _, geometry in
                    tracker.report(geometry, cellSize: cellSize)
                }
                .onAppear {
                    tracker.jump = { point in
                        position.scrollTo(point: RackViewport.offset(centering: point, content: tracker.geometry.content,
                                                                     view: tracker.geometry.view))
                    }
                }
                .onChange(of: highlighted, initial: true) { _, slot in
                    // A bottle to find: bring its place into view.
                    if let slot, slot.rackID == rack.id { tracker.show(slot, in: rack) }
                }
            }
        }
        #endif
    }

    #if os(macOS)
    private func grid(_ cellSize: CGFloat) -> some View {
        RackGrid(rack: rack, cellSize: cellSize, selected: selected, highlighted: highlighted,
                 selectedPlace: selectedPlace, focusWine: focusWine, onTap: onTap, onDrop: onDrop,
                 interaction: interaction)
    }
    #endif
}

/// Where a rack that scrolls is: the part in view and the size it is drawn at, for its
/// overview; and how to bring another part into view. Read by the overview only, so
/// scrolling does not draw the rack again.
@MainActor @Observable
final class RackViewportTracker {
    struct Geometry: Equatable {
        var content: CGSize = .zero
        var view: CGSize = .zero
        var offset: CGPoint = .zero
    }

    /// The part in view, as fractions of the drawing.
    private(set) var visible = CGRect(x: 0, y: 0, width: 1, height: 1)
    /// The cell size the rack is drawn at.
    private(set) var cellSize: CGFloat = 0
    @ObservationIgnored private(set) var geometry = Geometry()
    /// Brings a point of the drawing (fractions of it) to the middle of the view, as far as it goes.
    @ObservationIgnored var jump: (@MainActor (CGPoint) -> Void)? {
        didSet { showPending() }
    }
    /// A place to bring into view once the drawing is laid out (fractions of the rack).
    @ObservationIgnored private var pending: CGPoint?

    func report(_ geometry: Geometry, cellSize: CGFloat) {
        self.geometry = geometry
        let visible = RackViewport.visibleArea(content: geometry.content, view: geometry.view, offset: geometry.offset)
        if visible != self.visible { self.visible = visible }
        if cellSize != self.cellSize { self.cellSize = cellSize }
        showPending()
    }

    /// Brings a bottle's place into view (a bottle to find).
    func show(_ slot: Slot, in rack: Rack) {
        pending = RackViewport.center(of: slot.cell, depth: RackPlace(slot, in: rack).depth, in: rack)
        showPending()
    }

    private func showPending() {
        let content = geometry.content
        guard let point = pending, let jump, content.width > 0, content.height > 0 else { return }
        pending = nil
        // The rack is drawn inside a padding.
        let padding = FittedRackGrid.padding
        jump(CGPoint(x: (padding + point.x * max(0, content.width - 2 * padding)) / content.width,
                     y: (padding + point.y * max(0, content.height - 2 * padding)) / content.height))
    }
}

/// The whole rack, small, above a rack that scrolls: the part in view is framed. A tap, or a
/// drag, brings that part into view. Hidden from VoiceOver, which moves from place to place
/// and scrolls to each one.
private struct RackOverview: View {
    let rack: Rack
    let tracker: RackViewportTracker
    var selected: Set<Slot> = []
    var highlighted: Slot?
    var focusWine: UUID?
    /// Whether the rack, scaled to the overview's height, is wide enough to be read.
    var onReadable: (Bool) -> Void = { _ in }
    /// The rack's own size, before scaling it down.
    @State private var natural: CGSize = .zero

    var body: some View {
        GeometryReader { proxy in
            let scale = natural.width > 0 && natural.height > 0
                ? min(proxy.size.width / natural.width, proxy.size.height / natural.height, 1) : 0
            let shown = CGSize(width: natural.width * scale, height: natural.height * scale)
            // Drawn as in the view, then scaled down, so the frame matches it exactly.
            RackGrid(rack: rack, cellSize: max(1, tracker.cellSize), selected: selected, highlighted: highlighted,
                     focusWine: focusWine, isPicture: true)
                .padding(FittedRackGrid.padding)
                .fixedSize()
                .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                    natural = size
                    guard size.height > 0 else { return }
                    onReadable(size.width / size.height * FittedRackGrid.overviewHeight >= FittedRackGrid.overviewMinWidth)
                }
                .scaleEffect(scale, anchor: .topLeading)
                .frame(width: shown.width, height: shown.height, alignment: .topLeading)
                .allowsHitTesting(false)
                .overlay { ViewportFrame(tracker: tracker) }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0).onChanged { value in
                        guard shown.width > 0, shown.height > 0 else { return }
                        tracker.jump?(CGPoint(x: min(1, max(0, value.location.x / shown.width)),
                                              y: min(1, max(0, value.location.y / shown.height))))
                    }
                )
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .opacity(tracker.cellSize > 0 ? 1 : 0)
        // One hidden element without children: none of the small places is exposed.
        .accessibilityElement(children: .ignore)
        .accessibilityHidden(true)
    }
}

/// The part of the rack in view, framed on its overview.
private struct ViewportFrame: View {
    let tracker: RackViewportTracker

    var body: some View {
        GeometryReader { proxy in
            let area = tracker.visible
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(Color.accentColor.opacity(0.12))
                .overlay(RoundedRectangle(cornerRadius: 3, style: .continuous).strokeBorder(Color.accentColor, lineWidth: 2))
                .frame(width: max(8, area.width * proxy.size.width), height: max(8, area.height * proxy.size.height))
                .offset(x: area.minX * proxy.size.width, y: area.minY * proxy.size.height)
        }
        .allowsHitTesting(false)
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
    /// Drawn only, in an overview: no button, identifier or accessibility.
    var isPicture = false
    @State private var isTargeted = false

    /// From this cell size the vintage is written on the bottle; from `nameSize`, the name below it.
    static let vintageSize: CGFloat = 34
    static let nameSize: CGFloat = 56
    /// Cells showing a name are this much taller than wide.
    static let nameHeightRatio: CGFloat = 1.3

    private func color(_ bottle: Bottle) -> WineColor? { model.cellar.wines[bottle.wineID]?.color }

    private var cell: RackCell { place.cell }
    /// An edge triangle of a diamond rack: its side.
    private var triangle: DiamondEdge? { rack.kind == .bins ? rack.triangleEdge(of: cell) : nil }
    /// A back place of a fridge drawn front and back.
    private var isBack: Bool { (place.depth ?? 0) > 0 }
    /// Bottles this place holds at most.
    /// A cell's places: its row's depth, and as deep as its bottles go (one an older version
    /// put behind a single row stays in view).
    private var capacity: Int {
        guard place.depth == nil else { return 1 }
        // Per cell: an edge triangle holds fewer bottles than a whole bin.
        return rack.shownPositions(of: cell, deepest: bottles.compactMap(\.slot?.depth).max())
    }

    /// Browsing a rack: empty places stay quiet (no name, a light outline), so the bottles
    /// stand out. Choosing a place (moving, checking, pickers) still names them.
    private var isQuiet: Bool { interaction.mode == .browsing }

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

    private var drawing: some View {
        Group {
            if let triangle { triangleBin(triangle) } else if rack.kind == .bins { bin } else if place.depth != nil { bandPlace } else { position }
        }
        // A left or right triangle fills half a place.
        .frame(width: triangle == .left || triangle == .right ? size / 2 : size, height: cellHeight)
        .contentShape(hitShape)
        .opacity(isDimmed ? 0.25 : 1)
    }

    var body: some View {
        if isPicture {
            drawing.accessibilityHidden(true)
        } else {
            interactive(drawing)
        }
    }

    /// The drawing as a place to tap, drop on and reach with VoiceOver.
    private func interactive(_ drawing: some View) -> some View {
        Button {
            onTap(place, bottles)
        } label: {
            drawing
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
        case .bins: triangle.map { AnyShape(EdgeTriangle(edge: $0)) } ?? AnyShape(Diamond())
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
            if bottles.isEmpty { return Text("Adds a bottle here") }
            return capacity == 1 ? Text("Shows this bottle") : Text("Shows the bottles here")
        case .moving:
            // The moving bottle's own position: a tap there stops the move.
            if selectedCount > 0 { return Text("Cancels the move") }
            if bottles.count < capacity { return Text("Puts the bottle here") }
            return capacity == 1 ? Text("Swaps the two bottles") : nil
        case .auditing:
            if bottles.isEmpty { return nil }
            return selectedCount >= bottles.count ? Text("Marks as not found") : Text("Marks as found")
        }
    }

    /// VoiceOver actions: those of the bottle card, or checking bottle by bottle.
    @ViewBuilder
    private var cellActions: some View {
        if interaction.mode == .browsing, capacity == 1, let bottle = bottles.first,
           let perform = interaction.perform {
            Button("Move") { perform(.move, bottle) }
            Button("Drink") { perform(.drink, bottle) }
            Button("Show Wine") { perform(.winePage, bottle) }
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
                    // Inside the cell's rounded border, which is 2 pt when highlighted.
                    .frame(width: size * 0.8)
                    .padding(.bottom, size * 0.05)
                    .foregroundStyle(isFocused ? Color.accentColor : Color.primary)
            }
        }
        .background {
            if rack.kind == .grid {
                RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                    .fill(Color.secondary.opacity(isQuiet && bottles.isEmpty ? 0.04 : 0.08))
                    .overlay(
                        RoundedRectangle(cornerRadius: size * 0.16, style: .continuous)
                            .strokeBorder(isFocused ? Color.accentColor : Color.secondary.opacity(isQuiet && bottles.isEmpty ? 0.1 : 0.18),
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
                BottleEnd(color: color(front), size: size * 0.72, isHighlighted: isHighlighted || isFocused, showsStyle: true)
                if showsVintage { vintageLabel(front, diameter: size * 0.72) }
            } else if let back = behind.first {
                // Nothing in front: show the bottle behind, smaller and faded.
                BottleEnd(color: color(back), size: size * 0.52, isHighlighted: isHighlighted, showsStyle: true)
                    .opacity(0.55)
            } else {
                BottleEnd(color: nil, size: size * 0.72, isEmpty: true, isQuiet: isQuiet, isHighlighted: isHighlighted)
                // A picture (preview, overview) shows the shape only: numbers would crowd it.
                if selectedCount == 0, !isPicture, !isQuiet {
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
            if capacity > 1 { depthDots.offset(y: rack.kind == .shelves && !showsName ? size * 0.16 : size * 0.02) }
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
                if isBack, rack.isBackReversed(row: cell.row) {
                    BottleNeck(color: color(bottle), size: diameter, isHighlighted: isHighlighted || isFocused, showsStyle: true)
                } else {
                    BottleEnd(color: color(bottle), size: diameter, isHighlighted: isHighlighted || isFocused, showsStyle: true)
                    if showsVintage, !isBack { vintageLabel(bottle, diameter: diameter) }
                }
            } else {
                BottleEnd(color: nil, size: diameter, isEmpty: true, isQuiet: isQuiet, isHighlighted: isHighlighted)
                if selectedCount == 0, !isPicture, !isQuiet {
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
        .foregroundStyle(dark ? Color.inkOnLight : Color.inkOnDark)
        .accessibilityHidden(true)
    }

    /// One dot per place in the cell, front first: filled when a bottle is there.
    private var depthDots: some View {
        HStack(spacing: max(2, size * 0.05)) {
            ForEach(0..<capacity, id: \.self) { depth in
                let bottle = bottles.first { $0.slot?.depth == depth }
                Circle()
                    .fill(bottle.map { color($0)?.tint ?? Color.neutral } ?? Color.clear)
                    .overlay(Circle().strokeBorder(Color.secondary.opacity(0.6), lineWidth: 0.75))
                    .frame(width: max(4, size * 0.1), height: max(4, size * 0.1))
            }
        }
        .accessibilityHidden(true)
    }

    // MARK: Bin

    private var bin: some View {
        let capacity = self.capacity
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
            // The count at the middle of the diamond, the pile resting above it and, while
            // checking or choosing, what is found or chosen below it.
            Text(verbatim: "\(bottles.count)/\(capacity)")
                .font(.system(size: max(10, size * 0.13), weight: .semibold).monospacedDigit())
                .foregroundStyle(isFull ? Color.primary : Color.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: size * 0.5)
                .overlay(alignment: .top) {
                    Pile(colors: bottles.map(color), size: size * 0.11)
                        .fixedSize()
                        .alignmentGuide(.top) { $0[.bottom] + size * 0.02 }
                }
                .overlay(alignment: .bottom) {
                    if selectedCount > 0 {
                        // Checking: found of the bottles there; choosing places: how many are chosen.
                        Text(verbatim: interaction.mode == .auditing ? "✓ \(selectedCount)/\(bottles.count)" : "+\(selectedCount)")
                            .font(.system(size: max(10, size * 0.12), weight: .bold).monospacedDigit())
                            .foregroundStyle(Color.accentColor)
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                            .fixedSize()
                            .alignmentGuide(.bottom) { $0[.top] - size * 0.02 }
                    }
                }
        }
    }

    /// An edge triangle of a diamond rack: the half diamond along its side, with its pile and
    /// count placed in its widest part.
    private func triangleBin(_ edge: DiamondEdge) -> some View {
        let capacity = self.capacity
        let isFull = bottles.count >= capacity
        let width = edge == .left || edge == .right ? size / 2 : size
        let center: CGPoint = switch edge {
        // The centroid of each triangle.
        case .top: CGPoint(x: width / 2, y: size * 2 / 3)
        case .bottom: CGPoint(x: width / 2, y: size / 3)
        case .left: CGPoint(x: size / 6, y: size / 2)
        case .right: CGPoint(x: width - size / 6, y: size / 2)
        }
        return ZStack(alignment: .topLeading) {
            EdgeTriangle(edge: edge, inset: size * 0.03)
                .fill(Color.secondary.opacity(0.08))
                .overlay(
                    EdgeTriangle(edge: edge, inset: size * 0.03)
                        .stroke(isHighlighted || isFocused ? Color.accentColor : Color.secondary.opacity(0.3),
                                style: StrokeStyle(lineWidth: isHighlighted || isFocused ? 3 : 1.5, lineJoin: .round))
                )
            // The count at the triangle's centroid, the pile above it (below it in a bottom
            // triangle, toward its wide edge) and what is found or chosen on the other side.
            Text(verbatim: "\(bottles.count)/\(capacity)")
                .font(.system(size: max(9, size * 0.1), weight: .semibold).monospacedDigit())
                .foregroundStyle(isFull ? Color.primary : Color.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .frame(maxWidth: edge == .left || edge == .right ? size * 0.28 : size * 0.45)
                .overlay(alignment: edge == .bottom ? .bottom : .top) {
                    Pile(colors: bottles.map(color), size: size * 0.065, widest: 3)
                        .fixedSize()
                        .alignmentGuide(edge == .bottom ? .bottom : .top) { edge == .bottom ? $0[.top] - size * 0.015 : $0[.bottom] + size * 0.015 }
                }
                .overlay(alignment: edge == .bottom ? .top : .bottom) {
                    if selectedCount > 0 {
                        Text(verbatim: interaction.mode == .auditing ? "✓ \(selectedCount)/\(bottles.count)" : "+\(selectedCount)")
                            .font(.system(size: max(9, size * 0.09), weight: .bold).monospacedDigit())
                            .foregroundStyle(Color.accentColor)
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                            .fixedSize()
                            .alignmentGuide(edge == .bottom ? .top : .bottom) { edge == .bottom ? $0[.bottom] + size * 0.015 : $0[.top] - size * 0.015 }
                    }
                }
            .position(center)
        }
        .frame(width: width, height: size)
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
                        .foregroundStyle(Color.onTint)
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

    /// The wine, its style and maturity in words (the drawing shows the style by the bottle's
    /// color and symbol): "Château X 2015, Red, Ready".
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
            label = String(localized: "\(place), \(bottles.count) of \(capacity) bottles")
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
        (0..<capacity).map { depth -> String in
            let depthName = rack.depthName(depth, row: cell.row)
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

/// An edge triangle of a diamond rack, as drawn in its place: the lower half of a diamond along
/// the top (its frame a whole place, the upper half outside the rack), the upper half along the
/// bottom, the right half along the left (its frame half a place wide), the left half along the
/// right.
struct EdgeTriangle: Shape {
    let edge: DiamondEdge
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let r = rect.insetBy(dx: inset, dy: inset)
        let points: [CGPoint] = switch edge {
        case .top: [CGPoint(x: r.minX, y: rect.midY), CGPoint(x: r.maxX, y: rect.midY), CGPoint(x: r.midX, y: r.maxY)]
        case .bottom: [CGPoint(x: r.midX, y: r.minY), CGPoint(x: r.maxX, y: rect.midY), CGPoint(x: r.minX, y: rect.midY)]
        case .left: [CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.midY), CGPoint(x: r.minX, y: r.maxY)]
        case .right: [CGPoint(x: r.maxX, y: r.minY), CGPoint(x: r.minX, y: r.midY), CGPoint(x: r.maxX, y: r.maxY)]
        }
        return Path { path in
            path.addLines(points)
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
    /// Bottles in the bottom row: 4 in a bin, 3 in a triangle.
    var widest = 4

    var body: some View {
        // Rows from the bottom: 4, 3, 2, 1 (3, 2, 1 in a triangle).
        let widths = Array((1...widest).reversed())
        let shown = Array(colors.prefix(widths.reduce(0, +)))
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
        .frame(minHeight: size * (CGFloat(widest) - 0.8), alignment: .bottom)
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
    private var shownShelves: [Range<Int>] { rack.thumbnailShelves(layers: 8) }

    private func places(_ row: Int, _ byCell: [RackCell: [Bottle]]) -> some View {
        HStack(spacing: 3) {
            ForEach(0..<min(rack.columns(inRow: row), 10), id: \.self) { column in
                let bottles = byCell[RackCell(rackID: rack.id, row: row, column: column)] ?? []
                let color = bottles.first.flatMap { model.cellar.wines[$0.wineID]?.color }
                Group {
                    if rack.kind == .bins {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(bottles.isEmpty ? Color.secondary.opacity(0.15) : (color?.tint ?? Color.neutral))
                            .rotationEffect(.degrees(45))
                            .scaleEffect(0.75)
                    } else {
                        Circle()
                            .fill(bottles.isEmpty ? Color.secondary.opacity(0.15) : (color?.tint ?? Color.neutral))
                    }
                }
                .frame(width: 9, height: 9)
            }
        }
        .padding(.leading, rack.kind == .staggered && rack.isOffset(row: row) ? 6 : 0)
    }
}
