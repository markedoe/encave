import SwiftUI

/// Icon and title for buttons. Unlike Label, the icon keeps the button's own color:
/// inside a List, Label icons are tinted with the accent color, which makes them
/// invisible on filled (prominent) buttons and pushes the title off center.
struct ActionLabel: View {
    let title: LocalizedStringKey
    let systemImage: String

    init(_ title: LocalizedStringKey, systemImage: String) {
        self.title = title
        self.systemImage = systemImage
    }

    /// On one line with the icon if it fits, else on one line without it (a narrow
    /// iPhone, a long French title), else wrapped (the largest text sizes).
    var body: some View {
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

    private var icon: some View {
        Image(systemName: systemImage)
            .accessibilityHidden(true)
    }
}
