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
    /// The style's symbol on the glass (in racks), so the style never relies on color alone.
    var showsStyle = false

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
                if color == .sparkling { Bubbles(size: size) }
                if showsStyle, let color, StyleMark.fits(size) {
                    // Above the vintage written in the middle.
                    StyleMark(color: color, size: size * 0.24)
                        .offset(y: -size * 0.29)
                }
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

/// The neck of a bottle lying the other way round (head-to-tail behind another), seen end on:
/// a ring of glass around the capsule, both in the wine's tint. Its shape tells it apart from
/// a base (`BottleEnd`); sparkling wine shows its wire cage.
struct BottleNeck: View {
    var color: WineColor?
    var size: CGFloat = 20
    var isHighlighted = false
    /// The style's symbol on the capsule (see `BottleEnd.showsStyle`).
    var showsStyle = false

    var body: some View {
        ZStack {
            // The glass around the neck.
            Circle()
                .strokeBorder(fill.opacity(0.75), lineWidth: max(2, size * 0.14))
            Circle()
                .strokeBorder(.black.opacity(0.18), lineWidth: max(0.75, size * 0.04))
            // The capsule.
            Circle()
                .fill(fill.gradient)
                .overlay(Circle().strokeBorder(.black.opacity(0.3), lineWidth: max(0.75, size * 0.05)))
                .padding(size * 0.24)
            Circle()
                .fill(.white.opacity(0.45))
                .frame(width: size * 0.14, height: size * 0.14)
                .offset(x: -size * 0.08, y: -size * 0.08)
            if color == .sparkling {
                // The wire cage over the cork.
                Path { path in
                    path.move(to: CGPoint(x: size * 0.3, y: size * 0.5))
                    path.addLine(to: CGPoint(x: size * 0.7, y: size * 0.5))
                    path.move(to: CGPoint(x: size * 0.5, y: size * 0.3))
                    path.addLine(to: CGPoint(x: size * 0.5, y: size * 0.7))
                }
                .stroke(.white.opacity(0.8), lineWidth: max(1, size * 0.05))
            } else if showsStyle, let color, StyleMark.fits(size) {
                StyleMark(color: color, size: size * 0.26)
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

/// The style's symbol on a bottle (`WineColor.styleMark`), in a color readable on that wine's
/// tint. Sparkling wine keeps its drawn bubbles (or its wire cage) instead.
private struct StyleMark: View {
    let color: WineColor
    let size: CGFloat

    /// Drawn only on a bottle end this large; smaller ones keep the style in words.
    static func fits(_ bottle: CGFloat) -> Bool { bottle >= 28 }

    var body: some View {
        if color != .sparkling {
            Image(systemName: color.styleMark.systemImage)
                .font(.system(size: size, weight: .bold))
                .foregroundStyle(color.prefersDarkLabel ? Color.black.opacity(0.78) : Color.white)
                .accessibilityHidden(true)
        }
    }
}

/// Bubbles drawn on sparkling wine, so it differs from white by shape, not only color.
private struct Bubbles: View {
    let size: CGFloat

    // Positions relative to the bottle end, radius as a fraction of its size.
    private static let bubbles: [(x: CGFloat, y: CGFloat, r: CGFloat)] = [
        (0.36, 0.62, 0.11), (0.60, 0.40, 0.085), (0.64, 0.68, 0.07), (0.44, 0.33, 0.06),
    ]

    var body: some View {
        ZStack {
            ForEach(Self.bubbles.indices, id: \.self) { index in
                let bubble = Self.bubbles[index]
                Circle()
                    .strokeBorder(.black.opacity(0.35), lineWidth: max(0.75, size * 0.035))
                    .background(Circle().fill(.white.opacity(0.7)))
                    .frame(width: size * bubble.r * 2, height: size * bubble.r * 2)
                    .position(x: size * bubble.x, y: size * bubble.y)
            }
        }
        .frame(width: size, height: size)
    }
}

#Preview {
    HStack {
        ForEach(WineColor.allCases, id: \.self) { BottleEnd(color: $0, size: 40, showsStyle: true) }
        BottleEnd(color: nil, size: 40, isEmpty: true)
    }
    .padding()
}
