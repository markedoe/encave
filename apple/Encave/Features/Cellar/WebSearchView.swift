import CaveKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// "Search Online": shows the search terms, sends them only when the user taps Search,
/// lists the pages found with links to the browser and, with Apple Intelligence, proposes
/// details read from the results on this device. Nothing is applied without the user's choice.
struct WebSearchView: View {
    @Environment(\.dismiss) private var dismiss
    /// The wine's details now, to compare with what the results say.
    let current: WineDetails
    let vintage: Int?
    /// Fields that can be suggested here (no drinking window for a discovery).
    var fields: Set<WineDetailField> = Set(WineDetailField.allCases)
    /// Applies the accepted fields of the proposed details.
    let onApply: (WineDetails, Set<WineDetailField>) -> Void

    @State private var query: String
    @State private var settings = WebSearchSettings.load()
    @State private var results: [WebSearchResult] = []
    @State private var searchedTerms: String?
    @State private var searching = false
    @State private var problem: String?
    @State private var suggesting = false
    @State private var proposed: WineDetails?
    @State private var suggestions: [WineDetailSuggestion] = []
    @State private var accepted: Set<WineDetailField> = []
    @State private var suggestionProblem: String?

    init(terms: String, current: WineDetails, vintage: Int?, fields: Set<WineDetailField> = Set(WineDetailField.allCases),
         onApply: @escaping (WineDetails, Set<WineDetailField>) -> Void) {
        _query = State(initialValue: terms)
        self.current = current
        self.vintage = vintage
        self.fields = fields
        self.onApply = onApply
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Search terms", text: $query)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit(search)
                        .accessibilityIdentifier("webSearch.query")
                    Button(action: search) {
                        if searching {
                            HStack { ProgressView(); Text("Searching…") }
                        } else {
                            Label("Search", systemImage: "magnifyingglass")
                        }
                    }
                    .disabled(searching || trimmedQuery.isEmpty || !settings.isEnabled)
                } header: {
                    Text("Search terms")
                } footer: {
                    let serviceName = settings.service.name
                    Text("Only these words are sent, to \(serviceName), when you tap Search. Change them if you like. Nothing else about this wine or your cellar is sent.")
                }

                if let problem {
                    Section {
                        Label(problem, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.secondary)
                    }
                }

                if !results.isEmpty, DetailSuggester.isAvailable {
                    suggestionSections
                }

                if searchedTerms != nil, results.isEmpty, problem == nil, !searching {
                    Section {
                        Label("No pages found. Try fewer words.", systemImage: "magnifyingglass")
                            .foregroundStyle(.secondary)
                    }
                }

