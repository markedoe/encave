import SwiftUI

/// Bottom banner confirming an action, with Undo. Disappears after a few seconds.
/// While it shows, its Undo is also the window's undo action.
struct ToastOverlay: View {
    @Environment(CellarModel.self) private var model
    @Environment(\.undoManager) private var undoManager
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @State private var registered: ToastUndo?

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
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: toast.id) {
                    AccessibilityNotification.Announcement(toast.message).post()
                    // Longer with VoiceOver, to leave time to reach Undo.
                    try? await Task.sleep(for: .seconds(voiceOver ? 12 : 5))
                    if model.toast?.id == toast.id {
                        withAnimation { model.toast = nil }
                    }
                }
                .accessibilityElement(children: .contain)
            }
        }
        .animation(.spring(duration: 0.35), value: model.toast?.id)
        .onChange(of: model.toast?.id, initial: true) { registerUndo() }
    }

    /// Only the banner on screen can be undone this way: some undos restore a
    /// whole earlier cellar, which must not happen after later changes.
    private func registerUndo() {
        if let registered {
            undoManager?.removeAllActions(withTarget: registered)
            self.registered = nil
        }
        guard let undo = model.toast?.undo, let undoManager else { return }
        undoManager.registerUndo(withTarget: undo, selector: #selector(ToastUndo.run), object: nil)
        registered = undo
    }
}
