import CaveKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// "Find Current Price": searches the web with the wine's price terms (shown and editable,
/// sent only on Search), lists every price found with its site, suggests the median in the
/// cellar currency and keeps the price only when the user saves it.
struct PriceSearchView: View {
    @Environment(\.dismiss) private var dismiss
    let sizeML: Int
    /// Saves the accepted price per bottle and its currency.
    let onSave: (Decimal, String) -> Void

    @State private var query: String
    @State private var settings = WebSearchSettings.load()
    @State private var results: [WebSearchResult] = []
    @State private var prices: [FoundPrice] = []
    @State private var searchedTerms: String?
    @State private var searching = false
    @State private var problem: String?
    @State private var price: Decimal?
    @State private var currency: String
    @State private var estimating = false
    @State private var estimateNote: String?

    init(terms: String, sizeML: Int, currentPrice: Decimal?, currency: String, onSave: @escaping (Decimal, String) -> Void) {
        _query = State(initialValue: terms)
        _price = State(initialValue: currentPrice)
        _currency = State(initialValue: currency)
        self.sizeML = sizeML
        self.onSave = onSave
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var pricesInCurrency: Int { prices.filter { $0.currency == currency }.count }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Search terms", text: $query)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit(search)
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

                Section {
                    TextField("Price per bottle", value: $price, format: .currency(code: currency))
                        .numericKeyboard()
                        .font(.title3.monospacedDigit())
                        .accessibilityIdentifier("price.value")
                    if searchedTerms != nil {
                        if pricesInCurrency == 1 {
                            Text("The only price found in \(currencyLabel). Change it if you like.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else if pricesInCurrency > 1 {
                            let priceCount = pricesInCurrency
                            Text("The middle value of \(priceCount) prices found in \(currencyLabel). Change it if you like.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else if !prices.isEmpty {
                            Text("No price in \(currencyLabel) was found. Tap a price below to use it with its currency.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if !prices.isEmpty, DetailSuggester.isAvailable {
                        Button(action: estimate) {
                            if estimating {
                                HStack { ProgressView(); Text("Reading the prices…") }
                            } else {
                                Label("Estimate on This Device", systemImage: "sparkles")
                            }
                        }
                        .disabled(estimating)
                        if let estimateNote {
                            Text(verbatim: estimateNote)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button {
                        if let price, price > 0 { onSave(price, currency) }
                        dismiss()
                    } label: {
                        Label("Save Price", systemImage: "checkmark.circle")
                    }
                    .disabled((price ?? 0) <= 0)
                    .accessibilityIdentifier("price.save")
                } header: {
                    Text("Price today")
                } footer: {
                    Text("Kept with the wine and used for the estimated value of your cellar in Insights. Web prices vary: check them on the sites.")
                }

                if searchedTerms != nil, !searching, problem == nil {
                    Section {
                        if prices.isEmpty {
                            Text("No prices in these results. Try other words, or open a result below.")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(prices) { found in
                            FoundPriceRow(found: found, isSelected: price == found.amount && currency == found.currency) {
                                price = found.amount
                                currency = found.currency
                            }
                        }
                    } header: {
                        Text("Prices found")
                    } footer: {
                        Text("Prices for several bottles, delivery or another bottle size are left out. Tap a price to use it.")
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
            .navigationTitle("Find Current Price")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .macSheetFrame(width: 520, height: 680)
    }

    /// "euros (EUR)" in the user's language.
    private var currencyLabel: String {
        Locale.current.localizedString(forCurrencyCode: currency).map { "\($0) (\(currency))" } ?? currency
    }

    private func search() {
        let terms = trimmedQuery
        guard !terms.isEmpty, !searching, settings.isEnabled else { return }
        searching = true
        problem = nil
        estimateNote = nil
        let settings = settings
        let sizeML = sizeML
        Task {
            defer { searching = false }
            do {
                let found = try await WebSearchClient.search(terms, settings: settings, count: 20)
                results = found
                prices = PriceExtractor.prices(in: found, sizeML: sizeML)
                if let median = PriceExtractor.suggested(prices, currency: currency) { price = median }
            } catch {
                results = []
                prices = []
                problem = WebSearchClient.message(for: error)
            }
            searchedTerms = terms
        }
    }

    private func estimate() {
        guard !estimating, !prices.isEmpty else { return }
        estimating = true
        estimateNote = nil
        let prompt = PriceSuggestions.prompt(terms: searchedTerms ?? trimmedQuery, prices: prices, currency: currency,
                                             sizeML: sizeML)
        Task {
            defer { estimating = false }
            do {
                if let value = try await PriceEstimator.estimate(prompt: prompt) {
                    price = value
                    let valueLabel = value.formatted(.currency(code: currency))
                    estimateNote = String(localized: "Apple Intelligence suggests \(valueLabel) from these prices, on this device.")
                } else {
                    estimateNote = String(localized: "Apple Intelligence found no price that fits. The middle price is kept.")
                }
            } catch {
                estimateNote = String(localized: "No estimate this time. The middle price is kept.")
            }
        }
    }
}

/// A price found: the amount, the site and the words around it. Tap to use it.
private struct FoundPriceRow: View {
    @Environment(\.openURL) private var openURL
    let found: FoundPrice
    let isSelected: Bool
    let onUse: () -> Void

    var body: some View {
        Button(action: onUse) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: found.amount.formatted(.currency(code: found.currency)))
                        .font(.headline.monospacedDigit())
                        .foregroundStyle(.primary)
                    Text(verbatim: found.excerpt)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(verbatim: found.domain)
                        .font(.footnote)
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityHint(Text("Uses this price."))
        .accessibilityAction(named: Text("Open in Browser")) { openURL(found.url) }
        .contextMenu {
            Button("Open in Browser", systemImage: "safari") { openURL(found.url) }
        }
    }
}

// MARK: Estimate on This Device (Apple Intelligence)

/// Reads the prices found with the language model built into the device. Nothing is sent.
enum PriceEstimator {
    /// Nil when the model finds no price that fits.
    @MainActor
    static func estimate(prompt: String) async throws -> Decimal? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            return try await PriceGenerator.estimate(prompt: prompt)
        }
        #endif
        throw CellarAssistant.Failure.unavailable
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, macOS 26.0, *)
@Generable
private struct PriceEstimate {
    @Guide(description: "The usual price of one bottle today, or 0 if none of the prices fits.")
    var price: Double
}

@available(iOS 26.0, macOS 26.0, *)
private enum PriceGenerator {
    @MainActor
    static func estimate(prompt: String) async throws -> Decimal? {
        guard case .available = SystemLanguageModel.default.availability else { throw CellarAssistant.Failure.unavailable }
        let session = LanguageModelSession()
        do {
            let reply = try await session.respond(to: prompt, generating: PriceEstimate.self).content
            guard reply.price.isFinite, reply.price >= 1, reply.price <= 20_000 else { return nil }
            var value = Decimal(reply.price)
            var rounded = Decimal()
            NSDecimalRound(&rounded, &value, 2, .plain)
            return rounded
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
