import CaveKit
import SwiftUI

/// "Guess the Aromas": pick the aromas (and, if you like, the structure) you expect, then
/// reveal what to expect and get points. Kept on this device only (`AromaGameStore`).
struct AromaGameView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let wineID: UUID
    var bottleID: UUID?
    /// From the Drink sheet: copies the guesses into the tasting being written.
    var copyToTasting: (([String], [String: Int]) -> Void)?
    @State private var picks: [String] = []
    @State private var levels: [String: Int] = [:]
    @State private var guessesStructure = false
    @State private var result: AromaGameRecord?
    /// The level reached with this game, shown with its score.
    @State private var level: Palate.Level?

    var body: some View {
        NavigationStack {
            Group {
                if let wine = model.cellar.wines[wineID], let profile = wine.expectedOrBundledProfile {
                    Form {
                        if let result {
                            AromaGameResultSections(record: result, level: level)
                            if let copyToTasting {
                                Section {
                                    Button("Copy Guesses into the Tasting Note", systemImage: "square.and.pencil") {
                                        copyToTasting(result.guesses, result.structure)
                                        dismiss()
                                    }
                                    .accessibilityIdentifier("game.copy")
                                } footer: {
                                    Text("Adds your aromas and structure to the tasting you are writing; nothing is saved until you tap Drink.")
                                }
                            }
                        } else {
                            pickSections(wine: wine, profile: profile)
                        }
                    }
                    .formStyle(.grouped)
                } else {
                    ContentUnavailableView("Nothing to guess", systemImage: "nose",
                                           description: Text("This wine has no profile to compare with yet."))
                }
            }
            .navigationTitle("Guess the Aromas")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: result == nil ? .cancellationAction : .confirmationAction) {
                    Button(result == nil ? LocalizedStringKey("Cancel") : LocalizedStringKey("Done")) { dismiss() }
                        .accessibilityIdentifier("game.done")
                }
            }
        }
        .macSheetFrame(width: 520, height: 680)
    }

    @ViewBuilder
    private func pickSections(wine: Wine, profile: ExpectedProfile) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(wine.displayName)
                    .font(.headline)
                    .fontDesign(.serif)
                Text("Pick up to \(AromaGame.maxPicks) aromas you expect to find, then reveal what to expect.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        Section {
            ForEach(Vocabulary.aromaFamilies) { family in
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: VocabularyLabels.text(.aromaFamily, family.id))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityAddTraits(.isHeader)
                    FlowLayout(spacing: 8) {
                        ForEach(family.aromas, id: \.id) { aroma in
                            let isSelected = picks.contains(aroma.id)
                            ChoiceChip(verbatim: VocabularyLabels.text(.aroma, aroma.id), isSelected: isSelected) {
                                toggle(aroma.id)
                            } leading: {
                                if isSelected { Image(systemName: "checkmark").accessibilityHidden(true) }
                            }
                            .disabled(!isSelected && picks.count >= AromaGame.maxPicks)
                            .accessibilityIdentifier("game.aroma.\(aroma.id)")
                        }
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("Aromas")
        } footer: {
            let count = picks.count, most = AromaGame.maxPicks
            Text("\(count) of \(most) picked")
        }
        Section {
            Toggle("Also Guess the Structure", isOn: $guessesStructure.animation())
                .accessibilityIdentifier("game.structure")
            if guessesStructure {
                ForEach(Vocabulary.structure.filter { $0.isOffered(for: wine.color, value: levels[$0.id]) }) { attribute in
                    LevelRow(attribute: attribute, value: Binding(
                        get: { levels[attribute.id] },
                        set: { levels[attribute.id] = $0 }
                    ))
                }
            }
        } footer: {
            Text("Within one level of what to expect: 5 points each.")
        }
        Section {
            Button {
                reveal(profile: profile)
            } label: {
                Label("Reveal", systemImage: "eye").frame(maxWidth: .infinity)
            }
            .prominentButtonStyle()
            .disabled(picks.isEmpty)
            .accessibilityIdentifier("game.reveal")
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
        } footer: {
            Text("An expected aroma: 10 points. One of the same family: 4. Your guesses stay on this device.")
        }
    }

    private func toggle(_ id: String) {
        if let index = picks.firstIndex(of: id) {
            picks.remove(at: index)
        } else if picks.count < AromaGame.maxPicks {
            picks.append(id)
        }
    }

    private func reveal(profile: ExpectedProfile) {
        let record = AromaGameRecord(wineID: wineID, bottleID: bottleID, date: Date(), guesses: picks,
                                     structure: guessesStructure ? levels : [:], expected: profile.aromas ?? [],
                                     expectedStructure: profile.structure ?? [:])
        let store = AromaGameStore()
        store.add(record)
        level = Palate(records: store.load(), today: Date()).level
        withAnimation { result = record }
    }
}

