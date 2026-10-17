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
    @State private var choosingSlots = false
    @State private var prepared = false
    @State private var showsScanner = false
    @State private var labelPhoto: Data?
    @State private var autoFilled = WineDraft()
    @State private var aiEnabled = AISettings.load().isEnabled
    @State private var isAnalyzing = false
    @State private var aiError: String?

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
                    if LabelScanner.isAvailable {
                        Section {
                            Button {
                                showsScanner = true
                            } label: {
                                Group {
                                    if labelPhoto == nil {
                                        Label("Scan the Label", systemImage: "camera.viewfinder")
                                    } else {
                                        Label("Scan Again", systemImage: "camera.viewfinder")
                                    }
                                }
                                .font(.headline)
                            }
                            if let labelPhoto {
                                LabelPhotoRow(
                                    photo: labelPhoto, aiEnabled: aiEnabled, isAnalyzing: isAnalyzing,
                                    analyze: completeWithAI, remove: { withAnimation { self.labelPhoto = nil } }
                                )
                            }
                            if let aiError {
                                Label(aiError, systemImage: "exclamationmark.triangle")
                                    .font(.footnote)
                                    .foregroundStyle(.orange)
                            }
                        } footer: {
                            if aiEnabled {
                                Text("Reads the label on this device, then completes the details with the AI service set in Settings.")
                            } else {
                                Text("Reads the label on this device to fill in the name and vintage.")
                            }
                        }
                    }
                    if !model.cellar.wines.isEmpty {
                        Section("Already in your cellar?") {
                            TextField("Search your wines", text: $search)
                                .autocorrectionDisabled()
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
                        }
                    }
                    WineFormSections(draft: $draft, focusName: context.wine == nil && model.cellar.wines.isEmpty)
                }

                Section("Bottles") {
                    Stepper(value: $quantity, in: 1...48) {
                        LabeledContent("Quantity") {
                            Text(verbatim: "\(quantity)").monospacedDigit().font(.headline)
                        }
                    }
                    Picker("Size", selection: $sizeML) {
                        ForEach(BottleSize.all, id: \.self) { size in
                            Text(BottleSize.label(for: size)).tag(size)
                        }
                    }
                }

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
                    }
                } header: {
                    Text("Location")
                } footer: {
                    if selectedRack != nil, chosenSlots.isEmpty {
                        Text("The first free positions are used unless you choose them.")
                    }
                }

                Section {
                    DisclosureGroup("Purchase details", isExpanded: $showsPurchase) {
                        TextField("Price per bottle", value: $price,
                                  format: .currency(code: model.cellar.settings.currency))
                            .numericKeyboard()
                        Toggle("Purchase date", isOn: $hasPurchaseDate.animation())
                        if hasPurchaseDate {
                            DatePicker("Date", selection: $purchaseDate, in: ...Date.now, displayedComponents: .date)
                        }
                        TextField("Bought at", text: $purchasePlace)
                        TextField("Gift from", text: $receivedFrom)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Add Bottles")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
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
            .sheet(isPresented: $showsScanner) {
                LabelScanner(acceptsPhotoWithoutText: aiEnabled) { result, image in
                    showsScanner = false
                    applyScan(result)
                    guard let image else { return }
                    Task {
                        labelPhoto = await PhotoStore.resized(image)
                        if aiEnabled, labelPhoto != nil { completeWithAI() }
                    }
                }
            }
            .onChange(of: rackID) { chosenSlots = chosenSlots.filter { $0.rackID == rackID } }
            .onAppear(perform: prepare)
            .animation(.default, value: labelPhoto)
            .animation(.default, value: aiError)
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

    /// On-device reading: instant, fills only what the label clearly shows.
    private func applyScan(_ result: LabelScanResult) {
        aiError = nil
        if let name = result.name, draft.name.isEmpty || draft.name == autoFilled.name {
            draft.name = name
            autoFilled.name = name
        }
        if let vintage = result.vintage, draft.vintage == nil || draft.vintage == autoFilled.vintage {
            draft.vintage = vintage
            autoFilled.vintage = vintage
        }
        if let producer = result.producer, draft.producer.isEmpty || draft.producer == autoFilled.producer {
            draft.producer = producer
            autoFilled.producer = producer
        }
        if let appellation = result.appellation, draft.appellation.isEmpty || draft.appellation == autoFilled.appellation {
            draft.appellation = appellation
            autoFilled.appellation = appellation
        }
    }

    /// Sends the label photo to the AI service chosen in Settings and completes the form.
    private func completeWithAI() {
        guard let labelPhoto, !isAnalyzing else { return }
        isAnalyzing = true
        aiError = nil
        Task {
            defer { isAnalyzing = false }
            do {
                let analysis = try await AIService.analyzeLabel(jpeg: labelPhoto)
                withAnimation {
                    analysis.apply(to: &draft, replacing: &autoFilled)
                }
            } catch {
                aiError = error.localizedDescription
            }
        }
    }

    private func save() {
        var wine = existingWine ?? draft.makeWine()
        if existingWine == nil, let labelPhoto {
            wine.photoID = PhotoStore.save(labelPhoto)
        }
        var slots = chosenSlots
        if let rack = selectedRack, slots.count < quantity {
            let free = model.cellar.freeSlots(in: rack).filter { !slots.contains($0) }
            slots += free.prefix(quantity - slots.count)
        }
        let purchase = PurchaseInfo(
            date: hasPurchaseDate ? purchaseDate : nil, price: price,
            currency: model.cellar.settings.currency,
            place: purchasePlace.isEmpty ? nil : purchasePlace,
            receivedFrom: receivedFrom.isEmpty ? nil : receivedFrom,
            storageNote: selectedRack == nil && !storageNote.isEmpty ? storageNote : nil,
            sizeML: sizeML
        )
        model.addBottles(of: wine, quantity: quantity, slots: Array(slots.prefix(quantity)), purchase: purchase)
        dismiss()
    }
}

/// The label photo just taken, with the AI action.
private struct LabelPhotoRow: View {
    let photo: Data
    let aiEnabled: Bool
    let isAnalyzing: Bool
    let analyze: @MainActor () -> Void
    let remove: @MainActor () -> Void

    var body: some View {
        HStack(spacing: 12) {
            WinePhoto(data: photo, maxPixelSize: 240)
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Label photo")
                Group {
                    if isAnalyzing { Text("Reading the label…") } else { Text("Saved with the wine") }
                }
                .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if isAnalyzing {
                ProgressView()
            } else if aiEnabled {
                Button(action: analyze) {
                    Label("Complete with AI", systemImage: "sparkles")
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            Button(action: remove) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text("Remove Photo"))
        }
    }
}
