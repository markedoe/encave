import SwiftUI

/// Icon and title for buttons. Unlike Label, the icon keeps the button's own color:
/// inside a List, Label icons are tinted with the accent color, which makes them
/// invisible on filled (prominent) buttons and pushes the title off center.
struct ActionLabel: View {
    @Environment(\.actionLabelIcon) private var iconChoice
    let title: LocalizedStringKey
    let systemImage: String

    init(_ title: LocalizedStringKey, systemImage: String) {
        self.title = title
        self.systemImage = systemImage
    }

    /// On one line with the icon if it fits, else on one line without it (a narrow
    /// iPhone, a long French title), else wrapped (the largest text sizes).
    /// In an `ActionButtonRow`, the row decides once for all its buttons.
    var body: some View {
        switch iconChoice {
        case .shown:
            HStack(spacing: 6) {
                icon
                Text(title).lineLimit(1)
            }
        case .hidden:
            Text(title).lineLimit(1)
        case .fitting:
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    icon
                    Text(title).lineLimit(1)
                }
                Text(title).lineLimit(1)
                HStack(spacing: 6) {
                    icon
                    Text(title).multilineTextAlignment(.center)
                }
            }
        }
    }

    private var icon: some View {
        Image(systemName: systemImage)
            .accessibilityHidden(true)
    }
}

/// Whether `ActionLabel` shows its icon: chosen by each label, or once for a row.
enum ActionLabelIcon {
    case fitting, shown, hidden
}

extension EnvironmentValues {
    @Entry var actionLabelIcon = ActionLabelIcon.fitting
}

/// Action buttons side by side, or one above the other at the accessibility text sizes so
/// their titles are never broken mid-word. Give the buttons `.frame(maxWidth: .infinity)`
/// inside their labels to share the width; without it, stacked buttons keep their own width.
struct ActionButtonRow<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var spacing: CGFloat = 12
    @ViewBuilder var content: Content

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing))
            : AnyLayout(SharedWidthRow(spacing: spacing))
        // Every button with its icon if they all fit on one line, else none of them, so
        // two buttons side by side never differ.
        ViewThatFits(in: .horizontal) {
            layout { content }.environment(\.actionLabelIcon, .shown)
            layout { content }.environment(\.actionLabelIcon, .hidden)
            layout { content }
        }
    }
}

/// Buttons side by side. When every button stretches (`maxWidth: .infinity`), each gets an
/// equal share and the row's ideal width is the widest button times their count, so
/// `ViewThatFits` keeps the icons only if the longest title fits in its own half.
/// Otherwise the buttons keep their own width, as in an HStack, and share the width
/// equally only when they do not all fit.
struct SharedWidthRow: Layout {
    var spacing: CGFloat

    private func stretches(_ subviews: Subviews) -> Bool {
        subviews.count > 1 && subviews.allSatisfy {
            $0.sizeThatFits(ProposedViewSize(width: .infinity, height: nil)).width == .infinity
        }
    }

    /// The width proposed to each button, nil for its own width.
    private func widths(for width: CGFloat?, subviews: Subviews) -> [CGFloat?] {
        let gaps = spacing * CGFloat(max(0, subviews.count - 1))
        let count = CGFloat(max(1, subviews.count))
        let ideals = subviews.map { $0.sizeThatFits(.unspecified).width }
        if stretches(subviews) {
            let share: CGFloat? = width == .infinity ? nil : width
            let shared = share.map { max(0, ($0 - gaps) / count) } ?? (ideals.max() ?? 0)
            return ideals.map { _ in shared }
        }
        guard let width, width != .infinity, ideals.reduce(gaps, +) > width else { return ideals.map { _ in nil } }
        return ideals.map { _ in max(0, (width - gaps) / count) }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let sizes = zip(subviews, widths(for: proposal.width, subviews: subviews)).map { subview, width in
            subview.sizeThatFits(width.map { ProposedViewSize(width: $0, height: proposal.height) } ?? .unspecified)
        }
        let width = sizes.map(\.width).reduce(spacing * CGFloat(subviews.count - 1), +)
        return CGSize(width: width, height: sizes.map(\.height).max() ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        for (subview, width) in zip(subviews, widths(for: bounds.width, subviews: subviews)) {
            let proposed = width.map { ProposedViewSize(width: $0, height: bounds.height) } ?? .unspecified
            let size = subview.sizeThatFits(proposed)
            subview.place(at: CGPoint(x: x, y: bounds.midY), anchor: .leading,
                          proposal: ProposedViewSize(width: size.width, height: size.height))
            x += size.width + spacing
        }
    }
}
