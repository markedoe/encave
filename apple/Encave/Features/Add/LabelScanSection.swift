import CaveKit
import SwiftUI

/// "Scan the label" with the photo and the optional AI completion.
/// Shared by Add Bottles and Remember a Wine.
struct LabelScanSection: View {
    @Binding var draft: WineDraft
    /// Resized label photo, saved with the wine by the caller.
    @Binding var labelPhoto: Data?

    @State private var showsScanner = false
    /// Values filled automatically, which a later scan or the AI may replace.
    @State private var autoFilled = WineDraft()
    @State private var aiEnabled = AISettings.load().isEnabled
    @State private var isAnalyzing = false
    @State private var aiError: String?

    var body: some View {
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
            .animation(.default, value: labelPhoto)
            .animation(.default, value: aiError)
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
