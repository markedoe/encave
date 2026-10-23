import SwiftUI

/// Bottom banner confirming an action, with Undo (and another button, such as Merge). Disappears after a few seconds.
/// Its Undo is also the window's undo action (⌘Z on Mac and iPad, shake on iPhone), which
/// stays once the banner is gone, until it runs or the model cancels it (an Undo that puts
/// back a whole earlier cellar goes as soon as anything else changes).
struct ToastOverlay: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.undoManager) private var undoManager
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        VStack {
            Spacer()
            if let toast = model.toast {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Color.accentColor)
                    Text(toast.message)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    if let action = toast.action {
                        Button(action.title) {
                            action.handler.run()
                            // The action shows its own banner (Merged, with Undo).
                            if model.toast?.id == toast.id { model.toast = nil }
                        }
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.borderless)
                    }
                    if let undo = toast.undo {
                        Button("Undo") {
                            undo.run()
                            model.toast = nil
                        }
                        .font(.subheadline.weight(.semibold))
                        .buttonStyle(.borderless)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(.regularMaterial, in: Capsule())
                .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
                .padding(.horizontal, 16)
                .padding(.bottom, 64)
                .frame(maxWidth: 520)
                .motionTransition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: toast.id) {
                    AccessibilityNotification.Announcement(toast.message).post()
                    try? await Task.sleep(for: .seconds(toast.seconds(voiceOver: voiceOver)))
                    if model.toast?.id == toast.id {
                        withAnimation { model.toast = nil }
                    }
                }
                .accessibilityElement(children: .contain)
            }
        }
        .motionAnimation(.spring(duration: 0.35), reduced: .easeInOut(duration: 0.2), value: model.toast?.id)
        .onChange(of: model.toast?.id, initial: true) { registerUndo() }
    }

    /// Each Undo goes on the window's undo stack as its banner appears, and leaves it when
    /// it runs or is cancelled (`ToastUndo`), not when the banner goes.
    private func registerUndo() {
        guard let toast = model.toast, let undo = toast.undo, let undoManager else { return }
        undo.register(in: undoManager, name: toast.message)
    }
}
