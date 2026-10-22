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
    /// When the AI last completed the details: set by Complete with AI, kept on save.
    var aiCompletedAt: Date?
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
        // Automatic unless the years were entered: an estimate follows the vintage and style.
        windowIsAutomatic = wine.windowSource != .entered
        photoID = wine.photoID
        barcodes = wine.barcodes ?? []
        tags = wine.tags ?? []
        extraPhotoIDs = wine.extraPhotoIDs
        coverPhotoID = wine.coverPhotoID
        aiCompletedAt = wine.aiCompletedAt
    }

    var isValid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }

    /// Window used when automatic: estimated from style and vintage.
    var estimatedWindow: (from: Int, until: Int)? {
        Wine(name: name, vintage: vintage, color: color).estimatedWindow()
    }

    /// The years entered by hand, as the form shows them: a year not set yet shows
    /// (and is saved as) the estimate, or this year and five years on.
    var manualWindow: (from: Int, until: Int) {
        let year = CellarYear.current
        let from = drinkFrom ?? estimatedWindow?.from ?? year
        return (from, max(from, drinkUntil ?? estimatedWindow?.until ?? year + 5))
    }

    /// The window saved with the wine.
    var window: (from: Int, until: Int)? { windowIsAutomatic ? estimatedWindow : manualWindow }

    func makeWine() -> Wine {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
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
        wine.aiCompletedAt = aiCompletedAt
        return wine
    }
}

/// A field of the wine form, to open "Edit wine" scrolled to it (a detail tapped on the wine's page).
enum WineFormField: Hashable, Sendable {
    case name, producer, vintage, style, appellation, region, country, grapes, window, tags, notes
}

/// Form sections describing a wine. Used by "Add bottle" and "Edit wine".
struct WineFormSections: View {
    @Environment(CellarModel.self) private var model
    @Binding var draft: WineDraft
    @FocusState private var focused: WineFormField?
    /// The name is focused once, when the form opens, not each time its section reappears.
    @State private var focusedOnce = false
    var focusName = false
    /// A text field to focus when the form opens (the screen scrolls to the others).
    var focusField: WineFormField?
    /// Drinking window and wine notes, hidden when noting a wine found elsewhere.
    var showsCellarDetails = true
    /// Opens Search Online, presented by the screen. Shown only when web search is set up.
    var onSearchOnline: (() -> Void)?
    private let webSearch = WebSearchSettings.load()

    private let currentYear = CellarYear.current

    var body: some View {
        Section {
            TextField("Name", text: $draft.name, prompt: Text("Château, cuvée…"))
                .font(.headline)
                .fieldHelp("The name on the label, without the vintage. Required.")
                .focused($focused, equals: .name)
                .submitLabel(.next)
                .id(WineFormField.name)
            LabeledField("Producer", text: $draft.producer)
                .fieldHelp("The estate, domaine or house that made the wine.")
                .id(WineFormField.producer)
            VintagePicker(vintage: $draft.vintage)
                .id(WineFormField.vintage)
        } header: {
            Text("Wine")
        } footer: {
            Text("Only the name is required. Producer and vintage tell apart wines with the same name.")
        }
        .onAppear {
            guard !focusedOnce else { return }
            focusedOnce = true
            if focusName {
                focused = .name
            } else if let focusField, focusField == .name || focusField == .notes {
                // After the sheet has appeared and the screen has scrolled to the field.
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(600))
                    focused = focusField
                }
            }
        }

        Section {
            StyleChips(color: $draft.color)
                .id(WineFormField.style)
        } header: {
            Text("Style")
        } footer: {
            Text("Used for colors, serving temperature, the estimated drinking window and food pairing.")
        }

        Section {
            LabeledField("Appellation", text: $draft.appellation)
                .fieldHelp("The protected name of origin, such as Pauillac or Chablis.")
                .id(WineFormField.appellation)
            LabeledField("Region", text: $draft.region)
                .fieldHelp("The wine region, such as Bordeaux or Tuscany.")
                .id(WineFormField.region)
            LabeledField("Country", text: $draft.country)
                .id(WineFormField.country)
            LabeledField("Grapes", text: $draft.grapes, prompt: Text("Pinot Noir, Gamay"))
                .fieldHelp("Grape varieties, separated by commas.")
                .id(WineFormField.grapes)
        } header: {
            Text("Origin")
        } footer: {
            Text("Optional. Makes search and food pairing better. Separate grapes with commas.")
        }

        if webSearch.isEnabled, let onSearchOnline {
            Section {
                Button(action: onSearchOnline) {
                    Label("Search Online", systemImage: "globe")
                }
                .disabled(!draft.isValid)
                .accessibilityIdentifier("form.searchOnline")
            } footer: {
                let serviceName = webSearch.service.name
                Text("Finds this wine on the web with \(serviceName) to complete its details. You see the search terms before they are sent.")
            }
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
                .id(WineFormField.window)
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
                Stepper(value: fromBinding, in: 1900...(currentYear + 60)) {
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
                .id(WineFormField.tags)
        } header: {
            Text("Tags")
        } footer: {
            Text("Your own groups, such as gift, birth year or for a friend. Filter the Cellar by tag.")
        }

        Section {
            TextField("Notes about this wine", text: $draft.notes, axis: .vertical)
                .lineLimit(2...6)
                .focused($focused, equals: .notes)
                .id(WineFormField.notes)
        } header: {
            Text("Notes")
        } footer: {
            Text("About the wine itself. Notes on a bottle you opened go in its tasting.")
        }
    }

    private var fromBinding: Binding<Int> {
        Binding(
            get: { draft.manualWindow.from },
            set: { draft.drinkFrom = $0; if (draft.drinkUntil ?? $0) < $0 { draft.drinkUntil = $0 } }
        )
    }

    private var untilBinding: Binding<Int> {
        Binding(
            get: { draft.manualWindow.until },
            set: { draft.drinkUntil = $0; if draft.drinkFrom == nil { draft.drinkFrom = fromBinding.wrappedValue } }
        )
    }
}

