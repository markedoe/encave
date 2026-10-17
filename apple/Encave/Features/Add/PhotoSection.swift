import PhotosUI
import SwiftUI

/// Photo of a wine in a form: show, choose, replace or remove it.
struct PhotoSection: View {
    /// Photo already stored for the wine, if any.
    let photoID: String?
    /// New photo chosen in this form (resized JPEG), not saved yet.
    @Binding var newPhoto: Data?
    @Binding var removed: Bool

    @State private var item: PhotosPickerItem?

    private var hasPhoto: Bool { newPhoto != nil || (PhotoStore.exists(photoID) && !removed) }

    var body: some View {
        Section("Photo") {
            if hasPhoto {
                WinePhoto(photoID: removed ? nil : photoID, data: newPhoto)
                    .frame(height: 200)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel(Text("Label photo"))
            }
            PhotosPicker(selection: $item, matching: .images) {
                if hasPhoto {
                    Label("Replace Photo", systemImage: "photo")
                } else {
                    Label("Add a Photo", systemImage: "photo")
                }
            }
            if hasPhoto {
                Button("Remove Photo", systemImage: "trash", role: .destructive) {
                    newPhoto = nil
                    removed = true
                }
            }
        }
        .onChange(of: item) { _, selected in
            guard let selected else { return }
            Task {
                if let jpeg = await PhotoStore.resized(selected) {
                    newPhoto = jpeg
                    removed = false
                }
                item = nil
            }
        }
    }
}

extension PhotoStore {
    /// Loads a picked photo and resizes it off the main thread.
    static func resized(_ item: PhotosPickerItem) async -> Data? {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return nil }
        return await resized(data)
    }

    static func resized(_ data: Data) async -> Data? {
        await Task.detached(priority: .userInitiated) { PhotoStore.jpegData(from: data) }.value
    }
}
