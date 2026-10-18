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
    var photoID: String?
    var barcodes: [String] = []
    var tags: [String] = []
    /// Not edited in the form, but kept.
    var extraPhotoIDs: [String]?
    var coverPhotoID: String?
    /// A tag being typed, kept on save.
    var pendingTag = ""

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
        photoID = wine.photoID
        barcodes = wine.barcodes ?? []
        tags = wine.tags ?? []
        extraPhotoIDs = wine.extraPhotoIDs
        coverPhotoID = wine.coverPhotoID
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
        var wine = Wine(
            id: id, name: clean(name) ?? name, producer: clean(producer), vintage: vintage, color: color,
            region: clean(region), country: clean(country), appellation: clean(appellation),
            grapes: grapes.split(whereSeparator: { $0 == "," || $0 == ";" })
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
            drinkFrom: window?.0, drinkUntil: window?.1, notes: clean(notes), photoID: photoID, barcodes: barcodes,
            tags: tags + pendingTag.split(separator: ",").map(String.init)
        )
        wine.extraPhotoIDs = extraPhotoIDs
        wine.coverPhotoID = coverPhotoID
        return wine
    }
}

/// Form sections describing a wine. Used by "Add bottle" and "Edit wine".
struct WineFormSections: View {
    @Environment(CellarModel.self) private var model
    @Binding var draft: WineDraft
    @FocusState private var nameFocused: Bool
    var focusName = false
    /// Drinking window and wine notes, hidden when noting a wine found elsewhere.
    var showsCellarDetails = true

    private let currentYear = Calendar.current.component(.year, from: .now)

    var body: some View {
        Section {
            TextField("Name", text: $draft.name, prompt: Text("Château, cuvée…"))
                .font(.headline)
                .fieldHelp("The name on the label, without the vintage. Required.")
                .focused($nameFocused)
                .submitLabel(.next)
            LabeledField("Producer", text: $draft.producer)
                .fieldHelp("The estate, domaine or house that made the wine.")
            Picker("Vintage", selection: $draft.vintage) {
                Text("Non-vintage").tag(Int?.none)
                ForEach((1950...(currentYear + 1)).reversed(), id: \.self) { year in
                    Text(verbatim: "\(year)").tag(Int?.some(year))
                }
            }
            .fieldHelp("The harvest year on the label. Non-vintage for wines without a year.")
        } header: {
            Text("Wine")
        } footer: {
            Text("Only the name is required. Producer and vintage tell apart wines with the same name.")
        }
        .onAppear { if focusName { nameFocused = true } }

        Section {
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
        } header: {
            Text("Style")
        } footer: {
            Text("Used for colors, serving temperature, the estimated drinking window and food pairing.")
        }

        Section {
            LabeledField("Appellation", text: $draft.appellation)
                .fieldHelp("The protected name of origin, such as Pauillac or Chablis.")
            LabeledField("Region", text: $draft.region)
                .fieldHelp("The wine region, such as Bordeaux or Tuscany.")
            LabeledField("Country", text: $draft.country)
            LabeledField("Grapes", text: $draft.grapes, prompt: Text("Pinot Noir, Gamay"))
                .fieldHelp("Grape varieties, separated by commas.")
        } header: {
            Text("Origin")
        } footer: {
            Text("Optional. Makes search and food pairing better. Separate grapes with commas.")
        }

        if showsCellarDetails {
            cellarDetails
        }
    }

    @ViewBuilder
    private var cellarDetails: some View {
        Section {
            Toggle("Estimate automatically", isOn: $draft.windowIsAutomatic.animation())
                .fieldHelp("Works out the window from the style and the vintage. Turn off to enter the years yourself.")
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
            Text("The years when the wine is at its best, from the producer or a guide. Used by Tonight to suggest what to open.")
        }

        Section {
            TagEditor(tags: $draft.tags, suggestions: model.cellar.wineTags, newTag: $draft.pendingTag)
        } header: {
            Text("Tags")
        } footer: {
            Text("Your own groups, such as gift, birth year or for a friend. Filter the Cellar by tag.")
        }

        Section {
            TextField("Notes about this wine", text: $draft.notes, axis: .vertical)
                .lineLimit(2...6)
        } header: {
            Text("Notes")
        } footer: {
            Text("About the wine itself. Notes on a bottle you opened go in its tasting.")
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
    @State private var newPhoto: Data?
    @State private var photoRemoved = false

    var body: some View {
        NavigationStack {
            Form {
                WineFormSections(draft: $draft)
                PhotoSection(photoID: draft.photoID, newPhoto: $newPhoto, removed: $photoRemoved)
            }
            .formStyle(.grouped)
            .navigationTitle("Edit Wine")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
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

    private func save() {
        var wine = draft.makeWine()
        let oldPhoto = wine.photoID
        if let newPhoto {
            wine.photoID = PhotoStore.save(newPhoto) ?? oldPhoto
        } else if photoRemoved {
            wine.photoID = nil
        }
        // Photos added elsewhere while the form was open (another window, sync) stay.
        if let live = model.cellar.wines[wine.id] {
            wine.extraPhotoIDs = live.extraPhotoIDs
            wine.coverPhotoID = live.coverPhotoID
        }
        // A replaced photo file is removed by the cleanup at launch, after a grace period.
        guard model.update({ try $0.updateWine(wine) }) else { return }
        dismiss()
    }
}

/// A text field that keeps its title visible next to the value, as in Contacts.
/// On iPhone and iPad a plain TextField shows its title only while it is empty.
struct LabeledField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var prompt: Text?

    init(_ title: LocalizedStringKey, text: Binding<String>, prompt: Text? = nil) {
        self.title = title
        self._text = text
        self.prompt = prompt
    }

    var body: some View {
        #if os(iOS)
        LabeledContent {
            TextField(title, text: $text, prompt: prompt ?? Text(title))
                .multilineTextAlignment(.trailing)
        } label: {
            Text(title)
        }
        #else
        TextField(title, text: $text, prompt: prompt)
        #endif
    }
}
