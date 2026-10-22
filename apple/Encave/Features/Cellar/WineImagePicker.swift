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
                            Text("Shown in the studio look when it is on in Settings: cut out and framed on its label, on a backdrop in the wine's color.")
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

/// Pictures found on the internet, when a search service is set up in Settings. The
/// search terms are shown and editable, and sent only on Search Pictures; the chosen
/// picture is downloaded from its host and becomes the cover. The label photo is kept.
struct WebImageSection: View {
    let wine: Wine
    let onPick: @MainActor (Data) async -> Void

    @State private var settings = WebSearchSettings.load()
    @State private var query = ""
    @State private var images: [WebImageResult] = []
    @State private var searching = false
    @State private var searched = false
    @State private var problem: String?
    @State private var downloading: String?

    var body: some View {
        if settings.isEnabled {
            Section {
                TextField("Search terms", text: $query)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit(search)
                Button(action: search) {
                    if searching {
                        HStack { ProgressView(); Text("Searching…") }
                    } else {
                        Label("Search Pictures", systemImage: "photo.on.rectangle.angled")
                    }
                }
                .disabled(searching || query.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("picture.search")
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                }
                if searched, images.isEmpty, problem == nil, !searching {
                    Text("No pictures found. Try fewer words.")
                        .foregroundStyle(.secondary)
                }
                if !images.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 12) {
                        ForEach(images) { image in
                            thumbnail(image)
                        }
                    }
                    .padding(.vertical, 4)
                }
            } header: {
                Text("From the Internet")
            } footer: {
                let serviceName = settings.service.name
                Text("Only the search terms are sent to \(serviceName). The thumbnails and the picture you choose are downloaded from the sites that host them, which see the request and your IP address. Pictures from shops are for your private use. The label photo is kept.")
            }
            .onAppear {
                if query.isEmpty {
                    query = WebSearchQuery.pictureTerms(for: wine, bottleWord: String(localized: "bottle", comment: "Added to picture search terms"))
                }
            }
        }
    }

    private func thumbnail(_ image: WebImageResult) -> some View {
        Button {
            pick(image)
        } label: {
            VStack(spacing: 4) {
                Color.clear
                    .frame(height: 120)
                    .overlay {
                        AsyncImage(url: image.thumbnailURL) { phase in
                            switch phase {
                            case .success(let picture):
                                picture.resizable().scaledToFill()
                            case .failure:
                                Image(systemName: "photo").foregroundStyle(.secondary)
                            default:
                                ProgressView()
                            }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay {
                        if downloading == image.id {
                            ProgressView()
                                .padding(8)
                                .background(.regularMaterial, in: Capsule())
                        }
                    }
                Text(verbatim: image.domain)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.plain)
        .disabled(downloading != nil)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: image.title.isEmpty ? image.domain : "\(image.title), \(image.domain)"))
        .accessibilityHint(Text("Uses this picture for the wine."))
        .accessibilityAddTraits(.isButton)
    }

    private func search() {
        let terms = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !terms.isEmpty, !searching else { return }
        searching = true
        problem = nil
        let settings = settings
        Task {
            defer { searching = false }
            do {
                images = try await WebSearchClient.searchImages(terms, settings: settings)
            } catch {
                images = []
                problem = WebSearchClient.message(for: error)
            }
            searched = true
        }
    }

    private func pick(_ image: WebImageResult) {
        guard downloading == nil else { return }
        downloading = image.id
        problem = nil
        Task {
            defer { downloading = nil }
            do {
                let data = try await WebSearchClient.downloadImage(image.imageURL)
                await onPick(data)
            } catch {
                problem = String(localized: "This picture could not be downloaded. Try another one.")
            }
        }
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
