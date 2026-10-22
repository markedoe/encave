import Foundation
#if os(iOS)
import AVFoundation
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

#if os(iOS)
/// Camera access, asked for when the user first scans something (a label, a document,
/// a pairing code), never before.
enum CameraAccess {
    enum State { case allowed, notAsked, refused }

    static var state: State {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: .allowed
        case .notDetermined: .notAsked
        default: .refused
        }
    }

    /// Asks if needed; true when the camera can be used.
    static func request() async -> Bool {
        switch state {
        case .allowed: true
        case .refused: false
        case .notAsked: await AVCaptureDevice.requestAccess(for: .video)
        }
    }
}
#endif
