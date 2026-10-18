import CaveKit
import SwiftUI

/// Remember a wine tasted or seen elsewhere: scan it, say where, add notes and tags,
/// and put it on the shopping list. Also edits an existing discovery.
struct DiscoveryEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let discoveryID: UUID?

    @State private var draft = WineDraft()
    @State private var scan = LabelScan()
    @State private var searchingOnline = false
    @State private var newPhoto: Data?
    @State private var photoRemoved = false
    @State private var date = Date.now
    @State private var setting: Discovery.Setting?
    @State private var place = ""
    @State private var rating: Int?
    @State private var note = ""
    @State private var tags: [String] = []
    @State private var pendingTag = ""
    @State private var price: Decimal?
    @State private var wantsToBuy = false
    @State private var loaded = false
    @State private var confirmingDelete = false

    private var existing: Discovery? { discoveryID.flatMap { model.cellar.discoveries[$0] } }

    var body: some View {
        NavigationStack {
            Form {
                if discoveryID == nil {
                    LabelScanSection(scan: scan, draft: $draft)
                }
                if scan.showsSummary {
                    WineSummarySections(draft: $draft, scan: scan)
                } else {
                    WineFormSections(draft: $draft, focusName: false, showsCellarDetails: false,
                                     onSearchOnline: { searchingOnline = true })
                }

                Section {
                    FlowLayout(spacing: 8) {
                        ForEach(Discovery.Setting.allCases, id: \.self) { option in
                            ChoiceChip(title: option.title, isSelected: setting == option) {
                                withAnimation(.snappy) { setting = setting == option ? nil : option }
                            } leading: {
                                Image(systemName: option.systemImage)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                    TextField("Place", text: $place, prompt: Text("Restaurant, city, friends’ names…"))
                        .fieldHelp("Where you had it, to find the wine again later.")
                    DatePicker("Date", selection: $date, in: ...Date.now, displayedComponents: .date)
                } header: {
                    Text("Where")
                } footer: {
                    Text("Search in the Journal also looks in the place, notes and tags.")
                }

                Section("Your impression") {
                    RatingPicker(rating: $rating)
                    TextField("Notes", text: $note, prompt: Text("What you liked, the dish, the moment…"), axis: .vertical)
                        .lineLimit(3...8)
                }

                Section {
                    TagEditor(tags: $tags, suggestions: model.cellar.discoveryTags, newTag: $pendingTag)
                } header: {
                    Text("Tags")
                } footer: {
                    Text("Your own groups, such as gift ideas or for summer. Tap a tag to select it, or type a new one and press Return. Filter by tag in the Journal.")
                }

                Section {
                    Toggle(isOn: $wantsToBuy.animation()) {
                        Label("To buy", systemImage: "cart")
                    }
                    TextField("Price seen", value: $price, format: .currency(code: model.cellar.settings.currency))
                        .numericKeyboard()
                        .fieldHelp("The price on the wine list or in the shop, to compare when you buy it.")
                } footer: {
                    Text("Wines to buy appear in the shopping list in Journal. Discoveries are not counted as bottles in your cellar.")
                }

                if discoveryID != nil {
                    PhotoSection(photoID: draft.photoID, newPhoto: $newPhoto, removed: $photoRemoved)
                    Section {
                        Button("Delete Discovery", role: .destructive) { confirmingDelete = true }
                    }
                }
                Section {
                    HelpLink(topic: .discoveries, title: "Help with discoveries")
                }
            }
            .formStyle(.grouped)
            .labelScanner(scan, draft: $draft)
            .webSearchSheet(isPresented: $searchingOnline, draft: $draft, includesWindow: false)
            .navigationTitle(discoveryID == nil ? Text("Remember a Wine") : Text("Discovery"))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(!draft.isValid)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .confirmationDialog("Delete this discovery?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete Discovery", role: .destructive) {
                    if let discoveryID { model.deleteDiscovery(discoveryID) }
                    dismiss()
                }
            }
            .onAppear(perform: load)
            .onChange(of: scan.matchedWine) { _, wine in
                guard let wine else { return }
                withAnimation {
                    draft = WineDraft(wine)
                    scan.scanned = true
                }
                scan.matchedWine = nil
            }
        }
        .macSheetFrame(width: 520, height: 720)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let discovery = existing, let wine = model.cellar.wines[discovery.wineID] else { return }
        draft = WineDraft(wine)
        date = discovery.date
        setting = discovery.setting
        place = discovery.place ?? ""
        rating = discovery.rating
        note = discovery.note ?? ""
        tags = discovery.tags
        price = discovery.price
        wantsToBuy = discovery.wantsToBuy
    }

    private func save() {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        var wine = draft.makeWine()
        var discovery = existing ?? Discovery(wineID: wine.id)
        discovery.date = date
        discovery.setting = setting
        discovery.place = clean(place)
        discovery.rating = rating
        discovery.note = clean(note)
        discovery.tags = Discovery.normalized(tags + pendingTag.split(separator: ",").map(String.init))
        discovery.price = price
        discovery.currency = price == nil ? nil : (discovery.currency ?? model.cellar.settings.currency)
        if discovery.wantsToBuy != wantsToBuy, wantsToBuy { discovery.boughtAt = nil }
        discovery.wantsToBuy = wantsToBuy

        if existing == nil {
            if let labelPhoto = scan.photo { wine.photoID = PhotoStore.save(labelPhoto) }
            model.addDiscovery(discovery, wine: wine)
            dismiss()
            return
        }
        let oldPhoto = wine.photoID
        if let newPhoto {
            wine.photoID = PhotoStore.save(newPhoto) ?? oldPhoto
        } else if photoRemoved {
            wine.photoID = nil
        }
        guard model.update({ cellar in
            try cellar.updateWine(wine)
            try cellar.updateDiscovery(discovery)
        }) else { return }
        dismiss()
    }
}
