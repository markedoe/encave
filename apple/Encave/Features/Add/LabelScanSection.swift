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
    let aiEnabled = AISettings.load().isEnabled
    var isAnalyzing = false
    /// The photo just taken is being resized: Add waits for it, so it is saved with the wine.
    var preparingPhoto = false
    /// Set when the bottles were added before the AI answered: the answer then
    /// completes the saved wine (see `completeLater`).
    private var savedDraft: WineDraft?
    private weak var savedIn: CellarModel?
    var aiError: String?
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
        guard let image else { return }
        preparingPhoto = true
        Task {
            photo = await PhotoStore.resized(image)
            preparingPhoto = false
            if aiEnabled, photo != nil { completeWithAI(draft: draft) }
        }
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

    /// Sends the label photo to the AI service chosen in Settings and completes the form.
    func completeWithAI(draft: Binding<WineDraft>) {
        guard let photo, !isAnalyzing else { return }
        isAnalyzing = true
        aiError = nil
        Task {
            defer { isAnalyzing = false }
            do {
                let analysis = try await AIService.analyzeLabel(jpeg: photo)
                if let saved = savedDraft, let model = savedIn {
                    savedDraft = nil
                    var completed = saved
                    var filled = autoFilled
                    analysis.apply(to: &completed, replacing: &filled)
                    model.completeWine(saved.id, from: saved.makeWine(), to: completed.makeWine())
                    return
                }
                var value = draft.wrappedValue
                analysis.apply(to: &value, replacing: &autoFilled)
                withAnimation { draft.wrappedValue = value }
            } catch {
                aiError = error.localizedDescription
            }
        }
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
                    LabelPhotoRow(
                        photo: photo, aiEnabled: scan.aiEnabled, isAnalyzing: scan.isAnalyzing,
                        analyze: { scan.completeWithAI(draft: $draft) },
                        remove: { withAnimation { scan.photo = nil } }
                    )
                }
                if let aiError = scan.aiError {
                    Label(aiError, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(Color.warning)
                }
            } footer: {
                if scan.aiEnabled {
                    Text("Reads the label on this device, then completes the details with the AI service set in Settings.")
                } else {
                    Text("Reads the label on this device to fill in the name, producer, vintage and appellation.")
                }
            }
            .animation(.default, value: scan.photo)
            .animation(.default, value: scan.aiError)
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
            StyleChips(color: $draft.color)
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
            SummaryDetail(title: "Appellation", value: draft.appellation), SummaryDetail(title: "Region", value: draft.region),
            SummaryDetail(title: "Country", value: draft.country), SummaryDetail(title: "Grapes", value: draft.grapes),
        ].filter { !$0.value.trimmingCharacters(in: .whitespaces).isEmpty }
        if !details.isEmpty || window != nil {
            Section {
                ForEach(details) { detail in
                    LabeledContent(LocalizedStringKey(detail.title)) {
                        Text(detail.value).multilineTextAlignment(.trailing)
                    }
                }
                if let window {
                    LabeledContent("Drinking window") {
                        Text(verbatim: "\(window.0) – \(window.1)").monospacedDigit()
                    }
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
    var id: String { title }
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
