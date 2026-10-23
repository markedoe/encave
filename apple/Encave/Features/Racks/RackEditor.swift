import CaveKit
import SwiftUI

/// A ready-made size for a kind of storage.
struct RackPreset: Identifiable {
    let title: LocalizedStringKey
    let kind: RackKind
    let rows: Int
    let columns: Int
    var depth = 1
    var binCapacity = 10
    /// Honeycomb on shelves: the layers of each shelf, from the top, the bottles of each
    /// row (default: the usual quincunx) and the shelves that start a new zone.
    var shelfLayers: [Int]?
    var shelfRows: [Int]?
    var zoneBreaks: [Int]?
    /// Two deep: the back bottles lie head-to-tail.
    var backReversed = false
    var id: String { "\(kind)-\(rows)-\(columns)-\(depth)-\(binCapacity)-\(shelfLayers ?? [])-\(zoneBreaks ?? [])" }

    /// The fridge these shelves make.
    var shelvesRack: Rack? {
        shelfLayers.map { Rack(name: "", kind: kind, rows: rows, columns: columns, depth: depth, rowColumns: shelfRows,
                               shelfLayers: $0, zoneBreaks: zoneBreaks, backReversed: backReversed) }
    }

    // Computed: LocalizedStringKey is not Sendable, so it cannot be a stored static.
    static var all: [RackPreset] {
        [
            RackPreset(title: "6 rows of 8", kind: .grid, rows: 6, columns: 8),
            RackPreset(title: "Double depth, 6 × 8", kind: .grid, rows: 6, columns: 8, depth: 2),
            RackPreset(title: "Large, 10 × 12", kind: .grid, rows: 10, columns: 12),
            RackPreset(title: "5 rows of 8", kind: .staggered, rows: 5, columns: 8),
            RackPreset(title: "8 rows of 10", kind: .staggered, rows: 8, columns: 10),
            RackPreset(title: "Wine fridge, shelves in layers", kind: .staggered, rows: 10, columns: 6, depth: 2,
                       shelfLayers: Array(repeating: 2, count: 5), backReversed: true),
            RackPreset(title: "Wine fridge, 2 zones", kind: .staggered, rows: SampleData.twoZoneFridgeRows.count, columns: 6,
                       depth: 2, shelfLayers: SampleData.twoZoneFridgeLayers, shelfRows: SampleData.twoZoneFridgeRows,
                       zoneBreaks: [4], backReversed: true),
            RackPreset(title: "Fridge, 6 shelves of 6", kind: .shelves, rows: 6, columns: 6),
            RackPreset(title: "Fridge, 2 deep, 6 shelves of 7", kind: .shelves, rows: 6, columns: 7, depth: 2),
            RackPreset(title: "Cabinet, 10 shelves of 8, 2 deep", kind: .shelves, rows: 10, columns: 8, depth: 2),
            RackPreset(title: "Diamond bins, 2 × 3 of 10", kind: .bins, rows: 2, columns: 3, binCapacity: 10),
            RackPreset(title: "Crates of 6, 3 × 4", kind: .bins, rows: 3, columns: 4, binCapacity: 6),
            RackPreset(title: "Crates of 12, 2 × 4", kind: .bins, rows: 2, columns: 4, binCapacity: 12),
        ]
    }
}

