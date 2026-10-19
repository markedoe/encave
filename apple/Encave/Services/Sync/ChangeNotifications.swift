import CaveKit
import Foundation
import UserNotifications
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Optional (off by default): one local notification when a sync brings changes made
/// on another device while Encave is not in the foreground ("From Marc’s iPhone:
/// 2 bottles opened and 3 bottles added."). Worked out and posted on the device:
/// nothing is sent anywhere.
@MainActor
enum ChangeNotifications {
    nonisolated static let enabledKey = "sync.notifyChanges"
    /// One notification, replaced by the next one, so repeated syncs never pile up.
    nonisolated static let identifier = "encave.sync.changes"
    /// The changes the notification shown now tells about, added up across syncs.
    nonisolated static let shownKey = "sync.notifiedChanges"

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    /// Asks for permission (as for reminders). Returns false if the user refused.
    static func enable() async -> Bool {
        let granted = await Reminders.requestPermission()
        UserDefaults.standard.set(granted, forKey: enabledKey)
        return granted
    }

    static func disable() {
        UserDefaults.standard.set(false, forKey: enabledKey)
        UserDefaults.standard.removeObject(forKey: shownKey)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    /// The app is open in front: the changes are on the screen, the notification goes.
    static func appBecameActive() {
        UserDefaults.standard.removeObject(forKey: shownKey)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    /// The last notification posted, so the next one adds up after it.
    private static var posting: Task<Void, Never>?

    /// Tells the user about what a sync brought in, if they asked for it: a short banner
    /// in the app when it is in front, else a notification. Returns the posting, for a
    /// background run to wait for.
    static func announce(_ summary: CellarChangeSummary, membership: Membership?,
                         model: CellarModel) -> Task<Void, Never>? {
        guard isEnabled, !summary.isEmpty else { return nil }
        let deviceName = summary.device(in: membership)?.name
        if isAppInForeground {
            if let deviceName {
                model.showToast(String(localized: "Updated from \(deviceName)"))
            } else {
                model.showToast(String(localized: "Updated from your other devices"))
            }
            return nil
        }
        let previous = posting
        let task = Task {
            await previous?.value
            await post(summary, membership: membership)
        }
        posting = task
        return task
    }

    private static var isAppInForeground: Bool {
        #if os(macOS)
        NSApplication.shared.isActive
        #else
        UIApplication.shared.applicationState == .active
        #endif
    }

    private static func post(_ summary: CellarChangeSummary, membership: Membership?) async {
        let center = UNUserNotificationCenter.current()
        // Added to what the notification still shown says; a fresh count once it was
        // opened or dismissed.
        let shown = await center.deliveredNotifications().contains { $0.request.identifier == identifier }
        var total = shown ? stored : CellarChangeSummary()
        total.add(summary)
        if let data = try? JSONEncoder().encode(total) { UserDefaults.standard.set(data, forKey: shownKey) }

        let content = UNMutableNotificationContent()
        content.body = body(for: total, membership: membership)
        content.threadIdentifier = identifier
        // Tapping it opens the Journal when bottles were only opened, else the Cellar.
        content.userInfo = [Reminders.tabKey: (total.isOnlyOpened ? AppTab.journal : AppTab.cellar).rawValue]
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    private static var stored: CellarChangeSummary {
        UserDefaults.standard.data(forKey: shownKey)
            .flatMap { try? JSONDecoder().decode(CellarChangeSummary.self, from: $0) } ?? CellarChangeSummary()
    }

    /// "From Marc’s iPhone: 2 bottles opened and 3 bottles added."
    static func body(for summary: CellarChangeSummary, membership: Membership?) -> String {
        var parts: [String] = []
        if summary.bottlesOpened > 0 { parts.append(String(localized: "\(summary.bottlesOpened) bottles opened")) }
        if summary.bottlesAdded > 0 { parts.append(String(localized: "\(summary.bottlesAdded) bottles added")) }
        if summary.bottlesRemoved > 0 { parts.append(String(localized: "\(summary.bottlesRemoved) bottles removed")) }
        if summary.winesAdded > 0 { parts.append(String(localized: "\(summary.winesAdded) wines added")) }
        if summary.otherChanges > 0 { parts.append(String(localized: "\(summary.otherChanges) other changes")) }
        let changesLabel = parts.formatted(.list(type: .and))
        if let deviceName = summary.device(in: membership)?.name {
            return String(localized: "From \(deviceName): \(changesLabel).")
        }
        return String(localized: "From your other devices: \(changesLabel).")
    }
}
