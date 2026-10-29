import Foundation

/// A critic's or guide's score or award for a wine, as the user typed it from a guide, a
/// magazine or a sticker on the bottle. No database: Encave looks nothing up.
/// Written as "criticScores" on the wine (docs/SYNC.md §1).
public struct CriticScore: Codable, Hashable, Sendable {
    /// Who gave it ("Guide Hachette", "Decanter", "Concours de Mâcon").
    public var source: String
    /// The score, with its `scale`; nil for an award.
    public var score: Decimal?
    /// 100 or 20 for a score; nil for an award or medal given as text. Other values (from a
    /// newer version) are kept and shown as "score/scale".
    public var scale: Int?
    /// A medal or award in words ("Gold medal", "Coup de cœur"), when there is no score.
    public var award: String?
    /// A note such as the vintage it was given for ("for the 2015").
    public var note: String?
    /// Keys written by a newer version, kept and written back unchanged (docs/SYNC.md §1).
    public var unknownFields = UnknownFields()

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case source, score, scale, award, note
    }

    public init(source: String, score: Decimal? = nil, scale: Int? = nil, award: String? = nil, note: String? = nil) {
        self.source = source; self.score = score; self.scale = scale; self.award = award; self.note = note
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        source = try c.decode(String.self, forKey: .source)
        score = try c.decodeIfPresent(Decimal.self, forKey: .score)
        scale = try c.decodeIfPresent(Int.self, forKey: .scale)
        award = try c.decodeIfPresent(String.self, forKey: .award)
        note = try c.decodeIfPresent(String.self, forKey: .note)
        unknownFields = try UnknownFields(from: decoder, known: CodingKeys.self)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(source, forKey: .source)
        try c.encodeIfPresent(score, forKey: .score)
        try c.encodeIfPresent(scale, forKey: .scale)
        try c.encodeIfPresent(award, forKey: .award)
        try c.encodeIfPresent(note, forKey: .note)
        try unknownFields.encode(to: encoder, known: CodingKeys.self)
    }

    /// "94/100", "17.5/20" or the award, without the source; nil when there is neither.
    /// Numbers are written with a dot: the apps format them for the person's language.
    public var value: String? {
        if let score {
            let number = "\(score)"
            return scale.map { "\(number)/\($0)" } ?? number
        }
        let text = award?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.isEmpty ? nil : text
    }

    /// The score out of 100 by the shared conversion (`Score`), for a score out of 100 or
    /// 20; nil for an award or another scale.
    public var score100: Double? {
        guard let score else { return nil }
        let number = NSDecimalNumber(decimal: score).doubleValue
        switch scale {
        case 100: return Score.normalized(number)
        case 20: return Score.score100(twenty: number)
        default: return nil
        }
    }
}
