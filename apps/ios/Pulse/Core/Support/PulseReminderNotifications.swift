import Foundation
import UserNotifications

// Wave 7 F-RO-06 - Tier-1 local reminder notifications.
//
// Two independent delivery paths (spec: "local schedule fires
// offline; the due loop polls the API when online"):
//  1. UNCalendarNotificationTrigger at remindAt - scheduled at
//     create/refresh time, survives reboot, fires without network.
//  2. The shell due-loop (PulseSession, 30 s foreground) - server
//     truth, PATCHes firedAt after the nudge so the web sheet and
//     other devices converge.
//
// Authorization is requested lazily when the first reminder lands;
// denial is honest (in-app surfaces stay live, no crash).

public enum PulseReminderNotifications {

    /// Ask once; the verdict is logged, never asserted.
    public static func requestAuthorization() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error {
                NSLog("Pulse reminders: authorization error %@", error.localizedDescription)
            } else if !granted {
                NSLog("Pulse reminders: authorization denied - local fires stay silent")
            }
        }
    }

    /// Schedule the offline-capable one-shot; replaces any pending copy.
    /// R2-D - `conversationId` rides the userInfo so the tap routes to the
    /// room (web reminder-tap parity); nil keeps the notification tapless.
    public static func schedule(reminderId: String, note: String, remindAtEpochMs: Int64, conversationId: String? = nil, ownerId: String? = nil) {
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = "Reminder"
        content.sound = .default
        content.userInfo = Self.userInfo(reminderId: reminderId, conversationId: conversationId)
        // R10-b - conversation-bound postings carry the quick-reply category
        // ("Reply" text-input action → PulseQuickReplyCoordinator). Tapless
        // postings stay tapless - a Reply control without a routable room
        // would be a dead control.
        if PulseQuickReply.applies(toConversationId: conversationId) {
            content.categoryIdentifier = PulseQuickReply.categoryId
        }

        let components = Self.triggerComponents(remindAtEpochMs: remindAtEpochMs)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let request = UNNotificationRequest(
            identifier: notificationID(reminderId: reminderId, ownerId: ownerId),
            content: content,
            trigger: trigger,
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("Pulse reminders: schedule failed %@", error.localizedDescription)
            }
        }
    }

    static func notificationID(reminderId: String, ownerId: String?) -> String {
        guard let ownerId else { return "pulse-reminder-\(reminderId)" }
        return "pulse-reminder:\(ownerId.utf8.count):\(ownerId):\(reminderId)"
    }

    static func triggerComponents(remindAtEpochMs: Int64) -> DateComponents {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = Date(timeIntervalSince1970: TimeInterval(remindAtEpochMs) / 1000)
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        components.calendar = calendar
        components.timeZone = calendar.timeZone
        return components
    }

    public static func cancel(reminderId: String, ownerId: String? = nil) {
        let center = UNUserNotificationCenter.current()
        let ids = [notificationID(reminderId: reminderId, ownerId: ownerId), "pulse-reminder-\(reminderId)", "pulse-reminder-fired-\(reminderId)"]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    /// True means accepted by the OS (or already delivered), not proof the user saw it.
    public static func showNow(reminderId: String, note: String, body: String, conversationId: String? = nil, ownerId: String? = nil) async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard !Task.isCancelled else { return false }
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: break
        default: return false
        }
        let identifier = notificationID(reminderId: reminderId, ownerId: ownerId)
        let delivered = await center.deliveredNotifications()
        guard !Task.isCancelled else { return false }
        if delivered.contains(where: { $0.request.identifier == identifier }) { return true }
        let content = UNMutableNotificationContent()
        content.title = note.isEmpty ? "Reminder" : String(note.prefix(64))
        content.body = String(body.prefix(178))
        content.sound = .default
        content.userInfo = Self.userInfo(reminderId: reminderId, conversationId: conversationId)
        // R10-b - same category stamp as schedule() (see above).
        if PulseQuickReply.applies(toConversationId: conversationId) {
            content.categoryIdentifier = PulseQuickReply.categoryId
        }
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: nil,
        )
        do { try await center.add(request); return true }
        catch { return false }
    }

    /// The reminder payload - reminderId + (R2-D) the conversationId the tap
    /// deep-links into. Kept in one place so schedule/showNow stay in lockstep.
    /// Internal (not private) so the tests can pin the exact payload shape.
    static func userInfo(reminderId: String, conversationId: String?) -> [String: String] {
        var info = ["reminderId": reminderId]
        // Whitespace-only ids count as blank - a tap would deep-link nowhere.
        if let conversationId, !conversationId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            info["conversationId"] = conversationId
        }
        return info
    }
}

