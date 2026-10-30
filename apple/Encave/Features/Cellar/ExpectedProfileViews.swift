import CaveKit
import SwiftUI

/// "What to expect" on a wine's page: the profile kept with the wine (from the AI), else the
/// typical one bundled with Encave for its appellation, grapes or style. With AI set up,
/// "What to Expect" asks the models, sending only the wine's identity.
struct ExpectedProfileSection: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.locale) private var locale
    let wine: Wine
    /// Opens the aroma game (Guess the Aromas), when there is something to guess.
    var playGame: (() -> Void)?
    @State private var asking = false
    @State private var problem: String?
    private let aiConfiguration = AISettings.load()

    var body: some View {
        let profile = wine.expectedOrBundledProfile
        if profile != nil || aiConfiguration.isEnabled {
            Section {
                if let profile {
                    ExpectedProfileRows(profile: profile)
                    ExpectedSourceLabel(profile: profile)
                } else {
                    Text("Nothing bundled matches this wine yet. Add its grapes, appellation or style, or ask the AI.")
                        .foregroundStyle(.secondary)
                }
                if let playGame, !(profile?.aromas ?? []).isEmpty {
                    Button(action: playGame) {
                        Label("Guess the Aromas", systemImage: "nose")
                    }
                    .accessibilityIdentifier("action.wine.aromaGame")
                }
                if aiConfiguration.isEnabled {
                    Button(action: ask) {
                        if asking {
                            Label("Asking the AI…", systemImage: "sparkles")
                        } else {
                            Label(wine.expectedProfile?.source == ExpectedProfile.ai
                                  ? LocalizedStringKey("Ask Again") : LocalizedStringKey("What to Expect"),
                                  systemImage: "sparkles")
                        }
                    }
                    .disabled(asking)
                    .accessibilityIdentifier("action.wine.whatToExpect")
                }
                if wine.expectedProfile != nil, ExpectedProfiles.profile(for: wine) != nil {
                    Button("Use the Typical Profile", systemImage: "arrow.uturn.backward") {
                        model.setExpectedProfile(nil, for: wine.id)
                    }
                    .accessibilityIdentifier("action.wine.typicalProfile")
                }
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("What to expect")
            } footer: {
                if aiConfiguration.isEnabled {
                    Text("What to Expect sends only the producer, name, appellation, vintage, colour and grapes to the AI models set in Settings, never the photo, notes, prices or places.")
                } else {
                    Text("Typical of the appellation, grape or style, bundled with Encave: nothing is sent.")
                }
            }
        }
    }

    private func ask() {
        guard !asking else { return }
        asking = true
        problem = nil
        let identity = WineIdentity(wine)
        let wineID = wine.id
        Task {
            defer { asking = false }
            do {
                let (profile, _) = try await AIService.expectedProfile(for: identity, language: AIService.language)
                guard let profile else {
                    problem = String(localized: "The AI does not know this wine well enough.")
                    return
                }
                model.setExpectedProfile(profile, for: wineID)
            } catch is CancellationError {
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}

/// Aromas, structure, serving, pairings and the short text of a profile.
struct ExpectedProfileRows: View {
    let profile: ExpectedProfile
    /// In the tasting sheet: aromas and structure only.
    var compact = false

    var body: some View {
        if let aromas = profile.aromas, !aromas.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Aromas")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityAddTraits(.isHeader)
                FlowLayout(spacing: 8) {
                    ForEach(aromas, id: \.self) { aroma in
                        TagCapsule(Text(verbatim: VocabularyLabels.text(.aroma, aroma)))
                    }
                }
            }
            .padding(.vertical, 2)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("expected.aromas")
        }
        ForEach(Vocabulary.structure.filter { profile.level($0.id) != nil }) { attribute in
            let value = profile.level(attribute.id) ?? 0
            let word = Vocabulary.level(value, of: attribute).map { VocabularyLabels.levelText($0, of: attribute.id) } ?? ""
            LabeledContent {
                HStack(spacing: 8) {
                    Text(verbatim: word)
                    LevelBar(value: value)
                }
            } label: {
                Text(verbatim: VocabularyLabels.text(.structure, attribute.id))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: VocabularyLabels.text(.structure, attribute.id)))
            .accessibilityValue(Text(verbatim: word))
        }
        if !compact {
            if let low = profile.servingMin, let high = profile.servingMax {
                LabeledContent {
                    Text(verbatim: low == high ? "\(low) °C" : "\(low)–\(high) °C").monospacedDigit()
                } label: {
                    Label("Temperature", systemImage: "thermometer.medium")
                }
            }
            if let minutes = profile.decantMinutes {
                LabeledContent {
                    if minutes > 0 { Text("\(minutes) min") } else { Text("Not needed") }
                } label: {
                    Label("Decanting", systemImage: "drop")
                }
            }
            let dishes = (profile.pairings ?? []).compactMap(DishCategory.init(rawValue:))
            if !dishes.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Goes with", systemImage: "fork.knife")
                    FlowLayout(spacing: 8) {
                        ForEach(dishes, id: \.self) { dish in
                            TagCapsule(Text(dish.title))
                        }
                    }
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .combine)
            }
            if let text = profileText {
                Text(text)
                    .font(.callout)
                    .accessibilityIdentifier("expected.text")
            }
        }
    }

    /// The AI's text, or the bundled note in the app's language.
    private var profileText: String? {
        if let text = profile.text { return text }
        guard let id = profile.bundledID else { return nil }
        return ExpectedProfiles.note(id: id, language: AIService.language)
    }
}

