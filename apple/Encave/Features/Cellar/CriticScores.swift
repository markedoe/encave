import CaveKit
import SwiftUI

/// Critics' and guides' scores and awards on the wine's page, as the user typed them from a
/// guide, a magazine or a sticker on the bottle: Encave looks nothing up. Without any, only
/// "Add Critic Score". The editor is presented by the page (`editing`), not from a row.
struct CriticScoresSection: View {
    @Environment(CellarModel.self) private var model
    let wine: Wine
    @Binding var editing: CriticScoreEdit?

    var body: some View {
        let scores = wine.criticScores ?? []
        Section {
            ForEach(Array(scores.enumerated()), id: \.offset) { _, critic in
                Button {
                    editing = CriticScoreEdit(original: critic)
                } label: {
                    CriticScoreRow(critic: critic)
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Edits this score."))
                .accessibilityIdentifier("wine.critic")
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        delete(critic)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
                .contextMenu {
                    if let value = CriticScoreRow.valueText(critic) {
                        Button("Copy", systemImage: "doc.on.doc") { Clipboard.copy("\(critic.source) \(value)") }
                    }
                    Button("Edit", systemImage: "pencil") { editing = CriticScoreEdit(original: critic) }
                    Divider()
                    Button("Delete", systemImage: "trash", role: .destructive) { delete(critic) }
                }
                .accessibilityAction(named: Text("Delete")) { delete(critic) }
            }
            Button {
                editing = CriticScoreEdit(original: nil)
            } label: {
                Label("Add Critic Score", systemImage: "rosette")
            }
            .accessibilityIdentifier("wine.critics.add")
        } header: {
            if !scores.isEmpty { Text("Critic Scores") }
        } footer: {
            Text("Scores and awards you copy from a guide, a magazine or the bottle. Encave looks nothing up.")
        }
    }

    private func delete(_ critic: CriticScore) {
        let wineID = wine.id
        model.update { cellar in
            guard var wine = cellar.wines[wineID], var scores = wine.criticScores,
                  let index = scores.firstIndex(of: critic) else { return }
            scores.remove(at: index)
            wine.criticScores = scores
            try cellar.updateWine(wine)
        }
    }
}

/// The score being edited, as a sheet item (nil original: a new one).
struct CriticScoreEdit: Identifiable {
    let id = UUID()
    let original: CriticScore?
}

/// "Cellar Review   93/100" with its note under the source; an award with a medal.
struct CriticScoreRow: View {
    let critic: CriticScore

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: critic.source)
                if let note = critic.note, !note.isEmpty {
                    Text(verbatim: note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if let value = Self.valueText(critic) {
                if critic.score == nil {
                    Label {
                        Text(verbatim: value)
                    } icon: {
                        Image(systemName: "medal")
                    }
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.trailing)
                } else {
                    Text(verbatim: value)
                        .font(.headline.monospacedDigit())
                }
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spokenText))
    }

    /// "93/100", "17.5/20" with the number for the person's language, or the award.
    static func valueText(_ critic: CriticScore) -> String? {
        guard let score = critic.score else { return critic.value }
        let number = score.formatted(.number)
        return critic.scale.map { "\(number)/\($0)" } ?? number
    }

    /// "Cellar Review, 93 out of 100, Tasted from barrel".
    private var spokenText: String {
        var parts = [critic.source]
        if let score = critic.score {
            let formattedScore = score.formatted(.number)
            if let scale = critic.scale {
                let scaleLabel = scale.formatted()
                parts.append(String(localized: "\(formattedScore) out of \(scaleLabel)"))
            } else {
                parts.append(formattedScore)
            }
        } else if let award = critic.value {
            parts.append(award)
        }
        if let note = critic.note, !note.isEmpty { parts.append(note) }
        return parts.joined(separator: ", ")
    }
}

