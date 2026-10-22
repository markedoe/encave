import SwiftUI

extension View {
    /// For a note being written: while it has changes, the sheet cannot be swiped down,
    /// and `isAsking` shows "Discard this note?" (set it from Cancel or Back instead of
    /// closing). Without changes, Cancel closes at once.
    func discardGuard(isDirty: Bool, isAsking: Binding<Bool>, onDiscard: @escaping () -> Void) -> some View {
        interactiveDismissDisabled(isDirty)
            .confirmationDialog("Discard this note?", isPresented: isAsking, titleVisibility: .visible) {
                Button("Discard", role: .destructive, action: onDiscard)
                    .accessibilityIdentifier("discard.confirm")
                Button("Keep Editing", role: .cancel) {}
            }
    }
}
