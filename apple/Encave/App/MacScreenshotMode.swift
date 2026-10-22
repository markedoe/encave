#if os(macOS)
import AppKit

/// Screenshots of the Mac app in CI (apple/tools/mac-screenshots.sh), with the sample
/// cellar only: launched with `--sample-data -EncaveScreenshots YES`. The main window
/// goes full screen on the sharpest screen (the HiDPI virtual display the script adds),
/// then its number is printed for `screencapture -l`.
/// `-EncaveAppearance light|dark` sets the appearance whatever the system uses.
@MainActor
enum MacScreenshotMode {
    private static var started = false
    private static var window: NSWindow?

    static func startIfAsked() {
        let defaults = UserDefaults.standard
        guard !started, defaults.bool(forKey: "EncaveScreenshots") else { return }
        started = true
        log("started")
        switch defaults.string(forKey: "EncaveAppearance") {
        case "dark": NSApplication.shared.appearance = NSAppearance(named: .darkAqua)
        case "light": NSApplication.shared.appearance = NSAppearance(named: .aqua)
        default: break
        }
        _ = NotificationCenter.default.addObserver(forName: NSWindow.didEnterFullScreenNotification, object: nil,
                                                   queue: .main) { note in
            let window = note.object as? NSWindow
            MainActor.assumeIsolated { if let window, window === self.window { report(window) } }
        }
        // Launched from a script, the app may not come to the front: look for its window.
        Task {
            for _ in 0..<120 {
                try? await Task.sleep(for: .milliseconds(250))
                if let window = NSApplication.shared.windows.first(where: { $0.isVisible && $0.canBecomeMain && !($0 is NSPanel) }) {
                    NSApplication.shared.activate()
                    window.makeKeyAndOrderFront(nil)
                    prepare(window)
                    return
                }
            }
            log("no window among \(NSApplication.shared.windows.count)")
        }
    }

    /// The first main window: moved to the screen with the most pixels per point, then
    /// full screen (no desktop, no menu bar, the same size every time).
    private static func prepare(_ window: NSWindow) {
        guard self.window == nil else { return }
        self.window = window
        let screens = NSScreen.screens
        if let screen = screens.max(by: { ($0.backingScaleFactor, $0.frame.width) < ($1.backingScaleFactor, $1.frame.width) }) {
            let area = screen.visibleFrame
            let size = CGSize(width: min(1180, area.width), height: min(780, area.height))
            window.setFrame(CGRect(x: area.midX - size.width / 2, y: area.midY - size.height / 2,
                                   width: size.width, height: size.height), display: true)
        }
        log("screens: " + screens.map { "\(Int($0.frame.width))x\(Int($0.frame.height))@\($0.backingScaleFactor)x" }
            .joined(separator: ", "))
        window.collectionBehavior.insert(.fullScreenPrimary)
        Task {
            // After the first layout pass, or the toggle can be ignored.
            try? await Task.sleep(for: .seconds(1.5))
            if window.styleMask.contains(.fullScreen) { report(window) } else { window.toggleFullScreen(nil) }
            // Full screen refused (no space for it): the window as it is.
            try? await Task.sleep(for: .seconds(15))
            if !window.styleMask.contains(.fullScreen) { report(window) }
        }
    }

    private static var reported = false

    /// Printed once the window has settled; the script captures it then.
    private static func report(_ window: NSWindow) {
        guard !reported else { return }
        reported = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            let size = window.frame.size
            log("window \(window.windowNumber) \(Int(size.width))x\(Int(size.height))@\(window.backingScaleFactor)x"
                + (window.styleMask.contains(.fullScreen) ? " full screen" : ""))
        }
    }

    private static func log(_ line: String) {
        FileHandle.standardError.write(Data(("encave-screenshots: " + line + "\n").utf8))
    }
}
#endif
