import CaveKit
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreVideo
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import Vision

/// The "studio" look of a label photo, made on this device from the label photo
/// (kept exactly as taken) with Vision and Core Image only; nothing is sent
/// anywhere. In order:
/// - the photo is first turned so the label's text is level (up to 25°);
/// - a clear cut-out of a standing bottle is framed on its label, the label about
///   80% of the picture, the glass running off its top and bottom edges;
/// - a clear cut-out of anything else (a label held up, a box) is shown whole;
/// - without a clear cut-out, a label in a rectangle is straightened, else the photo
///   is shown filling the picture, centred on its label, with its light evened out.
///
/// The result is only a local cache (each device makes its own), so it is never
/// synced and can be thrown away at any time.
enum StudioPhoto {
    /// Settings key; on by default.
    static let settingKey = "studioPhotos"

    /// How a studio picture is shown.
    enum Kind: String, CaseIterable, Sendable {
        /// A cut-out shown whole on the backdrop, with a soft shadow.
        case cutout
        /// A bottle framed on its label: as tall as the picture, its glass running
        /// off the top and bottom edges, the backdrop on its sides.
        case label
        /// A photo filling the picture, centred on its label, with a light vignette.
        case photo
    }

    struct Picture: @unchecked Sendable {
        var image: CGImage
        var kind: Kind
    }

    private static let context = CIContext()

    /// A new folder when the look changes, so every device makes its pictures again
    /// (photos that gave nothing before are tried again too). Older folders are removed.
    private static let folderName = "Encave/Studio3"
    private static let oldFolderNames = ["Encave/Studio", "Encave/Studio2"]

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

    /// The studio picture, from the cache or made now. Nil when the photo cannot be
    /// read: it is then shown as taken.
    static func picture(for photoID: String, maxPixelSize: Int) async -> Picture? {
        let cached = await Task.detached(priority: .utility) { cachedData(for: photoID) }.value
        let made: Stored?
        if let cached {
            made = cached
        } else {
            made = await renderer.data(for: photoID)
        }
        guard let stored = made else { return nil }
        return await Task.detached(priority: .utility) {
            PhotoStore.cgImage(from: stored.data, maxPixelSize: maxPixelSize).map { Picture(image: $0, kind: stored.kind) }
        }.value
    }

    /// A PNG and how to show it; the kind is in the file name.
    struct Stored: Sendable {
        var data: Data
        var kind: Kind
    }

    private static func file(_ photoID: String, _ kind: Kind) -> URL? {
        folder?.appendingPathComponent("\(photoID).\(kind.rawValue).png")
    }

    /// The cached picture (`.some(nil)` when the photo gave nothing before), or nil when not made yet.
    private static func cachedData(for photoID: String) -> Stored?? {
        guard PhotoStore.isSafe(photoID), let folder else { return .some(nil) }
        for kind in Kind.allCases {
            if let url = file(photoID, kind), let data = try? Data(contentsOf: url) { return Stored(data: data, kind: kind) }
        }
        if FileManager.default.fileExists(atPath: folder.appendingPathComponent("\(photoID).none").path) { return .some(nil) }
        return nil
    }

    /// The studio picture, made and cached now. A photo that cannot be read is
    /// remembered so it is not tried again.
    private static func studioData(for photoID: String) -> Stored? {
        if let cached = cachedData(for: photoID) { return cached }
        guard let folder, PhotoStore.exists(photoID) else { return nil }
        if let source = PhotoStore.data(for: photoID),
           let image = PhotoStore.cgImage(from: source, maxPixelSize: PhotoStore.maxPixelSize),
           let rendered = render(image), let data = pngData(rendered.image) {
            if let url = file(photoID, rendered.kind) { try? data.write(to: url, options: [.atomic]) }
            return Stored(data: data, kind: rendered.kind)
        }
        FileManager.default.createFile(atPath: folder.appendingPathComponent("\(photoID).none").path, contents: nil)
        return nil
    }