/// Create or edit a rack: choose the kind of storage, its size, and see it live.
struct RackEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rackID: UUID?
    @State private var name = ""
    @State private var kind: RackKind = .grid
    @State private var rows = 6
    @State private var columns = 8
    @State private var depth = 1
    /// A fridge on shelves two deep: the back bottles lie head-to-tail.
    @State private var backReversed = false
    @State private var binCapacity = 10
    @State private var customRows = false
    @State private var rowColumns: [Int] = []
    /// Honeycomb: on shelves (a wine fridge with bottles in layers) rather than continuous.
    @State private var onShelves = false
    @State private var shelves: [ShelfDraft] = []
    @State private var loaded = false
    /// The type of the storage being edited: only its settings are shown (nil for a new one).
    @State private var storageType: StorageType?
    @State private var deletingRackID: UUID?
    /// The form as loaded, to ask before discarding changes.
    @State private var initial: EditorState?
    @State private var confirmingDiscard = false
    /// Saving would leave bottles without a place: asked first.
    @State private var confirmingTakeOut = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name, prompt: Text("Main rack"))
                        .font(.headline)
                        .fieldHelp("A name you will recognize: cellar left wall, kitchen fridge…")
                } footer: {
                    Text("Describe where you keep bottles: a rack, a wine fridge, a set of bins.")
                }

                if kinds.count > 1 {
                    Section("Kind of storage") {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                            ForEach(kinds, id: \.self) { option in
                                KindCard(kind: option, isSelected: kind == option) { choose(option) }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section {
                    if kind == .staggered && storageType == nil {
                        Picker("Layout", selection: $onShelves.animation()) {
                            Text("Continuous").tag(false)
                            Text("On shelves").tag(true)
                        }
                        .pickerStyle(.segmented)
                        .fieldHelp("A honeycomb rack, or the shelves of a wine fridge holding bottles stacked in quincunx.")
                    }
                    if !layered {
                        Stepper(value: $rows, in: 1...40) {
                            LabeledContent { Text(verbatim: "\(rows)").monospacedDigit() } label: { Text(kind.rowTitle) }
                        }
                        .fieldHelp(kind == .shelves ? "Counted from the top. Shelves are numbered 1, 2, 3…"
                                                    : "Counted from the top. Rows are lettered A, B, C…")
                    }
                    if !customRows && !layered {
                        Stepper(value: $columns, in: 1...40) {
                            LabeledContent { Text(verbatim: "\(columns)").monospacedDigit() } label: { Text(kind.columnTitle) }
                        }
                        .fieldHelp("Counted from the left. Positions are numbered 1, 2, 3…")
                    }
                    switch kind {
                    case .grid:
                        Picker("Bottles per position", selection: $depth) {
                            Text("One").tag(1)
                            Text("Two, one behind the other").tag(2)
                        }
                        .fieldHelp("Deep racks hold a second bottle behind the first one.")
                    case .shelves:
                        Picker("Rows deep", selection: $depth) {
                            Text(verbatim: "1").tag(1)
                            Text(verbatim: "2").tag(2)
                            Text(verbatim: "3").tag(3)
                        }
                        .pickerStyle(.segmented)
                        .fieldHelp("How many bottles fit one behind the other on a shelf.")
                    case .bins:
                        Stepper(value: $binCapacity, in: 2...40) {
                            LabeledContent("Bottles per bin") { Text(verbatim: "\(binCapacity)").monospacedDigit() }
                        }
                        .fieldHelp("A diamond bin of about 30 cm holds around 10 standard bottles.")
                    case .staggered:
                        Picker("Rows deep", selection: $depth.animation()) {
                            Text(verbatim: "1").tag(1)
                            Text(verbatim: "2").tag(2)
                        }
                        .pickerStyle(.segmented)
                        .fieldHelp(depthHelp)
                        if layered, depth == 2 {
                            Toggle("Back bottles head-to-tail", isOn: $backReversed)
                                .fieldHelp("The back bottles lie the other way round: their necks face you between the bases of the front ones.")
                        }
                    }
                } header: {
                    Text("Size")
                } footer: {
                    if layered, depth == 2 {
                        Text("Holds \(preview.capacity) bottles. Shelves are numbered from the top, positions from the left. Each shelf shows its front bottles, then the ones behind; head-to-tail, their necks face you.")
                    } else if layered {
                        Text("Holds \(preview.capacity) bottles. Shelves are numbered from the top, positions from the left.")
                    } else if kind == .shelves {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Holds \(preview.capacity) bottles. Shelves are numbered from the top, positions from the left.")
                            outsideWarning
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Holds \(preview.capacity) bottles. Positions are named like B3; with two bottles in a position, “B3, back” is the one behind.")
                            outsideWarning
                        }
                    }
                }

                if layered {
                    Section {
                        ForEach($shelves) { $shelf in
                            let index = shelves.firstIndex { $0.id == shelf.id } ?? 0
                            ShelfRow(number: index + 1, shelf: $shelf)
                                .contextMenu { shelfActions(index) }
                                .swipeActions {
                                    if shelves.count > 1 {
                                        Button("Delete", role: .destructive) { removeShelf(index) }
                                    }
                                    Button("Duplicate") { duplicateShelf(index) }
                                }
                        }
                        Button {
                            withAnimation { shelves.append(shelves.last.map { ShelfDraft(copying: $0) } ?? ShelfDraft()) }
                        } label: {
                            Label("Add Shelf", systemImage: "plus.circle.fill")
                        }
                        .disabled(layerCount + Rack.maxLayers > Rack.maxSize)
                    } header: {
                        Text("Shelves, from the top")
                    } footer: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Holds \(preview.capacity) bottles. Touch and hold a shelf to duplicate it or apply it to all shelves.")
                            outsideWarning
                        }
                    }
                }

                if offersCustomRows {
                    Section {
                        Toggle("Rows of different lengths", isOn: $customRows.animation())
                        if customRows {
                            ForEach(0..<rows, id: \.self) { row in
                                Stepper(value: rowBinding(row), in: 1...60) {
                                    LabeledContent {
                                        Text(verbatim: "\(rowBinding(row).wrappedValue)").monospacedDigit()
                                    } label: {
                                        if kind == .shelves {
                                            Text("Shelf \(row + 1)")
                                        } else {
                                            Text("Row \(Rack.rowLetters(row))")
                                        }
                                    }
                                }
                            }
                        }
                    } footer: {
                        Text("For a rack with a sloped top, a staircase or an irregular cellar wall.")
                    }
                }

                if rackID == nil {
                    Section("Quick start") {
                        FlowLayout(spacing: 8) {
                            ForEach(RackPreset.all.filter { $0.kind == kind }) { preset in
                                ChoiceChip(title: preset.title, isSelected: matches(preset)) { apply(preset) }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section("Preview") {
                    ScrollView([.horizontal, .vertical]) {
                        RackGrid(rack: preview, cellSize: 26, showsHeaders: false)
                            .allowsHitTesting(false)
                            .padding(12)
                    }
                    .frame(minHeight: 160, maxHeight: 300)
                    .accessibilityHidden(true)
                }

                if rackID != nil {
                    Section {
                        Button("Delete Storage…", role: .destructive) { deletingRackID = rackID }
                    } footer: {
                        Text("Bottles in this storage stay in your cellar without a position.")
                    }
                }
                Section {
                    HelpLink(topic: .racks, title: "Help with storage")
                }
            }
            .formStyle(.grouped)
            .navigationTitle(rackID == nil ? Text("New Storage") : Text("Edit Storage"))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if isDirty { confirmingDiscard = true } else { dismiss() }
                    }
                    .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Discard Changes?", isPresented: $confirmingDiscard, titleVisibility: .visible) {
                Button("Discard Changes", role: .destructive) { dismiss() }
                Button("Keep Editing", role: .cancel) {}
            }
            .confirmationDialog(Text("\(outsideCount) bottles are in positions you removed"),
                                isPresented: $confirmingTakeOut, titleVisibility: .visible) {
                Button("Take Them Out and Save", role: .destructive) { commit() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("They stay in your cellar without a position.")
            }
            .confirmsRackDeletion($deletingRackID) { dismiss() }
            .onAppear(perform: load)
            .onChange(of: rows) { _, count in if customRows { resizeRows(count) } }
            .onChange(of: onShelves) { _, on in if on { startShelves() } }
            // Turned on by hand: every row starts at the rack's width. Loading an irregular
            // rack sets its rows first, so they are kept.
            .onChange(of: customRows) { _, on in
                if !on {
                    rowColumns = []
                } else if rowColumns.count != rows {
                    rowColumns = Array(repeating: columns, count: rows)
                }
            }
            // Swiping the sheet away would lose the changes: Cancel asks first.
            .interactiveDismissDisabled(isDirty)
        }
        .macSheetFrame(width: 560, height: 760)
    }

    /// A honeycomb on shelves, edited shelf by shelf.
    private var layered: Bool { kind == .staggered && onShelves }

    /// The kinds this storage can become: a rack stays a rack (grid or honeycomb), a fridge
    /// and crates keep theirs. Every kind for a new storage.
    private var kinds: [RackKind] {
        switch storageType {
        case .rack: [.grid, .staggered]
        case .wineFridge, .crates: []
        case nil: RackKind.allCases
        }
    }

    /// Rows of different lengths fit a rack or crates; a fridge only keeps the ones it has.
    private var offersCustomRows: Bool {
        kind != .staggered && (storageType != .wineFridge || initial?.customRows == true)
    }

    private var current: EditorState {
        EditorState(name: name, kind: kind, rows: rows, columns: columns, depth: depth, binCapacity: binCapacity,
                    customRows: customRows, rowColumns: rowColumns, onShelves: onShelves, shelves: shelves,
                    backReversed: backReversed)
    }

    /// Something was changed since the editor opened.
    private var isDirty: Bool { initial.map { $0 != current } ?? false }

    /// Where the rows of the fridge go when its shelves were added, duplicated or removed:
    /// its bottles stay on their shelf and layer.
    private var rowMap: [Int: Int?] {
        guard layered, let rackID, let old = model.cellar.rack(rackID) else { return [:] }
        return Rack.shelfRowMap(from: old, to: preview, origins: shelves.map(\.originalShelf))
    }

    /// Bottles whose place this edit removes.
    private var outsideCount: Int {
        guard rackID != nil else { return 0 }
        return model.cellar.displacedBottles(by: preview, rowMap: rowMap).count
    }

    /// Said under the size while bottles are in places removed.
    @ViewBuilder
    private var outsideWarning: some View {
        let count = outsideCount
        if count > 0 {
            Label {
                Text("\(count) bottles are in positions you removed")
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill")
                    .symbolRenderingMode(.multicolor)
            }
        }
    }

    private var depthHelp: LocalizedStringKey {
        layered ? "Two rows, often head to tail." : "How many bottles fit one behind the other in each position."
    }

    private var layerCount: Int { shelves.reduce(0) { $0 + $1.layers } }

    private var preview: Rack {
        if layered {
            // Rows are the layers, from the top: each shelf's top layer first.
            let widths = shelves.flatMap { $0.counts.prefix($0.layers).reversed() }
            return Rack(id: rackID ?? UUID(), name: name, kind: kind, rows: max(1, widths.count),
                        columns: shelves.map { $0.counts[0] }.max() ?? 1, depth: depth, rowColumns: widths,
                        shelfLayers: shelves.map(\.layers),
                        zoneBreaks: shelves.indices.filter { $0 > 0 && shelves[$0].startsZone },
                        backReversed: backReversed)
        }
        return Rack(id: rackID ?? UUID(), name: name, kind: kind, rows: rows,
                    columns: customRows ? (rowColumns.max() ?? columns) : columns,
                    depth: depth, binCapacity: binCapacity, rowColumns: customRows ? rowColumns : nil)
    }

    @ViewBuilder
    private func shelfActions(_ index: Int) -> some View {
        Button("Duplicate", systemImage: "plus.square.on.square") { duplicateShelf(index) }
        Button("Apply to All Shelves", systemImage: "square.stack.3d.down.right") { applyToAll(index) }
        if shelves.count > 1 {
            Button("Delete", systemImage: "trash", role: .destructive) { removeShelf(index) }
        }
    }

    private func duplicateShelf(_ index: Int) {
        guard shelves.indices.contains(index), layerCount + shelves[index].layers <= Rack.maxSize else { return }
        withAnimation { shelves.insert(ShelfDraft(copying: shelves[index]), at: index + 1) }
    }

    private func removeShelf(_ index: Int) {
        guard shelves.count > 1, shelves.indices.contains(index) else { return }
        withAnimation { _ = shelves.remove(at: index) }
    }

    /// Every shelf takes the layers and bottles of this one.
    private func applyToAll(_ index: Int) {
        guard shelves.indices.contains(index) else { return }
        let source = shelves[index]
        withAnimation {
            shelves = shelves.map { shelf in
                var copy = source
                copy.id = shelf.id
                copy.originalShelf = shelf.originalShelf
                copy.startsZone = shelf.startsZone
                return copy
            }
        }
    }

    private func rowBinding(_ row: Int) -> Binding<Int> {
        Binding(
            get: { rowColumns.indices.contains(row) ? rowColumns[row] : columns },
            set: { value in
                resizeRows(rows)
                rowColumns[row] = value
            }
        )
    }

    private func resizeRows(_ count: Int) {
        if rowColumns.count < count {
            rowColumns += Array(repeating: rowColumns.last ?? columns, count: count - rowColumns.count)
        } else if rowColumns.count > count {
            rowColumns.removeLast(rowColumns.count - count)
        }
    }

    private func choose(_ option: RackKind) {
        withAnimation(.snappy) {
            kind = option
            if option == .staggered {
                customRows = false
                depth = min(depth, Rack.maxStaggeredDepth)
            }
            if rackID == nil, let preset = RackPreset.all.first(where: { $0.kind == option }) { apply(preset) }
        }
    }

    private func matches(_ preset: RackPreset) -> Bool {
        if let fridge = preset.shelvesRack {
            return layered && depth == preset.depth && shelves == ShelfDraft.shelves(of: fridge)
                && backReversed == preset.backReversed
        }
        return !customRows && !layered && rows == preset.rows && columns == preset.columns
            && (kind == .bins ? binCapacity == preset.binCapacity : depth == preset.depth)
    }

    private func apply(_ preset: RackPreset) {
        withAnimation(.snappy) {
            kind = preset.kind; rows = preset.rows; columns = preset.columns
            depth = preset.depth; binCapacity = preset.binCapacity; customRows = false
            onShelves = preset.shelfLayers != nil
            backReversed = preset.backReversed
            if let fridge = preset.shelvesRack { shelves = ShelfDraft.shelves(of: fridge) }
        }
    }

    /// A new fridge on shelves: five shelves of 6 bottles and 5 on top, two deep, head-to-tail.
    private func startShelves() {
        guard shelves.isEmpty else { return }
        shelves = (0..<5).map { _ in ShelfDraft() }
        depth = 2
        backReversed = true
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let rackID, let rack = model.cellar.rack(rackID) {
            storageType = rack.storageType
            name = rack.name; kind = rack.kind; rows = rack.rows; columns = rack.columns
            depth = rack.depth; binCapacity = rack.binCapacity
            backReversed = rack.isBackReversed
            if rack.hasShelfLayers {
                onShelves = true
                shelves = ShelfDraft.shelves(of: rack, keepingOrigins: true)
            } else if let custom = rack.rowColumns {
                rowColumns = custom
                customRows = true
            }
        } else {
            name = model.cellar.racks.isEmpty ? String(localized: "Main rack") : ""
        }
        initial = current
    }

    private func save() {
        guard rackID != nil else {
            let rack = named(preview)
            if model.update({ $0.addRack(rack) }) { dismiss() }
            return
        }
        if outsideCount > 0 { confirmingTakeOut = true } else { commit() }
    }

    /// Saves the edited rack, its bottles moved with their shelves; those whose place is
    /// gone are taken out (the person agreed), with Undo.
    private func commit() {
        if model.updateRack(named(preview), rowMap: rowMap, takingOut: outsideCount) { dismiss() }
    }

    private func named(_ rack: Rack) -> Rack {
        var rack = rack
        rack.name = name.trimmingCharacters(in: .whitespaces)
        return rack
    }
}

