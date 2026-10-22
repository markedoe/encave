import CaveKit
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Label photos for the sample cellar of the screen tour, drawn here (no image
/// files, no real labels) and kept in memory only (their studio versions are
/// cached as for any photo). The colors below are the
/// pixels of these made-up photos (glass, paper, ink), not interface colors. They give the studio look
/// something to work on: whole bottles standing on a table, a close-up of a label,
/// and a label without text.
enum SamplePhotos {
    private enum Shot {
        case bottle(glass: UInt32, capsule: UInt32, paper: UInt32, ink: UInt32, shape: Shape, text: Bool)
        case closeUp(paper: UInt32, ink: UInt32)
    }

    private enum Shape { case bordeaux, burgundy, flute }

    /// Gives a few sample wines of each kind of shot a label photo, drawn from its name,
    /// producer and vintage. Named after their content, as real photos, so their
    /// studio versions are cached once and reused by the next launches of the tour.
    static func attach(to cellar: inout Cellar) {
        let wines = cellar.wines.values.sorted { ($0.name, $0.vintage ?? 0) < ($1.name, $1.vintage ?? 0) }
        let counts: [(WineColor, Int)] = [(.red, 2), (.white, 2), (.rose, 1), (.sparkling, 1), (.fortified, 1)]
        for (color, count) in counts {
            for original in wines.filter({ $0.color == color && $0.photoID == nil }).prefix(count) {
                var wine = original
                guard let data = jpeg(draw(shot(for: color), wine: wine)) else { continue }
                let id = PhotoID.make(for: data)
                PhotoStore.keepInMemory(data, id: id)
                wine.photoID = id
                cellar.wines[wine.id] = wine
            }
        }
    }

    private static func shot(for color: WineColor?) -> Shot {
        switch color {
        case .white: .bottle(glass: 0x5B6B2E, capsule: 0xC9A646, paper: 0xFBFAF6, ink: 0x1E2A3A, shape: .burgundy, text: true)
        case .rose: .bottle(glass: 0xE9C3BE, capsule: 0xD8A3A8, paper: 0xFFFFFF, ink: 0x6B3A44, shape: .flute, text: true)
        case .sparkling: .closeUp(paper: 0x15233B, ink: 0xE8D8A8)
        case .dessert: .bottle(glass: 0xC8902E, capsule: 0x8A5A1C, paper: 0xF6EEDC, ink: 0x3A2A14, shape: .flute, text: true)
        case .fortified: .bottle(glass: 0x161212, capsule: 0x2C2C2C, paper: 0xE9DFC7, ink: 0x3B2A20, shape: .bordeaux, text: false)
        case .orange: .bottle(glass: 0x8A5320, capsule: 0x3F5A3A, paper: 0xF4EFE4, ink: 0x2E2A24, shape: .burgundy, text: true)
        case .red, nil: .bottle(glass: 0x1F2B1C, capsule: 0x7A1626, paper: 0xF1E8D6, ink: 0x2A2320, shape: .bordeaux, text: true)
        }
    }

    // MARK: Drawing

    private static let size = CGSize(width: 1200, height: 1600)

    private static func draw(_ shot: Shot, wine: Wine) -> CGImage? {
        // Drawn in a 1200 × 1600 space, saved at 720 × 960: enough for Vision, quick in the simulator.
        let scale: CGFloat = 0.6
        guard let context = CGContext(data: nil, width: Int(size.width * scale), height: Int(size.height * scale),
                                      bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.scaleBy(x: scale, y: scale)
        context.textMatrix = .identity
        switch shot {
        case let .bottle(glass, capsule, paper, ink, shape, text):
            room(context, tableTop: 430)
            bottle(context, glass: glass, capsule: capsule, paper: paper, ink: ink, shape: shape,
                   lines: text ? lines(for: wine) : [])
        case let .closeUp(paper, ink):
            fill(context, CGRect(origin: .zero, size: size), 0x6E523B)
            let label = CGRect(x: 110, y: 170, width: 980, height: 1260)
            context.saveGState()
            context.setShadow(offset: CGSize(width: 0, height: -12), blur: 30, color: color(0x000000, 0.45))
            fill(context, label, paper)
            context.restoreGState()
            context.setStrokeColor(color(ink, 0.9))
            context.setLineWidth(6)
            context.stroke(label.insetBy(dx: 40, dy: 40))
            var y: CGFloat = 1180
            for (text, size, bold) in lines(for: wine) {
                write(context, text, size: size * 2.6, bold: bold, ink: ink, centerX: label.midX, y: y)
                y -= size * 2.6 * 1.9
            }
        }
        return context.makeImage()
    }

    private static func lines(for wine: Wine) -> [(String, CGFloat, Bool)] {
        var lines: [(String, CGFloat, Bool)] = []
        if let producer = wine.producer { lines.append((producer.uppercased(), 22, false)) }
        lines.append((wine.name, 40, true))
        if let vintage = wine.vintage { lines.append(("\(vintage)", 34, true)) }
        lines.append(("APPELLATION CONTRÔLÉE", 16, false))
        return lines
    }

    /// A wall and a wooden table, lit from the left.
    private static func room(_ context: CGContext, tableTop: CGFloat) {
        gradient(context, from: CGPoint(x: 0, y: size.height), to: CGPoint(x: 0, y: tableTop),
                 colors: [color(0xE9E3DC), color(0xCFC6BC)])
        context.saveGState()
        context.clip(to: CGRect(x: 0, y: 0, width: size.width, height: tableTop))
        gradient(context, from: CGPoint(x: 0, y: tableTop), to: .zero, colors: [color(0x9A7656), color(0x5E4431)])
        context.restoreGState()
    }

    private static func bottle(_ context: CGContext, glass: UInt32, capsule: UInt32, paper: UInt32, ink: UInt32,
                               shape: Shape, lines: [(String, CGFloat, Bool)]) {
        let cx = size.width / 2, base: CGFloat = 250
        let (width, body, shoulder, neckWidth): (CGFloat, CGFloat, CGFloat, CGFloat) = switch shape {
        case .bordeaux: (330, 640, 150, 112)
        case .burgundy: (350, 520, 300, 116)
        case .flute: (300, 560, 230, 100)
        }
        let top: CGFloat = 1380
        let left = cx - width / 2, right = cx + width / 2

        // Shadow on the table.
        context.saveGState()
        context.setShadow(offset: .zero, blur: 40, color: color(0x000000, 0.55))
        context.setFillColor(color(0x000000, 0.35))
        context.fillEllipse(in: CGRect(x: left - 30, y: base - 28, width: width + 60, height: 56))
        context.restoreGState()

        let path = CGMutablePath()
        let shoulderTop = base + body + shoulder
        path.move(to: CGPoint(x: left + 14, y: base))
        path.addQuadCurve(to: CGPoint(x: left, y: base + 14), control: CGPoint(x: left, y: base))
        path.addLine(to: CGPoint(x: left, y: base + body))
        let (bend1, bend2): (CGFloat, CGFloat) = shape == .bordeaux ? (0.6, 0.4) : (0.25, 0.15)
        path.addCurve(to: CGPoint(x: cx - neckWidth / 2, y: shoulderTop),
                      control1: CGPoint(x: left, y: base + body + shoulder * bend1),
                      control2: CGPoint(x: cx - neckWidth / 2, y: shoulderTop - shoulder * bend2))
        path.addLine(to: CGPoint(x: cx - neckWidth / 2, y: top))
        path.addLine(to: CGPoint(x: cx + neckWidth / 2, y: top))
        path.addLine(to: CGPoint(x: cx + neckWidth / 2, y: shoulderTop))
        path.addCurve(to: CGPoint(x: right, y: base + body),
                      control1: CGPoint(x: cx + neckWidth / 2, y: shoulderTop - shoulder * bend2),
                      control2: CGPoint(x: right, y: base + body + shoulder * bend1))
        path.addLine(to: CGPoint(x: right, y: base + 14))
        path.addQuadCurve(to: CGPoint(x: right - 14, y: base), control: CGPoint(x: right, y: base))
        path.closeSubpath()

        context.saveGState()
        context.addPath(path)
        context.clip()
        // Round glass: dark edges, a highlight on the left.
        gradient(context, from: CGPoint(x: left, y: 0), to: CGPoint(x: right, y: 0),
                 colors: [shade(glass, 0.45), shade(glass, 1.25), shade(glass, 1.0), shade(glass, 0.8), shade(glass, 0.4)])
        context.setFillColor(color(0xFFFFFF, 0.22))
        context.fill(CGRect(x: left + width * 0.2, y: base + 30, width: width * 0.06, height: body + shoulder * 0.6))
        // Capsule over the top of the neck.
        let capsuleRect = CGRect(x: cx - neckWidth / 2 - 4, y: top - 170, width: neckWidth + 8, height: 170)
        context.saveGState()
        context.clip(to: capsuleRect)
        gradient(context, from: CGPoint(x: capsuleRect.minX, y: 0), to: CGPoint(x: capsuleRect.maxX, y: 0),
                 colors: [shade(capsule, 0.6), shade(capsule, 1.3), shade(capsule, 0.7)])
        context.restoreGState()
        // The label, on the body.
        let label = CGRect(x: left, y: base + 120, width: width, height: shape == .flute ? 300 : 380)
        context.clip(to: label)
        fill(context, label, paper)
        gradient(context, from: CGPoint(x: left, y: 0), to: CGPoint(x: right, y: 0),
                 colors: [color(0x000000, 0.28), color(0x000000, 0), color(0x000000, 0), color(0x000000, 0.3)])
        context.setStrokeColor(color(ink, 0.8))
        context.setLineWidth(3)
        context.stroke(label.insetBy(dx: 26, dy: 18))
        if lines.isEmpty {
            // A crest only: the photo has no text to find.
            context.setLineWidth(5)
            context.strokeEllipse(in: CGRect(x: cx - 70, y: label.midY - 40, width: 140, height: 140))
            context.fill(CGRect(x: cx - 90, y: label.minY + 60, width: 180, height: 10))
            context.fill(CGRect(x: cx - 60, y: label.minY + 90, width: 120, height: 6))
        } else {
            var y = label.maxY - 70
            let scale: CGFloat = shape == .flute ? 0.8 : 1
            for (text, size, bold) in lines {
                write(context, text, size: size * scale, bold: bold, ink: ink, centerX: cx, y: y, maxWidth: width - 70)
                y -= size * scale * 2.1
            }
        }
        context.restoreGState()
    }

    // MARK: Helpers

    private static func write(_ context: CGContext, _ text: String, size: CGFloat, bold: Bool, ink: UInt32,
                              centerX: CGFloat, y: CGFloat, maxWidth: CGFloat = .infinity) {
        func makeLine(_ fontSize: CGFloat) -> CTLine {
            let font = CTFontCreateWithName((bold ? "Georgia-Bold" : "Georgia") as CFString, fontSize, nil)
            let string = NSAttributedString(string: text, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): color(ink),
            ])
            return CTLineCreateWithAttributedString(string)
        }
        // Smaller until it fits across the label.
        var fontSize = size
        var line = makeLine(fontSize)
        while CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil)) > maxWidth, fontSize > 6 {
            fontSize *= 0.9
            line = makeLine(fontSize)
        }
        let width = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        context.textPosition = CGPoint(x: centerX - width / 2, y: y)
        CTLineDraw(line, context)
    }

    private static func color(_ rgb: UInt32, _ alpha: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                blue: CGFloat(rgb & 0xFF) / 255, alpha: alpha)
    }

    private static func shade(_ rgb: UInt32, _ factor: CGFloat) -> CGColor {
        func channel(_ shift: UInt32) -> CGFloat { min(1, CGFloat((rgb >> shift) & 0xFF) / 255 * factor) }
        return CGColor(srgbRed: channel(16), green: channel(8), blue: channel(0), alpha: 1)
    }

    private static func fill(_ context: CGContext, _ rect: CGRect, _ rgb: UInt32) {
        context.setFillColor(color(rgb))
        context.fill(rect)
    }

    private static func gradient(_ context: CGContext, from start: CGPoint, to end: CGPoint, colors: [CGColor]) {
        guard let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: colors as CFArray,
                                        locations: nil) else { return }
        context.drawLinearGradient(gradient, start: start, end: end,
                                   options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    }

    private static func jpeg(_ image: CGImage?) -> Data? {
        guard let image else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}
