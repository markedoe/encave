import CaveKit
import SwiftUI

/// State of a label scan: the photo, what was filled automatically, and the AI request.
/// Owned by the screen, so it survives the form redrawing (for instance when the keyboard closes).
@MainActor @Observable
final class LabelScan {
    var showsScanner = false
    /// Resized label photo, saved with the wine by the screen.
    var photo: Data?
    /// Values filled automatically, which a later scan or the AI may replace.
    var autoFilled = WineDraft()
    /// The models set up in Settings › AI, in order.
    let aiConfiguration = AISettings.load()
    var aiEnabled: Bool { aiConfiguration.isEnabled }
    /// The model that read the current photo, and its connection's name.
    var readBy: AIReading?
    var isAnalyzing = false
    /// The photo just taken is being resized: Add waits for it, so it is saved with the wine.
    var preparingPhoto = false
    /// Set when the bottles were added before the AI answered: the answer then
    /// completes the saved wine (see `completeLater`).
    private var savedDraft: WineDraft?
    private weak var savedIn: CellarModel?
    /// The AI request for the current photo; a new scan cancels it, and its answer is
    /// applied only while its photo is still the current one.
    private var aiTask: Task<Void, Never>?
    private var photoToken = RequestToken()
    var aiError: String?
    /// The AI answered but had nothing to add to what is filled already.
    var aiNote: String?
    /// A scan filled the form: show the wine as a card, until the user wants to edit it.
    var scanned = false
    var editingDetails = false
    /// The camera recognized the barcode of a wine already in the cellar.
    var matchedWine: Wine?

    var showsSummary: Bool { scanned && !editingDetails }

    /// Result of the scanner: fill the form now, then let the AI complete it.
    func handle(_ result: LabelScanResult, image: Data?, draft: Binding<WineDraft>) {
        showsScanner = false
        if let barcode = result.barcode { draft.wrappedValue.barcodes.appendIfNew(Wine.normalizedBarcode(barcode)) }
        apply(result, to: draft)
        withAnimation { scanned = true }
        // A new photo: the answer for the previous one must not fill this wine.
        let token = photoToken.next()
        cancelAI()
        readBy = nil
        guard let image else { return }
        preparingPhoto = true
        Task {
            let resized = await PhotoStore.resized(image)
            guard photoToken.isCurrent(token) else { return }
            photo = resized
            preparingPhoto = false
            if aiEnabled, photo != nil { completeWithAI(draft: draft) }
        }
    }

    /// Remove Photo: forget it, and the AI answer about it.
    func removePhoto() {
        photoToken.next()
        readBy = nil
        cancelAI()
        preparingPhoto = false
        photo = nil
    }

    private func cancelAI() {
        aiTask?.cancel()
        aiTask = nil
        isAnalyzing = false
    }

    /// "Another Vintage" in the scanner: a new wine like `wine` (a barcode shared by its
    /// vintages), with the vintage read on this label, if any, to check in the summary.
    func handleAnotherVintage(of wine: Wine, result: LabelScanResult, image: Data?, draft: Binding<WineDraft>) {
        var value = draft.wrappedValue
        value.name = wine.name
        value.producer = wine.producer ?? ""
        value.color = wine.color
        value.appellation = wine.appellation ?? ""
        value.region = wine.region ?? ""
        value.country = wine.country ?? ""
        value.grapes = wine.grapes.joined(separator: ", ")
        value.vintage = nil
        draft.wrappedValue = value
        // The known wine's details count as typed: only the vintage is read from the label (or the AI).
        autoFilled = WineDraft()
        var read = result
        read.name = nil
        read.producer = nil
        read.appellation = nil
        editingDetails = false
        handle(read, image: image, draft: draft)
    }

    /// The form was saved while the AI was still reading the label: keep going, and
    /// complete the saved wine with its answer. Adding stays under ten seconds.
    func completeLater(_ saved: WineDraft, in model: CellarModel) {
        guard isAnalyzing else { return }
        savedDraft = saved
        savedIn = model
    }

