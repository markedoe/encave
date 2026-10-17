import CaveKit
import SwiftUI

/// Editable copy of a wine, with text fields for list values.
struct WineDraft: Equatable {
    var id = UUID()
    var name = ""
    var producer = ""
    var vintage: Int?
    var color: WineColor?
    var region = ""
    var country = ""
    var appellation = ""
    var grapes = ""
    var notes = ""
    var windowIsAutomatic = true
    var drinkFrom: Int?
    var drinkUntil: Int?

    init() {}

    init(_ wine: Wine) {
        id = wine.id
        name = wine.name
        producer = wine.producer ?? ""
        vintage = wine.vintage
        color = wine.color
        region = wine.region ?? ""
        country = wine.country ?? ""
        appellation = wine.appellation ?? ""
        grapes = wine.grapes.joined(separator: ", ")
        notes = wine.notes ?? ""
        drinkFrom = wine.drinkFrom
        drinkUntil = wine.drinkUntil
        windowIsAutomatic = wine.drinkFrom == nil
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Window used when automatic: estimated from style and vintage.
    var estimatedWindow: (from: Int, until: Int)? {
        Wine(name: name, vintage: vintage, color: color).estimatedWindow()
    }

    func makeWine() -> Wine {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let window = windowIsAutomatic ? estimatedWindow : drinkFrom.flatMap { from in drinkUntil.map { (from, max(from, $0)) } }
        return Wine(
            id: id, name: clean(name) ?? name, producer: clean(producer), vintage: vintage, color: color,
            region: clean(region), country: clean(country), appellation: clean(appellation),
            grapes: grapes.split(whereSeparator: { $0 == "," || $0 == ";" })
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
            drinkFrom: window?.0, drinkUntil: window?.1, notes: clean(notes)
        )
    }
}

/// Form sections describing a wine. Used by "Add bottle" and "Edit wine".
struct WineFormSections: View {
    @Binding var draft: WineDraft
    @FocusState private var nameFocused: Bool
    var focusName = false

    private let currentYear = Calendar.current.component(.year, from: .now)

    var body: some View {
        Section {
            TextField("Name", text: $draft.name, prompt: Text("Château, cuvée…"))
                .font(.headline)
                .focused($nameFocused)
                .submitLabel(.next)
            TextField("Producer", text: $draft.producer)
            Picker("Vintage", selection: $draft.vintage) {
                Text("Non-vintage").tag(Int?.none)
                ForEach((1950...(currentYear + 1)).reversed(), id: \.self) { year in
                    Text(verbatim: "\(year)").tag(Int?.some(year))
                }
            }
        } header: {
            Text("Wine")
        }
        .onAppear { if focusName { nameFocused = true } }

        Section("Style") {
            FlowLayout(spacing: 8) {
                ForEach(WineColor.allCases, id: \.self) { color in
                    ChoiceChip(title: color.title, isSelected: draft.color == color) {
                        draft.color = draft.color == color ? nil : color
                    } leading: {
                        BottleEnd(color: color, size: 16)
                    }
                }
            }
            .padding(.vertical, 4)
        }

        Section("Origin") {
            TextField("Appellation", text: $draft.appellation)
            TextField("Region", text: $draft.region)
            TextField("Country", text: $draft.country)
            TextField("Grapes", text: $draft.grapes, prompt: Text("Pinot Noir, Gamay"))
        }

        Section {
            Toggle("Estimate automatically", isOn: $draft.windowIsAutomatic.animation())
            if draft.windowIsAutomatic {
                if let window = draft.estimatedWindow {
                    LabeledContent("Suggested") {
                        Text(verbatim: "\(window.from) – \(window.until)").monospacedDigit()
                    }
                } else {
                    Text("Choose a vintage to estimate the window.")
                        .foregroundStyle(.secondary)
                }
            } else {
                Stepper(value: fromBinding, in: 1950...(currentYear + 60)) {
                    LabeledContent("From") { Text(verbatim: "\(fromBinding.wrappedValue)").monospacedDigit() }
                }
                Stepper(value: untilBinding, in: fromBinding.wrappedValue...(currentYear + 80)) {
                    LabeledContent("Until") { Text(verbatim: "\(untilBinding.wrappedValue)").monospacedDigit() }
                }
            }
        } header: {
            Text("Drinking window")
        } footer: {
            Text("Used by Tonight to suggest what to open. You can adjust it anytime.")
        }

        Section("Notes") {
            TextField("Notes about this wine", text: $draft.notes, axis: .vertical)
                .lineLimit(2...6)
        }
    }

    private var fromBinding: Binding<Int> {
        Binding(
            get: { draft.drinkFrom ?? draft.estimatedWindow?.from ?? currentYear },
            set: { draft.drinkFrom = $0; if (draft.drinkUntil ?? $0) < $0 { draft.drinkUntil = $0 } }
        )
    }

    private var untilBinding: Binding<Int> {
        Binding(
            get: { max(draft.drinkUntil ?? draft.estimatedWindow?.until ?? currentYear + 5, fromBinding.wrappedValue) },
            set: { draft.drinkUntil = $0; if draft.drinkFrom == nil { draft.drinkFrom = fromBinding.wrappedValue } }
        )
    }
}

/// Edit an existing wine.
struct EditWineView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let wineID: UUID
    @State private var draft = WineDraft()
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                WineFormSections(draft: $draft)
            }
            .formStyle(.grouped)
            .navigationTitle("Edit Wine")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if model.update({ try $0.updateWine(draft.makeWine()) }) { dismiss() }
                    }
                    .disabled(!draft.isValid)
                }
            }
            .onAppear {
                guard !loaded, let wine = model.cellar.wines[wineID] else { return }
                draft = WineDraft(wine)
                loaded = true
            }
        }
        .macSheetFrame()
    }
}