/// Add or change a critic's score: who gave it, a score out of 100 or 20 or an award in
/// words, and a note. Keys from a newer version are kept.
struct CriticScoreEditor: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let wineID: UUID
    let original: CriticScore?

    private enum Kind: Hashable {
        case scale(Int)
        case award
    }

    @State private var source = ""
    @State private var kind = Kind.scale(100)
    @State private var score: Decimal?
    @State private var award = ""
    @State private var note = ""
    @State private var loaded = false

    /// Scores out of 100 and 20, and the scale of a score from a newer version.
    private var scales: [Int] {
        var scales = [100, 20]
        if let other = original?.scale, !scales.contains(other) { scales.append(other) }
        return scales
    }

    private var isValid: Bool {
        guard !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        switch kind {
        case .scale(let maximum):
            guard let score else { return false }
            return score >= 0 && score <= Decimal(maximum)
        case .award:
            return !award.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Source", text: $source, prompt: Text("Guide, magazine or competition"))
                        .accessibilityIdentifier("critic.source")
                    Picker("Kind", selection: $kind) {
                        ForEach(scales, id: \.self) { maximum in
                            Text("Out of \(maximum)").tag(Kind.scale(maximum))
                        }
                        Text("Award").tag(Kind.award)
                    }
                    .pickerStyle(.segmented)
                    switch kind {
                    case .scale(let maximum):
                        LabeledContent {
                            HStack(spacing: 4) {
                                TextField("Score", value: $score, format: .number, prompt: Text(verbatim: maximum == 20 ? "17" : "93"))
                                    .multilineTextAlignment(.trailing)
                                    .numericKeyboard()
                                Text(verbatim: "/\(maximum)")
                                    .foregroundStyle(.secondary)
                                    .accessibilityHidden(true)
                            }
                        } label: {
                            Text("Score")
                        }
                        if let score, score > Decimal(maximum) || score < 0 {
                            Label("Enter a score from 0 to \(maximum).", systemImage: "exclamationmark.triangle")
                                .font(.footnote)
                                .foregroundStyle(Color.warning)
                        }
                    case .award:
                        TextField("Award", text: $award, prompt: Text("Gold medal, Coup de cœur…"))
                    }
                    TextField("Note", text: $note, prompt: Text("For the 2015, tasted from barrel…"), axis: .vertical)
                } footer: {
                    Text("Typed by you, from a guide, a magazine or a sticker on the bottle. Encave looks nothing up.")
                }
                if original != nil {
                    Section {
                        Button("Delete Score", role: .destructive) {
                            save(nil)
                        }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(original == nil ? Text("Add Critic Score") : Text("Critic Score"))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save(edited) }
                        .disabled(!isValid)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .onAppear(perform: load)
        }
        .macSheetFrame(width: 440, height: 420)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        guard let original else { return }
        source = original.source
        if original.score != nil {
            kind = .scale(original.scale ?? 100)
            score = original.score
        } else {
            kind = .award
        }
        award = original.award ?? ""
        note = original.note ?? ""
    }

    /// The score as entered, on the original so its unknown keys are kept.
    private var edited: CriticScore {
        func clean(_ text: String) -> String? {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        var critic = original ?? CriticScore(source: "")
        critic.source = clean(source) ?? source
        switch kind {
        case .scale(let maximum):
            critic.score = score
            critic.scale = maximum
            critic.award = nil
        case .award:
            critic.score = nil
            critic.scale = nil
            critic.award = clean(award)
        }
        critic.note = clean(note)
        return critic
    }

    /// Replaces the original (or adds the new one); nil deletes the original.
    private func save(_ critic: CriticScore?) {
        let original = original
        let saved = model.update { cellar in
            guard var wine = cellar.wines[wineID] else { return }
            var scores = wine.criticScores ?? []
            if let original, let index = scores.firstIndex(of: original) {
                if let critic { scores[index] = critic } else { scores.remove(at: index) }
            } else if let critic {
                scores.append(critic)
            }
            wine.criticScores = scores
            try cellar.updateWine(wine)
        }
        if saved { dismiss() }
    }
}