/// What the rack editor holds, to tell whether anything changed.
private struct EditorState: Equatable {
    var name: String
    var kind: RackKind
    var rows: Int
    var columns: Int
    var depth: Int
    var binCapacity: Int
    var customRows: Bool
    var rowColumns: [Int]
    var onShelves: Bool
    var shelves: [ShelfDraft]
    var backReversed: Bool
}

/// One shelf of a fridge with bottles in layers, while editing.
struct ShelfDraft: Identifiable, Equatable {
    var id = UUID()
    /// 1 to 3 layers stacked in quincunx.
    var layers: Int
    /// Bottles in each layer, from the bottom; always three values, so changing the
    /// number of layers keeps what was typed.
    var counts: [Int]
    /// A divider above this shelf: it starts a new temperature zone.
    var startsZone = false
    /// The shelf of the saved rack this one is (0 is the top one), so its bottles follow it
    /// when shelves are added or removed above; nil for a new shelf.
    var originalShelf: Int?

    /// A bottom layer of `bottom` bottles; each layer above rests in the gaps of the one below.
    init(layers: Int = 2, bottom: Int = 6) {
        self.layers = layers
        counts = [bottom, max(1, bottom - 1), bottom]
    }

    /// Bottles of each layer from the bottom, completed with the usual quincunx.
    init(layers: Int, counts: [Int]) {
        self.layers = min(Rack.maxLayers, max(1, layers))
        var all = counts
        let bottom = all.first ?? 6
        if all.isEmpty { all.append(bottom) }
        if all.count < 2 { all.append(max(1, bottom - 1)) }
        if all.count < 3 { all.append(bottom) }
        self.counts = Array(all.prefix(3))
    }

