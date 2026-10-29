import CaveKit
import SwiftUI

/// Create a storage in three steps: its type, the settings of that type with a live
/// preview, then its name. Editing a storage later uses `RackEditor`.
struct RackWizard: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var settings = StorageSettings(type: .rack)
    @State private var path: [Step] = []
    @State private var name = ""
    @State private var loaded = false
    /// The preview's largest height (a fridge two deep is tall), growing with Dynamic Type like
    /// its captions, and the width of its row.
    @ScaledMetric(relativeTo: .body) private var previewHeight: CGFloat = 380
    @State private var previewWidth: CGFloat = 0

    enum Step: Hashable { case settings, name }

    var body: some View {
        NavigationStack(path: $path) {
            typeStep
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .settings: settingsStep
                    case .name: nameStep
                    }
                }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = defaultName(for: settings.type)
        }
        .macSheetFrame(width: 560, height: 700)
    }

    // MARK: Step 1, type

    private var typeStep: some View {
        Form {
            Section {
                ForEach(StorageType.allCases, id: \.self) { type in
                    Button { choose(type) } label: { TypeRow(type: type, isSelected: settings.type == type) }
                        .accessibilityIdentifier("wizard.type.\(type.rawValue)")
                }
            } footer: {
                Text("The type decides what you set up next. You can change the sizes later.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("New Storage")
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .accessibilityIdentifier("sheet.cancel")
            }
            nextButton(to: .settings)
        }
    }

    // MARK: Step 2, settings

    private var settingsStep: some View {
        Form {
            Section {
                // The whole storage, as large as the row's width and the preview's height allow
                // (a small storage takes less height).
                let space = CGSize(width: max(previewWidth, 200) - 16, height: previewHeight - 16)
                RackGrid(rack: preview, cellSize: RackGrid.fittingCellSize(for: preview, in: space, showsHeaders: false, floor: 0),
                         showsHeaders: false, isPicture: true, captionWidth: space.width)
                    .allowsHitTesting(false)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { previewWidth = $0 }
                    .accessibilityHidden(true)
            } header: {
                Text("Preview")
            } footer: {
                Text("Holds \(preview.capacity) bottles.")
            }
            switch settings.type {
            case .rack: rackSettings
            case .wineFridge: fridgeSettings
            case .crates: crateSettings
            }
        }
        .formStyle(.grouped)
        // Another layout starts its rows again from the uniform settings.
        .onChange(of: settings.layout) {
            guard rowsDiffer else { return }
            settings.setRowsDiffer(false)
            settings.setRowsDiffer(true)
        }
        .navigationTitle(settings.type.name)
        .inlineTitle()
        .toolbar {
            // On the Mac a pushed page cannot be swiped away: Cancel stays at hand.
            #if os(macOS)
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .accessibilityIdentifier("sheet.cancel")
            }
            #endif
            nextButton(to: .name)
        }
    }

    @ViewBuilder
    private var rackSettings: some View {
        Section {
            Picker("Layout", selection: $settings.layout.animation(.snappy)) {
                Text("Grid").tag(RackLayout.grid)
                Text("Honeycomb").tag(RackLayout.honeycomb)
                Text("Diamond bins").tag(RackLayout.diamond)
                    .accessibilityIdentifier("wizard.layout.diamond")
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } header: {
            Text("Layout")
        } footer: {
            switch settings.layout {
            case .grid: Text("One bottle per position, in rows and columns.")
            case .honeycomb: Text("Offset rows nesting into each other, like metal or terracotta racks.")
            case .diamond: Text("Each bin holds several bottles in a pile.")
            }
        }
        countSection("Rows", value: $settings.rowCount, footer: "Counted from the top. Rows are lettered A, B, C…")
        if !rowsDiffer {
            countSection(settings.layout == .diamond ? "Bins per row" : settings.layout == .honeycomb ? "Bottles in the widest row" : "Bottles per row",
                         value: $settings.columns, footer: "Counted from the left. Positions are numbered 1, 2, 3…")
        }
        if settings.layout == .diamond {
            countSection("Bottles per bin", value: $settings.bottlesPerBin, range: 2...StorageSettings.maxCount,
                         footer: "A diamond bin of about 30 cm holds around 10 standard bottles.")
            if settings.offersDiamondEdges {
                DiamondEdgesSection(edges: $settings.diamondEdges, triangleCapacity: triangleCapacity,
                                    binCapacity: settings.bottlesPerBin)
            }
        } else if !rowsDiffer {
            depthSection(footer: "How many bottles fit one behind the other in each position.")
        }
        rowsSection
    }

    @ViewBuilder
    private var fridgeSettings: some View {
        countSection("Shelves", value: $settings.rowCount, range: 1...StorageSettings.maxShelves,
                     footer: "Counted from the top. Shelves are numbered 1, 2, 3…")
        if !rowsDiffer { uniformFridgeSettings }
        rowsSection
    }

    /// Every shelf alike: bottles, layers, depth and head-to-tail for all.
    @ViewBuilder
    private var uniformFridgeSettings: some View {
        countSection("Bottles per shelf", value: $settings.bottlesPerShelf,
                     footer: "Bottles side by side on the bottom layer of a shelf.")
        Section {
            Picker("Layers per shelf", selection: $settings.layersPerShelf.animation(.snappy)) {
                ForEach(1...Rack.maxLayers, id: \.self) { Text(verbatim: "\($0)").tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        } header: {
            Text("Layers per shelf")
        } footer: {
            Text("Bottles stacked in quincunx, each layer resting in the gaps of the one below. 1 is a single row.")
        }
        depthSection(footer: "How many bottles fit one behind the other on a shelf.")
        if settings.offersHeadToTail {
            Section {
                Toggle("Back bottles head-to-tail", isOn: $settings.headToTail)
                    .accessibilityIdentifier("wizard.headToTail")
            } footer: {
                Text("The back bottles lie the other way round: their necks face you between the bases of the front ones.")
            }
        }
    }

    // MARK: Rows set one by one

    /// The rows (a fridge's shelves) are set one by one.
    private var rowsDiffer: Bool { settings.rowSettings != nil }

    private var rowsDifferBinding: Binding<Bool> {
        Binding(get: { rowsDiffer }, set: { on in withAnimation(.snappy) { settings.setRowsDiffer(on) } })
    }

    /// Off by default: the switch, then, once on, each row with its own settings.
    @ViewBuilder
    private var rowsSection: some View {
        let fridge = settings.type == .wineFridge
        Section {
            Group {
                if fridge {
                    Toggle("Shelves are not all the same", isOn: rowsDifferBinding)
                } else {
                    Toggle("Rows are not all the same", isOn: rowsDifferBinding)
                }
            }
            .accessibilityIdentifier("wizard.rowsDiffer")
        } footer: {
            if fridge {
                Text("For a fridge whose shelves differ: one two deep head-to-tail, another single, a shelf of fewer bottles.")
            } else {
                Text("For a rack whose rows differ: a shorter top row, a single row between rows two deep.")
            }
        }
        if let rows = settings.rowSettings {
            Section {
                ForEach(rows.indices, id: \.self) { index in
                    RowSettingsRow(settings: settings, index: index, row: rowBinding(index))
                        .contextMenu {
                            if fridge {
                                Button("Apply to All Shelves", systemImage: "square.stack.3d.down.right") { applyToAll(index) }
                            } else {
                                Button("Apply to All Rows", systemImage: "square.stack.3d.down.right") { applyToAll(index) }
                            }
                        }
                }
            } header: {
                if fridge { Text("Shelves, from the top") } else { Text("Rows, from the top") }
            } footer: {
                if fridge {
                    Text("Touch and hold a shelf to apply it to all shelves.")
                } else {
                    Text("Touch and hold a row to apply it to all rows.")
                }
            }
        }
    }

    /// Bottles per triangle: half a bin until set, never more than a bin.
    private var triangleCapacity: Binding<Int> {
        Binding(
            get: { min(settings.bottlesPerBin, settings.bottlesPerTriangle ?? Rack.defaultTriangleCapacity(binCapacity: settings.bottlesPerBin)) },
            set: { settings.bottlesPerTriangle = $0 }
        )
    }

    private func rowBinding(_ index: Int) -> Binding<StorageRowSettings> {
        Binding(
            get: { settings.rowSettings.flatMap { $0.indices.contains(index) ? $0[index] : nil } ?? StorageRowSettings(positions: 1) },
            set: { row in
                guard settings.rowSettings?.indices.contains(index) == true else { return }
                withAnimation(.snappy) { settings.rowSettings?[index] = row }
            }
        )
    }

    /// Every row takes the settings of this one.
    private func applyToAll(_ index: Int) {
        guard let rows = settings.rowSettings, rows.indices.contains(index) else { return }
        withAnimation(.snappy) { settings.rowSettings = Array(repeating: rows[index], count: rows.count) }
    }

    @ViewBuilder
    private var crateSettings: some View {
        countSection("Bins", value: $settings.bins, footer: "Each crate or box is a bin, named like B3.")
        countSection("Bottles per bin", value: $settings.bottlesPerBin, range: 2...StorageSettings.maxCount,
                     footer: "A wooden crate usually holds 6 or 12 bottles.")
    }

    private func countSection(_ title: LocalizedStringKey, value: Binding<Int>, range: ClosedRange<Int> = 1...StorageSettings.maxCount,
                              footer: LocalizedStringKey) -> some View {
        Section {
            Stepper(value: value.animation(.snappy), in: range) {
                LabeledContent { Text(verbatim: "\(value.wrappedValue)").monospacedDigit() } label: { Text(title) }
            }
        } footer: {
            Text(footer)
        }
    }

    private func depthSection(footer: LocalizedStringKey) -> some View {
        Section {
            Picker("Rows deep", selection: $settings.depth.animation(.snappy)) {
                Text(verbatim: "1").tag(1)
                Text(verbatim: "2").tag(2)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        } header: {
            Text("Rows deep")
        } footer: {
            Text(footer)
        }
    }

    // MARK: Step 3, name

    private var nameStep: some View {
        Form {
            Section {
                TextField("Name", text: $name, prompt: Text(defaultName(for: settings.type)))
                    .font(.headline)
                    .accessibilityIdentifier("wizard.name")
                    .onSubmit(create)
            } footer: {
                Text("A name you will recognize: cellar left wall, kitchen fridge…")
            }
            Section {
                LabeledContent {
                    Text(preview.storageSummary)
                } label: {
                    Label { Text("Holds \(preview.capacity) bottles") } icon: { Image(systemName: preview.systemImage) }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Name")
        .inlineTitle()
        .toolbar {
            // On the Mac a pushed page cannot be swiped away: Cancel stays at hand.
            #if os(macOS)
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .accessibilityIdentifier("sheet.cancel")
            }
            #endif
            ToolbarItem(placement: .confirmationAction) {
                Button("Create", action: create)
                    .disabled(trimmedName.isEmpty)
                    .accessibilityIdentifier("wizard.create")
            }
        }
    }

    // MARK: Actions

    private func nextButton(to step: Step) -> some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Next") { path.append(step) }
                .accessibilityIdentifier("wizard.next")
        }
    }

    private var preview: Rack { settings.rack(name: trimmedName) }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }

    private func defaultName(for type: StorageType) -> String {
        StorageSettings.uniqueName(type.name, among: model.cellar.racks.map(\.name))
    }

    /// A new type starts from its own settings; a name still the default follows it.
    private func choose(_ type: StorageType) {
        guard type != settings.type else { return }
        if trimmedName.isEmpty || name == defaultName(for: settings.type) { name = defaultName(for: type) }
        withAnimation(.snappy) { settings = StorageSettings(type: type) }
    }

    private func create() {
        guard !trimmedName.isEmpty else { return }
        if model.update({ $0.addRack(settings.rack(name: trimmedName)) }) { dismiss() }
    }
}

/// One row (a fridge's shelf) of a storage being created, set on its own: its positions,
/// its depth, its layers and whether its back bottles lie head-to-tail, as they apply.
private struct RowSettingsRow: View {
    let settings: StorageSettings
    let index: Int
    @Binding var row: StorageRowSettings

    private var isFridge: Bool { settings.type == .wineFridge }

    /// "Row B" or "Shelf 2", also said before each control by VoiceOver.
    private var title: String {
        isFridge ? String(localized: "Shelf \(index + 1)") : String(localized: "Row \(Rack.rowLetters(index))")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: title)
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            if isFridge {
                Picker("Layers per shelf", selection: $row.layers) {
                    ForEach(1...Rack.maxLayers, id: \.self) { Text(verbatim: "\($0)").tag($0) }
                }
                .pickerStyle(.segmented)
                .accessibilityLabel(spoken("Layers per shelf"))
            }
            Stepper(value: $row.positions, in: 1...StorageSettings.maxCount) {
                LabeledContent { Text(verbatim: "\(row.positions)").monospacedDigit() } label: { Text(positionsTitle) }
            }
            .accessibilityLabel(spoken(positionsTitle))
            .accessibilityValue(Text(verbatim: "\(row.positions)"))
            if settings.offersRowDepth {
                LabeledContent("Rows deep") {
                    Picker("Rows deep", selection: $row.depth) {
                        ForEach(1...StorageSettings.maxRowDepth, id: \.self) { Text(verbatim: "\($0)").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .fixedSize()
                    .accessibilityLabel(spoken("Rows deep"))
                }
            }
            if settings.offersHeadToTail(row) {
                Toggle("Back bottles head-to-tail", isOn: $row.headToTail)
                    .accessibilityLabel(spoken("Back bottles head-to-tail"))
            }
        }
        .padding(.vertical, 4)
    }

    private var positionsTitle: LocalizedStringKey {
        if settings.type == .rack, settings.layout == .diamond { return "Bins" }
        if isFridge { return row.layers > 1 ? "Bottom layer" : "Bottles" }
        return "Positions"
    }

    /// "Row B, Rows deep": the row first, so each control says which row it sets.
    private func spoken(_ key: LocalizedStringKey) -> Text {
        Text(verbatim: "\(title), ") + Text(key)
    }
}

/// A type of storage to choose: its symbol, name and what it is for.
private struct TypeRow: View {
    let type: StorageType
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: type.systemImage)
                .font(.title2)
                .foregroundStyle(Color.accentColor)
                .frame(minWidth: 36)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                // Plain colours: in a Form button, .primary would take the accent tint.
                Text(type.name)
                    .font(.headline)
                    .foregroundStyle(Color.primary)
                Text(type.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(Color.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            if isSelected {
                Image(systemName: "checkmark")
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.accentColor)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