/// Where a profile comes from, in words: bundled, by the AI, or from the web.
struct ExpectedSourceLabel: View {
    let profile: ExpectedProfile

    var body: some View {
        Group {
            switch profile.source {
            case ExpectedProfile.bundled:
                Label("Typical profile, bundled with Encave", systemImage: "book.closed")
            case ExpectedProfile.web:
                let modelName = profile.model ?? "", dayLabel = day
                Label("From the web, by \(modelName) · \(dayLabel)", systemImage: "globe")
            default:
                let modelName = profile.model ?? "", dayLabel = day
                Label("Estimated by \(modelName) · \(dayLabel)", systemImage: "sparkles")
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .accessibilityIdentifier("expected.source")
    }

    private var day: String { profile.date.map(AICompletedText.day) ?? "" }
}

/// A word in a capsule, not a button.
struct TagCapsule: View {
    let text: Text

    init(_ text: Text) { self.text = text }

    var body: some View {
        text
            .font(.subheadline)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(.fill.tertiary, in: Capsule())
    }
}

/// Five small segments filled up to a level; the word beside it says the same.
struct LevelBar: View {
    let value: Int

    var body: some View {
        HStack(spacing: 3) {
            ForEach(1...5, id: \.self) { level in
                Capsule()
                    .fill(level <= value ? Color.accentColor : Color.secondary.opacity(0.25))
                    .frame(width: 10, height: 6)
            }
        }
        .accessibilityHidden(true)
    }
}

/// After a tasting: the aromas found compared with those expected.
struct AromaComparisonView: View {
    let wineName: String
    let comparison: AromaComparison
    let done: () -> Void

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verbatim: wineName)
                        .font(.headline)
                        .fontDesign(.serif)
                    let found = comparison.matched.count, expected = comparison.expectedCount
                    Text("You found \(found) of \(expected) expected aromas")
                        .font(.title3.weight(.semibold))
                        .accessibilityIdentifier("comparison.summary")
                }
                .padding(.vertical, 4)
            }
            if !comparison.matched.isEmpty {
                Section("Found") {
                    ForEach(comparison.matched, id: \.self) { aroma in
                        Label(VocabularyLabels.text(.aroma, aroma), systemImage: "checkmark.circle")
                    }
                }
            }
            if !comparison.missed.isEmpty {
                Section("Not found this time") {
                    ForEach(comparison.missed, id: \.self) { aroma in
                        Label(VocabularyLabels.text(.aroma, aroma), systemImage: "circle")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if !comparison.extra.isEmpty {
                Section {
                    ForEach(comparison.extra, id: \.self) { aroma in
                        Label(VocabularyLabels.text(.aroma, aroma), systemImage: "plus.circle")
                    }
                } header: {
                    Text("Also found")
                } footer: {
                    Text("Aromas you found that were not expected: every bottle and every palate is different.")
                }
            }
        }
        .navigationTitle("Compared with What to Expect")
        .inlineTitle()
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done", action: done)
                    .accessibilityIdentifier("comparison.done")
            }
        }
    }
}

/// "Complete from the Web" and the pages the AI cited, when a connection allows web
/// search or the wine has pages from an earlier completion.
struct WebCompletionSection: View {
    @Environment(CellarModel.self) private var model
    let wine: Wine
    @State private var running = false
    @State private var problem: String?
    private let available = WebCompletionSettings.isAvailable

    var body: some View {
        let sources = (wine.webSources ?? []).filter(\.isWebAddress)
        if available || !sources.isEmpty {
            Section {
                if available {
                    Button {
                        run()
                    } label: {
                        if running {
                            Label("Searching the web…", systemImage: "globe")
                        } else {
                            Label("Complete from the Web", systemImage: "globe")
                        }
                    }
                    .disabled(running)
                    .accessibilityIdentifier("action.wine.completeFromWeb")
                }
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
                ForEach(sources, id: \.url) { source in
                    if let url = URL(string: source.url) {
                        Link(destination: url) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: source.title ?? url.host() ?? source.url)
                                    .lineLimit(2)
                                Text(verbatim: url.host() ?? source.url)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityHint(Text("Opens the page in the browser."))
                    }
                }
            } header: {
                Text("From the web")
            } footer: {
                if available {
                    Text("Sends only the producer, name, appellation, vintage, colour and grapes to the AI model allowed to search the web, which looks the wine up and lists the pages it used. Fills only empty details and what to expect, with Undo.")
                } else {
                    Text("The pages the AI used when it last completed this wine.")
                }
            }
        }
    }

    private func run() {
        guard !running else { return }
        running = true
        problem = nil
        Task {
            problem = await model.completeFromWeb(wine.id)
            running = false
        }
    }
}
