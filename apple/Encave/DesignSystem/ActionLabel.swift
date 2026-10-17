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

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .accessibilityHidden(true)
            Text(title)
        }
    }
}
