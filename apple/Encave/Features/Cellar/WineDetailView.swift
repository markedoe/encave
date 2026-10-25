import CaveKit
import SwiftUI

struct WineDetailView: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @Environment(\.dismiss) private var dismiss
    /// Pushed on a stack (from Tonight, a rack, a list of wines), rather than its root.
    @Environment(\.isPresented) private var isPresented
    let wineID: UUID
    @State private var searchingOnline = false
    @State private var findingPrice = false
    /// "Search the Web" on one detail.
    @State private var searchingDetail: WebSearchTerms?
    @State private var documents = DocumentActions()
    private let webSearchEnabled = WebSearchSettings.load().isEnabled

    var body: some View {
        if let wine = model.cellar.wines[wineID] {
            let bottles = model.cellar.bottles(of: wineID)
            let tastings = model.cellar.tastings(of: wineID)
            let maturity = Maturity.of(wine, year: model.currentYear)
            List {
                Section {
                    WineHeader(wine: wine, count: bottles.count)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }

                Section {
                    ActionButtonRow {
                        Button {
                            if let bottle = bottles.bottleToOpen { router?.sheet = .drink(bottleID: bottle.id) }
                        } label: {
                            ActionLabel("Drink", systemImage: "wineglass").frame(maxWidth: .infinity)
                        }
                        .prominentButtonStyle()
                        .accessibilityIdentifier("action.wine.drink")
                        .disabled(bottles.isEmpty)
                        Button {
                            router?.sheet = .addBottle(AddBottleContext(wine: wine))
                        } label: {
                            ActionLabel("Add", systemImage: "plus").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("action.wine.add")
                    }
                    .controlSize(.large)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }

                // Where the bottles are, and what to do with each, right under Drink and Add.
                let orders = model.cellar.bottlesOnOrder.filter { $0.wineID == wineID }
                    .sorted { ($0.expectedAt ?? .distantFuture) < ($1.expectedAt ?? .distantFuture) }
                if !bottles.isEmpty || orders.isEmpty {
                    Section {
                        ForEach(model.cellar.inPlaceOrder(bottles)) { bottle in
                            BottleRow(bottle: bottle)
                                // Onto a storage in the sidebar: to its first free position.
                                .sidebarDraggable(BottleDrag(bottleID: bottle.id, name: model.cellar.wines[bottle.wineID]?.displayName ?? ""))
                        }
                    } header: {
                        Text("\(bottles.count) bottles in the cellar")
                    }
                }
                if !orders.isEmpty {
                    Section {
                        ForEach(orders) { bottle in
                            OrderedBottleRow(bottle: bottle)
                        }
                        Button("Mark All Received", systemImage: "shippingbox") {
                            model.receive(orders.map(\.id))
                        }
                        .accessibilityIdentifier("action.order.receive")
                    } header: {
                        Text("\(orders.count) bottles on order")
                    } footer: {
                        Text("Received bottles join the cellar without a position; move them to storage from here.")
                    }
                }

                Section("Drinking window") {
                    EditDetailRow(hint: Text("Edits the drinking window.")) {
                        router?.sheet = .editWine(wineID: wineID, field: .window)
                    } content: {
                        VStack(alignment: .leading, spacing: 0) {
                            MaturityLabel(maturity: maturity)
                                .font(.headline)
                            if let from = wine.drinkFrom, let until = wine.drinkUntil {
                                MaturityGauge(from: from, until: until, year: model.currentYear, maturity: maturity)
                                    .padding(.top, 12)
                                    .padding(.bottom, 6)
                            }
                        }
                    }
                    .accessibilityIdentifier("detail.edit.window")
                    .contextMenu { editMenu(windowText(wine), field: .window) }
                    if wine.drinkFrom != nil, wine.drinkUntil != nil {
                        WindowExplanation(wine: wine)
                    } else {
                        Button("Set a drinking window") { router?.sheet = .editWine(wineID: wineID, field: .window) }
                    }
                }

                if let advice = ServingAdvice.advice(for: wine, year: model.currentYear) {
                    // Worked out from the style and the vintage: a tap edits the style.
                    Section("Serving") {
                        EditDetailRow(hint: Text("Edits the style, which sets the serving advice.")) {
                            router?.sheet = .editWine(wineID: wineID, field: .style)
                        } content: {
                            LabeledContent {
                                Text(verbatim: advice.temperatureRange()).monospacedDigit()
                            } label: {
                                Label("Temperature", systemImage: "thermometer.medium")
                            }
                        }
                        .contextMenu { editMenu(advice.temperatureRange(), field: .style) }
                        EditDetailRow(hint: Text("Edits the style, which sets the serving advice.")) {
                            router?.sheet = .editWine(wineID: wineID, field: .style)
                        } content: {
                            LabeledContent {
                                if let minutes = advice.decantMinutes {
                                    Text("\(minutes) min")
                                } else {
                                    Text("Not needed")
                                }
                            } label: {
                                Label("Decanting", systemImage: "drop")
                            }
                        }
                        .contextMenu { editMenu(nil, field: .style) }
                    }
                }

                if wine.currentPrice != nil || webSearchEnabled {
                    priceSection(wine)
                }

                Section("Details") {
                    // Details other wines can share list them; the others edit the wine.
                    if let color = wine.color {
                        ExploreDetailRow(title: "Style", attribute: .color(color), wineID: wineID, content: {
                            HStack(spacing: 6) {
                                BottleEnd(color: color, size: 16)
                                Text(color.title)
                            }
                        })
                    }
                    exploreRow("Producer", wine.producer.map(WineAttribute.producer))
                    exploreRow("Vintage", wine.vintage.map(WineAttribute.vintage))
                    exploreRow("Appellation", wine.appellation.map(WineAttribute.appellation))
                    exploreRow("Region", wine.region.map(WineAttribute.region))
                    exploreRow("Country", wine.country.map(WineAttribute.country))
                    ForEach(distinctValues(wine.grapes), id: \.self) { grape in
                        exploreRow("Grape", .grape(grape))
                    }
                    ForEach(distinctValues(wine.tags ?? []), id: \.self) { tag in
                        exploreRow("Tag", .tag(tag))
                    }
                    if let notes = wine.notes, !notes.isEmpty {
                        EditDetailRow(hint: Text("Edits the notes.")) {
                            router?.sheet = .editWine(wineID: wineID, field: .notes)
                        } content: {
                            Text(notes)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityIdentifier("detail.edit.notes")
                        .contextMenu { editMenu(notes, field: .notes) }
                    }
                    if let completedAt = wine.aiCompletedAt {
                        Label {
                            AICompletedText(date: completedAt)
                        } icon: {
                            Image(systemName: "sparkles")
                        }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("detail.aiCompleted")
                    }
                    if webSearchEnabled {
                        Button {
                            searchingOnline = true
                        } label: {
                            Label("Search Online", systemImage: "globe")
                        }
                        .accessibilityHint(Text("Shows the search terms before anything is sent."))
                        .accessibilityIdentifier("action.wine.searchOnline")
                    }
                }

                MorePhotosSection(wine: wine)

                WineDocumentsSection(wineID: wineID, actions: documents)

                let discoveries = model.cellar.discoveries(of: wineID)
                if !discoveries.isEmpty {
                    Section("Tasted elsewhere") {
                        ForEach(discoveries) { discovery in
                            Button {
                                router?.sheet = .discovery(discoveryID: discovery.id)
                            } label: {
                                DiscoveryRow(discovery: discovery, showsWine: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !tastings.isEmpty {
                    Section("Tastings") {
                        ForEach(tastings) { bottle in
                            Button {
                                router?.sheet = .editTasting(bottleID: bottle.id)
                            } label: {
                                TastingRow(bottle: bottle, showsWine: false)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .documentActions(documents)
            .navigationTitle(wine.displayName)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .sheet(isPresented: $findingPrice) {
                let sizeML = usualSize(of: bottles)
                PriceSearchView(
                    terms: WebSearchQuery.priceTerms(for: wine, sizeML: sizeML,
                                                     priceWord: String(localized: "price", comment: "Added to price search terms")),
                    sizeML: sizeML, wineName: wine.name, producer: wine.producer, vintage: wine.vintage,
                    currentPrice: wine.currentPrice, currency: wine.currentPriceCurrency ?? model.cellar.settings.currency,
                    onChangeSize: { newSize, price, currency in
                        model.changeSize(ofWine: wineID, from: sizeML, to: newSize, price: price, currency: currency)
                    }
                ) { price, currency in
                    setPrice(price, currency: currency)
                }
            }
            .sheet(isPresented: $searchingOnline) {
                WebSearchView(
                    terms: WebSearchQuery.terms(for: wine, wineWord: String(localized: "wine", comment: "Added to web search terms")),
                    current: WineDetails(wine), vintage: wine.vintage
                ) { details, fields in
                    apply(details, fields: fields)
                }
            }
            // Only the detail's value, shown and editable before anything is sent.
            .sheet(item: $searchingDetail) { search in
                WebSearchView(terms: search.terms, current: WineDetails(wine), vintage: wine.vintage) { details, fields in
                    apply(details, fields: fields)
                }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Edit Wine", systemImage: "pencil") { router?.sheet = .editWine(wineID: wineID) }
                        Button("Change Picture…", systemImage: "photo.on.rectangle") { router?.sheet = .wineImage(wineID: wineID) }
                        Button("Add Bottles", systemImage: "plus") { router?.sheet = .addBottle(AddBottleContext(wine: wine)) }
                        Divider()
                        Button("Delete Wine", systemImage: "trash", role: .destructive) {
                            model.deleteFollowingRule(wines: [wineID], router: router)
                        }
                    } label: {
                        Label("More", systemImage: "ellipsis.circle")
                    }
                }
            }
        } else {
            ContentUnavailableView("Wine not found", systemImage: "questionmark")
                // Deleted while shown: back to where it was opened from. As the root of a
                // stack, the list beside it selects another wine (`replacingDeletedWine`).
                .onAppear { if isPresented { dismiss() } }
        }
    }

    private func apply(_ details: WineDetails, fields: Set<WineDetailField>) {
        model.update { cellar in
            guard var updated = cellar.wines[wineID] else { return }
            updated.apply(details, fields: fields)
            try cellar.updateWine(updated)
        }
    }

    @ViewBuilder
    private func priceSection(_ wine: Wine) -> some View {
        Section {
            if let price = wine.currentPrice {
                let priceText = price.formatted(.currency(code: wine.currentPriceCurrency ?? model.cellar.settings.currency))
                EditDetailRow(hint: Text("Changes the price.")) {
                    findingPrice = true
                } content: {
                    LabeledContent {
                        Text(verbatim: priceText)
                            .monospacedDigit()
                    } label: {
                        Label("Price today", systemImage: "tag")
                    }
                }
                .accessibilityIdentifier("detail.edit.price")
                .contextMenu {
                    DetailMenu(value: priceText, onEdit: { findingPrice = true })
                }
                if let date = wine.currentPriceDate {
                    Text("Set on \(date.formatted(date: .abbreviated, time: .omitted)), for one bottle.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            if webSearchEnabled {
                Button {
                    findingPrice = true
                } label: {
                    Label("Find Current Price", systemImage: "magnifyingglass.circle")
                }
                .accessibilityHint(Text("Shows the search terms before anything is sent."))
                .accessibilityIdentifier("action.wine.findPrice")
            }
            if wine.currentPrice != nil {
                Button("Remove Price", systemImage: "xmark.circle", role: .destructive) {
                    model.removePrice(of: wineID)
                }
            }
        } header: {
            Text("Value")
        } footer: {
            Text("What a bottle sells for today. Counted in the estimated value of your cellar in Insights.")
        }
    }

    /// The size of most of the wine's bottles, 75 cl when there are none.
    private func usualSize(of bottles: [Bottle]) -> Int {
        let counts = Dictionary(grouping: bottles, by: \.sizeML).mapValues(\.count)
        return counts.max { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }?.key ?? 750
    }

    private func setPrice(_ price: Decimal?, currency: String?) {
        model.update { cellar in
            guard var wine = cellar.wines[wineID] else { return }
            wine.currentPrice = price
            wine.currentPriceCurrency = price == nil ? nil : currency
            wine.currentPriceDate = price == nil ? nil : .now
            try cellar.updateWine(wine)
        }
    }

    /// A detail other wines can share, when the wine has it.
    @ViewBuilder
    private func exploreRow(_ title: LocalizedStringKey, _ attribute: WineAttribute?) -> some View {
        if let attribute, !attribute.value.isEmpty {
            ExploreDetailRow(title: title, attribute: attribute, wineID: wineID,
                             onSearchWeb: webSearchEnabled ? { searchingDetail = WebSearchTerms(terms: $0) } : nil)
        }
    }

    /// Grapes or tags once each, as written first, without blanks.
    private func distinctValues(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
    }

    /// Copy and Edit for a detail with nothing to explore (never sent to a web search).
    private func editMenu(_ value: String?, field: WineFormField) -> some View {
        DetailMenu(value: value, onEdit: { router?.sheet = .editWine(wineID: wineID, field: field) })
    }

    /// "2020 – 2030", to copy.
    private func windowText(_ wine: Wine) -> String? {
        guard let from = wine.drinkFrom, let until = wine.drinkUntil else { return nil }
        return "\(from) – \(until)"
    }
}

/// Large header: bottle end, name in serif, vintage and origin.
private struct WineHeader: View {
    let wine: Wine
    let count: Int
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    @AppStorage(StudioPhoto.settingKey) private var studio = true
    @State private var asTaken = false
    @State private var showsPhoto = false

    /// The photo shown large: the label photo as taken, or the picture chosen for the wine.
    private var largePhotoID: String? {
        asTaken ? wine.photoID : wine.shownPhotoID
    }

    var body: some View {
        VStack(spacing: 12) {
            if wine.hasShownPhoto {
                Button {
                    showsPhoto = true
                } label: {
                    Group {
                        if asTaken, let photoID = wine.photoID {
                            WinePhoto(photoID: photoID)
                        } else {
                            WineImage(wine: wine, inset: 18)
                        }
                    }
                    .frame(maxWidth: 360)
                    .frame(height: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
                // VoiceOver reaches it through the header's "Show Photo" action.
                .accessibilityHidden(true)
                .sheet(isPresented: $showsPhoto) {
                    NavigationStack {
                        WinePhoto(photoID: largePhotoID, maxPixelSize: 1600, fits: true)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .accessibilityLabel(Text("Photo"))
                            .toolbar {
                                ToolbarItem(placement: .confirmationAction) {
                                    Button("Done") { showsPhoto = false }
                                }
                            }
                    }
                    .macSheetFrame(width: 640, height: 720)
                }
                .contextMenu {
                    Button("Change Picture…", systemImage: "photo.on.rectangle") { router?.sheet = .wineImage(wineID: wine.id) }
                    if studio, wine.coverPhotoID == nil, PhotoStore.exists(wine.photoID) {
                        Button(asTaken ? LocalizedStringKey("Show Studio Look") : LocalizedStringKey("Show Photo as Taken"),
                               systemImage: asTaken ? "wand.and.stars" : "photo") { asTaken.toggle() }
                    }
                }
            } else {
                BottleEnd(color: wine.color, size: 72)
                    .padding(.top, 8)
            }
            Text(wine.name)
                .font(.largeTitle.weight(.semibold))
                .fontDesign(.serif)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .minimumScaleFactor(0.7)
            HStack(spacing: 8) {
                if let vintage = wine.vintage {
                    Text(verbatim: "\(vintage)")
                        .font(.title3.monospacedDigit())
                }
                if let color = wine.color {
                    Text(color.title)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        // The backdrop's tint (white wine as straw gold, not khaki); text stays primary.
                        .background(color.studioTint.opacity(0.18), in: Capsule())
                }
            }
            if let producer = wine.producer {
                Text(producer)
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
            let origin = [wine.appellation, wine.region, wine.country].compactMap { $0 }.joined(separator: " · ")
            if !origin.isEmpty {
                Text(origin)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .padding(.horizontal)
        .background(
            LinearGradient(
                colors: [(wine.color?.studioTint ?? Color.neutral).opacity(0.22), .clear],
                startPoint: .top, endPoint: .bottom
            ),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if wine.hasShownPhoto {
                Button("Show Photo") { showsPhoto = true }
            }
        }
    }
}

/// One bottle in the cellar with its location and quick actions.
struct BottleRow: View {
    @Environment(CellarModel.self) private var model
    @Environment(SceneRouter.self) private var router: SceneRouter?
    let bottle: Bottle
    @State private var reserving = false
    @State private var occasion = ""

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: bottle.slot == nil ? "shippingbox" : model.locationIcon(for: bottle))
                .foregroundStyle(.secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.locationLabel(for: bottle) ?? String(localized: "Not in storage"))
                    .font(.body)
                Text(details)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let occasionName = bottle.reservedFor {
                    Label("Kept for \(occasionName)", systemImage: "gift")
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)
                }
            }
            Spacer()
            if let documents = bottle.attachments?.count {
                Button {
                    router?.sheet = .bottleDocuments(bottleID: bottle.id, importing: false)
                } label: {
                    DocumentCount(count: documents)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("\(documents) documents"))
                .accessibilityHint(Text("Shows the documents of this bottle."))
            }
            if bottle.slot != nil {
                Button {
                    router?.showInRack(bottle)
                } label: {
                    Image(systemName: "scope")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Show in Storage"))
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                router?.sheet = .drink(bottleID: bottle.id)
            } label: {
                Label("Drink", systemImage: "wineglass")
            }
            .tint(.accentColor)
        }
        // No full swipe: the bottle leaves the cellar only on purpose.
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                model.markGone(bottle.id)
            } label: {
                Label("No Longer in Cellar", systemImage: "minus.circle")
            }
            .tint(Color.destructive)
            Button {
                router?.sheet = .moveBottle(bottleID: bottle.id)
            } label: {
                Label("Move", systemImage: "arrow.up.and.down.and.arrow.left.and.right")
            }
            .tint(Color.action)
            Button {
                router?.sheet = .bottleDocuments(bottleID: bottle.id, importing: bottle.attachments == nil)
            } label: {
                Label("Documents", systemImage: "paperclip")
            }
            .tint(Color.neutral)
        }
        .contextMenu {
            Button("Drink", systemImage: "wineglass") { router?.sheet = .drink(bottleID: bottle.id) }
            Button("Move…", systemImage: "arrow.up.and.down.and.arrow.left.and.right") { router?.sheet = .moveBottle(bottleID: bottle.id) }
            if bottle.reservedFor == nil {
                Button("Keep for an Occasion…", systemImage: "gift") {
                    occasion = model.cellar.reservations.first ?? ""
                    reserving = true
                }
            } else {
                Button("Free This Bottle", systemImage: "gift.slash") { model.reserve([bottle.id], for: nil) }
            }
            if bottle.slot != nil {
                Button("Show in Storage", systemImage: "scope") { router?.showInRack(bottle) }
            }
            BottleDocumentButtons(bottle: bottle) { router?.sheet = $0 }
            Divider()
            NoLongerInCellarButton { model.markGone(bottle.id) }
            Button(role: .destructive) {
                model.deleteBottle(bottle.id)
            } label: {
                Label {
                    Text("Delete Bottle")
                    Text("Entered by mistake")
                } icon: {
                    Image(systemName: "trash")
                }
            }
        }
        .alert("Keep for an Occasion", isPresented: $reserving) {
            TextField("Occasion", text: $occasion)
            Button("Keep Bottle") { model.reserve([bottle.id], for: occasion) }
                .disabled(occasion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The bottle stays where it is but is no longer suggested for tonight.")
        }
    }

    private var details: String {
        var parts: [String] = []
        if let price = bottle.purchasePrice {
            parts.append(price.formatted(.currency(code: bottle.currency ?? model.cellar.settings.currency)))
        }
        if let date = bottle.purchaseDate {
            parts.append(date.formatted(date: .abbreviated, time: .omitted))
        }
        if let place = bottle.purchasePlace { parts.append(place) }
        if bottle.sizeML != 750 { parts.append(BottleSize.label(for: bottle.sizeML)) }
        return parts.isEmpty ? String(localized: "Added \(bottle.addedAt.formatted(date: .abbreviated, time: .omitted))") : parts.joined(separator: " · ")
    }
}

/// A bottle that left the cellar without being drunk here, in the menus of a bottle.
struct NoLongerInCellarButton: View {
    let action: () -> Void

    var body: some View {
        Button(role: .destructive, action: action) {
            Label {
                Text("No Longer in Cellar")
                Text("Given away, sold or broken")
            } icon: {
                Image(systemName: "minus.circle")
            }
        }
    }
}

/// Why the window is what it is, and a quick way to move its end.
private struct WindowExplanation: View {
    @Environment(CellarModel.self) private var model
    let wine: Wine

    var body: some View {
        Group {
            switch wine.windowSource {
            case .estimated(let yearsAfterHarvest, let yearsAtBest):
                Text("Estimated from the style and the vintage: ready \(yearsAfterHarvest) years after the harvest, then at its best for \(yearsAtBest) years. Adjust it if the producer or a guide says otherwise.")
            case .entered, .missing:
                Text("The years entered for this wine.")
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        HStack {
            Button {
                model.update { try $0.updateWine(wine.movingWindowEnd(by: -1)) }
            } label: {
                ActionLabel("Drink Sooner", systemImage: "minus.circle")
            }
            .accessibilityHint(Text("Ends the drinking window a year earlier."))
            Spacer()
            Button {
                model.update { try $0.updateWine(wine.movingWindowEnd(by: 1)) }
            } label: {
                ActionLabel("Keep Longer", systemImage: "plus.circle")
            }
            .accessibilityHint(Text("Ends the drinking window a year later."))
        }
        .buttonStyle(.borderless)
    }
}

/// A bottle bought but not delivered yet.
struct OrderedBottleRow: View {
    @Environment(CellarModel.self) private var model
    let bottle: Bottle

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ArrivalLabel(date: bottle.expectedAt)
                .font(.body)
            let details = [
                bottle.purchasePrice.map { $0.formatted(.currency(code: bottle.currency ?? model.cellar.settings.currency)) },
                bottle.purchasePlace,
                bottle.sizeML != 750 ? BottleSize.label(for: bottle.sizeML) : nil,
            ].compactMap { $0 }
            if !details.isEmpty {
                Text(details.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                model.receive([bottle.id])
            } label: {
                Label("Received", systemImage: "shippingbox")
            }
            .tint(.accentColor)
        }
        // No full swipe, as for a bottle in the cellar: an order is cancelled only on purpose.
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                model.markGone(bottle.id)
            } label: {
                Label("Cancel Order", systemImage: "xmark.circle")
            }
        }
        .contextMenu {
            Button("Mark Received", systemImage: "shippingbox") { model.receive([bottle.id]) }
            Divider()
            Button("Cancel Order", systemImage: "xmark.circle", role: .destructive) { model.markGone(bottle.id) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: Text("Mark Received")) { model.receive([bottle.id]) }
        .accessibilityAction(named: Text("Cancel Order")) { model.markGone(bottle.id) }
    }
}

/// Common bottle sizes, by volume: also 50 cl, the Jura clavelin (62 cl), the Swiss 70 cl and the litre.
enum BottleSize {
    static let all = [375, 500, 620, 700, 750, 1000, 1500, 3000]

    static func label(for ml: Int) -> String {
        switch ml {
        case 375: String(localized: "Half bottle")
        case 500: String(localized: "50 cl", comment: "Bottle size")
        case 620: String(localized: "Clavelin 62 cl", comment: "Bottle size")
        case 700: String(localized: "70 cl", comment: "Bottle size")
        case 750: String(localized: "Bottle")
        case 1000: String(localized: "1 l", comment: "Bottle size")
        case 1500: String(localized: "Magnum")
        case 3000: String(localized: "Double magnum")
        default: "\(ml) ml"
        }
    }

    /// "75 cl", "37,5 cl", "1,5 l": the volume alone, numbers as the user writes them.
    static func volume(for ml: Int) -> String {
        let format = FloatingPointFormatStyle<Double>.number.precision(.fractionLength(0...2))
        return ml >= 1000 ? "\((Double(ml) / 1000).formatted(format))\u{00A0}l" : "\((Double(ml) / 10).formatted(format))\u{00A0}cl"
    }

    /// The size as VoiceOver reads it, units in words.
    static func spokenLabel(for ml: Int) -> String {
        switch ml {
        case 375: String(localized: "Half bottle, 37.5 centilitres")
        case 500: String(localized: "50 centilitres")
        case 620: String(localized: "Clavelin, 62 centilitres")
        case 700: String(localized: "70 centilitres")
        case 750: String(localized: "Bottle, 75 centilitres")
        case 1000: String(localized: "1 litre")
        case 1500: String(localized: "Magnum, 1.5 litres")
        case 3000: String(localized: "Double magnum, 3 litres")
        default: String(localized: "\(ml) millilitres")
        }
    }
}