    /// A new shelf like `other`: it holds no bottles yet.
    init(copying other: ShelfDraft) {
        self = other
        id = UUID()
        startsZone = false
        originalShelf = nil
    }

    /// The shelves of a fridge with layers, from the top. `keepingOrigins`: the rack being
    /// edited, whose bottles follow their shelf.
    static func shelves(of rack: Rack, keepingOrigins: Bool = false) -> [ShelfDraft] {
        rack.shelfRows.enumerated().map { shelf, rows in
            // From the bottom layer up: the shelf's last row first.
            var draft = ShelfDraft(layers: rows.count, counts: rows.reversed().map { rack.columns(inRow: $0) })
            draft.startsZone = rack.zoneBreaks?.contains(shelf) == true
            if keepingOrigins { draft.originalShelf = shelf }
            return draft
        }
    }

    /// Same layers, bottles and zone, whatever the shelf.
    static func == (a: ShelfDraft, b: ShelfDraft) -> Bool {
        a.layers == b.layers && a.counts.prefix(a.layers) == b.counts.prefix(b.layers) && a.startsZone == b.startsZone
    }
}

/// A shelf in the editor: its layers and the bottles of each.
private struct ShelfRow: View {
    let number: Int
    @Binding var shelf: ShelfDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Shelf \(number)")
                .font(.headline)
            Picker("Layers per shelf", selection: $shelf.layers) {
                Text(verbatim: "1").tag(1)
                Text(verbatim: "2").tag(2)
                Text(verbatim: "3").tag(3)
            }
            .pickerStyle(.segmented)
            .fieldHelp("Layers of bottles stacked on this shelf.")
            Stepper(value: bottom, in: 1...40) {
                LabeledContent("Bottom layer") { Text(verbatim: "\(shelf.counts[0])").monospacedDigit() }
            }
            if shelf.layers == 3 {
                Stepper(value: $shelf.counts[1], in: 1...40) {
                    LabeledContent("Middle layer") { Text(verbatim: "\(shelf.counts[1])").monospacedDigit() }
                }
            }
            if shelf.layers > 1 {
                let top = shelf.layers - 1
                Stepper(value: $shelf.counts[top], in: 1...40) {
                    LabeledContent("Top layer") { Text(verbatim: "\(shelf.counts[top])").monospacedDigit() }
                }
            }
            hint
                .font(.footnote)
                .foregroundStyle(.secondary)
            if number > 1 {
                Toggle("New zone from this shelf", isOn: $shelf.startsZone)
                    .fieldHelp("For a wine fridge with two temperature zones: a divider is drawn above this shelf.")
            }
        }
        .padding(.vertical, 4)
    }

    /// The bottom layer; the layers above follow it while they keep their usual count.
    private var bottom: Binding<Int> {
        Binding(get: { shelf.counts[0] }, set: { value in
            let old = shelf.counts[0]
            if shelf.counts[1] == max(1, old - 1) { shelf.counts[1] = max(1, value - 1) }
            if shelf.counts[2] == old { shelf.counts[2] = value }
            shelf.counts[0] = value
        })
    }

    private var hint: Text {
        let counts = shelf.counts
        switch shelf.layers {
        case 1: return Text("A single layer of \(counts[0]) bottles.")
        case 2: return Text("Bottles stacked in quincunx: \(counts[0]) at the bottom, \(counts[1]) on top.")
        default: return Text("Bottles stacked in quincunx: \(counts[0]) at the bottom, \(counts[1]) in the middle, \(counts[2]) on top.")
        }
    }
}