    /// On-device reading: instant, fills only what the label clearly shows.
    private func apply(_ result: LabelScanResult, to draft: Binding<WineDraft>) {
        aiError = nil
        aiNote = nil
        var value = draft.wrappedValue
        if let name = result.name, value.name.isEmpty || value.name == autoFilled.name {
            value.name = name
            autoFilled.name = name
        }
        if let vintage = result.vintage, value.vintage == nil || value.vintage == autoFilled.vintage {
            value.vintage = vintage
            autoFilled.vintage = vintage
        }
        if let producer = result.producer, value.producer.isEmpty || value.producer == autoFilled.producer {
            value.producer = producer
            autoFilled.producer = producer
        }
        if let appellation = result.appellation, value.appellation.isEmpty || value.appellation == autoFilled.appellation {
            value.appellation = appellation
            autoFilled.appellation = appellation
        }
        draft.wrappedValue = value
    }

    /// Sends the label photo to the models set in Settings, one after the other until one
    /// reads it, or to `model` only ("Read Again with Another Model"), and completes the form.
    /// What an earlier reading filled is replaced, what the user typed is kept.
    func completeWithAI(draft: Binding<WineDraft>, model: UUID? = nil) {
        guard let photo, !isAnalyzing else { return }
        isAnalyzing = true
        aiError = nil
        aiNote = nil
        let token = photoToken.current
        aiTask = Task {
            do {
                let reading = try await AIService.analyzeLabel(jpeg: photo, only: model)
                let analysis = reading.analysis
                // The ID the photo will have once saved with the wine: the label frame is for it.
                let photoID = await Task.detached(priority: .userInitiated) { PhotoStore.id(for: photo) }.value
                // Another photo was taken (or this one removed) meanwhile: not about this wine.
                guard photoToken.isCurrent(token), !Task.isCancelled else { return }
                defer { finishAI() }
                readBy = reading
                let frame = analysis.labelFrame(forPhoto: photoID)
                if let saved = savedDraft, let model = savedIn {
                    savedDraft = nil
                    // Where the label is, kept even when the AI completes nothing else.
                    if let frame { model.setLabelFrame(frame, for: saved.id) }
                    var completed = saved
                    if let frame { completed.labelFrame = frame }
                    var filled = autoFilled
                    guard analysis.apply(to: &completed, replacing: &filled) else {
                        model.aiFoundNothing(for: saved.id)
                        return
                    }
                    completed.aiCompletedAt = Date()
                    model.completeWine(saved.id, from: saved.makeWine(), to: completed.makeWine())
                    return
                }
                if let frame { draft.wrappedValue.labelFrame = frame }
                var value = draft.wrappedValue
                guard analysis.apply(to: &value, replacing: &autoFilled) else {
                    aiNote = String(localized: "The AI found nothing to add")
                    return
                }
                value.aiCompletedAt = Date()
                withAnimation { draft.wrappedValue = value }
            } catch {
                guard photoToken.isCurrent(token), !Task.isCancelled else { return }
                defer { finishAI() }
                // The bottles were added meanwhile: the form is gone, so a banner says it.
                if let saved = savedDraft, let model = savedIn {
                    savedDraft = nil
                    model.aiFailed(for: saved.id, message: error.localizedDescription)
                    return
                }
                aiError = error.localizedDescription
            }
        }
    }

    private func finishAI() {
        isAnalyzing = false
        aiTask = nil
    }
}

extension View {
    /// The scanner, presented from the screen rather than from a form row: a sheet attached
    /// to a row was dismissed the first time, when the keyboard closed and the form redrew.
    func labelScanner(_ scan: LabelScan, draft: Binding<WineDraft>) -> some View {
        modifier(LabelScannerSheet(scan: scan, draft: draft))
    }
}

private struct LabelScannerSheet: ViewModifier {
    @Environment(CellarModel.self) private var model
    @Bindable var scan: LabelScan
    @Binding var draft: WineDraft

    func body(content: Content) -> some View {
        let cellar = model.cellar
        content.sheet(isPresented: $scan.showsScanner) {
            LabelScanner(acceptsPhotoWithoutText: scan.aiEnabled,
                         knownWines: { cellar.wines(barcode: $0) },
                         onKnownWine: { wine in
                             scan.showsScanner = false
                             scan.matchedWine = wine
                         },
                         onAnotherVintage: { wine, result, image in
                             scan.handleAnotherVintage(of: wine, result: result, image: image, draft: $draft)
                         }) { result, image in
                scan.handle(result, image: image, draft: $draft)
            }
            .environment(model)
        }
    }
}

extension Array where Element == String {
    mutating func appendIfNew(_ value: String) {
        if !value.isEmpty, !contains(value) { append(value) }
    }
}

