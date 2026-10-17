import CaveKit
import SwiftUI

/// Drinking window as a timeline: the window is a filled band, "now" a marker.
struct MaturityGauge: View {
    let from: Int
    let until: Int
    let year: Int
    let maturity: Maturity

    private var lower: Int { min(from, year) - 1 }
    private var upper: Int { max(until, year) + 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { proxy in
                let width = proxy.size.width
                let span = CGFloat(max(1, upper - lower))
                let x: (Int) -> CGFloat = { CGFloat($0 - lower) / span * width }
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.secondary.opacity(0.15))
                        .frame(height: 10)
                    Capsule().fill(maturity.tint.gradient)
                        .frame(width: max(10, x(until + 1) - x(from)), height: 10)
                        .offset(x: x(from))
                    // "Now" marker.
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(Color.primary)
                        .frame(width: 3, height: 22)
                        .offset(x: min(max(0, x(year) + (x(year + 1) - x(year)) / 2 - 1.5), width - 3))
                }
                .frame(height: 22)
            }
            .frame(height: 22)
            HStack {
                Text(verbatim: "\(from)")
                Spacer()
                Text("Today")
                    .fontWeight(.semibold)
                Spacer()
                Text(verbatim: "\(until)")
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Drinking window from \(from) to \(until)"))
        .accessibilityValue(Text(maturity.title))
    }
}
