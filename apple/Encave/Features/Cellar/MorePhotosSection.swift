import CaveKit
import PhotosUI
import SwiftUI

/// More photos of a wine (back label, receipt, the bottle in its rack), beside the
/// label photo: a strip of thumbnails, tap one to see it large.
struct MorePhotosSection: View {
    @Environment(CellarModel.self) private var model
    let wine: Wine
    @State private var item: PhotosPickerItem?
    @State private var shown: String?
    @State private var adding = false

    var body: some View {
        let photos = (wine.extraPhotoIDs ?? []).filter { PhotoStore.exists($0) }
        Section {
            if !photos.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(photos, id: \.self) { id in
                            Button {
                                shown = id
                            } label: {
                                WinePhoto(photoID: id, maxPixelSize: 400)
                                    .frame(width: 96, height: 128)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Photo"))
                            .accessibilityHint(Text("Shows the photo large."))
                            .contextMenu {
                                Button("Remove Photo", systemImage: "trash", role: .destructive) { remove(id) }
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            PhotosPicker(selection: $item, matching: .images) {
                if adding {
                    HStack { ProgressView(); Text("Adding…") }
                } else {
                    Label("Add a Photo", systemImage: "photo.badge.plus")
                }
            }
            .disabled(adding)
        } header: {
            Text("More photos")
        } footer: {
            Text("The back label, the receipt, the bottle in its rack. They sync with your devices, encrypted.")
        }
        .onChange(of: item) { _, selected in
            guard let selected else { return }
            adding = true
            Task {
                defer { adding = false; item = nil }
                guard let data = try? await selected.loadTransferable(type: Data.self) else { return }
                let id = await Task.detached(priority: .userInitiated) { PhotoStore.save(data) }.value
                guard let id else { return }
                model.update { cellar in
                    guard var updated = cellar.wines[wine.id] else { return }
                    updated.extraPhotoIDs = (updated.extraPhotoIDs ?? []) + [id]
                    try cellar.updateWine(updated)
                }
            }
        }
        .sheet(item: Binding(get: { shown.map(ShownPhoto.init) }, set: { shown = $0?.id })) { photo in
            NavigationStack {
                WinePhoto(photoID: photo.id, maxPixelSize: 1600, fits: true)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel(Text("Photo"))
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { shown = nil }
                        }
                        ToolbarItem(placement: .destructiveAction) {
                            Button("Remove Photo", systemImage: "trash", role: .destructive) {
                                remove(photo.id)
                                shown = nil
                            }
                        }
                    }
            }
            .macSheetFrame(width: 640, height: 720)
        }
    }

    private func remove(_ id: String) {
        model.update { cellar in
            guard var updated = cellar.wines[wine.id] else { return }
            let left = (updated.extraPhotoIDs ?? []).filter { $0 != id }
            updated.extraPhotoIDs = left.isEmpty ? nil : left
            try cellar.updateWine(updated)
        }
    }
}

private struct ShownPhoto: Identifiable {
    let id: String
}