/// The score of a game: points, accuracy, and each aroma and level.
struct AromaGameResultSections: View {
    let record: AromaGameRecord
    /// The player's level with this game (My palate).
    var level: Palate.Level?

    var body: some View {
        let score = record.score
        // Big enough to show across the table.
        Section {
            VStack(spacing: 8) {
                let points = score.points
                Text("\(points) points")
                    .font(.system(size: 64, weight: .heavy, design: .rounded))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .accessibilityIdentifier("game.points")
                if let level {
                    Label { Text(level.title) } icon: { Image(systemName: "medal") }
                        .font(.title.weight(.semibold))
                        .accessibilityIdentifier("game.level")
                }
                let found = score.exact.count, expected = score.exact.count + score.family.count + score.missed.count
                Text("\(found) of \(expected) aromas found")
                    .font(.title2)
                let accuracyLabel = (Double(score.accuracy) / 100).formatted(.percent.precision(.fractionLength(0)))
                Text("Accuracy: \(accuracyLabel)")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .accessibilityElement(children: .combine)
        }
        if !score.exact.isEmpty {
            Section("Found") {
                ForEach(score.exact, id: \.self) { aroma in
                    pointsRow(VocabularyLabels.text(.aroma, aroma), points: AromaGame.exactPoints, systemImage: "checkmark.circle")
                }
            }
        }
        if !score.family.isEmpty {
            Section("Same family") {
                ForEach(score.family, id: \.guess) { match in
                    let guessName = VocabularyLabels.text(.aroma, match.guess), expectedName = VocabularyLabels.text(.aroma, match.expected)
                    pointsRow(String(localized: "\(guessName), for \(expectedName)"), points: AromaGame.familyPoints,
                              systemImage: "circle.lefthalf.filled")
                }
            }
        }
        if !score.missed.isEmpty {
            Section("Expected, not picked") {
                ForEach(score.missed, id: \.self) { aroma in
                    Label(VocabularyLabels.text(.aroma, aroma), systemImage: "circle")
                }
            }
        }
        if !score.wrong.isEmpty {
            Section("Not expected") {
                ForEach(score.wrong, id: \.self) { aroma in
                    Label(VocabularyLabels.text(.aroma, aroma), systemImage: "xmark.circle")
                        .foregroundStyle(.secondary)
                }
            }
        }
        if !score.levels.isEmpty {
            Section("Structure") {
                ForEach(score.levels, id: \.attribute) { level in
                    let name = VocabularyLabels.text(.structure, level.attribute)
                    let guessedLabel = word(level.guess, level.attribute), expectedLabel = word(level.expected, level.attribute)
                    LabeledContent {
                        Text(verbatim: level.isClose ? "+\(AromaGame.structurePoints)" : "0").monospacedDigit()
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: name)
                                Text("You: \(guessedLabel) · Expected: \(expectedLabel)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: level.isClose ? "checkmark.circle" : "xmark.circle")
                        }
                    }
                }
            }
        }
    }

    private func pointsRow(_ title: String, points: Int, systemImage: String) -> some View {
        LabeledContent {
            Text(verbatim: "+\(points)").monospacedDigit()
        } label: {
            Label(title, systemImage: systemImage)
        }
    }

    private func word(_ value: Int, _ attribute: String) -> String {
        guard let entry = Vocabulary.structure.first(where: { $0.id == attribute }),
              let id = Vocabulary.level(value, of: entry) else { return "\(value)" }
        return VocabularyLabels.levelText(id, of: attribute)
    }
}

/// "My palate" in Insights: the games played on this device.
struct MyPalateCard: View {
    @Environment(CellarModel.self) private var model
    @State private var records: [AromaGameRecord] = []
    @State private var confirmingReset = false

