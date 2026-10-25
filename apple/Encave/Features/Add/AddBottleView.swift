import CaveKit
import SwiftUI

/// Add one or more bottles: of a wine already in the cellar, or of a new wine.
struct AddBottleView: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let context: AddBottleContext

    @State private var existingWine: Wine?
    @State private var draft = WineDraft()
    @State private var search = ""
    @State private var quantity = 1
    @State private var sizeML = 750
    @State private var rackID: UUID?
    @State private var chosenSlots: [Slot] = []
    @State private var storageNote = ""
    @State private var showsPurchase = false
    @State private var price: Decimal?
    @State private var hasPurchaseDate = false
    @State private var purchaseDate = Date.now
    @State private var purchasePlace = ""
    @State private var receivedFrom = ""
    @State private var isOrdered = false
    @State private var hasExpectedDate = false
    @State private var expectedDate = Date.now
    @State private var choosingSlots = false
    @State private var prepared = false
    @State private var scan = LabelScan()
    @State private var searchingOnline = false
    /// Documents picked here, attached to the wine when the bottles are added.
    @State private var documents = DocumentActions()
    /// The form as it opened, to ask before discarding changes.
    @State private var initial: FormState?
    /// "It's a Different Wine": the wine of the cellar the draft matched, not offered again.
    @State private var dismissedMatchID: UUID?
    @State private var scannerOffered = false

    private var wineIsValid: Bool { existingWine != nil || draft.isValid }

    /// What the user can change, to tell whether something was entered.
    private struct FormState: Equatable {
        var existingWineID: UUID?
        var draft: WineDraft
        var quantity: Int
        var sizeML: Int
        var rackID: UUID?
        var chosenSlots: [Slot]
        var storageNote: String
        var price: Decimal?
        var hasPurchaseDate: Bool
        var purchasePlace: String
        var receivedFrom: String
        var isOrdered: Bool
        var hasExpectedDate: Bool
    }

    private var formState: FormState {
        FormState(existingWineID: existingWine?.id, draft: draft, quantity: quantity, sizeML: sizeML, rackID: rackID,
                  chosenSlots: chosenSlots, storageNote: storageNote, price: price, hasPurchaseDate: hasPurchaseDate,
                  purchasePlace: purchasePlace, receivedFrom: receivedFrom, isOrdered: isOrdered,
                  hasExpectedDate: hasExpectedDate)
    }

    private var isDirty: Bool {
        guard let initial else { return false }
        return formState != initial || scan.photo != nil || !documents.pending.isEmpty
    }

    /// A wine of the cellar the new one would duplicate (same name, producer and vintage):
    /// offered, so its bottles and tastings stay together.
    private var sameWine: Wine? {
        guard existingWine == nil, draft.isValid,
              let match = model.cellar.wine(matching: draft.makeWine()), match.id != dismissedMatchID else { return nil }
        return match
    }

    /// Near the top, right after the label or the wine: how many bottles, their size and
    /// whether they are here yet, then where they go.
    @ViewBuilder
    private var quantitySection: some View {
        Section {
            Stepper(value: $quantity, in: 1...48) {
                LabeledContent("Quantity") {
                    Text(verbatim: "\(quantity)").monospacedDigit().font(.headline)
                }
            }
            .fieldHelp("How many identical bottles you add.")
            .accessibilityIdentifier("add.quantity")
            // A case in one tap rather than eleven.
            LabeledContent("A case of") {
                HStack(spacing: 8) {
                    ForEach([6, 12], id: \.self) { size in
                        Button {
                            quantity = size
                        } label: {
                            Text(verbatim: "\(size)").monospacedDigit()
                        }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(Text("\(size) bottles"))
                        .accessibilityAddTraits(quantity == size ? .isSelected : [])
                    }
                }
            }
            Picker("Size", selection: $sizeML) {
                ForEach(BottleSize.all, id: \.self) { size in
                    Text(BottleSize.label(for: size))
                        .accessibilityLabel(Text(BottleSize.spokenLabel(for: size)))
                        .tag(size)
                }
            }
            .fieldHelp("Half bottle 37.5 cl, bottle 75 cl, magnum 1.5 l, double magnum 3 l; also 50 cl, clavelin 62 cl, 70 cl (as in Switzerland) and 1 l.")
            Toggle("Not delivered yet", isOn: $isOrdered.animation())
                .fieldHelp("Bought or ordered en primeur. The bottles join the cellar when you mark them received.")
                .accessibilityIdentifier("add.ordered")
            if isOrdered {
                Toggle("Expected delivery", isOn: $hasExpectedDate.animation())
                if hasExpectedDate {
                    DatePicker("Date", selection: $expectedDate, displayedComponents: .date)
                }
            }
        } header: {
            Text("Bottles")
        } footer: {
            if isOrdered {
                Text("Bottles on order are listed apart and are not suggested for tonight until they arrive.")
            } else {
                Text("Each bottle is tracked on its own, so you can drink or move them one by one.")
            }
        }
        if !isOrdered {
            Section {
                Picker("Store in", selection: $rackID) {
                    Text("No storage").tag(UUID?.none)
                    ForEach(model.cellar.racks) { rack in
                        Text(rack.name).tag(UUID?.some(rack.id))
                    }
                }
                if let rack = selectedRack {
                    let free = model.cellar.freeSlots(in: rack).count
                    Button {
                        choosingSlots = true
                    } label: {
                        LabeledContent("Positions") {
                            Text(slotSummary(rack: rack))
                                .foregroundStyle(chosenSlots.isEmpty ? Color.secondary : Color.primary)
                        }
                    }
                    if free < quantity {
                        Label("Only \(free) free positions: the other bottles will have no position.",
                              systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(Color.warning)
                    }
                } else {
                    TextField("Where is it?", text: $storageNote, prompt: Text("Kitchen, box in the garage…"))
                        .fieldHelp("For bottles that are not in storage.")
                }
            } header: {
                Text("Storage")
            } footer: {
                if selectedRack != nil, chosenSlots.isEmpty {
                    Text("The first free positions are used unless you choose them. Tap Positions to pick them.")
                } else if selectedRack == nil {
                    Text("Choose storage to see exactly where the bottles are, or note where they are kept.")
                }
            }
        }
        PendingDocumentsSection(actions: documents)
    }

    /// The wine chosen in the cellar, and the barcodes scanned here that it will keep.
    private func existingWineSection(_ wine: Wine) -> some View {
        Section {
            HStack(spacing: 12) {
                BottleEnd(color: wine.color, size: 36)
                VStack(alignment: .leading) {
                    Text(wine.displayName).font(.headline)
                    if let producer = wine.producer {
                        Text(producer).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if context.wine == nil {
                    Button("Change") { existingWine = nil }
                        .buttonStyle(.borderless)
                }
            }
            ForEach(draft.barcodes.filter { !(wine.barcodes ?? []).contains($0) }, id: \.self) { digits in
                HStack {
                    Label("Barcode \(digits), kept with the wine", systemImage: "barcode")
                        .font(.subheadline)
                    Spacer()
                    Button {
                        draft.barcodes.removeAll { $0 == digits }
                    } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("Remove Barcode"))
                }
            }
        } header: {
            Text("Wine")
        }
    }

    /// Right under the name typed by hand: the wine of the cellar it matches, or a search of
    /// the cellar's wines (by the name typed, until something is typed in the search), to
    /// add bottles to one without typing the wine again.
    @ViewBuilder
    private var cellarWinesSection: some View {
        if let match = sameWine {
            sameWineSection(match)
        } else if !model.cellar.wines.isEmpty {
            let typed = search.trimmingCharacters(in: .whitespaces)
            let suggestions = model.cellar.wineSuggestions(matching: typed.isEmpty ? draft.name : typed, limit: 5)
            Section {
                TextField("Search your wines", text: $search)
                    .autocorrectionDisabled()
                    .fieldHelp("Type a few letters of the name to add bottles of a wine you already have.")
                ForEach(suggestions) { wine in
                    Button {
                        withAnimation { existingWine = wine }
                    } label: {
                        HStack(spacing: 10) {
                            BottleEnd(color: wine.color, size: 22)
                            Text(wine.displayName)
                            Spacer()
                            Image(systemName: "plus.circle.fill").foregroundStyle(Color.accentColor)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("add.cellarWine")
                }
            } header: {
                Text("Already in your cellar?")
            } footer: {
                Text("Choose it to add bottles without typing the wine again.")
            }
        }
    }

    /// The scan or the name typed is a wine already in the cellar: add to it in one tap.
    private func sameWineSection(_ wine: Wine) -> some View {
        Section {
            HStack(spacing: 12) {
                BottleEnd(color: wine.color, size: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: wine.displayName).font(.headline)
                    Text("\(model.cellar.bottles(of: wine.id).count) bottles in the cellar")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            Button {
                withAnimation { existingWine = wine }
            } label: {
                Label("Add to This Wine", systemImage: "plus.circle.fill")
            }
            .accessibilityIdentifier("add.sameWine.use")
            Button("It’s a Different Wine") {
                withAnimation { dismissedMatchID = wine.id }
            }
            .accessibilityIdentifier("add.sameWine.different")
        } header: {
            Text("Already in your cellar")
        } footer: {
            Text("Adding to it keeps the bottles and tastings of this wine together.")
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let wine = existingWine {
                    existingWineSection(wine)
                    quantitySection
                } else if scan.showsSummary {
                    // After a scan: the label and what was read, then the bottles.
                    LabelScanSection(scan: scan, draft: $draft)
                    if let match = sameWine { sameWineSection(match) }
                    WineSummarySections(draft: $draft, scan: scan)
                    quantitySection
                } else {
                    // By hand (`AddFormLayout.byHand`): the wine first, its name the first
                    // field, then the bottles, where they go and their documents. The label
                    // can still be scanned after.
                    WineFormSections(draft: $draft, focusName: context.wine == nil && !context.scan,
                                     onSearchOnline: { searchingOnline = true },
                                     afterName: AnyView(cellarWinesSection))
                    quantitySection
                    LabelScanSection(scan: scan, draft: $draft)
                }

                Section {
                    DisclosureGroup("Purchase details", isExpanded: $showsPurchase) {
                        TextField("Price per bottle", value: $price,
                                  format: .currency(code: model.cellar.settings.currency))
                            .numericKeyboard()
                            .fieldHelp("Used for the value of your cellar in Insights. The currency is set in Settings.")
                        Toggle("Purchase date", isOn: $hasPurchaseDate.animation())
                        if hasPurchaseDate {
                            DatePicker("Date", selection: $purchaseDate, in: ...Date.now, displayedComponents: .date)
                        }
                        TextField("Bought at", text: $purchasePlace)
                            .fieldHelp("Shop, estate or website.")
                        TextField("Gift from", text: $receivedFrom)
                            .fieldHelp("Who gave you the bottle.")
                    }
                } footer: {
                    Text("Optional. Helps you remember where to buy a wine again and what your cellar is worth.")
                }
                Section {
                    HelpLink(topic: .adding, title: "Help with Adding Bottles")
                }
            }
            .formStyle(.grouped)
            .documentActions(documents)
            .labelScanner(scan, draft: $draft)
            .webSearchSheet(isPresented: $searchingOnline, draft: $draft)
            .navigationTitle("Add Bottles")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    DiscardChangesCancelButton(isDirty: isDirty) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { save() }
                        // A label photo just taken is still being resized: a moment.
                        .disabled(!wineIsValid || scan.preparingPhoto)
                        .keyboardShortcut(.defaultAction)
                }
            }
            .sheet(isPresented: $choosingSlots) {
                if let rack = selectedRack {
                    SlotPickerView(rack: rack, limit: quantity, selection: $chosenSlots)
                }
            }
            .onChange(of: rackID) { chosenSlots = chosenSlots.filter { $0.rackID == rackID } }
            // The scanner recognized a wine of the cellar by its barcode.
            .onChange(of: scan.matchedWine) { _, wine in
                guard let wine else { return }
                withAnimation { existingWine = wine }
                scan.matchedWine = nil
            }
            .onAppear(perform: prepare)
            .task { await openScannerFirst() }
            // Cancelled (or added: nothing is left): the temporary copies go. Not while the
            // document camera covers the screen, which also makes the form disappear.
            .onDisappear { if !documents.scanning { documents.discardPending() } }
        }
        // A swipe down would lose what was entered: Cancel asks first.
        .interactiveDismissDisabled(isDirty)
        .macSheetFrame(width: 520, height: 720)
    }

    private var selectedRack: Rack? { rackID.flatMap { model.cellar.rack($0) } }

    private func slotSummary(rack: Rack) -> String {
        if chosenSlots.isEmpty { return String(localized: "Automatic") }
        return chosenSlots.map { rack.displayLabel(for: $0) }.joined(separator: ", ")
    }

    private func prepare() {
        guard !prepared else { return }
        prepared = true
        existingWine = context.wine
        if let slot = context.slot {
            rackID = slot.rackID
            chosenSlots = [slot]
        } else {
            applyLastLocation()
        }
        // After the defaults: only what the user changes counts.
        initial = formState
    }

    /// Where the last bottles went on this device, if that rack still has room; else the
    /// first rack with room. Positions stay automatic.
    private func applyLastLocation() {
        let racks = model.cellar.racks
        let hasRoom = { (rack: Rack) in !model.cellar.freeSlots(in: rack).isEmpty }
        switch LastAddLocation.load() {
        case .rack(let id):
            rackID = racks.first { $0.id == id && hasRoom($0) }?.id ?? racks.first(where: hasRoom)?.id
        case .elsewhere(let note):
            rackID = nil
            storageNote = note
        case nil:
            rackID = racks.first(where: hasRoom)?.id
        }
    }

    /// "Scan a label" (the Cellar's + on iPhone and iPad, Siri): the camera first. Cancel
    /// in the scanner leaves the form to type the wine.
    private func openScannerFirst() async {
        #if os(iOS)
        guard context.scan, !scannerOffered, context.wine == nil, context.slot == nil, LiveTextScanner.opensFirst else { return }
        scannerOffered = true
        // Once the form's sheet is up: a sheet cannot open while another is still appearing.
        try? await Task.sleep(for: .milliseconds(450))
        scan.showsScanner = true
        #endif
    }

    private func save() {
        guard !scan.preparingPhoto else { return }
        // Documents picked here go with the wine, for all its bottles. One that could not be
        // stored is taken off the list: the form stays open under the alert.
        guard let attachments = documents.commitPending() else { return }
        var wine: Wine
        if let chosen = existingWine {
            // The wine as it is now: it may have been edited since it was chosen (another
            // window, sync), and adding bottles saves the wine record too. It keeps the
            // barcodes scanned here, and the label photo if it has none.
            wine = model.cellar.wines[chosen.id] ?? chosen
            draft.barcodes.forEach { wine.addBarcode($0) }
            if wine.photoID == nil, let labelPhoto = scan.photo {
                wine.photoID = PhotoStore.save(labelPhoto)
                // The AI's label frame, for the photo just saved.
                if let frame = draft.labelFrame, frame.photoID == wine.photoID { wine.labelFrame = frame }
            }
        } else {
            wine = draft.makeWine()
            if let labelPhoto = scan.photo {
                wine.photoID = PhotoStore.save(labelPhoto)
            }
        }
        if !attachments.isEmpty {
            wine.attachments = (wine.attachments ?? []) + attachments
        }
        var slots = isOrdered ? [] : chosenSlots
        if !isOrdered, let rack = selectedRack, slots.count < quantity {
            let free = model.cellar.freeSlots(in: rack).filter { !slots.contains($0) }
            slots += free.prefix(quantity - slots.count)
        }
        let purchase = PurchaseInfo(
            date: hasPurchaseDate ? purchaseDate : nil, price: price,
            currency: model.cellar.settings.currency,
            place: purchasePlace.isEmpty ? nil : purchasePlace,
            receivedFrom: receivedFrom.isEmpty ? nil : receivedFrom,
            storageNote: !isOrdered && selectedRack == nil && !storageNote.isEmpty ? storageNote : nil,
            sizeML: sizeML, isOrdered: isOrdered, expectedAt: hasExpectedDate ? expectedDate : nil
        )
        // On failure (a place taken meanwhile…) the form stays open under the error.
        guard model.addBottles(of: wine, quantity: quantity, slots: Array(slots.prefix(quantity)), purchase: purchase,
                               discoveryID: context.discoveryID)
        else { return }
        documents.discardPending()
        if !isOrdered {
            LastAddLocation(rackID: selectedRack?.id, storageNote: storageNote).save()
        }
        if existingWine == nil {
            var saved = draft
            saved.photoID = wine.photoID
            scan.completeLater(saved, in: model)
        }
        dismiss()
    }
}

/// Where the last bottles were put on this device, offered first the next time
/// (a device preference: each device may sit next to a different rack).
enum LastAddLocation: Equatable {
    case rack(UUID)
    case elsewhere(String)

    private static let key = "add.lastLocation"

    init(rackID: UUID?, storageNote: String) {
        self = rackID.map(LastAddLocation.rack) ?? .elsewhere(storageNote.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    static func load() -> LastAddLocation? {
        guard let value = UserDefaults.standard.string(forKey: key) else { return nil }
        if value.hasPrefix("rack:") { return UUID(uuidString: String(value.dropFirst(5))).map(LastAddLocation.rack) }
        if value.hasPrefix("other:") { return .elsewhere(String(value.dropFirst(6))) }
        return nil
    }

    func save() {
        let value: String
        switch self {
        case .rack(let id): value = "rack:\(id.uuidString)"
        case .elsewhere(let note): value = "other:\(note)"
        }
        UserDefaults.standard.set(value, forKey: Self.key)
    }
}