/// A kind of storage to choose, with a small drawing.
private struct KindCard: View {
    let kind: RackKind
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: kind.systemImage)
                    .font(.title2)
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                Text(kind.title)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.leading)
                Text(kind.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(isSelected ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.08))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(isSelected ? Color.accentColor : Color.clear, lineWidth: 2)
            )
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Choose up to `limit` free positions in a rack.
struct SlotPickerView: View {
    @Environment(\.dismiss) private var dismiss
    let rack: Rack
    let limit: Int
    @Binding var selection: [Slot]

    var body: some View {
        NavigationStack {
            FittedRackGrid(rack: rack, selected: Set(selection)) { place, _ in toggle(place) }
            .safeAreaInset(edge: .bottom) {
                Text("\(selection.count) of \(limit) chosen")
                    .font(.subheadline.weight(.medium).monospacedDigit())
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(.bar)
            }
            .navigationTitle(rack.name)
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("sheet.done")
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") { selection = [] }
                        .disabled(selection.isEmpty)
                }
            }
        }
        .macSheetFrame(width: 560, height: 520)
    }

    @Environment(CellarModel.self) private var model

    /// Adds the next free place of a cell, or clears the cell when it is all chosen (on a
    /// fridge drawn front and back, the very place tapped).
    private func toggle(_ place: RackPlace) {
        let free = model.cellar.freeSlots(in: rack).filter(place.contains)
        let chosen = selection.filter(place.contains)
        if let next = free.first(where: { !selection.contains($0) }), selection.count < limit {
            selection.append(next)
        } else if !chosen.isEmpty {
            selection.removeAll(where: place.contains)
        } else if limit == 1, let first = free.first {
            selection = [first]
        }
    }
}

