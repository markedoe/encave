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
    var id: String { "\(kind)-\(rows)-\(columns)-\(depth)-\(binCapacity)" }

    // Computed: LocalizedStringKey is not Sendable, so it cannot be a stored static.
    static var all: [RackPreset] {
        [
            RackPreset(title: "6 rows of 8", kind: .grid, rows: 6, columns: 8),
            RackPreset(title: "Double depth, 6 × 8", kind: .grid, rows: 6, columns: 8, depth: 2),
            RackPreset(title: "Large, 10 × 12", kind: .grid, rows: 10, columns: 12),
            RackPreset(title: "5 rows of 8", kind: .staggered, rows: 5, columns: 8),
            RackPreset(title: "8 rows of 10", kind: .staggered, rows: 8, columns: 10),
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
    @State private var binCapacity = 10
    @State private var customRows = false
    @State private var rowColumns: [Int] = []
    @State private var loaded = false
    @State private var confirmingDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name, prompt: Text("Main rack"))
                        .font(.headline)
                        .fieldHelp("A name you will recognize: cellar left wall, kitchen fridge…")
                } footer: {
                    Text("Describe each place where you keep bottles: a rack, a wine fridge, a set of bins.")
                }

                Section("Kind of storage") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                        ForEach(RackKind.allCases, id: \.self) { option in
                            KindCard(kind: option, isSelected: kind == option) { choose(option) }
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section {
                    Stepper(value: $rows, in: 1...40) {
                        LabeledContent { Text(verbatim: "\(rows)").monospacedDigit() } label: { Text(kind.rowTitle) }
                    }
                    .fieldHelp("Counted from the top. Rows are lettered A, B, C…")
                    if !customRows {
                        Stepper(value: $columns, in: 1...40) {
                            LabeledContent { Text(verbatim: "\(columns)").monospacedDigit() } label: { Text(kind.columnTitle) }
                        }
                        .fieldHelp("Counted from the left. Places are numbered 1, 2, 3…")
                    }
                    switch kind {
                    case .grid:
                        Picker("Bottles per cell", selection: $depth) {
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
                        EmptyView()
                    }
                } header: {
                    Text("Size")
                } footer: {
                    Text("Holds \(preview.capacity) bottles. Places are named by row letter and number, like B3; B3-2 is the second bottle in a cell.")
                }

                if kind != .staggered {
                    Section {
                        Toggle("Rows of different lengths", isOn: $customRows.animation())
                        if customRows {
                            ForEach(0..<rows, id: \.self) { row in
                                Stepper(value: rowBinding(row), in: 1...60) {
                                    LabeledContent {
                                        Text(verbatim: "\(rowBinding(row).wrappedValue)").monospacedDigit()
                                    } label: {
                                        Text("Row \(Rack.rowLetters(row))")
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
                        Button("Delete Rack", role: .destructive) { confirmingDelete = true }
                    } footer: {
                        Text("Bottles in this rack stay in your cellar without a position.")
                    }
                }
                Section {
                    HelpLink(topic: .racks, title: "Help with racks")
                }
            }
            .formStyle(.grouped)
            .navigationTitle(rackID == nil ? Text("New Rack") : Text("Edit Rack"))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .confirmationDialog("Delete this rack?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete Rack", role: .destructive) {
                    if let rackID { model.update { $0.removeRack(rackID) } }
                    dismiss()
                }
            }
            .onAppear(perform: load)
            .onChange(of: rows) { _, count in resizeRows(count) }
            .onChange(of: customRows) { _, on in if on { rowColumns = Array(repeating: columns, count: rows) } }
        }
        .macSheetFrame(width: 560, height: 760)
    }

    private var preview: Rack {
        Rack(id: rackID ?? UUID(), name: name, kind: kind, rows: rows, columns: customRows ? (rowColumns.max() ?? columns) : columns,
             depth: depth, binCapacity: binCapacity, rowColumns: customRows ? rowColumns : nil)
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
            if option == .staggered { customRows = false }
            if rackID == nil, let preset = RackPreset.all.first(where: { $0.kind == option }) { apply(preset) }
        }
    }

    private func matches(_ preset: RackPreset) -> Bool {
        !customRows && rows == preset.rows && columns == preset.columns
            && (kind == .bins ? binCapacity == preset.binCapacity : (kind == .staggered || depth == preset.depth))
    }

    private func apply(_ preset: RackPreset) {
        withAnimation(.snappy) {
            kind = preset.kind; rows = preset.rows; columns = preset.columns
            depth = preset.depth; binCapacity = preset.binCapacity; customRows = false
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let rackID, let rack = model.cellar.rack(rackID) {
            name = rack.name; kind = rack.kind; rows = rack.rows; columns = rack.columns
            depth = rack.depth; binCapacity = rack.binCapacity
            if let custom = rack.rowColumns {
                rowColumns = custom
                customRows = true
            }
        } else {
            name = model.cellar.racks.isEmpty ? String(localized: "Main rack") : ""
        }
    }

    private func save() {
        var rack = preview
        rack.name = name.trimmingCharacters(in: .whitespaces)
        let ok = model.update { cellar in
            if rackID == nil { cellar.addRack(rack) } else { try cellar.updateRack(rack) }
        }
        if ok { dismiss() }
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
            FittedRackGrid(rack: rack, selected: Set(selection)) { cell, _ in toggle(cell) }
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

    /// Adds the next free place of a cell, or clears the cell when it is all chosen.
    private func toggle(_ cell: RackCell) {
        let free = model.cellar.freeSlots(in: rack).filter { $0.cell == cell }
        let chosen = selection.filter { $0.cell == cell }
        if let next = free.first(where: { !selection.contains($0) }), selection.count < limit {
            selection.append(next)
        } else if !chosen.isEmpty {
            selection.removeAll { $0.cell == cell }
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
                        Picker("Rack", selection: $rackID) {
                            Text("No rack").tag(UUID?.none)
                            ForEach(model.cellar.racks) { Text($0.name).tag(UUID?.some($0.id)) }
                        }
                        .pickerStyle(.menu)
                        .padding()
                        if let rack = rackID.flatMap({ model.cellar.rack($0) }) {
                            FittedRackGrid(rack: rack, highlighted: bottle.slot) { cell, _ in
                                    if bottle.slot?.cell == cell {
                                        dismiss()
                                    } else if let free = model.cellar.freeSlot(in: cell) {
                                        model.move(bottleID, to: free)
                                        dismiss()
                                    } else if rack.positionsPerCell == 1, let from = bottle.slot {
                                        model.swap(from, Slot(rackID: cell.rackID, row: cell.row, column: cell.column))
                                        dismiss()
                                    } else {
                                        model.showToast(String(localized: "This place is full."))
                                    }
                            }
                            Text("Tap a place. Tapping another bottle swaps them; a bin takes the bottle if it has room.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .padding()
                        } else {
                            Form {
                                TextField("Where is it?", text: $storageNote, prompt: Text("Kitchen, box in the garage…"))
                                Button("Save") {
                                    model.update { cellar in
                                        try cellar.move(bottleID, to: nil)
                                        if var updated = cellar.bottles[bottleID] {
                                            updated.storageNote = storageNote.isEmpty ? nil : storageNote
                                            try cellar.updateBottle(updated)
                                        }
                                    }
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
