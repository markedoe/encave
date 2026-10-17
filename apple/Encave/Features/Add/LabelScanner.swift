import CaveKit
import PhotosUI
import SwiftUI
import Vision
#if os(iOS)
import VisionKit
#endif

/// Reads a wine label on the device: live camera on iPhone and iPad, or a photo.
/// Returns the text found and the photo, which is sent nowhere unless the user asks for AI.
struct LabelScanner: View {
    /// When true, a photo is useful even if no text is found (AI can still read it).
    var acceptsPhotoWithoutText = false
    /// A wine of the cellar the camera's barcode belongs to, if any.
    var knownWine: (String) -> Wine? = { _ in nil }
    /// "Already in your cellar": use that wine instead of reading the label.
    var onKnownWine: ((Wine) -> Void)?
    let onResult: (LabelScanResult, Data?) -> Void

    static var isAvailable: Bool { true }

    @Environment(\.dismiss) private var dismiss
    @State private var photo: PhotosPickerItem?
    @State private var isReading = false
    @State private var lastResult: LabelScanResult?
    @State private var liveLines: [LabelLine] = []
    @State private var liveBarcode: String?
    @State private var isCapturing = false
    #if os(iOS)
    @State private var handle = LiveTextScanner.Handle()
    #endif

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                #if os(iOS)
                if LiveTextScanner.isSupported {
                    LiveTextScanner(lines: $liveLines, barcode: $liveBarcode, handle: handle)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(alignment: .bottom) { liveOverlay }
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    photoPrompt
                }
                #else
                photoPrompt
                #endif
            }
            .padding()
            .navigationTitle("Scan the Label")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("sheet.cancel")
                }
                ToolbarItem(placement: .primaryAction) {
                    PhotosPicker(selection: $photo, matching: .images) {
                        Label("Choose a Photo", systemImage: "photo")
                    }
                }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task { await read(item) }
            }
        }
        .macSheetFrame(width: 520, height: 560)
    }

    #if os(iOS)
    private var liveOverlay: some View {
        var result = LabelTextParser.parse(liveLines)
        result.barcode = liveBarcode
        let known = liveBarcode.flatMap(knownWine)
        return VStack(spacing: 12) {
            if let known, let onKnownWine {
                KnownWineCard(wine: known) { onKnownWine(known) }
            } else if result.isEmpty {
                Text("Point the camera at the label")
                    .font(.subheadline.weight(.medium))
            } else {
                VStack(spacing: 2) {
                    Text(result.name ?? "–").font(.headline)
                    Text([result.producer, result.vintage.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            Button {
                capture(result)
            } label: {
                if isCapturing {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("Use This Label").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled((result.isEmpty && !acceptsPhotoWithoutText) || isCapturing)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding()
    }

    private func capture(_ result: LabelScanResult) {
        isCapturing = true
        Task {
            let image = try? await handle.scanner?.capturePhoto()
            isCapturing = false
            onResult(result, image?.jpegData(compressionQuality: 0.9))
        }
    }
    #endif

    private var photoPrompt: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 56))
                .foregroundStyle(Color.accentColor)
            Text("Choose a photo of the label")
                .font(.headline)
            Text("The text is read on this device to fill in the name, producer and vintage.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            PhotosPicker(selection: $photo, matching: .images) {
                Text("Choose a Photo").frame(minWidth: 200)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            if isReading { ProgressView() }
            if let lastResult, lastResult.isEmpty, !acceptsPhotoWithoutText {
                Text("No text found. Try a sharper photo.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func read(_ item: PhotosPickerItem) async {
        isReading = true
        defer { isReading = false }
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let lines = await TextRecognizer.lines(in: data)
        let result = LabelTextParser.parse(lines)
        lastResult = result
        if !result.isEmpty || acceptsPhotoWithoutText { onResult(result, data) }
    }
}

/// Vision text recognition on an image, off the main thread.
enum TextRecognizer {
    static func lines(in imageData: Data) async -> [LabelLine] {
        await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            request.recognitionLanguages = ["fr-FR", "en-US", "de-DE", "it-IT", "es-ES"]
            let handler = VNImageRequestHandler(data: imageData)
            do {
                try handler.perform([request])
            } catch {
                return []
            }
            return (request.results ?? []).compactMap { observation in
                guard let text = observation.topCandidates(1).first?.string else { return nil }
                return LabelLine(text: text, height: observation.boundingBox.height)
            }
        }.value
    }
}

#if os(iOS)
/// Live text recognition from the camera (VisionKit).
struct LiveTextScanner: UIViewControllerRepresentable {
    /// Gives the overlay access to the camera to take the photo.
    @MainActor final class Handle {
        weak var scanner: DataScannerViewController?
    }

    @Binding var lines: [LabelLine]
    /// The last barcode seen (EAN, UPC), matched against the cellar on this device only.
    @Binding var barcode: String?
    let handle: Handle

    static var isSupported: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.text(), .barcode(symbologies: [.ean13, .ean8, .upce, .code128])],
            qualityLevel: .accurate,
            recognizesMultipleItems: true,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        handle.scanner = scanner
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(lines: $lines, barcode: $barcode) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let lines: Binding<[LabelLine]>
        let barcode: Binding<String?>

        init(lines: Binding<[LabelLine]>, barcode: Binding<String?>) { self.lines = lines; self.barcode = barcode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            update(allItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            update(allItems)
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didRemove removedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            update(allItems)
        }

        private func update(_ items: [RecognizedItem]) {
            for item in items {
                if case .barcode(let code) = item, let value = code.payloadStringValue,
                   Wine.normalizedBarcode(value).count >= 6, barcode.wrappedValue != value {
                    barcode.wrappedValue = value
                }
            }
            lines.wrappedValue = items.compactMap { item in
                guard case .text(let text) = item else { return nil }
                let height = Double(abs(text.bounds.bottomLeft.y - text.bounds.topLeft.y))
                return LabelLine(text: text.transcript, height: height)
            }
        }
    }
}
#endif

/// "Already in your cellar": shown when the camera sees the barcode of a known wine.
private struct KnownWineCard: View {
    @Environment(CellarModel.self) private var model
    let wine: Wine
    let use: () -> Void

    var body: some View {
        let count = model.cellar.bottles(of: wine.id).count
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                BottleEnd(color: wine.color, size: 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Already in your cellar").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(verbatim: wine.displayName).font(.headline).lineLimit(2)
                    Text("\(count) bottles").font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            Button(action: use) {
                ActionLabel("Use This Wine", systemImage: "checkmark.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("action.scan.known")
        }
        .accessibilityElement(children: .contain)
        .sensoryFeedback(.success, trigger: wine.id)
    }
}
