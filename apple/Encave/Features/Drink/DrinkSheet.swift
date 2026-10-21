import CaveKit
import SwiftUI

/// Log opening a bottle: rating, a quick note, and optionally a full tasting note.
/// Without a bottle (from the Journal's "+"), the wines of the cellar are listed first.
struct DrinkSheet: View {
    @Environment(\.dismiss) private var dismiss
    /// nil: choose the bottle first.
    let bottleID: UUID?

    var body: some View {
        NavigationStack {
            if let bottleID {
                DrinkForm(bottleID: bottleID, showsCancel: true) { dismiss() }
            } else {
                BottleToOpenPicker()
                    .navigationDestination(for: UUID.self) { id in
                        DrinkForm(bottleID: id, showsCancel: false) { dismiss() }
                    }
            }
        }
        .presentationDetents(bottleID == nil ? [.large] : [.medium, .large])
        .solidSheetBackground()
        .macSheetFrame(width: 480, height: 600)
    }
}

/// The wines with bottles in the cellar, to choose the one opened.
private struct BottleToOpenPicker: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        let groups = model.cellar.groups(matching: query)
        List {
            if groups.isEmpty {
                if model.cellar.bottlesInCellar.isEmpty {
                    ContentUnavailableView("No bottles in your cellar", systemImage: "wineglass",
                                           description: Text("Add bottles to your cellar to open them here."))
                        .listRowBackground(Color.clear)
                } else {
                    ContentUnavailableView.search(text: query)
                        .listRowBackground(Color.clear)
                }
            } else {
                Section {
                    ForEach(groups) { group in
                        if let bottle = group.bottles.bottleToOpen {
                            NavigationLink(value: bottle.id) {
                                WineRow(group: group)
                            }
                            .accessibilityIdentifier("drink.pick.row")
                        }
                    }
                } footer: {
                    Text("The bottle you choose leaves your cellar, and its place in the rack becomes free.")
                }
            }
        }
        .searchable(text: $query, prompt: Text("Wine, producer, region, grape, vintage"))
        .navigationTitle("Which Bottle?")
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
                    .accessibilityIdentifier("sheet.cancel")
            }
        }
    }
}

/// Rating and notes for the bottle being opened.
private struct DrinkForm: View {
    @Environment(CellarModel.self) private var model
    let bottleID: UUID
    var showsCancel: Bool
    /// Closes the whole sheet: in a pushed form, the dismiss action would only go back.
    let close: () -> Void
    @State private var draft = TastingDraft()

    var body: some View {
            Group {
                if let bottle = model.cellar.bottles[bottleID], let wine = model.cellar.wines[bottle.wineID] {
                    Form {
                        Section {
                            HStack(spacing: 14) {
                                BottleEnd(color: wine.color, size: 44)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(wine.displayName)
                                        .font(.title3.weight(.semibold))
                                        .fontDesign(.serif)
                                    if let location = model.locationLabel(for: bottle) {
                                        Text(location).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .listRowBackground(Color.clear)
                        }
                        TastingFormSections(draft: $draft)
                        Section {
                            HelpLink(topic: .drinking, title: "Help with tasting notes")
                        }
                    }
                    .formStyle(.grouped)
                } else {
                    ContentUnavailableView("Bottle not found", systemImage: "questionmark")
                }
            }
            .navigationTitle("Open a Bottle")
            .inlineTitle()
            .toolbar {
                if showsCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", action: close)
                            .accessibilityIdentifier("sheet.cancel")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        // On failure the sheet stays open with what was typed, under the error.
                        if model.drink(bottleID, tasting: draft.makeTasting()) { close() }
                    }
                    .keyboardShortcut(.defaultAction)
                }
            }
    }
}