    var body: some View {
        Group {
            if !records.isEmpty { card(Palate(records: records, today: Date())) }
        }
        .onAppear(perform: load)
    }

    private func load() {
        records = AromaGameStore().load()
        // The screen tour shows made-up games, never saved.
        if records.isEmpty, ProcessInfo.processInfo.arguments.contains("--sample-data") { records = sampleGames() }
    }

    private func card(_ palate: Palate) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("My palate").font(.headline)
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: palate.points.formatted())
                        .font(.largeTitle.weight(.bold))
                        .monospacedDigit()
                    Text("points").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Label { Text(palate.level.title) } icon: { Image(systemName: "medal") }
                    .font(.headline)
                    .accessibilityIdentifier("palate.level")
            }
            .accessibilityElement(children: .combine)
            if let next = palate.level.nextAt {
                ProgressView(value: Double(palate.points), total: Double(next)) {
                    let left = next - palate.points
                    Text("\(left) points to the next level").font(.caption).foregroundStyle(.secondary)
                }
                .tint(Color.accentColor)
            }
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 8) {
                GridRow {
                    Text("Accuracy").foregroundStyle(.secondary)
                    Text(verbatim: palate.accuracy.map { (Double($0) / 100).formatted(.percent.precision(.fractionLength(0))) } ?? "–")
                }
                GridRow {
                    Text("Streak").foregroundStyle(.secondary)
                    let days = palate.streak
                    Text("\(days) days")
                }
                GridRow {
                    Text("Games").foregroundStyle(.secondary)
                    Text(verbatim: palate.games.formatted())
                }
                if let best = palate.bestFamily {
                    GridRow {
                        Text("Best found").foregroundStyle(.secondary)
                        Text(verbatim: VocabularyLabels.text(.aromaFamily, best))
                    }
                }
                if let weakest = palate.weakestFamily {
                    GridRow {
                        Text("To practise").foregroundStyle(.secondary)
                        Text(verbatim: VocabularyLabels.text(.aromaFamily, weakest))
                    }
                }
            }
            .font(.subheadline)
            if !palate.recent.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Last games").font(.subheadline.weight(.semibold))
                    ForEach(palate.recent) { game in
                        HStack {
                            Text(verbatim: model.cellar.wines[game.wineID]?.displayName ?? String(localized: "A wine no longer in the cellar"))
                                .lineLimit(1)
                            Spacer()
                            Text(verbatim: "+\(game.points)").monospacedDigit()
                            Text(verbatim: AICompletedText.day(game.date)).foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            Text("Your guesses and scores stay on this device: never synced, backed up or exported.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button("Reset My Palate", role: .destructive) { confirmingReset = true }
                .font(.subheadline)
                .accessibilityIdentifier("palate.reset")
        }
        .padding(16)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("palate.card")
        .confirmationDialog("Reset your palate?", isPresented: $confirmingReset, titleVisibility: .visible) {
            Button("Remove All Games", role: .destructive) {
                AromaGameStore().removeAll()
                records = []
            }
        } message: {
            Text("Every game played on this device is removed. This cannot be undone.")
        }
    }

    /// Three games on wines of the sample cellar, for the screen tour.
    private func sampleGames() -> [AromaGameRecord] {
        let wines = model.cellar.wines.values.sorted { $0.name < $1.name }.compactMap { wine -> (Wine, ExpectedProfile)? in
            guard let profile = wine.expectedOrBundledProfile, let aromas = profile.aromas, aromas.count >= 3 else { return nil }
            return (wine, profile)
        }.prefix(3)
        return wines.enumerated().map { index, item in
            let (wine, profile) = item
            let aromas = profile.aromas ?? []
            return AromaGameRecord(wineID: wine.id, date: Date().addingTimeInterval(-Double(index) * 86_400),
                                   guesses: Array(aromas.prefix(2 + index % 2)) + ["spice.clove"], structure: profile.structure ?? [:],
                                   expected: aromas, expectedStructure: profile.structure ?? [:])
        }
    }
}

extension Palate.Level {
    var title: LocalizedStringKey {
        switch self {
        case .novice: "Novice"
        case .amateur: "Amateur"
        case .connoisseur: "Connoisseur"
        case .sommelier: "Sommelier"
        }
    }
}
