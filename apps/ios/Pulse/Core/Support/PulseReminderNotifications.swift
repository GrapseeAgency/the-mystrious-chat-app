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
    /// R2-D — `conversationId` rides the userInfo so the tap routes to the
    /// room (web reminder-tap parity); nil keeps the notification tapless.
    public static func schedule(reminderId: String, note: String, remindAtEpochMs: Int64, conversationId: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = "Reminder"
        content.sound = .default
        content.userInfo = Self.userInfo(reminderId: reminderId, conversationId: conversationId)

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

    public static func showNow(reminderId: String, note: String, body: String, conversationId: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = String(body.prefix(178))
        content.sound = .default
        content.userInfo = Self.userInfo(reminderId: reminderId, conversationId: conversationId)
        let request = UNNotificationRequest(
            identifier: "pulse-reminder-fired-\(reminderId)",
            content: content,
            trigger: nil,
        )
        UNUserNotificationCenter.current().add(request)
    }

    /// The reminder payload — reminderId + (R2-D) the conversationId the tap
    /// deep-links into. Kept in one place so schedule/showNow stay in lockstep.
    /// Internal (not private) so the tests can pin the exact payload shape.
    static func userInfo(reminderId: String, conversationId: String?) -> [String: String] {
        var info = ["reminderId": reminderId]
        if let conversationId, !conversationId.isEmpty {
            info["conversationId"] = conversationId
        }
        return info
    }
}

// ─────────────────────────────────────────────────────────────
// R2-D — notification-tap routing (web reminder tap parity).
//
// willPresent keeps reminder alerts visible while the app is foregrounded
// (the due-loop fires while the user is IN the app); didReceive maps the
// tapped reminder to PulseDeepLink.room(conversationId) and hands it to the
// RootView-assigned onOpenRoom closure. The delegate is registered once in
// PulseApp.init; the last tapped room survives the cold-start window before
// RootView.onAppear attaches (consumeLastTappedRoomId replays it).
// ─────────────────────────────────────────────────────────────

public final class PulseReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = PulseReminderNotificationDelegate()

    /// RootView assigns — receives the deep link parsed from the tapped
    /// notification (reminder taps produce PulseDeepLink.room).
    public var onDeepLink: ((PulseDeepLink) -> Void)?

    /// Cold-start handoff: a tap delivered before RootView attached its
    /// closure is stored here and consumed on attach.
    private var lastTappedRoomId: String?

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void,
    ) {
        completionHandler([.banner, .list, .sound])
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void,
    ) {
        let userInfo = response.notification.request.content.userInfo
        if let link = Self.deepLink(from: userInfo), case .room(let conversationId) = link {
            // The routing closure is formed on the main actor (RootView) —
            // hop there before invoking so the session handoff is isolated.
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.lastTappedRoomId = conversationId
                self.onDeepLink?(link)
            }
        }
        completionHandler()
    }

    /// Cold-start replay — returns (and clears) a tap that arrived before the
    /// RootView closure was assigned. Nil when nothing is pending.
    public func consumeLastTappedRoomId() -> String? {
        let pending = lastTappedRoomId
        lastTappedRoomId = nil
        return pending
    }

    /// userInfo → deep link. Reminder notifications map to
    /// PulseDeepLink.room(conversationId) — the exact value the pulse://room
    /// scheme produces, so tap routing stays single-sourced with F-DL.
    static func deepLink(from userInfo: [AnyHashable: Any]) -> PulseDeepLink? {
        guard let conversationId = roomId(from: userInfo) else { return nil }
        return PulseDeepLink.room(conversationId: conversationId)
    }

    /// Pure userInfo decode — "conversationId" must be a non-empty string.
    static func roomId(from userInfo: [AnyHashable: Any]) -> String? {
        guard let raw = userInfo["conversationId"] else { return nil }
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
