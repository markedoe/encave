import Foundation

/// How ratings are shown and entered on this device: 5 stars, out of 20 or out of 100.
/// A device preference, never synced.
public enum RatingScale: String, CaseIterable, Codable, Sendable {
    case stars, twenty, hundred
}

/// The one conversion between stars, scores out of 20 and scores out of 100, the same in
/// the Kotlin core and pinned by `fixtures/rating-scale-vectors.json` (docs/SYNC.md §1).
///
/// A precise score is kept as `score100`, from 50 to 100 in quarter points, next to the
/// 1–5 `rating`, which is always written so that older versions show stars:
/// - out of 20: `score100 = 50 + 2.5 × score20` (16/20 is 90, 17.5/20 is 93.75), and back;
/// - stars from a score: below 70 is 1, below 80 is 2, below 87 is 3, below 94 is 4, else 5;
/// - a rating in stars only is shown on the other scales as 65, 75, 83, 90 or 96 out of 100.
public enum Score {
    public static let lowest = 50.0
    public static let highest = 100.0

    /// Brought into 50…100 and rounded to the nearest quarter point; nil for nil or a value
    /// that is not a finite number.
    public static func normalized(_ score100: Double?) -> Double? {
        guard let score100, score100.isFinite else { return nil }
        let clamped = min(highest, max(lowest, score100))
        return (clamped * 4).rounded(.toNearestOrAwayFromZero) / 4
    }

    /// The 1–5 stars written with a score.
    public static func stars(score100: Double) -> Int {
        switch score100 {
        case ..<70: 1
        case ..<80: 2
        case ..<87: 3
        case ..<94: 4
        default: 5
        }
    }

    /// What a rating in stars (1 to 5) stands for out of 100, to show it on another scale.
    public static func score100(stars: Int) -> Double {
        [65, 75, 83, 90, 96][min(5, max(1, stars)) - 1]
    }

    /// A score out of 20 as a score out of 100 (normalized).
    public static func score100(twenty: Double) -> Double? { normalized(lowest + 2.5 * twenty) }

    /// A score out of 100 as a score out of 20.
    public static func twenty(score100: Double) -> Double { (score100 - lowest) / 2.5 }

    /// The score kept with a rating: only while the rating is the stars of that score. An
    /// older version that changed or removed the rating left the score behind: it is stale
    /// and read as left out, the same way on every device.
    public static func kept(_ score100: Double?, rating: Int?) -> Double? {
        guard let rating, let score = normalized(score100), stars(score100: score) == rating else { return nil }
        return score
    }

    /// The rating and score to store for a value entered on a scale: stars give no score;
    /// out of 20 (0…20) and out of 100 (50…100) give both. Nil when the value is not a number.
    public static func entered(_ value: Double, on scale: RatingScale) -> (rating: Int, score100: Double?)? {
        guard value.isFinite else { return nil }
        switch scale {
        case .stars:
            return (min(5, max(1, Int(value.rounded(.toNearestOrAwayFromZero)))), nil)
        case .twenty:
            guard let score = score100(twenty: value) else { return nil }
            return (stars(score100: score), score)
        case .hundred:
            guard let score = normalized(value) else { return nil }
            return (stars(score100: score), score)
        }
    }

    /// The value to show on a scale for a rating and its score: stars (1 to 5), out of 20 to
    /// the nearest half point, out of 100 to the nearest point. Nil without a rating.
    public static func shown(rating: Int?, score100: Double?, on scale: RatingScale) -> Double? {
        guard let rating else { return nil }
        let stars = min(5, max(1, rating))
        let score = kept(score100, rating: stars) ?? Self.score100(stars: stars)
        switch scale {
        case .stars: return Double(stars)
        case .twenty: return (twenty(score100: score) * 2).rounded(.toNearestOrAwayFromZero) / 2
        case .hundred: return score.rounded(.toNearestOrAwayFromZero)
        }
    }

    /// True when the value shown comes from the stars alone, not from a score entered on
    /// that scale (the apps may say it is approximate).
    public static func isFromStars(rating: Int?, score100: Double?) -> Bool {
        rating != nil && kept(score100, rating: rating) == nil
    }

    /// The range and step of a value entered on a scale.
    public static func range(_ scale: RatingScale) -> ClosedRange<Double> {
        switch scale {
        case .stars: 1...5
        case .twenty: 0...20
        case .hundred: 50...100
        }
    }

    public static func step(_ scale: RatingScale) -> Double { scale == .twenty ? 0.5 : 1 }
}
