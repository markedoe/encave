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

    var systemImage: String {
        switch self {
        case .drinkSoon: "hourglass"
        case .ready: "checkmark.seal"
        case .pastPeak: "exclamationmark.triangle"
        case .young: "leaf"
        case .unknown: "questionmark.circle"
        }
    }

    /// Semantic system colors; always paired with a symbol and a label.
    var tint: Color {
        switch self {
        case .drinkSoon: .orange
        case .ready: .green
        case .pastPeak: .red
        case .young: .teal
        case .unknown: .secondary
        }
    }
}

struct MaturityLabel: View {
    let maturity: Maturity

    var body: some View {
        Label(maturity.title, systemImage: maturity.systemImage)
            .foregroundStyle(maturity.tint)
    }
}
