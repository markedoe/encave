#if os(iOS)
import CaveKit
import Foundation
import WidgetKit

/// Keeps the summary the home screen and lock screen widgets show (`WidgetSnapshot`) in
/// the App Group container shared with the widget extension: written a moment after the
/// last change of the cellar, made here or received from another device, and when the
/// app comes to the front (a new day), only when it changed; then the widgets are drawn
/// again. The extension reads only this file: no cellar, no keys, no network.
@MainActor
final class WidgetPublisher {
    private static let delay: Duration = .seconds(1)

    /// The cellar to summarize once the changes pause.
    private var pending: Cellar?
    private var debounce: Task<Void, Never>?
    /// What the file holds, as far as this launch knows.
    private var written: Data?

    nonisolated init() {}

    /// The App Group's copy of the summary; nil when the app has no App Group (a build
    /// without signing, such as the screen tour's).
    static var fileURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: WidgetSnapshot.appGroup)?
            .appendingPathComponent(WidgetSnapshot.fileName)
    }

    func schedule(_ cellar: Cellar) {
        pending = cellar
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: Self.delay)
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Writes the pending summary now (the app goes to the background).
    func flush() {
        debounce?.cancel()
        debounce = nil
        guard let cellar = pending else { return }
        pending = nil
        guard let url = Self.fileURL else { return }
        let now = Date.now
        // The wine Tonight's card keeps for today, so the widget shows the same one.
        let kept = TonightView.savedFirstPick(on: Cellar.tonightDay(now))
        let snapshot = WidgetSnapshot(cellar, now: now, keeping: kept, place: { CellarAccess.location(of: $0, in: cellar) })
        guard let data = try? snapshot.encoded() else { return }
        if written == nil { written = try? Data(contentsOf: url) }
        guard data != written else { return }
        do {
            // Readable by the lock screen widgets once the device was unlocked after a restart.
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            written = data
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            // Tried again with the next change.
            written = nil
        }
    }
}
#endif
