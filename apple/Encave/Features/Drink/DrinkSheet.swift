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
        .modifier(DrinkSheetDetents(choosing: bottleID == nil))
        // Choosing the bottle: full height, with the standard background, so the search
        // field's placeholder keeps its usual contrast.
        .solidSheetBackground(bottleID != nil)
        .macSheetFrame(width: 480, height: 600)
    }
}

/// Choosing the bottle: full height. A known bottle: half height on iPhone.
private struct DrinkSheetDetents: ViewModifier {
    let choosing: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if choosing {
            content.presentationDetents([.large])
        } else {
            content.halfSheetDetents()
        }
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
                if model.bottlesInCellar.isEmpty {
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
                    Text("The bottle you choose leaves your cellar, and its position becomes free.")
                }
            }
        }
        .searchable(text: $query, prompt: Text("Wine, producer, region, grape, vintage [drink]"))
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

/// Rating and notes for the bottle being opened, and which bottle when the wine is in
/// several places.
private struct DrinkForm: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    var showsCancel: Bool
    /// Closes the whole sheet: in a pushed form, the dismiss action would only go back.
    let close: () -> Void
    @State private var draft: TastingDraft
    /// The form as it opened, to know whether something was typed.
    @State private var original: TastingDraft
    /// The place chosen in "Which Bottle?"; the bottle the sheet opened with until then.
    @State private var chosenID: UUID?
    @State private var confirmingDiscard = false
    /// Where Cancel or Back goes once the note is discarded.
    @State private var discardCloses = true

    init(bottleID: UUID, showsCancel: Bool, close: @escaping () -> Void) {
        self.bottleID = bottleID
        self.showsCancel = showsCancel
        self.close = close
        let start = TastingDraft()
        _draft = State(initialValue: start)
        _original = State(initialValue: start)
    }

    private var isDirty: Bool { draft != original }

    /// One bottle per place, free ones first; the one the sheet opened with stands for its place.
    private func choices(of bottle: Bottle) -> [Bottle] {
        let others = model.cellar.bottles(of: bottle.wineID).filter { $0.id != bottle.id }
        return ([bottle] + others).drinkChoices
    }

    var body: some View {
            Group {
                if let opened = model.cellar.bottles[bottleID], let wine = model.cellar.wines[opened.wineID] {
                    let choices = choices(of: opened)
                    let bottle = chosenID.flatMap { id in choices.first { $0.id == id } } ?? opened
                    Form {
                        Section {
                            HStack(spacing: 14) {
                                BottleEnd(color: wine.color, size: 44)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(wine.displayName)
                                        .font(.title3.weight(.semibold))
                                        .fontDesign(.serif)
                                    if choices.count < 2, let location = model.locationLabel(for: bottle) {
                                        Text(location).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .listRowBackground(Color.clear)
                            if choices.count > 1 {
                                Picker("Which Bottle?", selection: Binding(get: { bottle.id }, set: { chosenID = $0 })) {
                                    ForEach(choices) { choice in
                                        BottleChoiceLabel(bottle: choice).tag(choice.id)
                                    }
                                }
                                .pickerStyle(.menu)
                                .accessibilityIdentifier("drink.which")
                            }
                        }
                        TastingFormSections(draft: $draft, color: wine.color)
                        Section {
                            HelpLink(topic: .drinking, title: "Help with Tasting Notes")
                        }
                    }
                    .formStyle(.grouped)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Drink") {
                                // On failure the sheet stays open with what was typed, under the error.
                                if model.drink(bottle.id, tasting: draft.makeTasting()) { close() }
                            }
                            .keyboardShortcut(.defaultAction)
                            .accessibilityIdentifier("drink.confirm")
                        }
                    }
                } else {
                    ContentUnavailableView("Bottle not found", systemImage: "questionmark")
                }
            }
            .navigationTitle("Open a Bottle")
            .inlineTitle()
            // Pushed from "Which Bottle?": going back with a note asks first, too.
            .navigationBarBackButtonHidden(!showsCancel && isDirty)
            .toolbar {
                if showsCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { leave(closing: true) }
                            .accessibilityIdentifier("sheet.cancel")
                    }
                } else if isDirty {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Back") { leave(closing: false) }
                    }
                }
            }
            .discardGuard(isDirty: isDirty, isAsking: $confirmingDiscard) {
                if discardCloses { close() } else { dismiss() }
            }
    }

    private func leave(closing: Bool) {
        guard isDirty else { return closing ? close() : dismiss() }
        discardCloses = closing
        confirmingDiscard = true
    }
}

