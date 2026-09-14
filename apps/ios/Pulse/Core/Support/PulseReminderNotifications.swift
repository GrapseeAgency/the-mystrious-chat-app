import Foundation
import UserNotifications

// ─────────────────────────────────────────────────────────────
// Wave 7 F-RO-06 — Tier-1 local reminder notifications.
//
// Two independent delivery paths (spec: "local schedule fires
// offline; the due loop polls the API when online"):
//  1. UNCalendarNotificationTrigger at remindAt — scheduled at
//     create/refresh time, survives reboot, fires without network.
//  2. The shell due-loop (PulseSession, 30 s foreground) — server
//     truth, PATCHes firedAt after the nudge so the web sheet and
//     other devices converge.
//
// Authorization is requested lazily when the first reminder lands;
// denial is honest (in-app surfaces stay live, no crash).
// ─────────────────────────────────────────────────────────────

public enum PulseReminderNotifications {

    /// Ask once; the verdict is logged, never asserted.
    public static func requestAuthorization() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error {
                NSLog("Pulse reminders: authorization error %@", error.localizedDescription)
            } else if !granted {
                NSLog("Pulse reminders: authorization denied — local fires stay silent")
            }
        }
    }

    /// Schedule the offline-capable one-shot; replaces any pending copy.
    public static func schedule(reminderId: String, note: String, remindAtEpochMs: Int64) {
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = "Reminder"
        content.sound = .default
        content.userInfo = ["reminderId": reminderId]

        let fireDate = Date(timeIntervalSince1970: TimeInterval(remindAtEpochMs) / 1000)
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: fireDate)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let request = UNNotificationRequest(
            identifier: "pulse-reminder-\(reminderId)",
            content: content,
            trigger: trigger,
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("Pulse reminders: schedule failed %@", error.localizedDescription)
            }
        }
    }

    public static func cancel(reminderId: String) {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: ["pulse-reminder-\(reminderId)"])
    }

    public static func showNow(reminderId: String, note: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = String(body.prefix(178))
        content.sound = .default
        content.userInfo = ["reminderId": reminderId]
        let request = UNNotificationRequest(
            identifier: "pulse-reminder-fired-\(reminderId)",
            content: content,
            trigger: nil,
        )
        UNUserNotificationCenter.current().add(request)
    }
}
