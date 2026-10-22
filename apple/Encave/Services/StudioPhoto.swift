import CaveKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import Vision

/// The "studio" look of a label photo: the bottle cut out of its background and
/// framed on its label (a small part of the bottle around it), or a label
/// straightened from its corners, with the light evened out; shown on a backdrop
/// in the wine's color.
///
/// It is made on this device from the label photo, which is kept exactly as
/// taken, with Vision and Core Image only (nothing is sent anywhere). The result is
/// only a local cache (each device makes its own), so it is never synced and can
/// be thrown away at any time.
enum StudioPhoto {
    /// Settings key; on by default.
    static let settingKey = "studioPhotos"

    private static let context = CIContext()

    /// A new folder when the look changes, so every device makes its pictures again
    /// (photos that gave nothing before are tried again too). Older folders are removed.
    private static let folderName = "Encave/Studio2"
    private static let oldFolderNames = ["Encave/Studio"]

    private static let folder: URL? = {
        guard let base = try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
                                                       appropriateFor: nil, create: true) else { return nil }
        for old in oldFolderNames {
            try? FileManager.default.removeItem(at: base.appendingPathComponent(old, isDirectory: true))
        }
        let folder = base.appendingPathComponent(folderName, isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }()

    /// Makes the studio versions one at a time, a photo asked for by several views
    /// only once, so a long list of new photos does not run Vision on all of them at once.
    private static let renderer = Renderer()

    /// The cut-out image, transparent around the subject, from the cache or made
    /// now. Nil when nothing stands out clearly: the photo is then shown as taken.
    static func cutout(for photoID: String, maxPixelSize: Int) async -> CGImage? {
        let cached = await Task.detached(priority: .utility) { cachedData(for: photoID) }.value
        let made: Data?
        if let cached {
            made = cached
        } else {
            made = await renderer.data(for: photoID)
        }
        guard let data = made else { return nil }
        return await Task.detached(priority: .utility) { PhotoStore.cgImage(from: data, maxPixelSize: maxPixelSize) }.value
    }

    /// The cached PNG (`.some(nil)` when the photo gave nothing before), or nil when not made yet.
    private static func cachedData(for photoID: String) -> Data?? {
        guard PhotoStore.isSafe(photoID), let folder else { return .some(nil) }
        if let data = try? Data(contentsOf: folder.appendingPathComponent("\(photoID).png")) { return data }
        if FileManager.default.fileExists(atPath: folder.appendingPathComponent("\(photoID).none").path) { return .some(nil) }
        return nil
    }

    /// PNG of the studio version, made and cached now. A photo with no usable result
    /// is remembered so it is not tried again.
    private static func studioData(for photoID: String) -> Data? {
        if let cached = cachedData(for: photoID) { return cached }
        guard let folder, PhotoStore.exists(photoID) else { return nil }
        if let data = make(photoID) {
            try? data.write(to: folder.appendingPathComponent("\(photoID).png"), options: [.atomic])
            return data
        }
        FileManager.default.createFile(atPath: folder.appendingPathComponent("\(photoID).none").path, contents: nil)
        return nil
    }

    private actor Renderer {
        private var running: [String: Task<Data?, Never>] = [:]
        private var last: Task<Data?, Never>?

        func data(for photoID: String) async -> Data? {
            if let task = running[photoID] { return await task.value }
            let previous = last
            let task = Task.detached(priority: .utility) { () -> Data? in
                _ = await previous?.value
                return StudioPhoto.studioData(for: photoID)
            }
            running[photoID] = task
            last = task
            let result = await task.value
            running[photoID] = nil
            return result
        }
    }

    private static func make(_ photoID: String) -> Data? {
        guard let source = PhotoStore.data(for: photoID),
              let image = PhotoStore.cgImage(from: source, maxPixelSize: PhotoStore.maxPixelSize),
              let result = render(image) else { return nil }
        return pngData(result)
    }

