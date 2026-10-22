import Foundation
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Opens the system settings where the user turns an access back on for Encave.
@MainActor
enum SystemSettings {
    /// iPhone and iPad: Settings › Encave (camera, local network, notifications).
    /// Mac: System Settings › Privacy & Security › Local Network.
    static func openForLocalNetwork() {
        #if os(iOS)
        openAppSettings()
        #else
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }

    #if os(iOS)
    static func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
    #endif
}
