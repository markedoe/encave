import CaveKit
import Foundation
import UserNotifications

/// A monthly reminder, on the first of the month, of the bottles to drink this year,
/// those whose window opens and those past their peak, and one on the day bottles on
/// order are expected. Computed and scheduled on the device: no push server, nothing
/// leaves it.
@MainActor
enum Reminders {
    nonisolated static let enabledKey = "reminders.enabled"
    nonisolated static let identifier = "encave.monthly"
    /// Tapping the notification opens Tonight.
    nonisolated static let tabKey = "tab"
    /// Tapping a delivery reminder opens the wine.
    nonisolated static let wineKey = "wine"
    nonisolated static let deliveryPrefix = "encave.delivery."
    /// At most this many delivery reminders are pending (the system allows 64 in all).
    nonisolated static let maxDeliveries = 20

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
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        Task { await removeDeliveries(center) }
    }

    private static func removeDeliveries(_ center: UNUserNotificationCenter) async {
        let pending = await center.pendingNotificationRequests().map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: pending.filter { $0.hasPrefix(deliveryPrefix) })
    }

    /// Replaces the pending reminder with one for the first of next month, from the
    /// cellar as it is now. Called when the app opens and after changes.
    static func schedule(for cellar: Cellar) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        await removeDeliveries(center)
        guard isEnabled else { return }
        await scheduleDeliveries(for: cellar, center: center)
        let calendar = Calendar.current
        guard let next = calendar.nextDate(after: .now, matching: DateComponents(day: 1, hour: 10, minute: 0),
                                           matchingPolicy: .nextTime) else { return }
        let digest = cellar.drinkingDigest(year: CellarYear.of(next))
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

    /// One reminder per wine and expected day, at 10:00 on that day, counting the
    /// bottles due that day.
    private static func scheduleDeliveries(for cellar: Cellar, center: UNUserNotificationCenter) async {
        let calendar = Calendar.current
        var count = 0
        for group in cellar.orderGroups() {
            let byDay = Dictionary(grouping: group.bottles.compactMap(\.expectedAt)) { calendar.startOfDay(for: $0) }
            for (start, dates) in byDay.sorted(by: { $0.key < $1.key }) {
                guard count < maxDeliveries,
                      let day = calendar.date(bySettingHour: 10, minute: 0, second: 0, of: start),
                      day > .now else { continue } // Past days are shown as late in the app instead.
                let content = UNMutableNotificationContent()
                content.title = String(localized: "Delivery expected today")
                let wineName = group.wine.displayName
                let bottlesLabel = String(localized: "\(dates.count) bottles")
                content.body = String(localized: "\(wineName): \(bottlesLabel). Mark them received when they arrive.")
                content.sound = .default
                content.userInfo = [tabKey: AppTab.cellar.rawValue, wineKey: group.wine.id.uuidString]
                content.threadIdentifier = deliveryPrefix
                let trigger = UNCalendarNotificationTrigger(
                    dateMatching: calendar.dateComponents([.year, .month, .day, .hour, .minute], from: day), repeats: false)
                let id = deliveryPrefix + group.wine.id.uuidString + "." + String(Int(start.timeIntervalSinceReferenceDate))
                try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
                count += 1
            }
        }
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
        let info = response.notification.request.content.userInfo
        let tab = info[Reminders.tabKey] as? String
        let wineID = (info[Reminders.wineKey] as? String).flatMap(UUID.init(uuidString:))
        await MainActor.run {
            // Tapped while the app was not running: the window may not have set `model` yet.
            let model = self.model ?? CellarModel.current
            if let wineID, model?.cellar.wines[wineID] != nil {
                model?.open(wine: wineID)
            } else if let tab, let section = AppTab(rawValue: tab) {
                model?.selectedTab = section
            }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}
