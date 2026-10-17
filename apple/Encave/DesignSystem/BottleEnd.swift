import CaveKit
import SwiftUI

/// A bottle seen from its end, filled with the wine's color. Used in lists,
/// racks and headers so a bottle always looks the same across the app.
/// Decorative: the style is always also given as text.
struct BottleEnd: View {
    var color: WineColor?
    var size: CGFloat = 28
    var isEmpty = false
    var isHighlighted = false

    var body: some View {
        ZStack {
            if isEmpty {
                Circle()
                    .strokeBorder(Color.secondary.opacity(0.35), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            } else {
                Circle()
                    .fill(fill.gradient)
                // Glass reflection.
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [.white.opacity(0.45), .clear],
                            center: UnitPoint(x: 0.32, y: 0.28), startRadius: 0, endRadius: size * 0.45
                        )
                    )
                Circle()
                    .strokeBorder(.black.opacity(0.18), lineWidth: max(1, size * 0.06))
                // Punt (the dimple at the bottom of the bottle).
                Circle()
                    .strokeBorder(.white.opacity(0.18), lineWidth: max(0.5, size * 0.03))
                    .padding(size * 0.3)
            }
            if isHighlighted {
                Circle()
                    .strokeBorder(Color.accentColor, lineWidth: 3)
                    .padding(-4)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private var fill: Color { color?.tint ?? Color.gray }
}

#Preview {
    HStack {
        ForEach(WineColor.allCases, id: \.self) { BottleEnd(color: $0, size: 40) }
        BottleEnd(color: nil, size: 40, isEmpty: true)
    }
    .padding()
}
