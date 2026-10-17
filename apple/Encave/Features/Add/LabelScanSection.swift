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
    var aiError: String?

    /// Result of the scanner: fill the form now, then let the AI complete it.
    func handle(_ result: LabelScanResult, image: Data?, draft: Binding<WineDraft>) {
        showsScanner = false
        apply(result, to: draft)
        guard let image else { return }
        Task {
            photo = await PhotoStore.resized(image)
            if aiEnabled, photo != nil { completeWithAI(draft: draft) }
        }
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
    @Bindable var scan: LabelScan
    @Binding var draft: WineDraft

    func body(content: Content) -> some View {
        content.sheet(isPresented: $scan.showsScanner) {
            LabelScanner(acceptsPhotoWithoutText: scan.aiEnabled) { result, image in
                scan.handle(result, image: image, draft: $draft)
            }
        }
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
                        .foregroundStyle(.orange)
                }
            } footer: {
                if scan.aiEnabled {
                    Text("Reads the label on this device, then completes the details with the AI service set in Settings.")
                } else {
                    Text("Reads the label on this device to fill in the name and vintage.")
                }
            }
            .animation(.default, value: scan.photo)
            .animation(.default, value: scan.aiError)
        }
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
