import CaveKit
import SwiftUI

/// Note a wine tasted elsewhere (restaurant, friends, a fair): scan it, say where, rate it and
/// write the same tasting note as for a bottle of the cellar (full note, guided sheet, Guess
/// the Aromas, the comparison with what to expect), add tags, and put it on the shopping list.
/// The cellar does not change. Also edits an existing one.
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
    /// The rating, the note and the tasting sheet, as in the Drink sheet. The stars, score and
    /// note are saved in the discovery's own fields; the rest in its `tasting`.
    @State private var tasting = TastingDraft()
    /// Guess the Aromas, from what to expect of the wine as typed.
    @State private var playingGame = false
    /// After Save, with aromas noted: compared with what to expect.
    @State private var comparison: ShownComparison?
    @State private var tags: [String] = []
    @State private var pendingTag = ""
    @State private var price: Decimal?
    @State private var wantsToBuy = false
    @State private var loaded = false
    /// The form as it opened, to ask before discarding changes.
    @State private var initial: FormState?

    /// What the user can change, to tell whether something was entered.
    private struct FormState: Equatable {
        var draft: WineDraft
        var date: Date
        var setting: Discovery.Setting?
        var place: String
        var tasting: TastingDraft
        var tags: [String]
        var pendingTag: String
        var price: Decimal?
        var wantsToBuy: Bool
    }

    private var formState: FormState {
        FormState(draft: draft, date: date, setting: setting, place: place, tasting: tasting,
                  tags: tags, pendingTag: pendingTag, price: price, wantsToBuy: wantsToBuy)
    }

    private var isDirty: Bool {
        guard let initial else { return false }
        return formState != initial || scan.photo != nil || newPhoto != nil || photoRemoved
    }

    private var existing: Discovery? { discoveryID.flatMap { model.cellar.discoveries[$0] } }

    /// "Tasted Elsewhere" for a new one; the wine's name when editing.
    private var title: Text {
        if let name = existing.flatMap({ model.cellar.wines[$0.wineID]?.displayName }) {
            Text(verbatim: name)
        } else {
            Text("Tasted Elsewhere")
        }
    }

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

                // The same tasting as for a bottle of the cellar, with the game before it.
                let wine = draft.makeWine()
                if !(wine.expectedOrBundledProfile?.aromas ?? []).isEmpty {
                    Section {
                        Button {
                            playingGame = true
                        } label: {
                            Label("Guess the Aromas", systemImage: "nose")
                        }
                        .accessibilityIdentifier("discovery.aromaGame")
                    } footer: {
                        Text("Pick the aromas you expect before tasting, then compare. Just for fun: kept on this device.")
                    }
                }
                TastingFormSections(draft: $tasting, startsExpanded: discoveryID != nil, color: draft.color,
                                    expected: wine.expectedOrBundledProfile, showsOccasion: false)

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
                    // In the currency it was noted in, not the one set since.
                    TextField("Price seen", value: $price,
                              format: .currency(code: existing?.currency ?? model.cellar.settings.currency))
                        .numericKeyboard()
                        .fieldHelp("The price on the wine list or in the shop, to compare when you buy it.")
                } footer: {
                    Text("Wines to buy appear under To buy in the Journal. Wines tasted elsewhere are not counted as bottles in your cellar.")
                }

                if discoveryID != nil {
                    PhotoSection(photoID: draft.photoID, newPhoto: $newPhoto, removed: $photoRemoved)
                    Section {
                        // One entry, with Undo: no question first (`DeletionRule`).
                        Button("Delete from Journal", role: .destructive) {
                            if let discoveryID { model.deleteDiscovery(discoveryID) }
                            dismiss()
                        }
                    }
                }
                Section {
                    HelpLink(topic: .journal, title: "Help with the Journal")
                }
            }
            .formStyle(.grouped)
            .labelScanner(scan, draft: $draft)
            .webSearchSheet(isPresented: $searchingOnline, draft: $draft, includesWindow: false)
            .navigationTitle(title)
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    DiscardChangesCancelButton(isDirty: isDirty) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(!draft.isValid)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .onAppear(perform: load)
            .sheet(isPresented: $playingGame) {
                let wine = draft.makeWine()
                AromaGameView(wineID: wine.id, draftWine: wine,
                              copyHint: "Adds your aromas and structure to the tasting you are writing; nothing is saved until you tap Save.") { aromas, levels in
                    tasting.aromas = aromas
                    for (attribute, level) in levels { tasting.levels[attribute] = level }
                }
            }
            .navigationDestination(item: $comparison) { shown in
                AromaComparisonView(wineName: draft.makeWine().displayName, comparison: shown.comparison, done: { dismiss() })
                    .navigationBarBackButtonHidden()
            }
            .onChange(of: scan.matchedWine) { _, wine in
                guard let wine else { return }
                withAnimation {
                    draft = WineDraft(wine)
                    scan.scanned = true
                }
                scan.matchedWine = nil
            }
        }
        // A swipe down would lose what was entered: Cancel asks first.
        .interactiveDismissDisabled(isDirty)
        .macSheetFrame(width: 520, height: 720)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        // After loading: only what the user changes counts.
        defer { initial = formState }
        guard let discovery = existing, let wine = model.cellar.wines[discovery.wineID] else { return }
        draft = WineDraft(wine)
        date = discovery.date
        setting = discovery.setting
        place = discovery.place ?? ""
        var loadedTasting = TastingDraft(discovery.tasting ?? Tasting(date: discovery.date))
        loadedTasting.rating = discovery.rating
        loadedTasting.score100 = discovery.score100
        loadedTasting.note = discovery.note ?? ""
        tasting = loadedTasting
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
        // The stars, score and note in the discovery's own fields (older versions read them);
        // the full note and the guided sheet in its tasting, dated like it.
        var note = tasting.makeTasting()
        discovery.rating = note.rating
        discovery.score100 = Score.kept(note.score100, rating: note.rating)
        discovery.note = note.note
        note.rating = nil
        note.score100 = nil
        note.note = nil
        note.occasion = nil
        note.date = date
        discovery.tasting = note.isDetailed ? note : nil
        discovery.tags = Discovery.normalized(tags + pendingTag.split(separator: ",").map(String.init))
        discovery.price = price
        discovery.currency = price == nil ? nil : (discovery.currency ?? model.cellar.settings.currency)
        if discovery.wantsToBuy != wantsToBuy, wantsToBuy { discovery.boughtAt = nil }
        discovery.wantsToBuy = wantsToBuy

        if existing == nil {
            if let live = model.cellar.wines[wine.id] {
                // A wine of the cellar recognized by the scan: what was changed in the form is kept.
                wine.keepUnedited(from: live)
                wine.keepExpected(from: live, filledByScan: scan.autoFilled.expectedProfile)
                // The drinking window is not shown here: it stays as it is.
                wine.drinkFrom = live.drinkFrom
                wine.drinkUntil = live.drinkUntil
                if wine.photoID == nil, let labelPhoto = scan.photo { wine.photoID = PhotoStore.save(labelPhoto) }
                guard model.update({ try $0.updateWine(wine) }) else { return }
            } else if let labelPhoto = scan.photo {
                wine.photoID = PhotoStore.save(labelPhoto)
            }
            model.addDiscovery(discovery, wine: wine)
            finish(wine: wine)
            return
        }
        if let live = model.cellar.wines[wine.id] {
            wine.keepUnedited(from: live)
            wine.keepExpected(from: live, filledByScan: scan.autoFilled.expectedProfile)
            wine.drinkFrom = live.drinkFrom
            wine.drinkUntil = live.drinkUntil
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
        finish(wine: wine)
    }

    /// Saved: with aromas noted, how they compare with what to expect; else closed.
    private func finish(wine: Wine) {
        let expected = wine.expectedOrBundledProfile?.aromas ?? []
        if !tasting.aromas.isEmpty, !expected.isEmpty {
            comparison = ShownComparison(comparison: AromaComparison(expected: expected, found: tasting.aromas))
        } else {
            dismiss()
        }
    }
}
