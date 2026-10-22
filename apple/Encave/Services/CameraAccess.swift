#if os(iOS)
import AVFoundation
import SwiftUI
import UIKit

/// Camera permission for the label scanner and the pairing QR scanner. The live
/// scanners (VisionKit) are available only once the camera is allowed, so the
/// permission is asked first, and a refusal is explained with a way to Settings.
@MainActor
enum CameraAccess {
    static var status: AVAuthorizationStatus { AVCaptureDevice.authorizationStatus(for: .video) }

    /// Refused by the user, or not allowed on this device (Screen Time, a managed device).
    static func isOff(_ status: AVAuthorizationStatus) -> Bool {
        status == .denied || status == .restricted
    }

    /// Asks the first time; true when the camera may be used.
    static func request() async -> Bool {
        switch status {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    /// This app's page in the Settings app, where the camera can be allowed.
    static var settingsURL: URL? { URL(string: UIApplication.openSettingsURLString) }
}

/// Camera access is off: says what for and opens Settings. `actions` adds another way
/// (choose a photo, type the code).
struct CameraAccessOffView<Actions: View>: View {
    let message: LocalizedStringKey
    @ViewBuilder var actions: Actions
    @Environment(\.openURL) private var openURL

    var body: some View {
        ContentUnavailableView {
            Label("Camera Access Is Off", systemImage: "video.slash")
        } description: {
            Text(message)
        } actions: {
            if let url = CameraAccess.settingsURL {
                Button("Open Settings") { openURL(url) }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("camera.settings")
            }
            actions
        }
    }
}
#endif