/// "Scan the label" with the photo and the optional AI completion.
/// Shared by Add Bottles and Remember a Wine; the screen adds `.labelScanner(scan, draft:)`.
struct LabelScanSection: View {
    let scan: LabelScan
    @Binding var draft: WineDraft

    var body: some View {
        if LabelScanner.isAvailable {
            Section {
                Button {
                    scan.showsScanner = true
                } label: {
                    Group {
                        if scan.photo == nil {
                            Label("Scan the Label", systemImage: "camera.viewfinder")
                        } else {
                            Label("Scan Again", systemImage: "camera.viewfinder")
                        }
                    }
                    .font(.headline)
                }
                if let photo = scan.photo {
                    LabelPhotoRow(photo: photo, isAnalyzing: scan.isAnalyzing,
                                  remove: { withAnimation { scan.removePhoto() } })
                    // A row of its own: next to the photo, the button was too narrow
                    // and its title broke in the middle of words.
                    if scan.aiEnabled {
                        AICompletionButton(completedAt: draft.aiCompletedAt, isAnalyzing: scan.isAnalyzing) {
                            scan.completeWithAI(draft: $draft)
                        }
                        AIReadAgainRows(readBy: scan.readBy, configuration: scan.aiConfiguration, isAnalyzing: scan.isAnalyzing) {
                            scan.completeWithAI(draft: $draft, model: $0)
                        }
                    }
                }
                if let aiError = scan.aiError {
                    Label(aiError, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(Color.warning)
                }
                if let aiNote = scan.aiNote {
                    Label(aiNote, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } footer: {
                if scan.aiEnabled {
                    Text("Reads the label on this device, then completes the details with the AI models set in Settings.")
                } else {
                    Text("Reads the label on this device to fill in the name, producer, vintage and appellation.")
                }
            }
            .animation(.default, value: scan.photo)
            .animation(.default, value: scan.aiError)
            .animation(.default, value: scan.aiNote)
        }
    }
}

/// What the scan (and the AI) found, laid out like the wine page: a clear card and
/// labeled details, with an Edit button that brings back the form fields.
struct WineSummarySections: View {
    @Binding var draft: WineDraft
    let scan: LabelScan

    var body: some View {
        Section {
            HStack(alignment: .top, spacing: 14) {
                if let photo = scan.photo {
                    WinePhoto(data: photo, maxPixelSize: 300)
                        .frame(width: 64, height: 84)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .accessibilityHidden(true)
                } else {
                    BottleEnd(color: draft.color, size: 56)
                }
                VStack(alignment: .leading, spacing: 6) {
                    if draft.name.isEmpty {
                        Text("Name not found").font(.title3.weight(.semibold)).foregroundStyle(.secondary)
                    } else {
                        Text(draft.name).font(.title3.weight(.semibold)).fontDesign(.serif)
                    }
                    HStack(spacing: 8) {
                        if let vintage = draft.vintage {
                            Text(verbatim: "\(vintage)").font(.headline.monospacedDigit())
                        } else {
                            Text("Non-vintage").font(.subheadline).foregroundStyle(.secondary)
                        }
                        if let color = draft.color {
                            HStack(spacing: 4) {
                                BottleEnd(color: color, size: 12)
                                Text(color.title)
                            }
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(color.tint.opacity(0.18), in: Capsule())
                        }
                    }
                    if !draft.producer.isEmpty {
                        Text(draft.producer).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
        } header: {
            Text("Wine")
        }

        // Style and vintage in one tap: on-device reading never finds the style, and a
        // vintage may be missing or misread. The estimated window follows them.
        Section {
            VintagePicker(vintage: $draft.vintage)
                .aiEstimated(draft.isEstimatedByAI("vintage"))
            StyleChips(color: $draft.color)
                .aiEstimated(draft.isEstimatedByAI("color"))
        } footer: {
            if scan.isAnalyzing {
                Label("Reading the label…", systemImage: "sparkles")
            } else if scan.aiEnabled {
                Text("Check what was read on the label. Tap Edit Details to correct or complete it.")
            } else {
                Text("Read on this device. Choose the style, or tap Edit Details to correct the rest.")
            }
        }

        // Only what was found: a row per empty field would look like a failed scan.
        let details = [
            SummaryDetail(title: "Appellation", value: draft.appellation, key: "appellation"),
            SummaryDetail(title: "Region", value: draft.region, key: "region"),
            SummaryDetail(title: "Country", value: draft.country, key: "country"),
            SummaryDetail(title: "Grapes", value: draft.grapes, key: "grapes"),
            SummaryDetail(title: "Classification", value: draft.classification, key: "classification"),
            SummaryDetail(title: "Alcohol", value: draft.abv.map(WineDetailText.alcohol) ?? "", key: "abv"),
        ].filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
        if !details.isEmpty || window != nil {
            Section {
                ForEach(details) { detail in
                    LabeledContent(LocalizedStringKey(detail.title)) {
                        Text(detail.value).multilineTextAlignment(.trailing)
                    }
                    .aiEstimated(draft.isEstimatedByAI(detail.key))
                }
                if let window {
                    LabeledContent("Drinking window") {
                        Text(verbatim: "\(window.0) – \(window.1)").monospacedDigit()
                    }
                    .aiEstimated(draft.isEstimatedByAI("drinkWindow"))
                }
            } header: {
                Text("Details")
            }
        }

        Section {
            Button {
                withAnimation { scan.editingDetails = true }
            } label: {
                Label("Edit Details", systemImage: "pencil")
            }
        }
    }

    /// Years when the wine is at its best: entered, or estimated from style and vintage.
    private var window: (Int, Int)? {
        draft.window.map { ($0.from, $0.until) }
    }

}

/// A detail of the scan summary: its title (a localized key) and what was found.
private struct SummaryDetail: Identifiable {
    let title: String
    let value: String
    /// Its `LabelRequest.sourceKeys` key, to mark it "Estimated by AI".
    let key: String
    var id: String { title }
}

/// The label photo just taken, with Remove.
private struct LabelPhotoRow: View {
    let photo: Data
    let isAnalyzing: Bool
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
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button(action: remove) {
                Image(systemName: "xmark.circle.fill")
                    .imageScale(.large)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text("Remove Photo"))
        }
    }
}

/// "Complete with AI" as a full-width form row. Once the AI has completed the wine, it
/// reads "Complete Again" and says when, so it is not tapped again for nothing; while
/// the AI reads the label, it shows progress and cannot be tapped.
/// Shared by the label scan (Add Bottles, Remember a Wine) and Edit Wine.
struct AICompletionButton: View {
    let completedAt: Date?
    let isAnalyzing: Bool
    let action: @MainActor () -> Void

    var body: some View {
        Button(action: action) {
            if isAnalyzing {
                Label {
                    Text("Reading the label…")
                } icon: {
                    ProgressView()
                }
            } else {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        if completedAt == nil {
                            Text("Complete with AI")
                        } else {
                            Text("Complete Again")
                        }
                        if let completedAt {
                            AICompletedText(date: completedAt)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                } icon: {
                    Image(systemName: "sparkles")
                }
            }
        }
        .disabled(isAnalyzing)
        .accessibilityIdentifier("label.completeWithAI")
    }
}

/// Which model read the label, and "Read Again with Another Model": the same photo
/// sent to the model picked, whose answer replaces what the previous one filled.
struct AIReadAgainRows: View {
    let readBy: AIReading?
    let configuration: AIConfiguration
    let isAnalyzing: Bool
    let readAgain: @MainActor (UUID) -> Void

    var body: some View {
        if let readBy, !isAnalyzing {
            let modelName = readBy.modelTitle
            Label {
                Text("Read by \(modelName)")
            } icon: {
                Image(systemName: "sparkles")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("label.readBy")
            let others = configuration.usableModels.filter { $0.id != readBy.model.id }
            if !others.isEmpty {
                Menu {
                    ForEach(others) { model in
                        Button {
                            readAgain(model.id)
                        } label: {
                            Text(verbatim: configuration.connection(for: model).map { "\(model.model) · \($0.title)" } ?? model.model)
                        }
                    }
                } label: {
                    Label("Read Again with Another Model", systemImage: "arrow.triangle.2.circlepath")
                }
                .accessibilityIdentifier("label.readAgain")
            }
        }
    }
}

/// "Completed by AI · Oct 22": when the AI last completed the wine's details.
struct AICompletedText: View {
    let date: Date

    var body: some View {
        Text("Completed by AI · \(formattedDay)")
    }

    private var formattedDay: String { Self.day(date) }

    /// The day, with the year when it is not this year.
    static func day(_ date: Date) -> String {
        let calendar = Calendar.current
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: .now)
        return sameYear
            ? date.formatted(.dateTime.day().month(.abbreviated))
            : date.formatted(.dateTime.day().month(.abbreviated).year())
    }
}
