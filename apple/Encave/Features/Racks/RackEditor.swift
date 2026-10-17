import CaveKit
import SwiftUI

/// Create or edit a rack, with a live preview.
struct RackEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rackID: UUID?
    @State private var name = ""
    @State private var rows = 6
    @State private var columns = 8
    @State private var layout: RackLayout = .grid
    @State private var loaded = false
    @State private var confirmingDelete = false

    private let presets: [(LocalizedStringKey, Int, Int, RackLayout)] = [
        ("Wine fridge, 24", 4, 6, .grid),
        ("Rack, 48", 6, 8, .grid),
        ("Diamond bins, 36", 6, 6, .diamond),
        ("Large cellar, 120", 10, 12, .grid),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name, prompt: Text("Main rack"))
                        .font(.headline)
                    Picker("Layout", selection: $layout) {
                        Label("Grid", systemImage: "square.grid.3x3").tag(RackLayout.grid)
                        Label("Diamond", systemImage: "diamond").tag(RackLayout.diamond)
                    }
                    .pickerStyle(.segmented)
                    Stepper(value: $rows, in: 1...40) {
                        LabeledContent("Rows") { Text(verbatim: "\(rows)").monospacedDigit() }
                    }
                    Stepper(value: $columns, in: 1...40) {
                        LabeledContent("Columns") { Text(verbatim: "\(columns)").monospacedDigit() }
                    }
                } footer: {
                    Text("\(rows * columns) positions")
                }

                if rackID == nil {
                    Section("Quick start") {
                        FlowLayout(spacing: 8) {
                            ForEach(presets.indices, id: \.self) { index in
                                let preset = presets[index]
                                ChoiceChip(title: preset.0, isSelected: rows == preset.1 && columns == preset.2 && layout == preset.3) {
                                    withAnimation { rows = preset.1; columns = preset.2; layout = preset.3 }
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section("Preview") {
                    ScrollView([.horizontal, .vertical]) {
                        RackGrid(rack: preview, cellSize: 26)
                            .allowsHitTesting(false)
                            .padding(8)
                    }
                    .frame(minHeight: 160, maxHeight: 280)
                }

                if rackID != nil {
                    Section {
                        Button("Delete Rack", role: .destructive) { confirmingDelete = true }
                    } footer: {
                        Text("Bottles in this rack stay in your cellar without a position.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(rackID == nil ? "New Rack" : "Edit Rack")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
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
        }
        .macSheetFrame(width: 480, height: 640)
    }

    private var preview: Rack {
        Rack(id: rackID ?? UUID(), name: name, rows: rows, columns: columns, layout: layout)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let rackID, let rack = model.cellar.rack(rackID) {
            name = rack.name; rows = rack.rows; columns = rack.columns; layout = rack.layout
        } else {
            name = model.cellar.racks.isEmpty ? String(localized: "Main rack") : ""
        }
    }

    private func save() {
        let rack = Rack(id: rackID ?? UUID(), name: name.trimmingCharacters(in: .whitespaces),
                        rows: rows, columns: columns, layout: layout)
        let ok = model.update { cellar in
            if rackID == nil { cellar.addRack(rack) } else { try cellar.updateRack(rack) }
        }
        if ok { dismiss() }
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
            ScrollView([.horizontal, .vertical]) {
                RackGrid(rack: rack, cellSize: 50, selected: Set(selection)) { slot, bottle in
                    guard bottle == nil else { return }
                    if let index = selection.firstIndex(of: slot) {
                        selection.remove(at: index)
                    } else if selection.count < limit {
                        selection.append(slot)
                    } else if limit == 1 {
                        selection = [slot]
                    }
                }
                .padding(24)
            }
            .defaultScrollAnchor(.center)
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
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") { selection = [] }
                        .disabled(selection.isEmpty)
                }
            }
        }
        .macSheetFrame(width: 560, height: 520)
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
                            ScrollView([.horizontal, .vertical]) {
                                RackGrid(rack: rack, cellSize: 50, highlighted: bottle.slot) { slot, occupant in
                                    if occupant == nil || occupant?.id == bottle.id {
                                        model.move(bottleID, to: slot)
                                        dismiss()
                                    } else if let from = bottle.slot {
                                        model.swap(from, slot)
                                        dismiss()
                                    }
                                }
                                .padding(24)
                            }
                            .defaultScrollAnchor(.center)
                            Text("Tap a position. Tapping another bottle swaps them.")
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
                }
            }
        }
        .macSheetFrame(width: 560, height: 560)
    }
}
