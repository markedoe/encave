import CaveKit
import SwiftUI

/// Log opening a bottle: rating, a quick note, and optionally a full tasting note.
struct DrinkSheet: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    @State private var draft = TastingDraft()

    var body: some View {
        NavigationStack {
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
                    }
                    .formStyle(.grouped)
                } else {
                    ContentUnavailableView("Bottle not found", systemImage: "questionmark")
                }
            }
            .navigationTitle("Drink")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        model.drink(bottleID, tasting: draft.makeTasting())
                        dismiss()
                    }
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .macSheetFrame(width: 480, height: 600)
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
                        model.update { $0.undoDrink(bottleID, previousSlot: nil) }
                        dismiss()
                    }
                } footer: {
                    Text("Use this if the bottle was logged by mistake.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Tasting Note")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
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
            TextField("How was it?", text: $draft.note, axis: .vertical)
                .lineLimit(2...6)
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
                TextField("Nose", text: $draft.nose, axis: .vertical)
                TextField("Palate", text: $draft.palate, axis: .vertical)
                TextField("Finish", text: $draft.finish, axis: .vertical)
                TextField("Food served", text: $draft.foodPairing, axis: .vertical)
            }
        }
        .onAppear { if startsExpanded { showsDetails = true } }
    }
}
