import SwiftUI

/// Bottom banner confirming an action, with Undo. Disappears after a few seconds.
struct ToastOverlay: View {
    @Environment(CellarModel.self) private var model

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
                            undo()
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
                    try? await Task.sleep(for: .seconds(5))
                    if model.toast?.id == toast.id {
                        withAnimation { model.toast = nil }
                    }
                }
                .accessibilityElement(children: .contain)
            }
        }
        .animation(.spring(duration: 0.35), value: model.toast?.id)
    }
}
