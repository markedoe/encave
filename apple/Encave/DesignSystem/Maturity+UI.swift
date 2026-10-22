import CaveKit
import SwiftUI

extension Maturity {
    var title: LocalizedStringKey {
        switch self {
        case .drinkSoon: "Drink soon"
        case .ready: "Ready to drink"
        case .pastPeak: "Past its peak"
        case .young: "Keep for later"
        case .unknown: "No drinking window"
        }
    }

    /// Compact form for list rows.
    var shortTitle: LocalizedStringKey {
        switch self {
        case .drinkSoon: "Drink soon"
        case .ready: "Ready"
        case .pastPeak: "Past peak"
        case .young: "Keep"
        case .unknown: "Unknown"
        }
    }

    /// The compact words, as text (VoiceOver labels); nil without a drinking window.
    var shortName: String? {
        switch self {
        case .drinkSoon: String(localized: "Drink soon")
        case .ready: String(localized: "Ready")
        case .pastPeak: String(localized: "Past peak")
        case .young: String(localized: "Keep")
        case .unknown: nil
        }
    }

    var systemImage: String {
        switch self {
        case .drinkSoon: "hourglass"
        case .ready: "checkmark.seal"
        case .pastPeak: "exclamationmark.triangle"
        case .young: "leaf"
        case .unknown: "questionmark.circle"
        }
    }

    /// Colors from the asset catalog (see design/tokens.json), for the symbol only:
    /// the words next to it keep the text color, so they stay readable.
    var tint: Color {
        switch self {
        case .drinkSoon: Color("MaturityDrinkSoon")
        case .ready: Color("MaturityReady")
        case .pastPeak: Color("MaturityPastPeak")
        case .young: Color("MaturityKeep")
        case .unknown: .secondary
        }
    }
}

/// The maturity symbol in its color and the words in the text color: never color alone.
struct MaturityLabel: View {
    let maturity: Maturity
    /// The compact words of list rows ("Ready" rather than "Ready to drink").
    var short = false

    var body: some View {
        Label {
            Text(short ? maturity.shortTitle : maturity.title)
        } icon: {
            Image(systemName: maturity.systemImage).foregroundStyle(maturity.tint)
        }
    }
}
