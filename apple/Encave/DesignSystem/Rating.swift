import SwiftUI

/// Read-only stars.
struct RatingView: View {
    let rating: Int
    var font: Font = .caption

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { index in
                Image(systemName: index <= rating ? "star.fill" : "star")
            }
        }
        .font(font)
        .foregroundStyle(Color.accentColor)
        .accessibilityElement()
        .accessibilityLabel(Text("Rated \(rating) out of 5"))
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
