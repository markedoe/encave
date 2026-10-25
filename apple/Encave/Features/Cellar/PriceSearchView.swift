import CaveKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// "Find Current Price": searches the web with the wine's price terms (shown and editable,
/// sent only on Search), then opens the first result pages from this device to read the
/// price on them (`PagePrices`), lists every price found with its site, suggests the median
/// in the cellar currency and keeps the price only when the user saves it. Prices seen for
/// another bottle size are listed apart, never suggested; using one can change the size of
/// the wine's bottles.
struct PriceSearchView: View {
    @Environment(\.dismiss) private var dismiss
    let sizeML: Int
    /// The wine's name, to find its price on a page that lists several.
    let wineName: String?
    /// With the name's words, when the whole name is not on a page.
    let producer: String?
    /// Prices named for another vintage are left out.
    let vintage: Int?
    /// Changes the size of the wine's bottles of `sizeML` to the one given and saves the price.
    let onChangeSize: ((Int, Decimal, String) -> Void)?
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
    /// The search, then the result pages being read for their price; cancelled by a new
    /// search or on Close.
    @State private var searchTask: Task<Void, Never>?
    @State private var pageTask: Task<Void, Never>?
    @FocusState private var priceFocused: Bool
    @State private var readingPages = false
    /// The middle price last put in the field; new prices move it until the user sets one.
    @State private var suggestedPrice: Decimal?
    @State private var userSetPrice = false
    /// A price of another size tapped: asks whether to change the bottles' size.
    @State private var otherSizePrice: FoundPrice?

