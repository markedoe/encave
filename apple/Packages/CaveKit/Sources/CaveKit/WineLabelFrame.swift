import Foundation

/// Where the whole front label is in a wine's label photo, as the optional AI label
/// recognition answered it, to frame the studio picture on it. Fractions (0 to 1) of
/// the photo's width and height from its top-left corner, and the label's slant in
/// degrees (positive when its text runs uphill to the right). It belongs to the photo
/// named by `photoID`: a new label photo makes it stale, and it is then ignored.
/// Synced with the wine (docs/SYNC.md).
public struct WineLabelFrame: Codable, Hashable, Sendable {
    public var photoID: String
    public var x: Double
    public var y: Double
    public var w: Double
    public var h: Double
    public var angle: Double
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case photoID, x, y, w, h, angle
    }

    public init(photoID: String, x: Double, y: Double, w: Double, h: Double, angle: Double = 0) {
        self.photoID = photoID; self.x = x; self.y = y; self.w = w; self.h = h; self.angle = angle
    }

    public init(from decoder: Decoder) throws {
        // Every field is required: a reader never fills one in (docs/SYNC.md).
        let c = try decoder.container(keyedBy: CodingKeys.self)
        photoID = try c.decode(String.self, forKey: .photoID)
        x = try c.decode(Double.self, forKey: .x)
        y = try c.decode(Double.self, forKey: .y)
        w = try c.decode(Double.self, forKey: .w)
        h = try c.decode(Double.self, forKey: .h)
        angle = try c.decode(Double.self, forKey: .angle)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(photoID, forKey: .photoID)
        try c.encode(x, forKey: .x)
        try c.encode(y, forKey: .y)
        try c.encode(w, forKey: .w)
        try c.encode(h, forKey: .h)
        try c.encode(angle, forKey: .angle)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// Margin added around the label in the studio picture, as a share of its size.
    public static let margin = 0.06

    /// The part of a photo of `width` × `height` pixels to keep, once turned by
    /// `angle` around the label's centre so its text is level: the label and its
    /// margin, in pixels from the top-left corner, at most the photo's size.
    public func crop(width: Double, height: Double, margin: Double = WineLabelFrame.margin)
        -> (centerX: Double, centerY: Double, width: Double, height: Double) {
        (centerX: (x + w / 2) * width, centerY: (y + h / 2) * height,
         width: min(width, w * width * (1 + 2 * margin)), height: min(height, h * height * (1 + 2 * margin)))
    }

    /// A short, stable tag for caches made from this frame (the studio picture).
    public var cacheTag: String {
        func part(_ value: Double) -> String { String(Int((value * 1000).rounded())) }
        return "f\(part(x))-\(part(y))-\(part(w))-\(part(h))-a\(Int((angle * 10).rounded()))"
    }
}

/// The label's place in the photo as read from an AI answer ("label_box" and
/// "label_angle"), checked: anything invalid is left out, never failing the answer.
public struct AILabelPlacement: Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var w: Double
    public var h: Double
    public var angle: Double

    /// The smallest label, as a share of the photo's width and of its height.
    public static let minimumSize = 0.1
    /// The steepest slant believed, in degrees either way.
    public static let maximumAngle = 45.0

    /// Nil when a value is not finite or outside 0–1, the box leaves the photo, is
    /// narrower or lower than `minimumSize`, or the angle is steeper than `maximumAngle`.
    public static func validated(x: Double, y: Double, w: Double, h: Double, angle: Double = 0) -> AILabelPlacement? {
        let values = [x, y, w, h]
        guard values.allSatisfy({ $0.isFinite && $0 >= 0 && $0 <= 1 }), angle.isFinite else { return nil }
        // A little room for rounding (0.3 + 0.7).
        guard x + w <= 1 + 1e-6, y + h <= 1 + 1e-6 else { return nil }
        guard w >= minimumSize, h >= minimumSize, abs(angle) <= maximumAngle else { return nil }
        return AILabelPlacement(x: x, y: y, w: w, h: h, angle: angle)
    }

    /// From the JSON answer: nil when "label_box" is absent, null or invalid. A
    /// missing or null "label_angle" counts as level; an invalid one leaves out the box.
    public static func parse(_ json: Data) -> AILabelPlacement? {
        guard let answer = try? JSONDecoder().decode(Answer.self, from: json), let box = answer.box,
              !answer.angleIsInvalid else { return nil }
        return validated(x: box.x, y: box.y, w: box.w, h: box.h, angle: answer.angle ?? 0)
    }

    /// Only the two fields, each read on its own so the rest of the answer never fails.
    private struct Answer: Decodable {
        struct Box: Decodable { var x, y, w, h: Double }
        var box: Box?
        var angle: Double?
        var angleIsInvalid = false

        enum CodingKeys: String, CodingKey {
            case box = "label_box", angle = "label_angle"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            box = try? c.decodeIfPresent(Box.self, forKey: .box)
            do { angle = try c.decodeIfPresent(Double.self, forKey: .angle) } catch { angleIsInvalid = true }
        }
    }

    public func frame(forPhoto photoID: String) -> WineLabelFrame {
        WineLabelFrame(photoID: photoID, x: x, y: y, w: w, h: h, angle: angle)
    }
}

extension Wine {
    /// The label frame, only while it belongs to `photoID` (the label photo shown).
    public func labelFrame(forPhoto photoID: String?) -> WineLabelFrame? {
        guard let labelFrame, let photoID, labelFrame.photoID == photoID else { return nil }
        return labelFrame
    }
}
