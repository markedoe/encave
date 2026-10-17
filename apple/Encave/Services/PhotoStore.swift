import CaveKit
import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/// Wine photos stored as JPEG files next to the cellar, resized on the device.
enum PhotoStore {
    static let maxPixelSize = 1600

    static var folder: URL? {
        guard let base = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true) else { return nil }
        let folder = base.appendingPathComponent("Encave/Photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func url(for id: String) -> URL? {
        guard isSafe(id) else { return nil }
        return folder?.appendingPathComponent("\(id).jpg")
    }

    /// Downscales and saves image data; returns the new photo identifier, a hash of
    /// the JPEG, so synced devices can check they received the right photo.
    static func save(_ data: Data) -> String? {
        guard let jpeg = jpegData(from: data), let url = url(for: PhotoID.make(for: jpeg)) else { return nil }
        do {
            try jpeg.write(to: url, options: [.atomic])
            return url.deletingPathExtension().lastPathComponent
        } catch {
            return nil
        }
    }

    /// False when a wine refers to a photo that is not on this device (after importing a backup).
    static func exists(_ id: String?) -> Bool {
        guard let id, let url = url(for: id) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    static func delete(_ id: String?) {
        guard let id, let url = url(for: id) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Removes photo files no wine refers to any more.
    static func removeOrphans(keeping ids: Set<String>) {
        guard let folder,
              let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        else { return }
        for file in files where file.pathExtension == "jpg" && !ids.contains(file.deletingPathExtension().lastPathComponent) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    /// Saves a photo received from another device, as is.
    static func store(_ data: Data, id: String) {
        guard isSafe(id), let url = url(for: id) else { return }
        try? data.write(to: url, options: [.atomic])
    }

    /// Photo IDs come from other devices too: only letters, digits and dashes,
    /// so an ID can never point outside the photo folder.
    static func isSafe(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 80 && id.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
    }

    static func data(for id: String?) -> Data? {
        guard let id, let url = url(for: id) else { return nil }
        return try? Data(contentsOf: url)
    }

    /// Resized JPEG (max 1600 px), with metadata such as location removed.
    static func jpegData(from data: Data, maxPixelSize: Int = maxPixelSize) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }

    static func cgImage(from data: Data, maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}

/// Shows a stored photo, or image data not yet saved.
struct WinePhoto: View {
    var photoID: String?
    var data: Data?
    var maxPixelSize = 900
    /// Show the whole photo (a viewer) instead of filling the frame.
    var fits = false

    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .aspectRatio(contentMode: fits ? .fit : .fill)
            } else {
                Color.secondary.opacity(0.08)
            }
        }
        .task(id: data.map { "\($0.hashValue)" } ?? photoID) {
            let source = data ?? PhotoStore.data(for: photoID)
            let size = maxPixelSize
            image = await Task.detached(priority: .userInitiated) {
                source.flatMap { PhotoStore.cgImage(from: $0, maxPixelSize: size) }
            }.value
        }
    }
}
