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

    /// The style as text, next to its color wherever a wine is shown, so color is never the only cue.
    var name: String {
        switch self {
        case .red: String(localized: "Red")
        case .white: String(localized: "White")
        case .rose: String(localized: "Rosé")
        case .sparkling: String(localized: "Sparkling")
        case .dessert: String(localized: "Dessert")
        case .fortified: String(localized: "Fortified")
        case .orange: String(localized: "Orange")
        }
    }

    /// Light wine colors need dark text written on them; red and fortified need light text.
    var prefersDarkLabel: Bool {
        switch self {
        case .red, .fortified: false
        case .white, .rose, .sparkling, .dessert, .orange: true
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

extension WineColor {
    /// The studio backdrop's shade for this style (design/tokens.json): pale in
    /// light mode, deep and rich in dark mode, behind a cream label.
    var studioShade: Color {
        switch self {
        case .red: Color("StudioRed")
        case .white: Color("StudioWhite")
        case .rose: Color("StudioRose")
        case .sparkling: Color("StudioSparkling")
        case .dessert: Color("StudioDessert")
        case .fortified: Color("StudioFortified")
        case .orange: Color("StudioOrange")
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
