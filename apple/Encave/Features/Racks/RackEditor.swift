import CaveKit
import SwiftUI

/// Edit a storage: the settings of its type, its size, and see it live. New storages are
/// made step by step in `RackWizard`.
struct RackEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rackID: UUID
    @State private var name = ""
    @State private var kind: RackKind = .grid
    @State private var rows = 6
    @State private var columns = 8
    @State private var depth = 1
    /// A fridge on shelves two deep: the back bottles lie head-to-tail.
    @State private var backReversed = false
    @State private var binCapacity = 10
    /// Crates: how many bins, laid out as the wizard lays them out when their count changes.
    @State private var bins = 6
    /// Bins saved without a type (older versions): the type picked here, nil until then.
    @State private var chosenStyle: BinStyle?
    /// The storage as loaded.
    @State private var original: Rack?
    @State private var customRows = false
    @State private var rowColumns: [Int] = []
    /// Honeycomb: on shelves (a wine fridge with bottles in layers) rather than continuous.
    @State private var onShelves = false
    @State private var shelves: [ShelfDraft] = []
    @State private var loaded = false
    /// The type of the storage being edited: only its settings are shown.
    @State private var storageType: StorageType = .rack
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

                if offersBinType {
                    Section {
                        Picker("Kind of storage", selection: binType.animation()) {
                            Text(StorageType.rack.name).tag(StorageType.rack)
                            Text(StorageType.crates.name).tag(StorageType.crates)
                        }
                    }
                }

                Section {
                    if isCrates {
                        Stepper(value: $bins, in: 1...(original?.maxEditableBins ?? StorageSettings.maxCount)) {
                            LabeledContent("Bins") { Text(verbatim: "\(bins)").monospacedDigit() }
                        }
                        .fieldHelp("Each crate or box is a bin, named like B3.")
                    }
                    if !layered && !isCrates {
                        Stepper(value: $rows, in: 1...40) {
                            LabeledContent { Text(verbatim: "\(rows)").monospacedDigit() } label: { Text(kind.rowTitle) }
                        }
                        .fieldHelp(kind == .shelves ? "Counted from the top. Shelves are numbered 1, 2, 3…"
                                                    : "Counted from the top. Rows are lettered A, B, C…")
                    }
                    if !customRows && !layered && !isCrates {
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
                        .fieldHelp(storageType == .crates
                            ? "A wooden crate usually holds 6 or 12 bottles."
                            : "A diamond bin of about 30 cm holds around 10 standard bottles.")
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
                    } else if kind == .bins {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Holds \(preview.capacity) bottles. Bins are named like “Bin B3”: rows lettered from the top, bins numbered from the left.")
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

                Section("Preview") {
                    ScrollView([.horizontal, .vertical]) {
                        RackGrid(rack: preview, cellSize: 26, showsHeaders: false, isPicture: true)
                            .allowsHitTesting(false)
                            .padding(12)
                    }
                    .frame(minHeight: 160, maxHeight: 300)
                    .accessibilityHidden(true)
                }

                Section {
                    Button("Delete Storage…", role: .destructive) { deletingRackID = rackID }
                } footer: {
                    Text("Bottles in this storage stay in your cellar without a position.")
                }
                Section {
                    HelpLink(topic: .racks, title: "Help with Storage")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Edit Storage")
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

    /// Crates saved (or picked) as such are edited as a number of bins.
    private var isCrates: Bool { original?.editsAsCrates(chosen: chosenStyle) ?? false }

    /// Bins saved without a type: their type can be picked, shown under it from then on.
    private var offersBinType: Bool { original?.kind == .bins && original?.binStyle == nil }

    /// The type picked for bins saved without one; until then, the one their shape gives.
    private var binType: Binding<StorageType> {
        Binding(
            get: { chosenStyle.map { $0 == .crates ? .crates : .rack } ?? storageType },
            set: { chosenStyle = $0 == .crates ? .crates : .diamond }
        )
    }

    /// The kinds this storage can become: a rack stays a rack (grid or honeycomb); diamond
    /// bins, a fridge and crates keep theirs.
    private var kinds: [RackKind] { storageType == .rack && initial?.kind != .bins ? [.grid, .staggered] : [] }

    /// Rows of different lengths fit a rack; a fridge only keeps the ones it has, crates
    /// have none.
    private var offersCustomRows: Bool {
        kind != .staggered && !isCrates && (storageType != .wineFridge || initial?.customRows == true)
    }

    private var current: EditorState {
        EditorState(name: name, kind: kind, rows: rows, columns: columns, depth: depth, binCapacity: binCapacity,
                    bins: bins, chosenStyle: chosenStyle, customRows: customRows, rowColumns: rowColumns, onShelves: onShelves, shelves: shelves,
                    backReversed: backReversed)
    }

    /// Something was changed since the editor opened.
    private var isDirty: Bool { initial.map { $0 != current } ?? false }

    /// Where the rows of the fridge go when its shelves were added, duplicated or removed:
    /// its bottles stay on their shelf and layer.
    private var rowMap: [Int: Int?] {
        guard layered, let old = model.cellar.rack(rackID) else { return [:] }
        return Rack.shelfRowMap(from: old, to: preview, origins: shelves.map(\.originalShelf))
    }

    /// Bottles whose place this edit removes.
    private var outsideCount: Int { model.cellar.displacedBottles(by: preview, rowMap: rowMap).count }

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
            return Rack(id: rackID, name: name, kind: kind, rows: max(1, widths.count),
                        columns: shelves.map { $0.counts[0] }.max() ?? 1, depth: depth, rowColumns: widths,
                        shelfLayers: shelves.map(\.layers),
                        zoneBreaks: shelves.indices.filter { $0 > 0 && shelves[$0].startsZone },
                        backReversed: backReversed)
        }
        let shape = Rack(id: rackID, name: name, kind: kind, rows: rows,
                         columns: customRows ? (rowColumns.max() ?? columns) : columns,
                         depth: depth, binCapacity: binCapacity, rowColumns: customRows ? rowColumns : nil)
        // Bins keep their saved type unless one is picked; crates keep their shape unless
        // their count changes.
        if kind == .bins, let original {
            return original.savingBins(name: name, binCapacity: binCapacity, chosen: chosenStyle, bins: bins, shape: shape)
        }
        return shape
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
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let rack = model.cellar.rack(rackID) {
            original = rack
            storageType = rack.storageType
            name = rack.name; kind = rack.kind; rows = rack.rows; columns = rack.columns
            depth = rack.depth; binCapacity = rack.binCapacity
            backReversed = rack.isBackReversed
            bins = rack.cellCount
            if rack.hasShelfLayers {
                onShelves = true
                shelves = ShelfDraft.shelves(of: rack, keepingOrigins: true)
            } else if let custom = rack.rowColumns {
                rowColumns = custom
                customRows = true
            }
        }
        initial = current
    }

    private func save() {
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
    var bins: Int
    var chosenStyle: BinStyle?
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
                                    // As it is now: opened meanwhile (sync, another window), it takes no place.
                                    guard let bottle = model.cellar.bottles[bottleID], bottle.status == .inCellar else {
                                        dismiss()
                                        return
                                    }
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