    /// The bottle cut out (Vision's subject lifting, as in Photos) and framed on its
    /// label, or else the label straightened from its four corners, with automatic
    /// light correction.
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
            if share > 0.04, share < 0.9 { subject = LabelFrame.crop(cut, context: context) }
        }

        if subject == nil {
            let rectangles = VNDetectRectanglesRequest()
            rectangles.maximumObservations = 1
            rectangles.minimumSize = 0.3
            rectangles.minimumAspectRatio = 0.3
            rectangles.maximumAspectRatio = 1
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

/// Frames a cut-out bottle on its label, so the label fills the small pictures of
/// the lists (the rules are in CaveKit's `LabelFraming`, tested on every platform).
///
/// The label is the text read on the bottle (on the device, without language
/// correction); without text, a rectangle on the bottle; without either, the part
/// of a bottle where labels sit. A cut-out that is not a standing bottle (a
/// close-up of a label, several bottles), or mostly covered with text, is left whole.
enum LabelFrame {
    static func crop(_ cut: CIImage, context: CIContext) -> CIImage {
        let extent = cut.extent
        let width = Double(extent.width), height = Double(extent.height)
        guard LabelFraming.isStandingBottle(width: width, height: height) else { return cut }
        // Vision reads the bottle on a plain gray background, in the cut-out's coordinates.
        let gray = CIImage(color: CIColor(red: 0.5, green: 0.5, blue: 0.5)).cropped(to: extent)
        guard let flat = context.createCGImage(cut.composited(over: gray), from: extent) else { return cut }
        let handler = VNImageRequestHandler(cgImage: flat, options: [:])
        let band: ClosedRange<Double>
        if let text = LabelFraming.label(lines: textLines(handler)) {
            if text.coverage > LabelFraming.closeUpTextCoverage { return cut }
            band = text.band
        } else {
            band = rectangleBand(handler) ?? LabelFraming.fallbackBand
        }
        guard let frame = LabelFraming.frame(label: band, width: width, height: height) else { return cut }
        let below = CGFloat(1 - frame.upperBound), kept = CGFloat(frame.upperBound - frame.lowerBound)
        let rect = CGRect(x: extent.minX, y: extent.minY + extent.height * below,
                          width: extent.width, height: extent.height * kept)
        var result = cut.cropped(to: rect)
        // Soft edges where the bottle is cut, so it fades into the backdrop.
        let fade = rect.height * 0.08
        if frame.lowerBound > 0 { result = faded(result, edge: rect.maxY, inner: rect.maxY - fade) }
        if frame.upperBound < 1 { result = faded(result, edge: rect.minY, inner: rect.minY + fade) }
        return result
    }

    /// The lines of text on the bottle, read on the device (fast, no language correction).
    private static func textLines(_ handler: VNImageRequestHandler) -> [LabelFraming.Line] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        guard (try? handler.perform([request])) != nil else { return [] }
        let observations: [VNRecognizedTextObservation] = request.results ?? []
        return observations.map { observation -> LabelFraming.Line in
            let box = observation.boundingBox
            return LabelFraming.Line(minY: Double(box.minY), maxY: Double(box.maxY), width: Double(box.width))
        }
    }

    /// A rectangle across most of the bottle's width: a label without text.
    private static func rectangleBand(_ handler: VNImageRequestHandler) -> ClosedRange<Double>? {
        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 4
        request.minimumSize = 0.2
        request.minimumAspectRatio = 0.2
        request.maximumAspectRatio = 1
        request.quadratureTolerance = 30
        guard (try? handler.perform([request])) != nil else { return nil }
        let boxes: [CGRect] = (request.results ?? []).map { $0.boundingBox }
        var best: CGRect?
        for box in boxes where box.width >= 0.5 && box.height < 0.5 {
            if let current = best, current.width * current.height >= box.width * box.height { continue }
            best = box
        }
        guard let box = best else { return nil }
        let top = Double(1 - box.maxY), bottom = Double(1 - box.minY)
        return top...bottom
    }

    /// The image fading out towards `edge` (a y coordinate), fully shown from `inner`.
    private static func faded(_ image: CIImage, edge: CGFloat, inner: CGFloat) -> CIImage {
        let gradient = CIFilter.linearGradient()
        gradient.point0 = CGPoint(x: 0, y: edge)
        gradient.color0 = CIColor(red: 0, green: 0, blue: 0, alpha: 0)
        gradient.point1 = CGPoint(x: 0, y: inner)
        gradient.color1 = CIColor(red: 1, green: 1, blue: 1, alpha: 1)
        guard let mask = gradient.outputImage?.cropped(to: image.extent) else { return image }
        let blend = CIFilter.sourceInCompositing()
        blend.inputImage = image
        blend.backgroundImage = mask
        return blend.outputImage ?? image
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
                    StudioBackdrop(color: color)
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

/// The backdrop behind studio pictures and covers: a gradient in the wine's color
/// with a soft light behind the picture (so its shadow reads in light and dark
/// mode), and a discreet bunch of grapes and vine leaves in the corners when there
/// is room. Drawn here; the same drawing as on Android (StudioBackdrop.kt).
struct StudioBackdrop: View {
    var color: WineColor?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let rect = CGRect(origin: .zero, size: size)
            let tint = color?.tint ?? .accentColor
            let dark = scheme == .dark
            context.fill(Path(rect), with: .color(Color("StudioBackdrop")))
            context.fill(Path(rect), with: .linearGradient(
                Gradient(colors: [tint.opacity(dark ? 0.42 : 0.5), tint.opacity(dark ? 0.14 : 0.16)]),
                startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            VineArt.draw(in: context, size: size, ink: tint.opacity(dark ? 0.34 : 0.28),
                         light: Color("StudioGlow").opacity(0.55), vein: Color("StudioBackdrop").opacity(0.5))
            context.fill(Path(rect), with: .radialGradient(
                Gradient(colors: [Color("StudioGlow").opacity(dark ? 0.7 : 0.75), Color("StudioGlow").opacity(0)]),
                center: CGPoint(x: size.width / 2, y: size.height * 0.46),
                startRadius: 0, endRadius: max(size.width, size.height) * 0.48))
        }
        .accessibilityHidden(true)
    }
}

/// A bunch of grapes on its cane with a vine leaf and a tendril (bottom right), and
/// a lone leaf (top left), drawn in a 100 × 100 box. The numbers are the same in
/// StudioBackdrop.kt on Android.
enum VineArt {
    /// No art in small pictures (list rows), where it would only be noise.
    static let minimumSide: CGFloat = 90

    static func draw(in context: GraphicsContext, size: CGSize, ink: Color, light: Color, vein: Color) {
        guard min(size.width, size.height) >= minimumSide else { return }
        let side = min(size.height * 0.78, size.width * 0.5)
        var corner = context
        corner.translateBy(x: size.width - side * 0.82, y: size.height - side * 0.92)
        corner.scaleBy(x: side / 100, y: side / 100)
        let stroke = StrokeStyle(lineWidth: 2.2, lineCap: .round)
        // The cane, the bunch's stem and a tendril.
        var cane = Path()
        cane.move(to: CGPoint(x: 40, y: 8))
        cane.addQuadCurve(to: CGPoint(x: 98, y: 12), control: CGPoint(x: 60, y: 2))
        cane.move(to: CGPoint(x: 56, y: 6))
        cane.addLine(to: CGPoint(x: 52, y: 24))
        corner.stroke(cane, with: .color(ink), style: stroke)
        corner.stroke(Path { $0.addLines(tendril) }, with: .color(ink), style: StrokeStyle(lineWidth: 1.4, lineCap: .round))
        for grape in grapes {
            corner.fill(Path(ellipseIn: CGRect(x: grape.x - 6, y: grape.y - 6, width: 12, height: 12)), with: .color(ink))
            corner.fill(Path(ellipseIn: CGRect(x: grape.x - 3.8, y: grape.y - 3.8, width: 3.6, height: 3.6)), with: .color(light))
        }
        leaf(in: corner, center: CGPoint(x: 74, y: 30), radius: 19, rotation: -25, ink: ink, vein: vein)

        var top = context
        top.scaleBy(x: side / 100, y: side / 100)
        leaf(in: top, center: CGPoint(x: 10, y: 12), radius: 17, rotation: 155, ink: ink, vein: vein)
    }

    /// Grapes in rows of 4, 5, 4, 4, 3, 2 and 1, each row a little to the left.
    static let grapes: [CGPoint] = {
        var points: [CGPoint] = []
        for (row, count) in [4, 5, 4, 4, 3, 2, 1].enumerated() {
            let y: CGFloat = 28 + CGFloat(row) * 10.4
            let start: CGFloat = 52 - CGFloat(row) * 0.6 - CGFloat(count - 1) * 6
            for index in 0..<count { points.append(CGPoint(x: start + CGFloat(index) * 12, y: y)) }
        }
        return points
    }()

    /// A spiral of 1.5 turns, starting at the cane.
    static let tendril: [CGPoint] = (0...40).map { step in
        let t: CGFloat = CGFloat(step) * 3 * CGFloat.pi / 40
        let r: CGFloat = 7 - 7 * t / (3.6 * CGFloat.pi)
        return CGPoint(x: 34 + r * cos(t), y: 14 + r * sin(t))
    }

    /// The outline of a five-lobed leaf, with a notch where the stem joins; angles
    /// are measured from the tip, clockwise.
    static func leafOutline(center: CGPoint, radius: CGFloat, rotation: CGFloat) -> [CGPoint] {
        (0..<90).map { step in
            let theta: CGFloat = CGFloat(step) * 2 * CGFloat.pi / 90
            let distance: CGFloat = (theta - CGFloat.pi) / 0.25
            let notch: CGFloat = 1 - 0.45 * exp(-distance * distance)
            let lobes: CGFloat = 0.78 + 0.22 * cos(5 * theta)
            let r: CGFloat = radius * lobes * notch
            return point(center, r, theta, rotation)
        }
    }

    static func point(_ center: CGPoint, _ r: CGFloat, _ theta: CGFloat, _ rotation: CGFloat) -> CGPoint {
        let angle: CGFloat = theta + rotation * CGFloat.pi / 180
        return CGPoint(x: center.x + r * sin(angle), y: center.y - r * cos(angle))
    }

    private static func leaf(in context: GraphicsContext, center: CGPoint, radius: CGFloat, rotation: CGFloat,
                             ink: Color, vein: Color) {
        context.fill(Path { $0.addLines(leafOutline(center: center, radius: radius, rotation: rotation)); $0.closeSubpath() },
                     with: .color(ink))
        var veins = Path()
        for lobe in [0, 1, -1, 2, -2] {
            veins.move(to: point(center, radius * 0.45, .pi, rotation))
            veins.addLine(to: point(center, radius * 0.85, CGFloat(lobe) * 2 * .pi / 5, rotation))
        }
        context.stroke(veins, with: .color(vein), style: StrokeStyle(lineWidth: 0.9, lineCap: .round))
    }
}

/// The picture shown for a wine: its cover (from the internet or an illustration)
/// if it has one, else its label photo in the studio look. Callers check that
/// `wine.shownPhotoID` exists first and show the bottle end otherwise.
struct WineImage: View {
    let wine: Wine
    var maxPixelSize = 900
    var inset: CGFloat = 12

    var body: some View {
        if let cover = wine.coverPhotoID, PhotoStore.exists(cover) {
            ZStack {
                StudioBackdrop(color: wine.color)
                WinePhoto(photoID: cover, maxPixelSize: maxPixelSize, fits: true)
                    .padding(inset / 2)
            }
        } else if let photoID = wine.photoID {
            LabelPhoto(photoID: photoID, color: wine.color, maxPixelSize: maxPixelSize, inset: inset)
        }
    }
}

extension Wine {
    /// True when there is a picture to show on this device.
    var hasShownPhoto: Bool {
        PhotoStore.exists(coverPhotoID) || PhotoStore.exists(photoID)
    }
}
