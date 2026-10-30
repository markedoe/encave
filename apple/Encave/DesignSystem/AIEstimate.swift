import SwiftUI

/// "Estimated by AI" under a form field the AI filled from its own knowledge rather than
/// read on the label, until it is edited: text with a symbol, never color alone. The
/// field keeps its place in the view tree, so it keeps the focus when the note goes.
private struct AIEstimateNote: ViewModifier {
    let shown: Bool

    func body(content: Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            content
            if shown {
                Label("Estimated by AI", systemImage: "sparkles")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("form.aiEstimated")
            }
        }
    }
}

extension View {
    func aiEstimated(_ shown: Bool) -> some View { modifier(AIEstimateNote(shown: shown)) }
}
