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
        countSection("Rows", value: $settings.rows, footer: "Counted from the top. Rows are lettered A, B, C…")
        countSection(settings.layout == .diamond ? "Bins per row" : settings.layout == .honeycomb ? "Bottles in the widest row" : "Bottles per row",
                     value: $settings.columns, footer: "Counted from the left. Places are numbered 1, 2, 3…")
        if settings.layout == .diamond {
            countSection("Bottles per bin", value: $settings.bottlesPerBin, range: 2...StorageSettings.maxCount,
                         footer: "A diamond bin of about 30 cm holds around 10 standard bottles.")
        } else {
            depthSection(footer: "How many bottles fit one behind the other in each cell.")
        }
    }

    @ViewBuilder
    private var fridgeSettings: some View {
        countSection("Shelves", value: $settings.shelves, range: 1...StorageSettings.maxShelves,
                     footer: "Counted from the top. Shelves are numbered 1, 2, 3…")
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
                    Label { Text("Holds \(preview.capacity) bottles.") } icon: { Image(systemName: preview.systemImage) }
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
                Text(type.name)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(type.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
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