/// Edit the tasting of a bottle already opened.
struct TastingEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    @State private var draft = TastingDraft()
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                if let bottle = model.cellar.bottles[bottleID], let wine = model.cellar.wines[bottle.wineID] {
                    Section {
                        HStack(spacing: 14) {
                            BottleEnd(color: wine.color, size: 40)
                            Text(wine.displayName).font(.headline).fontDesign(.serif)
                        }
                        .listRowBackground(Color.clear)
                    }
                }
                TastingFormSections(draft: $draft, startsExpanded: true)
                Section {
                    Button("Put Back in the Cellar", role: .destructive) {
                        model.putBack(bottleID)
                        dismiss()
                    }
                } footer: {
                    Text("Use this if the bottle was logged by mistake. Its tasting note is deleted; you can undo right after.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("My Tasting")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if model.update({ try $0.updateTasting(bottleID, tasting: draft.makeTasting()) }) { dismiss() }
                    }
                }
            }
            .onAppear {
                guard !loaded else { return }
                loaded = true
                if let tasting = model.cellar.bottles[bottleID]?.tasting { draft = TastingDraft(tasting) }
            }
        }
        .macSheetFrame(width: 480, height: 680)
    }
}

struct TastingDraft: Equatable {
    var date = Date.now
    var rating: Int?
    var note = ""
    var occasion = ""
    var appearance = ""
    var nose = ""
    var palate = ""
    var finish = ""
    var foodPairing = ""

    init() {}

    init(_ tasting: Tasting) {
        date = tasting.date
        rating = tasting.rating
        note = tasting.note ?? ""
        occasion = tasting.occasion ?? ""
        appearance = tasting.appearance ?? ""
        nose = tasting.nose ?? ""
        palate = tasting.palate ?? ""
        finish = tasting.finish ?? ""
        foodPairing = tasting.foodPairing ?? ""
    }

    func makeTasting() -> Tasting {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        return Tasting(date: date, rating: rating, note: clean(note), occasion: clean(occasion),
                       appearance: clean(appearance), nose: clean(nose), palate: clean(palate),
                       finish: clean(finish), foodPairing: clean(foodPairing))
    }
}

/// Rating, quick note and occasion, with the full tasting grid behind a disclosure.
struct TastingFormSections: View {
    @Binding var draft: TastingDraft
    var startsExpanded = false
    @State private var showsDetails = false

    private let occasions: [LocalizedStringResource] = ["Dinner at home", "With friends", "Celebration", "Restaurant", "Tasting"]

    var body: some View {
        Section {
            RatingPicker(rating: $draft.rating)
                .frame(maxWidth: .infinity)
                .fieldHelp("From one star (disappointing) to five (exceptional). Tap the same star again to clear it.")
            TextField("How was it?", text: $draft.note, axis: .vertical)
                .lineLimit(2...6)
                .fieldHelp("A few words to remember the bottle.")
        } footer: {
            Text("Everything is optional and can be changed later from the Journal.")
        }

        Section("Occasion") {
            FlowLayout(spacing: 8) {
                ForEach(occasions.indices, id: \.self) { index in
                    let value = String(localized: occasions[index])
                    ChoiceChip(title: LocalizedStringKey(value), isSelected: draft.occasion == value) {
                        draft.occasion = draft.occasion == value ? "" : value
                    }
                }
            }
            .padding(.vertical, 4)
            DatePicker("Date", selection: $draft.date, in: ...Date.now, displayedComponents: .date)
        }

        Section {
            DisclosureGroup("Full tasting note", isExpanded: $showsDetails) {
                TextField("Appearance", text: $draft.appearance, axis: .vertical)
                    .fieldHelp("Color, intensity, clarity.")
                TextField("Nose", text: $draft.nose, axis: .vertical)
                    .fieldHelp("Aromas: fruit, flowers, spices, wood…")
                TextField("Palate", text: $draft.palate, axis: .vertical)
                    .fieldHelp("Taste and structure: sweetness, acidity, tannins, body.")
                TextField("Finish", text: $draft.finish, axis: .vertical)
                    .fieldHelp("How long the flavors last after swallowing.")
                TextField("Food served", text: $draft.foodPairing, axis: .vertical)
                    .fieldHelp("What you ate with it, to remember good pairings.")
            }
        } footer: {
            Text("A structured note: appearance, nose, palate, finish and the food you had with it.")
        }
        .onAppear { if startsExpanded { showsDetails = true } }
    }
}
