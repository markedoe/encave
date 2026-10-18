import CaveKit
import SwiftUI
#if canImport(ImagePlayground)
import ImagePlayground
#endif

/// Chooses the picture shown for a wine: the label photo in its studio look, an
/// illustration made on the device (Image Playground), or a picture found on the
/// internet. The label photo itself is always kept.
struct WineImagePicker: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let wineID: UUID
    @State private var creating = false
    @State private var working = false

    var body: some View {
        NavigationStack {
            Group {
            if let wine = model.cellar.wines[wineID] {
                List {
                    Section {
                        WineImage(wine: wine, inset: 18)
                            .frame(height: 240)
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .listRowInsets(EdgeInsets())
                            .accessibilityHidden(true)
                    }

                    if let photoID = wine.photoID, PhotoStore.exists(photoID) {
                        Section {
                            Button {
                                setCover(nil)
                            } label: {
                                Label("Use the Label Photo", systemImage: wine.coverPhotoID == nil ? "checkmark.circle.fill" : "photo")
                            }
                            .accessibilityAddTraits(wine.coverPhotoID == nil ? .isSelected : [])
                        } footer: {
                            Text("Shown in the studio look when it is on in Settings: cut out and straightened on a clean background.")
                        }
                    }

                    IfIllustrationAvailable {
                        Section {
                            Button {
                                creating = true
                            } label: {
                                if working {
                                    HStack { ProgressView(); Text("Adding…") }
                                } else {
                                    Label("Create an Illustration…", systemImage: "apple.image.playground")
                                }
                            }
                            .disabled(working)
                        } footer: {
                            Text("Made on this device with Image Playground, from the wine's name and its label photo.")
                        }
                    }

                    WebImageSection(wine: wine) { data in
                        await useImage(data)
                    }

                    if wine.coverPhotoID != nil {
                        Section {
                            Button("Remove This Picture", systemImage: "trash", role: .destructive) { setCover(nil) }
                        }
                    }
                }
                .modifier(IllustrationSheet(isPresented: $creating, wine: wine) { data in
                    Task { @MainActor in await useImage(data) }
                })
            } else {
                ContentUnavailableView("Wine not found", systemImage: "questionmark")
            }
            }
            .navigationTitle("Wine Picture")
            .inlineTitle()
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .macSheetFrame()
    }

    private func useImage(_ data: Data) async {
        working = true
        defer { working = false }
        let id = await Task.detached(priority: .userInitiated) { PhotoStore.save(data) }.value
        if let id { setCover(id) }
    }

    /// The previous picture file stays a while (PhotoStore.orphanGracePeriod).
    private func setCover(_ id: String?) {
        model.update { cellar in
            guard var wine = cellar.wines[wineID], wine.coverPhotoID != id else { return }
            wine.coverPhotoID = id
            try cellar.updateWine(wine)
        }
    }
}

/// Pictures found on the internet, when a search service is set up in Settings.
/// Filled in by the web search feature; shows nothing until then.
struct WebImageSection: View {
    let wine: Wine
    let onPick: @MainActor (Data) async -> Void

    var body: some View {
        EmptyView()
    }
}

/// Shows its content only where Image Playground can run (Apple Intelligence,
/// iOS 18.1, macOS 15.1).
private struct IfIllustrationAvailable<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        #if canImport(ImagePlayground)
        if #available(iOS 18.1, macOS 15.1, *) {
            Supported(content: content)
        }
        #endif
    }

    #if canImport(ImagePlayground)
    @available(iOS 18.1, macOS 15.1, *)
    private struct Supported: View {
        @Environment(\.supportsImagePlayground) private var supports
        let content: () -> Content

        var body: some View {
            if supports { content() }
        }
    }
    #endif
}

/// Presents Image Playground with the wine as concepts and its label photo as the
/// starting image; hands back the created image's data.
private struct IllustrationSheet: ViewModifier {
    @Binding var isPresented: Bool
    let wine: Wine
    let onCreate: (Data) -> Void

    func body(content: Content) -> some View {
        #if canImport(ImagePlayground)
        if #available(iOS 18.1, macOS 15.1, *) {
            content.imagePlaygroundSheet(
                isPresented: $isPresented,
                concepts: concepts,
                sourceImage: labelImage,
                onCompletion: { url in
                    if let data = try? Data(contentsOf: url) { onCreate(data) }
                }
            )
        } else {
            content
        }
        #else
        content
        #endif
    }

    #if canImport(ImagePlayground)
    @available(iOS 18.1, macOS 15.1, *)
    private var concepts: [ImagePlaygroundConcept] {
        let style = wine.color.map { "\($0.rawValue) wine" } ?? "wine"
        var parts = ["A bottle of \(style)", wine.displayName]
        if let producer = wine.producer { parts.append(producer) }
        if let origin = wine.appellation ?? wine.region { parts.append(origin) }
        return [.text(parts.joined(separator: ", "))]
    }
    #endif

    private var labelImage: Image? {
        guard let data = PhotoStore.data(for: wine.photoID),
              let image = PhotoStore.cgImage(from: data, maxPixelSize: 1024) else { return nil }
        return Image(decorative: image, scale: 1)
    }
}
