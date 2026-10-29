import CaveKit
import Observation
import SwiftUI

/// How ratings are shown and entered on this device (Settings › Display): stars, out of 20
/// or out of 100. A device preference, never synced: the cellar keeps the stars, and the
/// precise score when one was entered (`Score`).
/// Not @AppStorage in the rows: on the Mac that redraws on every write to the defaults,
/// AppKit's own included (see `DefaultsFlag`). This one changes only when the setting does.
@MainActor
@Observable
final class RatingScaleSetting {
    static let shared = RatingScaleSetting()
    static let key = "ratingScale"

    private var stored: RatingScale

    var scale: RatingScale {
        get { stored }
        set {
            guard newValue != stored else { return }
            stored = newValue
            UserDefaults.standard.set(newValue.rawValue, forKey: Self.key)
        }
    }

    private init() {
        stored = UserDefaults.standard.string(forKey: Self.key).flatMap(RatingScale.init(rawValue:)) ?? .stars
    }
}

extension RatingScale {
    /// Its name in Settings.
    var title: LocalizedStringKey {
        switch self {
        case .stars: "Stars"
        case .twenty: "Out of 20"
        case .hundred: "Out of 100"
        }
    }
}

/// A rating as text, on a scale: "17.5/20", "93/100", "≈ 90/100" when the value only comes
/// from the stars, and how VoiceOver says it ("17.5 out of 20").
enum ScoreText {
    /// The number for the person's language ("17,5" in French).
    static func number(_ value: Double, on scale: RatingScale) -> String {
        value.formatted(.number.precision(.fractionLength(scale == .twenty ? 0...1 : 0...0)))
    }

    /// "17.5/20", "≈ 90/100", or "4/5" in stars; nil without a rating.
    static func shown(rating: Int?, score100: Double?, on scale: RatingScale) -> String? {
        guard let value = Score.shown(rating: rating, score100: score100, on: scale) else { return nil }
        let text = "\(number(value, on: scale))/\(scale.maximum)"
        return scale != .stars && Score.isFromStars(rating: rating, score100: score100) ? "≈\u{00A0}" + text : text
    }

    /// "4 stars", "17.5 out of 20", "about 90 out of 100"; nil without a rating.
    static func spoken(rating: Int?, score100: Double?, on scale: RatingScale) -> String? {
        guard let rating, let value = Score.shown(rating: rating, score100: score100, on: scale) else { return nil }
        let formattedScore = number(value, on: scale)
        let approximate = Score.isFromStars(rating: rating, score100: score100)
        switch scale {
        case .stars:
            let count = min(5, max(1, rating))
            return String(localized: "\(count) stars")
        case .twenty:
            return approximate ? String(localized: "about \(formattedScore) out of 20") : String(localized: "\(formattedScore) out of 20")
        case .hundred:
            return approximate ? String(localized: "about \(formattedScore) out of 100") : String(localized: "\(formattedScore) out of 100")
        }
    }
}

extension RatingScale {
    /// The highest value of the scale, as written after the slash.
    var maximum: Int {
        switch self {
        case .stars: 5
        case .twenty: 20
        case .hundred: 100
        }
    }
}

/// A read-only rating, on the scale chosen in Settings: stars, or "17.5/20" with "≈" when
/// the score only comes from the stars.
struct RatingView: View {
    let rating: Int
    var score100: Double?
    var font: Font = .caption

    var body: some View {
        let scale = RatingScaleSetting.shared.scale
        Group {
            if scale == .stars {
                HStack(spacing: 2) {
                    ForEach(1...5, id: \.self) { index in
                        Image(systemName: index <= rating ? "star.fill" : "star")
                    }
                }
            } else {
                HStack(spacing: 3) {
                    Image(systemName: "star.fill")
                    Text(verbatim: ScoreText.shown(rating: rating, score100: score100, on: scale) ?? "")
                        .monospacedDigit()
                        .fontWeight(.semibold)
                }
            }
        }
        .font(font)
        .foregroundStyle(Color.accentColor)
        .accessibilityElement()
        .accessibilityLabel(label(scale))
    }

    private func label(_ scale: RatingScale) -> Text {
        if scale == .stars { return Text("Rated \(rating) out of 5") }
        let scoreLabel = ScoreText.spoken(rating: rating, score100: score100, on: scale) ?? ""
        return Text("Rated \(scoreLabel)")
    }
}

