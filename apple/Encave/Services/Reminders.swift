import CaveKit
import Foundation
import SwiftUI
#if os(iOS)
import UIKit
#endif
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

    /// Set once reminders were turned on or off, or the offer in Tonight was answered:
    /// Tonight then no longer offers them.
    nonisolated static let offeredKey = "reminders.offered"

    nonisolated static var wasOffered: Bool { UserDefaults.standard.bool(forKey: offeredKey) }

    static func markOffered() { UserDefaults.standard.set(true, forKey: offeredKey) }

    /// The system settings page of Encave's notifications, to turn them on after a refusal.
    nonisolated static var settingsURL: URL? {
        #if os(iOS)
        URL(string: UIApplication.openNotificationSettingsURLString)
        #else
        URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension")
        #endif
    }

    /// Asks for permission, then schedules. Returns false if the user refused.
    static func enable(for cellar: Cellar) async -> Bool {
        markOffered()
        let granted = await requestPermission()
        UserDefaults.standard.set(granted, forKey: enabledKey)
        if granted { await schedule(for: cellar) }
        return granted
    }

    /// Permission to post notifications, asked once by the system. Also used for the
    /// notifications of changes from other devices (`ChangeNotifications`).
    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// The system lets Encave post notifications: they may have been turned on or off in
    /// the system settings since permission was asked.
    nonisolated static func notificationsAllowed() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    /// Read aloud by VoiceOver when turning notifications on fails: the switch just turns
    /// back off, and the footer that says why is not read on its own.
    static func announceRefused() {
        AccessibilityNotification.Announcement(
            String(localized: "Notifications are off for Encave. Turn them on in the system settings.")
        ).post()
    }

    static func disable() {
        markOffered()
        UserDefaults.standard.set(false, forKey: enabledKey)
        let center = UNUserNotificationCenter.current()
        Task { await removePending(center) }
    }

    /// Months scheduled ahead: the system keeps at most 64 pending notifications.
    nonisolated static let monthsAhead = 12

    /// The pending monthly and delivery reminders ("encave.monthly", "encave.monthly.2026-11"…).
    private static func removePending(_ center: UNUserNotificationCenter) async {
        let pending = await center.pendingNotificationRequests().map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: pending.filter {
            $0.hasPrefix(identifier) || $0.hasPrefix(deliveryPrefix)
        })
    }

    /// Replaces the pending reminders with one on the first of each of the next 12 months,
    /// each with what the cellar as it is now holds for that month's year (none when there
    /// is nothing to say). Called when the app opens and after changes, so they follow the
    /// cellar.
    static func schedule(for cellar: Cellar) async {
        let center = UNUserNotificationCenter.current()
        await removePending(center)
        guard isEnabled else { return }
        await scheduleDeliveries(for: cellar, center: center)
        let calendar = Calendar.current
        var after = Date.now
        for _ in 0..<monthsAhead {
            guard let next = calendar.nextDate(after: after, matching: DateComponents(day: 1, hour: 10, minute: 0),
                                               matchingPolicy: .nextTime) else { return }
            after = next
            let digest = cellar.drinkingDigest(year: CellarYear.of(next))
            guard !digest.isEmpty else { continue }
            let content = UNMutableNotificationContent()
            content.title = String(localized: "Your cellar this month")
            content.body = body(for: digest)
            content.sound = .default
            content.userInfo = [tabKey: AppTab.tonight.rawValue]
            content.threadIdentifier = identifier
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: next)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            let id = identifier + "." + String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
            try? await center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
        }
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

/// "Open Settings", under the message that notifications are off for Encave.
struct NotificationSettingsButton: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        if let url = Reminders.settingsURL {
            Button {
                openURL(url)
            } label: {
                Label("Open Settings", systemImage: "gear")
            }
            .accessibilityHint(Text("Opens the system settings, to turn on notifications for Encave."))
        }
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