/// A place in "Which Bottle?": the rack position or storage note, and the occasion a
/// kept bottle is for, with a gift so it is not told by color alone.
private struct BottleChoiceLabel: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle

    var body: some View {
        let location = model.locationLabel(for: bottle) ?? String(localized: "Not in storage")
        if let occasionName = bottle.reservedFor {
            Label("\(location) · Kept for \(occasionName)", systemImage: "gift")
        } else {
            Label(location, systemImage: model.locationIcon(for: bottle))
        }
    }
}

/// Edit the tasting of a bottle already opened.
struct TastingEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @Environment(\.dismiss) private var dismiss
    let bottleID: UUID
    /// Opened from the Journal: the wine opens there.
    var fromJournal = false
    @State private var draft = TastingDraft()
    @State private var original = TastingDraft()
    @State private var loaded = false
    @State private var confirmingDiscard = false

    private var isDirty: Bool { loaded && draft != original }

    var body: some View {
        NavigationStack {
            Form {
                if let bottle = model.cellar.bottles[bottleID], let wine = model.cellar.wines[bottle.wineID] {
                    Section {
                        // Opens the wine, closing the note (after asking if it changed).
                        Button {
                            showWine(wine.id)
                        } label: {
                            HStack(spacing: 14) {
                                // Its picture, as in the Cellar, also once no bottle is left.
                                if wine.hasShownPhoto {
                                    WineImage(wine: wine, maxPixelSize: 300, inset: 3)
                                        .frame(width: 60, height: 80)
                                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                        .accessibilityHidden(true)
                                } else {
                                    BottleEnd(color: wine.color, size: 40)
                                        .accessibilityHidden(true)
                                }
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(wine.displayName).font(.headline).fontDesign(.serif)
                                    Text("Show Wine")
                                        .font(.subheadline)
                                        .foregroundStyle(Color.accentColor)
                                        .accessibilityHidden(true)
                                }
                                Spacer()
                                Image(systemName: "chevron.forward")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(Text("Show Wine"))
                        .accessibilityIdentifier("tasting.wine")
                        .listRowBackground(Color.clear)
                    }
                }
                TastingFormSections(draft: $draft, startsExpanded: true,
                                    color: model.cellar.bottles[bottleID].flatMap { model.cellar.wines[$0.wineID] }?.color)
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
                    Button("Cancel") {
                        if isDirty { pendingWine = nil; confirmingDiscard = true } else { dismiss() }
                    }
                    .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if model.update({ try $0.updateTasting(bottleID, tasting: draft.makeTasting()) }) { dismiss() }
                    }
                    .keyboardShortcut(.defaultAction)
                }
            }
            .discardGuard(isDirty: isDirty, isAsking: $confirmingDiscard) {
                dismiss()
                if let pendingWine { open(wine: pendingWine) }
            }
            .onAppear {
                guard !loaded else { return }
                loaded = true
                if let bottle = model.cellar.bottles[bottleID] {
                    if let tasting = bottle.tasting {
                        draft = TastingDraft(tasting)
                    } else {
                        // Opened without a tasting (imported): the date the Journal shows, as on
                        // Android, not today.
                        draft.date = bottle.addedAt
                    }
                }
                original = draft
            }
        }
        .macSheetFrame(width: 480, height: 680)
    }

    /// The wine to open once the note is discarded.
    @State private var pendingWine: UUID?

    private func showWine(_ id: UUID) {
        guard isDirty else {
            dismiss()
            open(wine: id)
            return
        }
        pendingWine = id
        confirmingDiscard = true
    }

    /// Done once the sheet has closed (`SceneRouter` waits for it).
    private func open(wine id: UUID) {
        if fromJournal, let router {
            router.perform(.openWineFromJournal(id))
        } else {
            model.open(wine: id)
        }
    }
}