/// Move a bottle to another rack position, or out of the racks.
struct MoveBottleView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    @State private var rackID: UUID?
    @State private var storageNote = ""

    var body: some View {
        NavigationStack {
            Group {
                if let bottle = model.cellar.bottles[bottleID] {
                    VStack(spacing: 0) {
                        Picker("Store in", selection: $rackID) {
                            Text("No storage").tag(UUID?.none)
                            ForEach(model.cellar.racks) { Text($0.name).tag(UUID?.some($0.id)) }
                        }
                        .pickerStyle(.menu)
                        .padding()
                        if let rack = rackID.flatMap({ model.cellar.rack($0) }) {
                            FittedRackGrid(rack: rack, highlighted: bottle.slot) { place, _ in
                                    if bottle.slot.map(place.contains) == true {
                                        dismiss()
                                    } else if let free = model.cellar.freeSlot(at: place) {
                                        model.move(bottleID, to: free)
                                        dismiss()
                                    } else if place.holdsOne(in: rack), let from = bottle.slot {
                                        model.swap(from, place.firstSlot)
                                        dismiss()
                                    } else {
                                        model.showToast(String(localized: "This position is full."))
                                    }
                            }
                            Text("Tap a position. A position with room takes the bottle; where each position holds one bottle, tapping another bottle swaps them.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .padding()
                        } else {
                            Form {
                                TextField("Where is it?", text: $storageNote, prompt: Text("Kitchen, box in the garage…"))
                                Button("Save") {
                                    model.takeOutOfRack(bottleID, note: storageNote.isEmpty ? nil : storageNote)
                                    dismiss()
                                }
                            }
                            .formStyle(.grouped)
                        }
                    }
                    .onAppear {
                        rackID = bottle.slot?.rackID ?? model.cellar.racks.first?.id
                        storageNote = bottle.storageNote ?? ""
                    }
                } else {
                    ContentUnavailableView("Bottle not found", systemImage: "questionmark")
                }
            }
            .navigationTitle("Move Bottle")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .macSheetFrame(width: 560, height: 560)
    }
}