// R2-D - notification-tap routing (web reminder tap parity).
//
// willPresent keeps reminder alerts visible while the app is foregrounded
// (the due-loop fires while the user is IN the app); didReceive maps the
// tapped reminder to PulseDeepLink.room(conversationId) and hands it to the
// RootView-assigned onOpenRoom closure. The delegate is registered once in
// PulseApp.init; the last tapped room survives the cold-start window before
// RootView.onAppear attaches (consumeLastTappedRoomId replays it).

public final class PulseReminderNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    public static let shared = PulseReminderNotificationDelegate()

    /// RootView assigns - receives the deep link parsed from the tapped
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
        let info = notification.request.content.userInfo
        if let expiry = info["expiresAt"] {
            guard let raw = expiry as? String, PulseRemediationLogic.isAlive(raw) else {
                completionHandler([])
                return
            }
        }
        completionHandler([.banner, .list, .sound])
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void,
    ) {
        // R10-b - quick reply: a text-input response on the PULSE_MSG
        // category carries the typed message. Route it to the coordinator
        // (live-session send, or the honest open-room fallback - never a
        // fake send). Any other response keeps the tap routing below.
        if let textResponse = response as? UNTextInputNotificationResponse,
           response.actionIdentifier == PulseQuickReply.replyActionId {
            let userInfo = response.notification.request.content.userInfo
            if let conversationId = Self.roomId(from: userInfo) {
                let text = textResponse.userText
                Task { @MainActor in
                    PulseQuickReplyCoordinator.shared.handle(text: text, conversationId: conversationId)
                }
                completionHandler()
                return
            }
        }
        let userInfo = response.notification.request.content.userInfo
        if let link = Self.deepLink(from: userInfo), case .room(let conversationId) = link {
            // The routing closure is formed on the main actor (RootView) -
            // hop there before invoking so the session handoff is isolated.
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.lastTappedRoomId = conversationId
                self.onDeepLink?(link)
            }
        }
        completionHandler()
    }

    /// Cold-start replay - returns (and clears) a tap that arrived before the
    /// RootView closure was assigned. Nil when nothing is pending.
    public func consumeLastTappedRoomId() -> String? {
        let pending = lastTappedRoomId
        lastTappedRoomId = nil
        return pending
    }

    /// userInfo → deep link. Reminder notifications map to
    /// PulseDeepLink.room(conversationId) - the exact value the pulse://room
    /// scheme produces, so tap routing stays single-sourced with F-DL.
    static func deepLink(from userInfo: [AnyHashable: Any]) -> PulseDeepLink? {
        guard let conversationId = roomId(from: userInfo) else { return nil }
        return PulseDeepLink.room(conversationId: conversationId)
    }

    /// Pure userInfo decode - "conversationId" must be a non-empty string.
    static func roomId(from userInfo: [AnyHashable: Any]) -> String? {
        guard let raw = userInfo["conversationId"] else { return nil }
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// A failed server resolve is retried without replaying sound every 30 seconds.
/// Record only accepted notifications; denied/failed enqueue remains retryable.
@MainActor
enum PulseReminderDelivery {
    private static var flights: [String: UUID] = [:]
    static func ownerKey(_ api: PulseAPIClient) -> String {
        let origin = api.baseURL.absoluteString
        return "\(origin.utf8.count):\(origin):\(api.userId)"
    }
    private static func key(_ owner: String, _ id: String) -> String {
        "reminder.queued:\(owner.utf8.count):\(owner):\(id)"
    }
    static func queueOnce(ownerId: String, reminderId: String, defaults: UserDefaults = .standard,
                          retract: () -> Void = {}, enqueue: () async -> Bool) async -> Bool {
        let key = key(ownerId, reminderId)
        if defaults.string(forKey: key) == "finished" { return false }
        if defaults.bool(forKey: key) { return true }
        guard flights[key] == nil else { return false }
        let token = UUID()
        flights[key] = token
        defer { if flights[key] == token { flights[key] = nil } }
        guard await enqueue() else { return false }
        // Cancellation may happen while the OS add() is suspended. Retract a
        // late acceptance as well as blocking all future stale due responses.
        guard flights[key] == token, defaults.string(forKey: key) != "finished" else {
            retract()
            return false
        }
        defaults.set(true, forKey: key)
        return true
    }
    static func finish(ownerId: String, reminderId: String, defaults: UserDefaults = .standard, cancel: () -> Void) {
        let key = key(ownerId, reminderId)
        defaults.set("finished", forKey: key)
        flights[key] = nil
        cancel()
    }
}