struct TastingDraft: Equatable {
    var date = Date.now
    var rating: Int?
    /// The precise score out of 100, when entered out of 20 or 100 (`Score`).
    var score100: Double?
    var note = ""
    var occasion = ""
    var appearance = ""
    var nose = ""
    var palate = ""
    var finish = ""
    var foodPairing = ""
    /// The guided sheet: aromas (vocabulary ids, in the order chosen), structure levels
    /// (1 to 5, by attribute id), intensity and hue.
    var aromas: [String] = []
    var levels: [String: Int] = [:]
    var intensity: String?
    var hue: String?
    /// The tasting as stored, so what this form does not show (keys of a newer version)
    /// is written back unchanged.
    private var stored: Tasting?

    init() {}

    init(_ tasting: Tasting) {
        stored = tasting
        date = tasting.date
        rating = tasting.rating
        score100 = tasting.score100
        note = tasting.note ?? ""
        occasion = tasting.occasion ?? ""
        appearance = tasting.appearance ?? ""
        nose = tasting.nose ?? ""
        palate = tasting.palate ?? ""
        finish = tasting.finish ?? ""
        foodPairing = tasting.foodPairing ?? ""
        aromas = tasting.aromas ?? []
        for attribute in Vocabulary.structure {
            if let value = tasting.level(of: attribute.id) { levels[attribute.id] = value }
        }
        intensity = tasting.intensity
        hue = tasting.hue
    }

    func makeTasting() -> Tasting {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        var tasting = stored ?? Tasting(date: date)
        tasting.date = date
        tasting.rating = rating
        tasting.score100 = Score.kept(score100, rating: rating)
        tasting.note = clean(note)
        tasting.occasion = clean(occasion)
        tasting.appearance = clean(appearance)
        tasting.nose = clean(nose)
        tasting.palate = clean(palate)
        tasting.finish = clean(finish)
        tasting.foodPairing = clean(foodPairing)
        tasting.aromas = aromas
        for attribute in Vocabulary.structure {
            tasting.setLevel(levels[attribute.id], of: attribute.id)
        }
        tasting.intensity = intensity
        tasting.hue = hue
        return tasting
    }
}

/// Rating, quick note and occasion, with the full tasting grid and the guided sheet behind
/// disclosures.
struct TastingFormSections: View {
    @Binding var draft: TastingDraft
    var startsExpanded = false
    /// The wine's style: the aromas and hues that suit it come first.
    var color: WineColor?
    @State private var showsDetails = false
    @State private var showsGuided = false

    var body: some View {
        Section {
            RatingInput(rating: $draft.rating, score100: $draft.score100)
            TextField("How was it?", text: $draft.note, axis: .vertical)
                .lineLimit(2...6)
                .fieldHelp("A few words to remember the bottle.")
        } footer: {
            Text("Everything is optional and can be changed later from the Journal.")
        }

        Section("Occasion") {
            FlowLayout(spacing: 8) {
                // A note written in another language still shows its chip as chosen.
                let chosen = Occasion.matching(draft.occasion)
                ForEach(Occasion.allCases, id: \.self) { occasion in
                    ChoiceChip(verbatim: String(localized: occasion.resource), isSelected: chosen == occasion) {
                        draft.occasion = chosen == occasion ? "" : String(localized: occasion.resource)
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

        Section {
            DisclosureGroup(isExpanded: $showsGuided) {
                GuidedTastingRows(draft: $draft, color: color)
            } label: {
                Text("More details")
                    .accessibilityIdentifier("tasting.more")
            }
        } footer: {
            Text("Appearance, aromas and structure in a few taps, for a wine-course style note.")
        }
        // The guided sheet stays closed until asked for: rating a bottle stays one tap.
        .onAppear { if startsExpanded { showsDetails = true } }
    }
}

extension Occasion {
    /// The chip's label, in the app's language (the same table as in CaveKit).
    var resource: LocalizedStringResource {
        switch self {
        case .dinnerAtHome: "Dinner at home"
        case .withFriends: "With friends"
        case .celebration: "Celebration"
        case .restaurant: "Restaurant"
        case .weeknight: "Weeknight"
        case .tasting: "Tasting"
        }
    }

    /// An occasion from statistics (a chip's English label, or text as typed), in the app's language.
    static func displayName(_ text: String) -> String {
        matching(text).map { String(localized: $0.resource) } ?? text
    }
}
