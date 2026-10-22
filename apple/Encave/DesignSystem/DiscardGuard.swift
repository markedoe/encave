import SwiftUI
#if os(iOS)
import UIKit
#endif

extension View {
    /// For a note being written: while it has changes, the sheet cannot be swiped down,
    /// and `isAsking` shows "Discard this note?" (set it from Cancel or Back instead of
    /// closing). A swipe down asks the same, as on Android. Without changes, Cancel closes at once.
    func discardGuard(isDirty: Bool, isAsking: Binding<Bool>, onDiscard: @escaping () -> Void) -> some View {
        interactiveDismissDisabled(isDirty)
            #if os(iOS)
            .background(DismissAttemptObserver { if isDirty { isAsking.wrappedValue = true } }.accessibilityHidden(true))
            #endif
            .confirmationDialog("Discard this note?", isPresented: isAsking, titleVisibility: .visible) {
                Button("Discard", role: .destructive, action: onDiscard)
                    .accessibilityIdentifier("discard.confirm")
                Button("Keep Editing", role: .cancel) {}
            }
    }
}

#if os(iOS)
/// Tells when the user tries to swipe down a sheet that cannot be closed that way, so it can
/// ask instead of doing nothing. Calls on to the sheet's own delegate, which SwiftUI set.
private struct DismissAttemptObserver: UIViewControllerRepresentable {
    let onAttempt: () -> Void

    func makeUIViewController(context: Context) -> Controller { Controller() }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.onAttempt = onAttempt
        controller.observe()
    }

    final class Controller: UIViewController, UIAdaptivePresentationControllerDelegate {
        var onAttempt: () -> Void = {}
        private weak var original: UIAdaptivePresentationControllerDelegate?

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            observe()
        }

        /// Becomes the delegate of the sheet this view is in, keeping SwiftUI's to call on.
        func observe() {
            guard let presentation = presentationController, presentation.delegate !== self else { return }
            original = presentation.delegate
            presentation.delegate = self
        }

        func presentationControllerDidAttemptToDismiss(_ presentationController: UIPresentationController) {
            original?.presentationControllerDidAttemptToDismiss?(presentationController)
            onAttempt()
        }

        func presentationControllerShouldDismiss(_ presentationController: UIPresentationController) -> Bool {
            original?.presentationControllerShouldDismiss?(presentationController) ?? true
        }

        func presentationControllerWillDismiss(_ presentationController: UIPresentationController) {
            original?.presentationControllerWillDismiss?(presentationController)
        }

        func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
            original?.presentationControllerDidDismiss?(presentationController)
        }
    }
}
#endif
