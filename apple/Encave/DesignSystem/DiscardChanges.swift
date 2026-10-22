import SwiftUI

/// Cancel for a form sheet. When something was changed, it asks "Discard Changes?" first.
/// Put `.interactiveDismissDisabled(isDirty)` on the sheet too, so a swipe down cannot
/// lose the changes either.
struct DiscardChangesCancelButton: View {
    let isDirty: Bool
    let discard: @MainActor () -> Void
    @State private var confirming = false

    var body: some View {
        Button("Cancel") {
            if isDirty { confirming = true } else { discard() }
        }
        .accessibilityIdentifier("sheet.cancel")
        .confirmationDialog("Discard Changes?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Discard Changes", role: .destructive) { discard() }
                .accessibilityIdentifier("sheet.discard")
            Button("Keep Editing", role: .cancel) {}
        } message: {
            Text("What you entered here is not saved.")
        }
    }
}