                if !results.isEmpty {
                    Section {
                        ForEach(results) { result in
                            WebResultRow(result: result)
                        }
                    } header: {
                        Text("Results")
                    } footer: {
                        Text("Links open in your browser.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Search Online")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .macSheetFrame(width: 520, height: 640)
    }

    @ViewBuilder
    private var suggestionSections: some View {
        Section {
            Button(action: suggest) {
                if suggesting {
                    HStack { ProgressView(); Text("Reading the results…") }
                } else {
                    Label("Suggest Details", systemImage: "sparkles")
                }
            }
            .disabled(suggesting)
            if let suggestionProblem {
                Label(suggestionProblem, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.secondary)
            }
        } footer: {
            Text("Apple Intelligence reads these results on this device and proposes details. The results are not sent anywhere else. Check each suggestion: web pages can be wrong.")
        }

        if proposed != nil {
            Section {
                if suggestions.isEmpty {
                    Text("Nothing new in these results.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(suggestions) { suggestion in
                        Toggle(isOn: acceptBinding(suggestion.field)) {
                            SuggestionRow(suggestion: suggestion)
                        }
                    }
                    Button {
                        if let proposed { onApply(proposed, accepted) }
                        dismiss()
                    } label: {
                        Label("Use Selected Details", systemImage: "checkmark.circle")
                    }
                    .disabled(accepted.isEmpty)
                }
            } header: {
                Text("Suggestions")
            } footer: {
                if !suggestions.isEmpty {
                    Text("Select the details to use. The others stay as they are.")
                }
            }
        }
    }

    private func acceptBinding(_ field: WineDetailField) -> Binding<Bool> {
        Binding(
            get: { accepted.contains(field) },
            set: { on in if on { accepted.insert(field) } else { accepted.remove(field) } }
        )
    }

    private func search() {
        let terms = trimmedQuery
        guard !terms.isEmpty, !searching, settings.isEnabled else { return }
        searching = true
        problem = nil
        proposed = nil
        suggestions = []
        accepted = []
        suggestionProblem = nil
        let settings = settings
        Task {
            defer { searching = false }
            do {
                results = try await WebSearchClient.search(terms, settings: settings)
            } catch {
                results = []
                problem = WebSearchClient.message(for: error)
            }
            searchedTerms = terms
        }
    }

    private func suggest() {
        guard !suggesting, !results.isEmpty else { return }
        suggesting = true
        suggestionProblem = nil
        let terms = searchedTerms ?? trimmedQuery
        let results = results
        let year = CellarYear.current
        Task {
            defer { suggesting = false }
            do {
                let raw = try await DetailSuggester.suggest(terms: terms, results: results)
                var details = WebSearchSuggestions.cleaned(raw, vintage: vintage, currentYear: year)
                // Fields that cannot be used here keep their current value, so they are not suggested.
                for field in WineDetailField.allCases where !fields.contains(field) {
                    details.take(field, from: current)
                }
                proposed = details
                suggestions = WebSearchSuggestions.suggestions(current: current, proposed: details)
                accepted = []
            } catch CellarAssistant.Failure.refused {
                suggestionProblem = String(localized: "These results cannot be read on this device. Try other search terms.")
            } catch CellarAssistant.Failure.tooLarge {
                suggestionProblem = String(localized: "These results are too long for the model on this device.")
            } catch CellarAssistant.Failure.unavailable {
                suggestionProblem = String(localized: "Apple Intelligence is not available on this device right now.")
            } catch {
                suggestionProblem = String(localized: "No suggestions this time. Try again.")
            }
        }
    }
}

/// A page found: title, snippet and site, opening in the browser.
struct WebResultRow: View {
    @Environment(\.openURL) private var openURL
    let result: WebSearchResult

    var body: some View {
        Link(destination: result.url) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: result.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                if !result.snippet.isEmpty {
                    Text(verbatim: result.snippet)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .lineLimit(5)
                }
                Label {
                    Text(verbatim: result.domain)
                } icon: {
                    Image(systemName: "safari")
                }
                .font(.footnote)
                .foregroundStyle(Color.accentColor)
            }
            .padding(.vertical, 2)
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Opens the page in your browser."))
        .contextMenu {
            Button("Open in Browser", systemImage: "safari") { openURL(result.url) }
            ShareLink(item: result.url)
        }
    }
}

