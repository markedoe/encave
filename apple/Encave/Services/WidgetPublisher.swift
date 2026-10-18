import CaveKit
import Foundation
#if os(iOS)
import WidgetKit
#endif

/// Keeps the widgets' summary of the cellar up to date (iPhone and iPad): writes it
/// to the App Group container and asks WidgetKit to reload, only when it changed.
/// The summary stays on this device; nothing is sent anywhere.
@MainActor
final class WidgetPublisher {
    private var published: WidgetSnapshot?
    private var pending: Task<Void, Never>?

    /// Publishes after a short pause, so a burst of changes is written once.
    func publish(_ cellar: Cellar, year: Int) {
        #if os(iOS)
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            self?.write(WidgetSnapshot(cellar: cellar, year: year))
        }
        #endif
    }

    #if os(iOS)
    private func write(_ snapshot: WidgetSnapshot) {
        // No App Group container in unsigned builds (CI simulators): nothing to do.
        guard snapshot != published, let url = WidgetSnapshotFile.url,
              let data = try? snapshot.encoded() else { return }
        do {
            // Readable after the first unlock, so lock screen widgets can show it.
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            published = snapshot
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            // The widgets keep their previous summary.
        }
    }
    #endif
}
