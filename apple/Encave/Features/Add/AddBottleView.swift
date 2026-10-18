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

    private var wineIsValid: Bool { existingWine != nil || draft.isValid }

    var body: some View {
        NavigationStack {
            Form {
                if let wine = existingWine {
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
                    } header: {
                        Text("Wine")
                    }
                } else {
                    LabelScanSection(scan: scan, draft: $draft)
                    if scan.showsSummary {
                        WineSummarySections(draft: $draft, scan: scan)
                    } else {
                    if !model.cellar.wines.isEmpty {
                        Section {
                            TextField("Search your wines", text: $search)
                                .autocorrectionDisabled()
                                .fieldHelp("Type a few letters of the name to add bottles of a wine you already have.")
                            ForEach(model.cellar.wineSuggestions(matching: search)) { wine in
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
                            }
                        } header: {
                            Text("Already in your cellar?")
                        } footer: {
                            Text("Choose it to add bottles without typing the wine again.")
                        }
                    }
                    WineFormSections(draft: $draft, focusName: context.wine == nil && model.cellar.wines.isEmpty,
                                     onSearchOnline: { searchingOnline = true })
                    }
                }

                Section {
                    Stepper(value: $quantity, in: 1...48) {
                        LabeledContent("Quantity") {
                            Text(verbatim: "\(quantity)").monospacedDigit().font(.headline)
                        }
                    }
                    .fieldHelp("How many identical bottles you add.")
                    Picker("Size", selection: $sizeML) {
                        ForEach(BottleSize.all, id: \.self) { size in
                            Text(BottleSize.label(for: size)).tag(size)
                        }
                    }
                    .fieldHelp("Half bottle 37.5 cl, bottle 75 cl, magnum 1.5 l, double magnum 3 l.")
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
                        Picker("Rack", selection: $rackID) {
                            Text("No rack").tag(UUID?.none)
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
                                    .foregroundStyle(.orange)
                            }
                        } else {
                            TextField("Where is it?", text: $storageNote, prompt: Text("Kitchen, box in the garage…"))
                                .fieldHelp("For bottles that are not in a rack.")
                        }
                    } header: {
                        Text("Location")
                    } footer: {
                        if selectedRack != nil, chosenSlots.isEmpty {
                            Text("The first free positions are used unless you choose them. Tap Positions to pick them on the rack.")
                        } else if selectedRack == nil {
                            Text("Choose a rack to see exactly where the bottles are, or note where they are kept.")
                        }
                    }
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
                    HelpLink(topic: .adding, title: "Help with adding bottles")
                }
            }
            .formStyle(.grouped)
            .labelScanner(scan, draft: $draft)
            .webSearchSheet(isPresented: $searchingOnline, draft: $draft)
            .navigationTitle("Add Bottles")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { save() }
                        .disabled(!wineIsValid)
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
        }
        .macSheetFrame(width: 520, height: 720)
    }

    private var selectedRack: Rack? { rackID.flatMap { model.cellar.rack($0) } }

    private func slotSummary(rack: Rack) -> String {
        if chosenSlots.isEmpty { return String(localized: "Automatic") }
        return chosenSlots.map { rack.label(for: $0) }.joined(separator: ", ")
    }

    private func prepare() {
        guard !prepared else { return }
        prepared = true
        existingWine = context.wine
        if let slot = context.slot {
            rackID = slot.rackID
            chosenSlots = [slot]
        }
    }

    private func save() {
        // An existing wine as it is now: it may have been edited since it was chosen
        // (another window, sync), and adding bottles saves the wine record too.
        var wine = existingWine.map { model.cellar.wines[$0.id] ?? $0 } ?? draft.makeWine()
        if existingWine == nil, let labelPhoto = scan.photo {
            wine.photoID = PhotoStore.save(labelPhoto)
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
        guard model.addBottles(of: wine, quantity: quantity, slots: Array(slots.prefix(quantity)), purchase: purchase)
        else { return }
        if let discoveryID = context.discoveryID {
            model.update { try $0.markBought(discoveryID) }
        }
        if existingWine == nil {
            var saved = draft
            saved.photoID = wine.photoID
            scan.completeLater(saved, in: model)
        }
        dismiss()
    }
}

