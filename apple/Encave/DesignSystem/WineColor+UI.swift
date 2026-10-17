import CaveKit
import SwiftUI

extension WineColor {
    var title: LocalizedStringKey {
        switch self {
        case .red: "Red"
        case .white: "White"
        case .rose: "Rosé"
        case .sparkling: "Sparkling"
        case .dessert: "Dessert"
        case .fortified: "Fortified"
        case .orange: "Orange"
        }
    }

    /// Colors come from the asset catalog (see design/tokens.json), with light and dark variants.
    var tint: Color {
        switch self {
        case .red: Color("WineRed")
        case .white: Color("WineWhite")
        case .rose: Color("WineRose")
        case .sparkling: Color("WineSparkling")
        case .dessert: Color("WineDessert")
        case .fortified: Color("WineFortified")
        case .orange: Color("WineOrange")
        }
    }
}

/// Small dot showing the wine style. Decorative: the style is always also given as text.
struct WineColorDot: View {
    let color: WineColor?

    var body: some View {
        Circle()
            .fill(color?.tint ?? Color.secondary.opacity(0.3))
            .overlay(Circle().strokeBorder(Color.primary.opacity(0.15), lineWidth: 0.5))
            .frame(width: 12, height: 12)
            .accessibilityHidden(true)
    }
}
