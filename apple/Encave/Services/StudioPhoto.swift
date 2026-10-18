import CaveKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import Vision

/// The "studio" look of a label photo: the bottle or the label cut out of its
/// background, straightened and evened out, then shown on a clean backdrop.
///
/// It is made on this device from the label photo, which is kept exactly as
/// taken. The result is only a local cache (each device makes its own), so it is
/// never synced and can be thrown away at any time.
enum StudioPhoto {
    /// Settings key; on by default.
    static let settingKey = "studioPhotos"

    private nonisolated(unsafe) static let context = CIContext()

    private static var folder: URL? {
        guard let base = try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true) else { return nil }
        let folder = base.appendingPathComponent("Encave/Studio", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    /// The cut-out image, transparent around the subject, from the cache or made
    /// now. Nil when nothing stands out clearly: the photo is then shown as taken.
    static func cutout(for photoID: String, maxPixelSize: Int) async -> CGImage? {
        await Task.detached(priority: .utility) {
            studioData(for: photoID).flatMap { PhotoStore.cgImage(from: $0, maxPixelSize: maxPixelSize) }
        }.value
    }

    /// PNG of the studio version. A photo with no usable result is remembered so
    /// it is not tried again.
    private static func studioData(for photoID: String) -> Data? {
        guard PhotoStore.isSafe(photoID), let folder else { return nil }
        let png = folder.appendingPathComponent("\(photoID).png")
        let none = folder.appendingPathComponent("\(photoID).none")
        if let data = try? Data(contentsOf: png) { return data }
        if FileManager.default.fileExists(atPath: none.path) { return nil }
        guard let source = PhotoStore.data(for: photoID),
              let image = PhotoStore.cgImage(from: source, maxPixelSize: 1400) else { return nil }
        if let result = render(image), let data = pngData(result) {
            try? data.write(to: png, options: [.atomic])
            return data
        }
        FileManager.default.createFile(atPath: none.path, contents: nil)
        return nil
    }

    /// The bottle cut out (Vision's subject lifting, as in Photos), or else the
    /// label straightened from its four corners, with automatic light correction.
    private static func render(_ image: CGImage) -> CGImage? {
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        let original = CIImage(cgImage: image)
        let area = original.extent.width * original.extent.height
        var subject: CIImage?

        let lift = VNGenerateForegroundInstanceMaskRequest()
        if (try? handler.perform([lift])) != nil, let observation = lift.results?.first,
           let buffer = try? observation.generateMaskedImage(ofInstances: observation.allInstances, from: handler,
                                                             croppedToInstancesExtent: true) {
            let cut = CIImage(cvPixelBuffer: buffer)
            let share = cut.extent.width * cut.extent.height / max(area, 1)
            // A "subject" filling almost the whole photo is the background itself.
            if share > 0.04, share < 0.9 { subject = cut }
        }

        if subject == nil {
            let rectangles = VNDetectRectanglesRequest()
            rectangles.maximumObservations = 1
            rectangles.minimumSize = 0.3
            rectangles.minimumAspectRatio = 0.3
            rectangles.quadratureTolerance = 25
            if (try? handler.perform([rectangles])) != nil, let quad = rectangles.results?.first {
                let size = original.extent.size
                func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * size.width, y: p.y * size.height) }
                let correction = CIFilter.perspectiveCorrection()
                correction.inputImage = original
                correction.topLeft = point(quad.topLeft)
                correction.topRight = point(quad.topRight)
                correction.bottomLeft = point(quad.bottomLeft)
                correction.bottomRight = point(quad.bottomRight)
                subject = correction.outputImage
            }
        }

        guard var result = subject else { return nil }
        for filter in result.autoAdjustmentFilters(options: [.redEye: false]) {
            filter.setValue(result, forKey: kCIInputImageKey)
            result = filter.outputImage ?? result
        }
        return context.createCGImage(result, from: result.extent)
    }

    private static func pngData(_ image: CGImage) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}

/// A wine's label photo: the studio version on a backdrop tinted by the wine's
/// color, or the photo as taken when the setting is off or nothing stands out.
struct LabelPhoto: View {
    let photoID: String
    var color: WineColor?
    var maxPixelSize = 900
    /// Inner margin around the cut-out.
    var inset: CGFloat = 12

    @AppStorage(StudioPhoto.settingKey) private var studio = true
    @State private var cutout: CGImage?

    var body: some View {
        Group {
            if studio, let cutout {
                ZStack {
                    Rectangle().fill(.background.secondary)
                    LinearGradient(colors: [(color?.tint ?? .accentColor).opacity(0.22), .clear],
                                   startPoint: .top, endPoint: .bottom)
                    Image(decorative: cutout, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .shadow(color: .black.opacity(0.18), radius: inset / 2, y: inset / 4)
                        .padding(inset)
                }
            } else {
                WinePhoto(photoID: photoID, maxPixelSize: maxPixelSize)
            }
        }
        .task(id: studio ? photoID : "") {
            cutout = studio ? await StudioPhoto.cutout(for: photoID, maxPixelSize: maxPixelSize) : nil
        }
    }
}