    private actor Renderer {
        private var running: [String: Task<Stored?, Never>] = [:]
        private var last: Task<Stored?, Never>?

        func data(for photoID: String) async -> Stored? {
            if let task = running[photoID] { return await task.value }
            let previous = last
            let task = Task.detached(priority: .utility) { () -> Stored? in
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

    // MARK: Making the picture

    private static func render(_ image: CGImage) -> Picture? {
        let level = straightened(image)
        guard let upright = context.createCGImage(level, from: level.extent) else { return nil }
        let handler = VNImageRequestHandler(cgImage: upright, options: [:])
        let photo = CIImage(cgImage: upright)
        var made: (picture: CIImage, kind: Kind)
        if let cut = liftedSubject(handler, photo) {
            if let framed = LabelFrame.crop(cut, context: context) {
                made = (framed, .label)
            } else {
                made = (cut, .cutout)
            }
        } else {
            made = LabelFrame.withoutCutout(photo, handler: handler)
        }
        // Only a photo shown as it is gets its light evened out: a cut-out keeps the label's own colors.
        let result = made.kind == .photo ? evenedOut(made.picture) : made.picture
        guard let output = context.createCGImage(result, from: result.extent) else { return nil }
        return Picture(image: output, kind: made.kind)
    }

    /// The photo turned so the lines of text read on it are level (their median
    /// angle, between 1.5° and 25°); as it is otherwise.
    private static func straightened(_ image: CGImage) -> CIImage {
        let photo = CIImage(cgImage: image)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        guard (try? handler.perform([request])) != nil else { return photo }
        let width = Double(image.width), height = Double(image.height)
        var angles: [Double] = []
        for line in request.results ?? [] where line.boundingBox.width >= 0.08 {
            let dx = Double(line.topRight.x - line.topLeft.x) * width
            let dy = Double(line.topRight.y - line.topLeft.y) * height
            angles.append(atan2(dy, dx) * 180 / .pi)
        }
        guard let degrees = LabelFraming.straightening(lineAngles: angles) else { return photo }
        let center = CGPoint(x: photo.extent.midX, y: photo.extent.midY)
        let turn = CGAffineTransform(translationX: center.x, y: center.y)
            .rotated(by: CGFloat(degrees * .pi / 180))
            .translatedBy(x: -center.x, y: -center.y)
        // Without the empty corners the turn leaves.
        let slant = abs(sin(CGFloat(degrees * .pi / 180)))
        let inner = photo.extent.insetBy(dx: photo.extent.height * slant / 2, dy: photo.extent.width * slant / 2)
        return photo.transformed(by: turn).cropped(to: inner)
    }

    /// The largest thing that stands out, cut out with a softened edge (about a
    /// pixel), or nil when the cut-out is not clear: low confidence, nearly the
    /// whole photo or a tiny part of it, or a ragged shape that fills little of its frame.
    private static func liftedSubject(_ handler: VNImageRequestHandler, _ photo: CIImage) -> CIImage? {
        let lift = VNGenerateForegroundInstanceMaskRequest()
        guard (try? handler.perform([lift])) != nil, let observation = lift.results?.first,
              observation.confidence >= 0.5,
              let shape = largestInstance(observation.instanceMask, among: observation.allInstances),
              shape.share > 0.04, shape.share < 0.9, shape.fill >= 0.4,
              let buffer = try? observation.generateScaledMaskForImage(forInstances: IndexSet(integer: shape.instance),
                                                                       from: handler) else { return nil }
        let extent = photo.extent
        let mask = CIImage(cvPixelBuffer: buffer).applyingGaussianBlur(sigma: 1).cropped(to: extent)
        let blend = CIFilter.blendWithRedMask()
        blend.inputImage = photo
        blend.backgroundImage = CIImage.empty()
        blend.maskImage = mask
        guard let cut = blend.outputImage else { return nil }
        let box = shape.box
        let rect = CGRect(x: extent.minX + box.minX * extent.width, y: extent.minY + (1 - box.maxY) * extent.height,
                          width: box.width * extent.width, height: box.height * extent.height).integral
        return cut.cropped(to: rect.intersection(extent))
    }

    /// The instance covering the most pixels in Vision's low-resolution instance
    /// mask: its frame (0 to 1, from the top left), its share of the photo and how
    /// much of its frame it fills.
    private static func largestInstance(_ mask: CVPixelBuffer, among instances: IndexSet)
        -> (instance: Int, box: CGRect, share: CGFloat, fill: CGFloat)? {
        CVPixelBufferLockBaseAddress(mask, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(mask, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(mask) else { return nil }
        let width = CVPixelBufferGetWidth(mask), height = CVPixelBufferGetHeight(mask)
        let rowBytes = CVPixelBufferGetBytesPerRow(mask)
        var counts = [Int](repeating: 0, count: 256)
        var minX = [Int](repeating: Int.max, count: 256), maxX = [Int](repeating: -1, count: 256)
        var minY = [Int](repeating: Int.max, count: 256), maxY = [Int](repeating: -1, count: 256)
        for y in 0..<height {
            let row = base.advanced(by: y * rowBytes).assumingMemoryBound(to: UInt8.self)
            for x in 0..<width {
                let label = Int(row[x])
                guard label > 0 else { continue }
                counts[label] += 1
                minX[label] = min(minX[label], x)
                maxX[label] = max(maxX[label], x)
                minY[label] = min(minY[label], y)
                maxY[label] = max(maxY[label], y)
            }
        }
        guard let best = instances.filter({ $0 < 256 }).max(by: { counts[$0] < counts[$1] }), counts[best] > 0 else {
            return nil
        }
        let boxWidth = maxX[best] - minX[best] + 1, boxHeight = maxY[best] - minY[best] + 1
        let box = CGRect(x: CGFloat(minX[best]) / CGFloat(width), y: CGFloat(minY[best]) / CGFloat(height),
                         width: CGFloat(boxWidth) / CGFloat(width), height: CGFloat(boxHeight) / CGFloat(height))
        let share = CGFloat(boxWidth * boxHeight) / CGFloat(width * height)
        let fill = CGFloat(counts[best]) / CGFloat(boxWidth * boxHeight)
        return (best, box, share, fill)
    }

    /// The light evened out, lightly: only the tone curve and the shadows and
    /// highlights of Core Image's automatic adjustment, no color boost.
    private static func evenedOut(_ image: CIImage) -> CIImage {
        var result = image
        let kept: Set<String> = ["CIToneCurve", "CIHighlightShadowAdjust"]
        for filter in image.autoAdjustmentFilters(options: [.redEye: false, .features: []]) where kept.contains(filter.name) {
            filter.setValue(result, forKey: kCIInputImageKey)
            result = filter.outputImage ?? result
        }
        return result
    }

    private static func pngData(_ image: CGImage) -> Data? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}

/// Frames a picture on its label (the rules are in CaveKit's `LabelFraming`,
/// tested on every platform). The label is the text read on the bottle (on the
/// device, without language correction); without text, a rectangle on the bottle;
/// without either, the part of a bottle where labels sit.
enum LabelFrame {
    /// A cut-out standing bottle framed on its label, its full width, cut through
    /// the glass above and below (shown running off the picture's edges). Nil for a
    /// cut-out that is not a standing bottle, mostly covered with text (a close-up),
    /// or when the frame would be nearly the whole bottle.
    static func crop(_ cut: CIImage, context: CIContext) -> CIImage? {
        let extent = cut.extent
        let width = Double(extent.width), height = Double(extent.height)
        guard LabelFraming.isStandingBottle(width: width, height: height) else { return nil }
        // Vision reads the bottle on a plain gray background, in the cut-out's coordinates.
        let gray = CIImage(color: CIColor(red: 0.5, green: 0.5, blue: 0.5)).cropped(to: extent)
        guard let flat = context.createCGImage(cut.composited(over: gray), from: extent) else { return nil }
        let handler = VNImageRequestHandler(cgImage: flat, options: [:])
        let band: ClosedRange<Double>
        if let text = LabelFraming.label(lines: textBoxes(handler).map(line)) {
            if text.coverage > LabelFraming.closeUpTextCoverage { return nil }
            band = text.band
        } else {
            band = rectangleBand(handler) ?? LabelFraming.fallbackBand
        }
        guard let frame = LabelFraming.frame(label: band, width: width, height: height) else { return nil }
        let below = CGFloat(1 - frame.upperBound), kept = CGFloat(frame.upperBound - frame.lowerBound)
        return cut.cropped(to: CGRect(x: extent.minX, y: extent.minY + extent.height * below,
                                      width: extent.width, height: extent.height * kept))
    }

    /// A photo without a clear cut-out: the label straightened when a rectangle
    /// holds its text, else the photo cut to the band of its label (shown filling
    /// the picture, centred on the label), else the whole photo.
    static func withoutCutout(_ photo: CIImage, handler: VNImageRequestHandler) -> (CIImage, StudioPhoto.Kind) {
        // Text near the middle, where the bottle stands, not on things around it.
        let boxes = textBoxes(handler).filter { $0.midX > 0.2 && $0.midX < 0.8 }
        if let label = straightenedLabel(photo, handler: handler, text: boxes) { return (label, .cutout) }
        return (labelBand(photo, boxes: boxes) ?? photo, .photo)
    }

    /// The largest rectangle holding at least two lines of text (a label, not a
    /// window or a tile), straightened from its four corners.
    private static func straightenedLabel(_ photo: CIImage, handler: VNImageRequestHandler, text: [CGRect]) -> CIImage? {
        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 4
        request.minimumSize = 0.3
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1
        request.quadratureTolerance = 25
        request.minimumConfidence = 0.8
        guard (try? handler.perform([request])) != nil else { return nil }
        var best: VNRectangleObservation?
        for quad in request.results ?? [] {
            let box = quad.boundingBox
            let inside = text.filter { box.contains(CGPoint(x: $0.midX, y: $0.midY)) }.count
            guard inside >= 2 else { continue }
            if let current = best, current.boundingBox.width * current.boundingBox.height >= box.width * box.height { continue }
            best = quad
        }
        guard let quad = best else { return nil }
        let size = photo.extent.size
        func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * size.width, y: p.y * size.height) }
        let correction = CIFilter.perspectiveCorrection()
        correction.inputImage = photo
        correction.topLeft = point(quad.topLeft)
        correction.topRight = point(quad.topRight)
        correction.bottomLeft = point(quad.bottomLeft)
        correction.bottomRight = point(quad.bottomRight)
        return correction.outputImage
    }

    /// The band of the photo holding its label (the label about 80% of its height),
    /// as wide as it can be while centred on the label's text, so it fills any
    /// picture with the label in the middle. Nil with fewer than two lines of label
    /// text, or when text covers most of the photo (a close-up of the label).
    private static func labelBand(_ photo: CIImage, boxes: [CGRect]) -> CIImage? {
        guard let label = LabelFraming.label(lines: boxes.map(line)), label.coverage <= LabelFraming.closeUpTextCoverage
        else { return nil }
        var left: CGFloat = 1, right: CGFloat = 0, count = 0
        for box in boxes where box.height >= 0.015 && box.width >= 0.08 {
            let top = Double(1 - box.maxY), bottom = Double(1 - box.minY)
            guard bottom >= label.band.lowerBound, top <= label.band.upperBound else { continue }
            left = min(left, box.minX)
            right = max(right, box.maxX)
            count += 1
        }
        let extent = photo.extent
        guard count >= 2, right > left,
              let frame = LabelFraming.frame(label: label.band, width: Double(extent.width), height: Double(extent.height))
        else { return nil }
        let center = (left + right) / 2
        let half = min(center, 1 - center)
        let below = CGFloat(1 - frame.upperBound), kept = CGFloat(frame.upperBound - frame.lowerBound)
        return photo.cropped(to: CGRect(x: extent.minX + (center - half) * extent.width, y: extent.minY + extent.height * below,
                                        width: 2 * half * extent.width, height: extent.height * kept))
    }

    private static func line(_ box: CGRect) -> LabelFraming.Line {
        LabelFraming.Line(minY: Double(box.minY), maxY: Double(box.maxY), width: Double(box.width))
    }

    /// Where text was read, in Vision's coordinates (0 to 1, from the bottom left).
    private static func textBoxes(_ handler: VNImageRequestHandler) -> [CGRect] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        guard (try? handler.perform([request])) != nil else { return [] }
        let observations: [VNRecognizedTextObservation] = request.results ?? []
        return observations.map { $0.boundingBox }
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
}

/// A wine's label photo in the studio look on its backdrop, or the photo as taken
/// when the setting is off or the photo cannot be read.
struct LabelPhoto: View {
    let photoID: String
    var color: WineColor?
    var maxPixelSize = 900
    /// Inner margin around a cut-out shown whole.
    var inset: CGFloat = 12

    @AppStorage(StudioPhoto.settingKey) private var studio = true
    @State private var picture: StudioPhoto.Picture?

    var body: some View {
        Group {
            if studio, let picture {
                ZStack {
                    StudioBackdrop(color: color)
                    switch picture.kind {
                    case .cutout:
                        Image(decorative: picture.image, scale: 1)
                            .resizable()
                            .scaledToFit()
                            .shadow(color: .black.opacity(0.22), radius: inset / 2, y: inset / 4)
                            .padding(inset)
                    case .label:
                        // As tall as the picture: the glass runs off its top and bottom edges.
                        GeometryReader { proxy in
                            let aspect = CGFloat(picture.image.width) / CGFloat(max(1, picture.image.height))
                            Image(decorative: picture.image, scale: 1)
                                .resizable()
                                .frame(width: proxy.size.height * aspect, height: proxy.size.height)
                                .shadow(color: .black.opacity(0.25), radius: inset / 2)
                                .frame(width: proxy.size.width, height: proxy.size.height)
                        }
                    case .photo:
                        Image(decorative: picture.image, scale: 1)
                            .resizable()
                            .scaledToFill()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .clipped()
                            .overlay {
                                // A light vignette.
                                EllipticalGradient(colors: [.clear, .black.opacity(0.28)], center: .center,
                                                   startRadiusFraction: 0.45, endRadiusFraction: 0.85)
                                    .allowsHitTesting(false)
                            }
                    }
                }
                .clipped()
            } else {
                WinePhoto(photoID: photoID, maxPixelSize: maxPixelSize)
            }
        }
        .task(id: studio ? photoID : "") {
            picture = studio ? await StudioPhoto.picture(for: photoID, maxPixelSize: maxPixelSize) : nil
        }
    }
}

/// The backdrop behind studio pictures and covers: a gradient in the wine style's
/// studio shade (design tokens, deep and rich in dark mode), a soft light behind
/// the picture so it stands out, and a bunch of grapes and vine leaves in the
/// corners when there is room. Drawn here; the same drawing as on Android
/// (StudioBackdrop.kt).
struct StudioBackdrop: View {
    var color: WineColor?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let rect = CGRect(origin: .zero, size: size)
            let dark = scheme == .dark
            let base = Color("StudioBackdrop")
            let shade = color?.studioShade ?? base
            context.fill(Path(rect), with: .color(base))
            context.fill(Path(rect), with: .linearGradient(
                Gradient(colors: [shade, shade.mix(with: base, by: dark ? 0.45 : 0.55)]),
                startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            let light = dark ? shade.mix(with: .white, by: 0.35) : .white
            let ink = dark ? shade.mix(with: .white, by: 0.55).opacity(0.32) : (color?.tint ?? .accentColor).opacity(0.3)
            VineArt.draw(in: context, size: size, ink: ink, light: light.opacity(0.6), vein: shade.opacity(0.6))
            context.fill(Path(rect), with: .radialGradient(
                Gradient(colors: [light.opacity(dark ? 0.4 : 0.7), light.opacity(0)]),
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
