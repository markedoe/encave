import CaveKit
import SwiftUI

/// Drinking window as a timeline: the window is a filled band, "now" a marker.
struct MaturityGauge: View {
    let from: Int
    let until: Int
    let year: Int
    let maturity: Maturity

    private var timeline: MaturityTimeline { MaturityTimeline(from: from, until: until, year: year) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { proxy in
                let width = proxy.size.width
                let x: (Int) -> CGFloat = { CGFloat(timeline.position($0)) * width }
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
                        .offset(x: min(max(0, CGFloat(timeline.marker) * width - 1.5), width - 3))
                }
                .frame(height: 22)
            }
            .frame(height: 22)
            // "Today" under the marker, between the first and last years.
            GaugeLabels(marker: timeline.marker) {
                Text(verbatim: "\(from)")
                Text("Today")
                    .fontWeight(.semibold)
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

/// Three labels: the first at the leading edge, the last at the trailing edge, the middle
/// one centred under `marker` (0...1 of the width) and kept clear of the other two.
private struct GaugeLabels: Layout {
    let marker: Double
    private let gap: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let height = sizes.map(\.height).max() ?? 0
        let width = proposal.width ?? sizes.reduce(0) { $0 + $1.width } + gap * 2
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 3 else { return }
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let width = Double(bounds.width)
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.midY), anchor: .leading, proposal: .unspecified)
        subviews[2].place(at: CGPoint(x: bounds.maxX, y: bounds.midY), anchor: .trailing, proposal: .unspecified)
        let origin = MaturityTimeline.labelOrigin(center: marker * width, labelWidth: Double(sizes[1].width), width: width,
                                                  leadingReserved: Double(sizes[0].width + gap),
                                                  trailingReserved: Double(sizes[2].width + gap))
        subviews[1].place(at: CGPoint(x: bounds.minX + CGFloat(origin), y: bounds.midY), anchor: .leading, proposal: .unspecified)
    }
}