/// The vintage of a wine, or Non-vintage. Used by the wine form and the scan summary.
struct VintagePicker: View {
    @Binding var vintage: Int?
    private let currentYear = CellarYear.current

    var body: some View {
        Picker("Vintage", selection: $vintage) {
            Text("Non-vintage").tag(Int?.none)
            // From 1900: old vintages of port, Madeira or Bordeaux are still in cellars.
            ForEach((1900...(currentYear + 1)).reversed(), id: \.self) { year in
                Text(verbatim: "\(year)").tag(Int?.some(year))
            }
        }
        .fieldHelp("The harvest year on the label. Non-vintage for wines without a year.")
    }
}

/// The style of a wine as chips, each with its color and its name. Tap again to clear it.
struct StyleChips: View {
    @Binding var color: WineColor?

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(WineColor.allCases, id: \.self) { option in
                ChoiceChip(title: option.title, isSelected: color == option) {
                    color = color == option ? nil : option
                } leading: {
                    BottleEnd(color: option, size: 16)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// Edit an existing wine.
struct EditWineView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let wineID: UUID
    /// The field to scroll to when the form opens.
    var field: WineFormField?
    @State private var draft = WineDraft()
    /// The wine as it opened, to ask before discarding changes.
    @State private var loadedDraft: WineDraft?
    @State private var searchingOnline = false
    @State private var loaded = false
    @State private var newPhoto: Data?
    @State private var photoRemoved = false
    @State private var analyzing = false
    @State private var aiProblem: String?
    private let aiEnabled = AISettings.load().isEnabled

    private var isDirty: Bool {
        guard let loadedDraft else { return false }
        return draft != loadedDraft || newPhoto != nil || photoRemoved
    }

    /// A label photo for the AI: a new one, or the one kept with the wine.
    private var hasLabelPhoto: Bool {
        newPhoto != nil || (!photoRemoved && PhotoStore.exists(draft.photoID))
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
            Form {
                WineFormSections(draft: $draft, focusField: field, onSearchOnline: { searchingOnline = true })
                PhotoSection(photoID: draft.photoID, newPhoto: $newPhoto, removed: $photoRemoved)
                if aiEnabled, hasLabelPhoto {
                    Section {
                        AICompletionButton(completedAt: draft.aiCompletedAt, isAnalyzing: analyzing, action: completeWithAI)
                        if let aiProblem {
                            Label(aiProblem, systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.secondary)
                        }
                    } footer: {
                        Text("Sends the label photo again to the AI service set in Settings and fills the empty fields. The photo stays with the wine, so this can be done again at any time.")
                    }
                }
            }
            .formStyle(.grouped)
            .task {
                // Once the sheet is up and the form laid out.
                guard let field else { return }
                try? await Task.sleep(for: .milliseconds(350))
                withAnimation { proxy.scrollTo(field, anchor: .top) }
            }
            }
            .webSearchSheet(isPresented: $searchingOnline, draft: $draft)
            .navigationTitle("Edit Wine")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    DiscardChangesCancelButton(isDirty: isDirty) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                    .disabled(!draft.isValid)
                }
            }
            .onAppear {
                guard !loaded, let wine = model.cellar.wines[wineID] else { return }
                draft = WineDraft(wine)
                loadedDraft = draft
                loaded = true
            }
        }
        // A swipe down would lose the changes: Cancel asks first.
        .interactiveDismissDisabled(isDirty)
        .macSheetFrame()
    }

    private func completeWithAI() {
        guard !analyzing, let photo = newPhoto ?? (photoRemoved ? nil : PhotoStore.data(for: draft.photoID)) else { return }
        analyzing = true
        aiProblem = nil
        Task {
            defer { analyzing = false }
            do {
                let analysis = try await AIService.analyzeLabel(jpeg: photo)
                var value = draft
                var filled = WineDraft()
                analysis.apply(to: &value, replacing: &filled)
                value.aiCompletedAt = Date()
                withAnimation { draft = value }
            } catch {
                aiProblem = error.localizedDescription
            }
        }
    }

    private func save() {
        var wine = draft.makeWine()
        let oldPhoto = wine.photoID
        if let newPhoto {
            wine.photoID = PhotoStore.save(newPhoto) ?? oldPhoto
        } else if photoRemoved {
            wine.photoID = nil
        }
        if let live = model.cellar.wines[wine.id] { wine.keepUnedited(from: live) }
        // A replaced photo file is removed by the cleanup at launch, after a grace period.
        guard model.update({ try $0.updateWine(wine) }) else { return }
        dismiss()
    }
}

extension Wine {
    /// Takes the fields the form does not edit from the wine as it is now: photos added
    /// elsewhere while the form was open (another window, sync), the documents and the
    /// current price.
    mutating func keepUnedited(from live: Wine) {
        extraPhotoIDs = live.extraPhotoIDs
        attachments = live.attachments
        coverPhotoID = live.coverPhotoID
        currentPrice = live.currentPrice
        currentPriceCurrency = live.currentPriceCurrency
        currentPriceDate = live.currentPriceDate
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
