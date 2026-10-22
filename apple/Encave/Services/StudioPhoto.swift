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
/// - a bottle leaning (up to 25°) is turned upright from the axis of its cut-out;
/// - a clear cut-out of a standing bottle is framed on its label, the label about
///   80% of the picture, the glass running off its top and bottom edges;
/// - a clear cut-out of anything else is shown whole, a label seen at an angle
///   straightened from its corners;
/// - without a clear cut-out, a label in a rectangle is straightened, else the photo
///   is shown filling the picture, centred on its label (found from its text, else
///   where its edges are densest, as on Android), with its light evened out.
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
        var photo = CIImage(cgImage: image)
        var handler = VNImageRequestHandler(cgImage: image, options: [:])
        var lifted = liftedSubject(handler, photo)
        // A leaning bottle: the photo is turned upright and the bottle cut out again.
        if let lean = lifted?.lean, let level = upright(photo, degrees: lean),
           let turned = context.createCGImage(level, from: level.extent) {
            photo = CIImage(cgImage: turned)
            handler = VNImageRequestHandler(cgImage: turned, options: [:])
            lifted = liftedSubject(handler, photo)
        }
        var made: (picture: CIImage, kind: Kind)
        if let cut = lifted?.cut {
            if let framed = LabelFrame.crop(cut, context: context) {
                made = (framed, .label)
            } else {
                made = (LabelFrame.squared(cut, context: context) ?? cut, .cutout)
            }
        } else {
            made = LabelFrame.withoutCutout(photo, handler: handler, context: context)
        }
        // Only a photo shown as it is gets its light evened out: a cut-out keeps the label's own colors.
        let result = made.kind == .photo ? evenedOut(made.picture) : made.picture
        guard let output = context.createCGImage(result, from: result.extent) else { return nil }
        return Picture(image: output, kind: made.kind)
    }

    /// The photo turned by `degrees` (counterclockwise) around its centre, without
    /// the empty corners the turn leaves.
    private static func upright(_ photo: CIImage, degrees: Double) -> CIImage? {
        let center = CGPoint(x: photo.extent.midX, y: photo.extent.midY)
        let turn = CGAffineTransform(translationX: center.x, y: center.y)
            .rotated(by: CGFloat(degrees * .pi / 180))
            .translatedBy(x: -center.x, y: -center.y)
        let slant = abs(sin(CGFloat(degrees * .pi / 180)))
        let inner = photo.extent.insetBy(dx: photo.extent.height * slant / 2, dy: photo.extent.width * slant / 2)
        guard inner.width > 0, inner.height > 0 else { return nil }
        return photo.transformed(by: turn).cropped(to: inner)
    }

    /// The largest thing that stands out, cut out with a softened edge (about a
    /// pixel), and how much it leans when it is long like a bottle (degrees to turn
    /// the photo by, counterclockwise; nil when upright or leaning too much to be
    /// sure). Nil when the cut-out is not clear: low confidence, nearly the whole
    /// photo or a tiny part of it, or a ragged shape that fills little of its frame.
    private static func liftedSubject(_ handler: VNImageRequestHandler, _ photo: CIImage) -> (cut: CIImage, lean: Double?)? {
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
        let leans = shape.elongation >= 3 && abs(shape.lean) >= LabelFraming.minimumTilt
            && abs(shape.lean) <= LabelFraming.maximumTilt
        return (cut.cropped(to: rect.intersection(extent)), leans ? shape.lean : nil)
    }

    /// The instance covering the most pixels in Vision's low-resolution instance
    /// mask: its frame (0 to 1, from the top left), its share of the photo, how much
    /// of its frame it fills, how long it is (its main axis over its width) and how
    /// far its main axis leans from upright (degrees, positive when the top leans
    /// right, which a counterclockwise turn by as much puts upright).
    private static func largestInstance(_ mask: CVPixelBuffer, among instances: IndexSet)
        -> (instance: Int, box: CGRect, share: CGFloat, fill: CGFloat, elongation: Double, lean: Double)? {
        CVPixelBufferLockBaseAddress(mask, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(mask, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(mask) else { return nil }
        let width = CVPixelBufferGetWidth(mask), height = CVPixelBufferGetHeight(mask)
        let rowBytes = CVPixelBufferGetBytesPerRow(mask)
        var counts = [Int](repeating: 0, count: 256)
        var minX = [Int](repeating: Int.max, count: 256), maxX = [Int](repeating: -1, count: 256)
        var minY = [Int](repeating: Int.max, count: 256), maxY = [Int](repeating: -1, count: 256)
        var sumX = [Double](repeating: 0, count: 256), sumY = [Double](repeating: 0, count: 256)
        var sumXX = [Double](repeating: 0, count: 256), sumYY = [Double](repeating: 0, count: 256)
        var sumXY = [Double](repeating: 0, count: 256)
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
                let fx = Double(x), fy = Double(y)
                sumX[label] += fx
                sumY[label] += fy
                sumXX[label] += fx * fx
                sumYY[label] += fy * fy
                sumXY[label] += fx * fy
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
        // The main axis from the second moments of the shape (y going down).
        let n = Double(counts[best])
        let meanX = sumX[best] / n, meanY = sumY[best] / n
        let xx: Double = sumXX[best] / n - meanX * meanX
        let yy: Double = sumYY[best] / n - meanY * meanY
        let xy: Double = sumXY[best] / n - meanX * meanY
        let theta: Double = 0.5 * atan2(2 * xy, xx - yy) * 180 / Double.pi
        let lean: Double = theta > 0 ? theta - 90 : theta + 90
        let half: Double = (xx - yy) / 2
        let spread: Double = (half * half + xy * xy).squareRoot()
        let major: Double = (xx + yy) / 2 + spread
        let minor: Double = max((xx + yy) / 2 - spread, 1e-9)
        return (best, box, share, fill, (major / minor).squareRoot(), lean)
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

    /// A cut-out that is a rectangle seen at an angle (a label photographed close
    /// up), straightened from its four corners. Nil when no clear rectangle fills it.
    static func squared(_ cut: CIImage, context: CIContext) -> CIImage? {
        let extent = cut.extent
        let gray = CIImage(color: CIColor(red: 0.5, green: 0.5, blue: 0.5)).cropped(to: extent)
        guard let flat = context.createCGImage(cut.composited(over: gray), from: extent) else { return nil }
        let request = VNDetectRectanglesRequest()
        request.maximumObservations = 1
        request.minimumSize = 0.5
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1
        request.quadratureTolerance = 25
        request.minimumConfidence = 0.8
        guard (try? VNImageRequestHandler(cgImage: flat, options: [:]).perform([request])) != nil,
              let quad = request.results?.first,
              quad.boundingBox.width * quad.boundingBox.height >= 0.5 else { return nil }
        func point(_ p: CGPoint) -> CGPoint {
            CGPoint(x: extent.minX + p.x * extent.width, y: extent.minY + p.y * extent.height)
        }
        let correction = CIFilter.perspectiveCorrection()
        correction.inputImage = cut
        correction.topLeft = point(quad.topLeft)
        correction.topRight = point(quad.topRight)
        correction.bottomLeft = point(quad.bottomLeft)
        correction.bottomRight = point(quad.bottomRight)
        return correction.outputImage
    }

    /// A photo without a clear cut-out: the label straightened when a rectangle
    /// holds its text, else the photo cut to the band of its label (shown filling
    /// the picture, centred on the label), else the whole photo.
    static func withoutCutout(_ photo: CIImage, handler: VNImageRequestHandler,
                              context: CIContext) -> (CIImage, StudioPhoto.Kind) {
        // Text near the middle, where the bottle stands, not on things around it.
        let boxes = textBoxes(handler).filter { $0.midX > 0.2 && $0.midX < 0.8 }
        if let label = straightenedLabel(photo, handler: handler, text: boxes) { return (label, .cutout) }
        return (labelBand(photo, boxes: boxes) ?? densestBand(photo, context: context) ?? photo, .photo)
    }

    /// The band where the photo's edges are densest (printed text), found as on
    /// Android (CaveKit's `LabelCrop`), when Vision reads no text.
    private static func densestBand(_ photo: CIImage, context: CIContext) -> CIImage? {
        let extent = photo.extent
        let scale = min(1, 640 / max(extent.width, extent.height))
        let small = photo.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let width = Int(small.extent.width), height = Int(small.extent.height)
        guard width >= 8, height >= 8, let image = context.createCGImage(small, from: small.extent),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var pixels = [UInt32](repeating: 0, count: width * height)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let bitmap = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                         bytesPerRow: width * 4, space: space,
                                         bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
                                             | CGBitmapInfo.byteOrder32Little.rawValue) else { return false }
            bitmap.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn, let box = LabelCrop.find(pixels: pixels, width: width, height: height) else { return nil }
        let unit = 1 / scale
        return photo.cropped(to: CGRect(x: extent.minX + CGFloat(box.left) * unit,
                                        y: extent.minY + CGFloat(height - box.bottom) * unit,
                                        width: CGFloat(box.width) * unit, height: CGFloat(box.height) * unit))
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
    /// The wine's identifier, for its backdrop.
    var seed: String
    var maxPixelSize = 900
    /// Inner margin around a cut-out shown whole.
    var inset: CGFloat = 12

    @AppStorage(StudioPhoto.settingKey) private var studio = true
    @State private var picture: StudioPhoto.Picture?

    var body: some View {
        Group {
            if studio, let picture {
                ZStack {
                    StudioBackdrop(color: color, seed: seed)
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

/// The backdrop behind studio pictures and covers: a cellar in soft focus, its
/// lights blurred into discs of candle light and of the wine's color over a warm
/// base, with a soft wash of the wine's color and a vignette (`StudioBokeh`). The
/// same for a wine at every launch. With Reduce Transparency or Increase Contrast,
/// only the base and the wash. Decorative.
struct StudioBackdrop: View {
    var color: WineColor?
    /// The wine's identifier: places the lights, so each wine keeps its own backdrop.
    var seed: String

    @Environment(\.self) private var environment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast
    @State private var image: CGImage?

    private var tint: Color { color?.studioTint ?? Color("StudioRed") }
    private var plain: Bool { reduceTransparency || contrast == .increased }

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                Rectangle().fill(Color("StudioBase"))
                RadialGradient(colors: [tint.opacity(scheme == .dark ? 0.35 : 0.25), tint.opacity(0)],
                               center: UnitPoint(x: 0.3, y: 0.25), startRadius: 0,
                               endRadius: max(size.width, size.height) * 1.1)
                if !plain, let image {
                    Image(decorative: image, scale: displayScale).resizable()
                }
            }
            .task(id: "\(seed)-\(color?.rawValue ?? "")-\(scheme == .dark)-\(Int(size.width))x\(Int(size.height))-\(plain)") {
                guard !plain, size.width >= 1, size.height >= 1 else { image = nil; return }
                let colors = StudioBokeh.Colors(base: rgb(Color("StudioBase")), candle: rgb(Color("StudioCandle")),
                                                tint: rgb(tint))
                image = await StudioBokeh.image(seed: seed, colors: colors, dark: scheme == .dark,
                                                width: Int(size.width * displayScale), height: Int(size.height * displayScale))
            }
        }
        .accessibilityHidden(true)
    }

    private func rgb(_ color: Color) -> StudioBokeh.RGB {
        let resolved = color.resolve(in: environment).cgColor
        let srgb = CGColorSpace(name: CGColorSpace.sRGB).flatMap {
            resolved.converted(to: $0, intent: .defaultIntent, options: nil)
        }
        let parts: [CGFloat] = (srgb ?? resolved).components ?? [0, 0, 0]
        guard parts.count >= 3 else { return StudioBokeh.RGB(r: parts[0], g: parts[0], b: parts[0]) }
        return StudioBokeh.RGB(r: parts[0], g: parts[1], b: parts[2])
    }
}

/// Draws the bokeh backdrop off the main thread and keeps it for each wine, color
/// scheme and size. The numbers and the order of the random draws are the same in
/// StudioBackdrop.kt on Android, so a wine looks the same on both.
enum StudioBokeh {
    struct RGB: Hashable, Sendable {
        var r: CGFloat
        var g: CGFloat
        var b: CGFloat
    }

    struct Colors: Hashable, Sendable {
        var base: RGB
        var candle: RGB
        var tint: RGB
    }

    private final class Cache: @unchecked Sendable {
        let images = NSCache<NSString, CGImage>()
        init() { images.countLimit = 120 }
    }

    private static let cache = Cache()

    static func image(seed: String, colors: Colors, dark: Bool, width: Int, height: Int) async -> CGImage? {
        let key = "\(seed)|\(colors.hashValue)|\(dark)|\(width)x\(height)" as NSString
        if let cached = cache.images.object(forKey: key) { return cached }
        let image = await Task.detached(priority: .utility) {
            draw(seed: seed, colors: colors, dark: dark, width: width, height: height)
        }.value
        if let image { cache.images.setObject(image, forKey: key) }
        return image
    }

    static func draw(seed: String, colors: Colors, dark: Bool, width: Int, height: Int) -> CGImage? {
        guard width > 0, height > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        let w = CGFloat(width), h = CGFloat(height)
        func color(_ c: RGB, _ alpha: CGFloat) -> CGColor { CGColor(srgbRed: c.r, green: c.g, blue: c.b, alpha: alpha) }
        func radial(_ center: CGPoint, _ radius: CGFloat, _ locations: [CGFloat], _ stops: [CGColor]) {
            guard let gradient = CGGradient(colorsSpace: space, colors: stops as CFArray, locations: locations) else { return }
            context.drawRadialGradient(gradient, startCenter: center, startRadius: 0, endCenter: center, endRadius: radius,
                                       options: [])
        }
        // Positions from the top, as on screen.
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: h - y) }

        context.setFillColor(color(colors.base, 1))
        context.fill(CGRect(x: 0, y: 0, width: w, height: h))
        // A very soft wash of the wine's color.
        let wash: CGFloat = dark ? 0.35 : 0.25
        radial(point(0.3 * w, 0.25 * h), 1.1 * max(w, h), [0, 1], [color(colors.tint, wash), color(colors.tint, 0)])
        // Out-of-focus lights: 60% candle light, 40% the wine's color brightened.
        let tint = colors.tint
        let bright = RGB(r: min(1, tint.r * 1.6), g: min(1, tint.g * 1.6), b: min(1, tint.b * 1.6))
        var random = StudioRandom(seed: seed)
        let blur: CGFloat = 0.02 * w
        let strength: CGFloat = dark ? 1 : 0.55
        context.setBlendMode(dark ? .screen : .normal)
        for _ in 0..<24 {
            let radius: CGFloat = w * (0.04 + 0.08 * CGFloat(random.next()))
            let x: CGFloat = w * CGFloat(random.next())
            let y: CGFloat = h * 0.8 * CGFloat(random.next())
            let light = random.next() < 0.6 ? colors.candle : bright
            let alpha: CGFloat = (0.25 + 0.45 * CGFloat(random.next())) * strength
            let outer = radius + blur
            let edge: CGFloat = max(0, (radius - blur) / outer)
            radial(point(x, y), outer, [0, edge, 1], [color(light, alpha), color(light, alpha * 0.9), color(light, 0)])
        }
        context.setBlendMode(.normal)
        // A soft vignette.
        let black = RGB(r: 0, g: 0, b: 0)
        radial(point(w / 2, h / 2), 0.75 * max(w, h), [0, 0.55, 1],
               [color(black, 0), color(black, 0), color(black, dark ? 0.5 : 0.18)])
        return context.makeImage()
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
                StudioBackdrop(color: wine.color, seed: wine.id.uuidString)
                WinePhoto(photoID: cover, maxPixelSize: maxPixelSize, fits: true)
                    .padding(inset / 2)
            }
        } else if let photoID = wine.photoID {
            LabelPhoto(photoID: photoID, color: wine.color, seed: wine.id.uuidString, maxPixelSize: maxPixelSize, inset: inset)
        }
    }
}

extension Wine {
    /// True when there is a picture to show on this device.
    var hasShownPhoto: Bool {
        PhotoStore.exists(coverPhotoID) || PhotoStore.exists(photoID)
    }
}