/// One proposed detail next to the current value.
private struct SuggestionRow: View {
    let suggestion: WineDetailSuggestion

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(suggestion.field.title)
                .font(.headline)
            let proposedLabel = suggestion.proposed
            Text("Found: \(proposedLabel)")
            if suggestion.current.isEmpty {
                Text("Now: not set")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                let currentLabel = suggestion.current
                Text("Now: \(currentLabel)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

extension WineDetailField {
    var title: LocalizedStringKey {
        switch self {
        case .producer: "Producer"
        case .region: "Region"
        case .appellation: "Appellation"
        case .country: "Country"
        case .grapes: "Grapes"
        case .drinkingWindow: "Drinking window"
        }
    }
}

extension WineDraft {
    /// The details that a search can complete, from the form.
    var webSearchDetails: WineDetails {
        return WineDetails(
            producer: producer, region: region, appellation: appellation, country: country,
            grapes: grapes.split(whereSeparator: { $0 == "," || $0 == ";" })
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
            drinkFrom: window?.from, drinkUntil: window?.until
        )
    }

    var webSearchTerms: String {
        WebSearchQuery.terms(producer: producer, name: name, appellation: appellation, vintage: vintage,
                             wineWord: String(localized: "wine", comment: "Added to web search terms"))
    }

    /// Fills the accepted fields in the form.
    mutating func apply(_ details: WineDetails, fields: Set<WineDetailField>) {
        for field in fields {
            switch field {
            case .producer: producer = details.producer
            case .region: region = details.region
            case .appellation: appellation = details.appellation
            case .country: country = details.country
            case .grapes: grapes = details.grapes.joined(separator: ", ")
            case .drinkingWindow:
                if let from = details.drinkFrom, let until = details.drinkUntil {
                    windowIsAutomatic = false
                    drinkFrom = from
                    drinkUntil = max(from, until)
                }
            }
        }
    }
}

extension View {
    /// Search Online for the wine in a form, presented from the screen rather than from a
    /// form row (a sheet attached to a row can close when the form redraws).
    func webSearchSheet(isPresented: Binding<Bool>, draft: Binding<WineDraft>, includesWindow: Bool = true) -> some View {
        sheet(isPresented: isPresented) {
            let value = draft.wrappedValue
            WebSearchView(
                terms: value.webSearchTerms, current: value.webSearchDetails, vintage: value.vintage,
                fields: includesWindow ? Set(WineDetailField.allCases) : Set(WineDetailField.allCases).subtracting([.drinkingWindow])
            ) { details, fields in
                withAnimation { draft.wrappedValue.apply(details, fields: fields) }
            }
        }
    }
}

// MARK: Suggest Details (Apple Intelligence)

/// Reads search results with the language model built into the device (Apple Intelligence)
/// and proposes details. The results never go to another AI service.
enum DetailSuggester {
    static var isAvailable: Bool { CellarAssistant.isAvailable }

    @MainActor
    static func suggest(terms: String, results: [WebSearchResult]) async throws -> WineDetails {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            return try await DetailGenerator.suggest(terms: terms, results: results)
        }
        #endif
        throw CellarAssistant.Failure.unavailable
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, macOS 26.0, *)
@Generable
private struct SuggestedDetails {
    @Guide(description: "The estate, domaine or house that made the wine, or an empty string if the results do not say.")
    var producer: String
    @Guide(description: "The wine region, such as Burgundy or Tuscany, or an empty string.")
    var region: String
    @Guide(description: "The appellation or protected name of origin, or an empty string.")
    var appellation: String
    @Guide(description: "The country, in English, or an empty string.")
    var country: String
    @Guide(description: "The grape varieties of this wine. Empty if the results do not say.")
    var grapes: [String]
    @Guide(description: "The first year to drink this wine, or 0 if the results do not say.")
    var drinkFrom: Int
    @Guide(description: "The last year to drink this wine, or 0 if the results do not say.")
    var drinkUntil: Int
}

@available(iOS 26.0, macOS 26.0, *)
private enum DetailGenerator {
    /// The model's context is small: with long results, fewer are tried before giving up.
    @MainActor
    static func suggest(terms: String, results: [WebSearchResult]) async throws -> WineDetails {
        guard case .available = SystemLanguageModel.default.availability else { throw CellarAssistant.Failure.unavailable }
        for limit in [8, 5, 3] {
            do {
                return try await suggest(terms: terms, results: results, limit: limit)
            } catch CellarAssistant.Failure.tooLarge where limit > 3 {
                continue
            }
        }
        throw CellarAssistant.Failure.tooLarge
    }

    @MainActor
    private static func suggest(terms: String, results: [WebSearchResult], limit: Int) async throws -> WineDetails {
        let prompt = WebSearchSuggestions.prompt(terms: terms, results: results, limit: limit)
        let session = LanguageModelSession()
        do {
            let reply = try await session.respond(to: prompt, generating: SuggestedDetails.self).content
            return WineDetails(
                producer: reply.producer, region: reply.region, appellation: reply.appellation, country: reply.country,
                grapes: reply.grapes,
                drinkFrom: reply.drinkFrom > 0 ? reply.drinkFrom : nil,
                drinkUntil: reply.drinkUntil > 0 ? reply.drinkUntil : nil
            )
        } catch let error as LanguageModelSession.GenerationError {
            switch error {
            case .guardrailViolation: throw CellarAssistant.Failure.refused
            case .exceededContextWindowSize: throw CellarAssistant.Failure.tooLarge
            default: throw CellarAssistant.Failure.other
            }
        }
    }
}
#endif