/// Large tappable stars. Tapping the current value clears it.
/// For VoiceOver, one adjustable "Rating": swipe up or down to change it.
struct RatingPicker: View {
    @Binding var rating: Int?

    var body: some View {
        HStack(spacing: 10) {
            ForEach(1...5, id: \.self) { index in
                Button {
                    rating = rating == index ? nil : index
                } label: {
                    Image(systemName: index <= (rating ?? 0) ? "star.fill" : "star")
                        .font(.title)
                        .foregroundStyle(index <= (rating ?? 0) ? Color.accentColor : Color.secondary.opacity(0.5))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .sensoryFeedback(.selection, trigger: rating)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Rating"))
        .accessibilityValue(rating.map { Text("\($0) stars") } ?? Text("No rating"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: rating = min(5, (rating ?? 0) + 1)
            case .decrement: rating = (rating ?? 0) <= 1 ? nil : (rating ?? 1) - 1
            @unknown default: break
            }
        }
    }
}

/// Entering a rating on the scale chosen in Settings: stars in one tap, or a score out of
/// 20 (half points) or out of 100. Stored as stars, with the precise score
/// (`Score.entered`); a rating left untouched keeps its score, whatever the scale.
struct RatingInput: View {
    @Binding var rating: Int?
    @Binding var score100: Double?

    var body: some View {
        let scale = RatingScaleSetting.shared.scale
        if scale == .stars {
            RatingPicker(rating: Binding(get: { rating }, set: { set($0.map(Double.init), on: .stars) }))
                .frame(maxWidth: .infinity)
                .fieldHelp("From one star (disappointing) to five (exceptional). Tap the same star again to clear it.")
        } else {
            ScoreSlider(scale: scale, rating: rating, score100: score100) { set($0, on: scale) }
        }
    }

    private func set(_ value: Double?, on scale: RatingScale) {
        guard let value, let entered = Score.entered(value, on: scale) else {
            rating = nil
            score100 = nil
            return
        }
        rating = entered.rating
        score100 = entered.score100
    }
}

/// A score out of 20 or 100: the value in large type, a slider and a stepper for the last
/// half point, and a button to clear it. One adjustable element for VoiceOver.
private struct ScoreSlider: View {
    let scale: RatingScale
    let rating: Int?
    let score100: Double?
    let set: (Double?) -> Void

    /// Where the slider starts before anything is entered: a good wine.
    private var start: Double { scale == .twenty ? 15 : 88 }

    private var value: Double? { Score.shown(rating: rating, score100: score100, on: scale) }

    var body: some View {
        let range = Score.range(scale), step = Score.step(scale)
        let current = value ?? start
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("Rating")
                Spacer()
                if let shown = ScoreText.shown(rating: rating, score100: score100, on: scale) {
                    Text(verbatim: shown)
                        .font(.title2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(Color.accentColor)
                    Button {
                        set(nil)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("Clear Rating"))
                } else {
                    Text("No rating")
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 12) {
                Slider(value: Binding(get: { current }, set: { set($0) }), in: range, step: step) {
                    Text("Rating")
                } minimumValueLabel: {
                    Text(verbatim: ScoreText.number(range.lowerBound, on: scale)).font(.caption).foregroundStyle(.secondary)
                } maximumValueLabel: {
                    Text(verbatim: ScoreText.number(range.upperBound, on: scale)).font(.caption).foregroundStyle(.secondary)
                }
                Stepper("Rating", value: Binding(get: { current }, set: { set($0) }), in: range, step: step)
                    .labelsHidden()
            }
        }
        .sensoryFeedback(.selection, trigger: value)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Rating"))
        .accessibilityValue(Text(verbatim: ScoreText.spoken(rating: rating, score100: score100, on: scale) ?? String(localized: "No rating")))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: set(value.map { min(range.upperBound, $0 + step) } ?? start)
            case .decrement: set(value.map { max(range.lowerBound, $0 - step) } ?? start)
            @unknown default: break
            }
        }
        .accessibilityAction(named: Text("Clear Rating")) { set(nil) }
        .accessibilityIdentifier("rating.score")
    }
}
