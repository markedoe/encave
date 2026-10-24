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
            : AnyLayout(HStackLayout(spacing: spacing))
        // Every button with its icon if they all fit on one line, else none of them, so
        // two buttons side by side never differ.
        ViewThatFits(in: .horizontal) {
            layout { content }.environment(\.actionLabelIcon, .shown)
            layout { content }.environment(\.actionLabelIcon, .hidden)
            layout { content }
        }
    }
}
