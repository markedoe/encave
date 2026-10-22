import CaveKit
import SwiftUI

/// Sync problems shown outside Settings › Devices: on the Settings button, the Devices
/// row and, on the Mac, at the bottom of the sidebar. Words and a symbol, never color alone.
extension SyncHealth {
    var needsAttention: Bool { self != .ok }

    /// "Sign-in needed", "Sync paused" or "Not syncing"; nil when all is well.
    var statusText: Text? {
        switch self {
        case .ok: nil
        case .needsAction(.signInNeeded): Text("Sign-in needed")
        case .needsAction: Text("Sync paused")
        case .failing: Text("Not syncing")
        }
    }
}

/// Re-evaluated every minute: sync counts as failing once failures have lasted an hour.
struct SyncHealthReader<Content: View>: View {
    @Environment(CellarModel.self) private var model
    @ViewBuilder let content: (SyncHealth) -> Content

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { _ in content(model.sync.health) }
    }
}

/// The Devices row's status: the problem, with a warning symbol.
struct SyncHealthLabel: View {
    let health: SyncHealth

    var body: some View {
        if let text = health.statusText {
            Label {
                text
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            }
        }
    }
}

/// The Settings button's label, with a badge when sync needs attention.
struct SettingsButtonLabel: View {
    var body: some View {
        SyncHealthReader { health in
            Label {
                Text("Settings")
            } icon: {
                Image(systemName: "gearshape")
                    .overlay(alignment: .topTrailing) {
                        if health.needsAttention {
                            Image(systemName: "exclamationmark.circle.fill")
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .orange)
                                .font(.caption2.weight(.bold))
                                .offset(x: 6, y: -6)
                                .accessibilityHidden(true)
                        }
                    }
            }
            .accessibilityValue(health.needsAttention ? Text("Sync needs attention") : Text(verbatim: ""))
        }
    }
}

#if os(macOS)
/// Mac: at the bottom of the sidebar while sync needs attention; opens Settings.
struct SyncAttentionFooter: View {
    var body: some View {
        SyncHealthReader { health in
            if health.needsAttention {
                SettingsLink {
                    Label {
                        VStack(alignment: .leading, spacing: 1) {
                            Text("Sync needs attention")
                            health.statusText?.font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                }
                .buttonStyle(.borderless)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .accessibilityHint(Text("Opens Settings, then choose Devices."))
                .accessibilityIdentifier("sidebar.syncAttention")
            }
        }
    }
}
#endif