    init(terms: String, sizeML: Int, wineName: String? = nil, producer: String? = nil, vintage: Int? = nil,
         currentPrice: Decimal?, currency: String, onChangeSize: ((Int, Decimal, String) -> Void)? = nil,
         onSave: @escaping (Decimal, String) -> Void) {
        _query = State(initialValue: terms)
        _price = State(initialValue: currentPrice)
        _currency = State(initialValue: currency)
        self.sizeML = sizeML
        self.wineName = wineName
        self.producer = producer
        self.vintage = vintage
        self.onChangeSize = onChangeSize
        self.onSave = onSave
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    /// Prices for the size searched, and those seen for another size.
    private var sizePrices: [FoundPrice] { prices.filter { !$0.isOtherSize } }
    private var otherSizePrices: [FoundPrice] { prices.filter(\.isOtherSize) }
    private var pricesInCurrency: Int { sizePrices.filter { $0.currency == currency }.count }

    var body: some View {
        NavigationStack {
            Form {
                // Web search off: only the price to type, nothing to search or send.
                if settings.isEnabled {
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
                        Text("Only these words are sent, to \(serviceName), when you tap Search. Change them if you like. The first pages found are then opened from this device to read their price: those sites see the page asked for, your IP address, the app's name and version, the system version and your preferred languages. Nothing else about this wine or your cellar is sent.")
                    }

                    if let problem {
                        Section {
                            Label(problem, systemImage: "exclamationmark.triangle")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    TextField("Price per bottle", value: $price, format: .currency(code: currency))
                        // While the user is in the field, prices still arriving do not replace it.
                        .focused($priceFocused)
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
                        } else if !sizePrices.isEmpty {
                            Text("No price in \(currencyLabel) was found. Tap a price below to use it with its currency.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if !sizePrices.isEmpty, DetailSuggester.isAvailable {
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
                    if settings.isEnabled {
                        Text("Kept with the wine and used for the estimated value of your cellar in Insights. Web prices vary: check them on the sites.")
                    } else {
                        Text("Kept with the wine and used for the estimated value of your cellar in Insights.")
                    }
                }

                if settings.isEnabled, searchedTerms != nil, !searching, problem == nil {
                    Section {
                        if readingPages {
                            HStack(spacing: 12) {
                                ProgressView()
                                    .controlSize(.small)
                                Text("Reading prices on the pages…")
                            }
                            .foregroundStyle(.secondary)
                            .accessibilityElement(children: .combine)
                        } else if sizePrices.isEmpty, otherSizePrices.isEmpty {
                            Text("No prices in these results. Try other words, or open a result below.")
                                .foregroundStyle(.secondary)
                        } else if sizePrices.isEmpty {
                            let sizeLabel = BottleSize.volume(for: sizeML)
                            Text("No price for bottles of \(sizeLabel) in these results. Prices for other sizes are below.")
                                .foregroundStyle(.secondary)
                        }
                        ForEach(sizePrices) { found in
                            FoundPriceRow(found: found, isSelected: price == found.amount && currency == found.currency) {
                                price = found.amount
                                currency = found.currency
                            }
                        }
                    } header: {
                        Text("Prices found")
                    } footer: {
                        Text("Prices for several bottles or delivery are left out, and those for another bottle size are listed apart. The first pages found are read on this device. Tap a price to use it.")
                    }

                    if !otherSizePrices.isEmpty {
                        Section {
                            ForEach(otherSizePrices) { found in
                                FoundPriceRow(found: found, isSelected: false) { otherSizePrice = found }
                            }
                        } header: {
                            Text("Other sizes")
                        } footer: {
                            let sizeLabel = BottleSize.volume(for: sizeML)
                            Text("Seen for another size than your bottles of \(sizeLabel), so never used for the middle value. Tap one to use it.")
                        }
                    }
                }

                if settings.isEnabled, !results.isEmpty {
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
            .navigationTitle(settings.isEnabled ? Text("Find Current Price") : Text("Price today"))
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
            }
        }
        .macSheetFrame(width: 520, height: 680)
        .confirmationDialog(otherSizeQuestion, isPresented: Binding(get: { otherSizePrice != nil },
                                                                     set: { if !$0 { otherSizePrice = nil } }),
                            titleVisibility: .visible, presenting: otherSizePrice) { found in
            if let onChangeSize, let size = found.sizeML {
                Button("Change") {
                    onChangeSize(size, found.amount, found.currency)
                    dismiss()
                }
            }
            Button("Use Without Changing") {
                price = found.amount
                currency = found.currency
            }
            Button("Cancel", role: .cancel) {}
        }
        .onChange(of: price) { _, new in
            if new != suggestedPrice { userSetPrice = true }
        }
        .onChange(of: priceFocused) { _, focused in
            if focused { userSetPrice = true }
        }
        .onDisappear {
            searchTask?.cancel()
            searchTask = nil
            stopReadingPages()
        }
    }

    /// "Your bottles of this wine are 75 cl. Change them to 70 cl and use this price?"
    private var otherSizeQuestion: Text {
        let currentLabel = BottleSize.volume(for: sizeML)
        guard let size = otherSizePrice?.sizeML else { return Text(verbatim: "") }
        let otherLabel = BottleSize.volume(for: size)
        return Text("Your bottles of this wine are \(currentLabel). Change them to \(otherLabel) and use this price?")
    }

    /// "euros (EUR)" in the user's language.
    private var currencyLabel: String {
        Locale.current.localizedString(forCurrencyCode: currency).map { "\($0) (\(currency))" } ?? currency
    }

    private func search() {
        let terms = trimmedQuery
        guard !terms.isEmpty, !searching, settings.isEnabled else { return }
        searchTask?.cancel()
        stopReadingPages()
        searching = true
        problem = nil
        estimateNote = nil
        let settings = settings
        let sizeML = sizeML
        searchTask = Task {
            defer { searching = false }
            do {
                let found = try await WebSearchClient.search(terms, settings: settings, count: 20)
                // Closed, or another search started: no page is opened.
                guard !Task.isCancelled else { return }
                results = found
                prices = PriceExtractor.prices(in: found, sizeML: sizeML)
                userSetPrice = false
                suggestMiddlePrice()
                readPages(of: found)
            } catch {
                results = []
                prices = []
                problem = WebSearchClient.message(for: error)
            }
            searchedTerms = terms
        }
    }

    /// Puts the middle price in the field, unless the user has set one since the search.
    private func suggestMiddlePrice() {
        guard !userSetPrice, !priceFocused, let median = PriceExtractor.suggested(prices, currency: currency) else { return }
        suggestedPrice = median
        price = median
    }

    /// Opens the first result pages from this device, a few at a time, and adds the prices
    /// read on each as soon as it is read.
    private func readPages(of results: [WebSearchResult]) {
        let pages = PagePrices.pages(of: results)
        guard !pages.isEmpty else { return }
        readingPages = true
        let sizeML = sizeML
        let wineName = wineName, producer = producer, vintage = vintage
        pageTask = Task {
            // A session of its own for each search: no connection is kept for the next one.
            let session = WebSearchClient.makePageSession()
            defer { session.invalidateAndCancel() }
            await withTaskGroup(of: [FoundPrice].self) { group in
                var next = 0
                while next < pages.count, next < PagePrices.parallelPages {
                    let page = pages[next]
                    group.addTask {
                        await WebSearchClient.pagePrices(of: page, session: session, sizeML: sizeML, wineName: wineName,
                                                         producer: producer, vintage: vintage)
                    }
                    next += 1
                }
                while let found = await group.next() {
                    if Task.isCancelled { break }
                    if !found.isEmpty {
                        prices = PagePrices.merging(found, into: prices)
                        suggestMiddlePrice()
                    }
                    if next < pages.count {
                        let page = pages[next]
                        group.addTask {
                            await WebSearchClient.pagePrices(of: page, session: session, sizeML: sizeML, wineName: wineName,
                                                             producer: producer, vintage: vintage)
                        }
                        next += 1
                    }
                }
                group.cancelAll()
            }
            if !Task.isCancelled { readingPages = false }
        }
    }

    private func stopReadingPages() {
        pageTask?.cancel()
        pageTask = nil
        readingPages = false
    }

    private func estimate() {
        guard !estimating, !sizePrices.isEmpty else { return }
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

    private var amount: String { found.amount.formatted(.currency(code: found.currency)) }

    var body: some View {
        Button(action: onUse) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    if let size = found.sizeML {
                        // "CHF 13.00 · 70 cl", read "CHF 13.00, 70 centilitres".
                        Text(verbatim: "\(amount) · \(BottleSize.volume(for: size))")
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(.primary)
                            .accessibilityLabel(Text(verbatim: "\(amount), \(BottleSize.spokenLabel(for: size))"))
                    } else {
                        Text(verbatim: amount)
                            .font(.headline.monospacedDigit())
                            .foregroundStyle(.primary)
                    }
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
        .accessibilityHint(found.isOtherSize ? Text("Asks whether to change the size of your bottles.") : Text("Uses this price."))
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
