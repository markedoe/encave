import CaveKit
import Foundation
import UserNotifications

/// A monthly reminder, on the first of the month, of the bottles to drink this year,
/// those whose window opens and those past their peak. Computed and scheduled on the
/// device: no push server, nothing leaves it.
@MainActor
enum Reminders {
    nonisolated static let enabledKey = "reminders.enabled"
    nonisolated static let identifier = "encave.monthly"
    /// Tapping the notification opens Tonight.
    nonisolated static let tabKey = "tab"

    nonisolated static var isEnabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    /// Asks for permission, then schedules. Returns false if the user refused.
    static func enable(for cellar: Cellar) async -> Bool {
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        UserDefaults.standard.set(granted, forKey: enabledKey)
        if granted { await schedule(for: cellar) }
        return granted
    }

    static func disable() {
        UserDefaults.standard.set(false, forKey: enabledKey)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [identifier])
    }

    /// Replaces the pending reminder with one for the first of next month, from the
    /// cellar as it is now. Called when the app opens and after changes.
    static func schedule(for cellar: Cellar) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        guard isEnabled else { return }
        let calendar = Calendar.current
        guard let next = calendar.nextDate(after: .now, matching: DateComponents(day: 1, hour: 10, minute: 0),
                                           matchingPolicy: .nextTime) else { return }
        let digest = cellar.drinkingDigest(year: calendar.component(.year, from: next))
        guard !digest.isEmpty else { return }

        let content = UNMutableNotificationContent()
        content.title = String(localized: "Your cellar this month")
        content.body = body(for: digest)
        content.sound = .default
        content.userInfo = [tabKey: AppTab.tonight.rawValue]
        content.threadIdentifier = identifier
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: calendar.dateComponents([.year, .month, .day, .hour, .minute], from: next), repeats: false)
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
    }

    static func body(for digest: DrinkingDigest) -> String {
        var parts: [String] = []
        if digest.drinkSoon > 0 { parts.append(String(localized: "To drink this year: \(digest.drinkSoon)")) }
        if digest.newlyReady > 0 { parts.append(String(localized: "Now ready: \(digest.newlyReady)")) }
        if digest.pastPeak > 0 { parts.append(String(localized: "Past their peak: \(digest.pastPeak)")) }
        var text = parts.joined(separator: " · ")
        if let wineName = digest.firstWine { text += "\n" + String(localized: "Start with \(wineName).") }
        return text
    }
}

/// Opens the right section when a reminder is tapped, and shows reminders while the app is open.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    weak var model: CellarModel?

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let tab = response.notification.request.content.userInfo[Reminders.tabKey] as? String
        await MainActor.run {
            if let tab, let section = AppTab(rawValue: tab) { model?.selectedTab = section }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}
