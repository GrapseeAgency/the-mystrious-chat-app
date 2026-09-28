import Foundation
import UserNotifications

// ─────────────────────────────────────────────────────────────
// R10-b — notification quick reply (UNTextInputNotificationAction).
//
// Chain: PulseApp.init registers the "PULSE_MSG" category (a "Reply"
// text-input action) once at process start → every LOCAL notification
// posting that points at a conversation carries
// content.categoryIdentifier = PULSE_MSG (PulseReminderNotifications —
// the only in-app notification posting site) → the ALREADY-REGISTERED
// UNUserNotificationCenter delegate (PulseReminderNotificationDelegate)
// routes UNTextInputNotificationResponse to PulseQuickReplyCoordinator →
// the SAME send path ChatRoomView uses (session.api →
// POST /api/conversations/{id}/messages; ChatRoomView.send calls
// api.sendMessageWithStreak — the identical route, this is its plain
// wrapper, the documented non-room send path).
//
// Honest fallbacks — a reply is NEVER faked:
//  • no live session / no identity / app lock armed → the handler opens
//    the conversation through the SAME pendingLinkedRoomId bridge a
//    notification TAP uses (RootView.openLinkedRoom);
//  • network-class send failure → the text rides the REAL offline queue
//    (session.enqueueOutbox — Wave 0 semantics, flushes on
//    foreground/reconnect), mirroring the room's own failure branch;
//  • droppable (4xx) failure → the typed text is preserved as the
//    conversation's local draft (PulseStore.saveDraft — the composer
//    hydrates from it, ChatRoomView :3669) and the room opens with it.
//
// Remote push note (documented honestly): message pushes arrive via APNs
// with the top-level `conversationId` key (server transport.ts sendIos),
// and the system only renders the Reply action when the payload carries
// `aps.category = "PULSE_MSG"`. That one key is a SERVER-side addition
// (src/ is out of this task's blast radius); the client registers the
// category, handles the response, and the action lights up on remote
// pushes the moment the server adds the key — no further client change.
// ─────────────────────────────────────────────────────────────

public enum PulseQuickReply {
    /// Registered in project.yml-independent client code; remote pushes
    /// reference it through the APNs `aps.category` key.
    public static let categoryId = "PULSE_MSG"
    public static let replyActionId = "PULSE_REPLY_ACTION"

    /// Registers the quick-reply category — idempotent per launch (the
    /// set replaces the whole category set). Called once from PulseApp.init
    /// beside the notification-delegate assignment so cold-start banners
    /// already offer the Reply action.
    public static func registerCategory() {
        let reply = UNTextInputNotificationAction(
            identifier: replyActionId,
            title: "Reply",
            options: [.authenticationRequired],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Message…"
        )
        let category = UNNotificationCategory(
            identifier: categoryId,
            actions: [reply],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// Whether a posting that carries this conversationId may carry the
    /// quick-reply category. Blank ids never qualify — a Reply action
    /// without a routable room would be a dead control.
    public static func applies(toConversationId conversationId: String?) -> Bool {
        guard let trimmed = conversationId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return false }
        return true
    }
}

/// Sends (or honestly falls back for) a quick reply typed on a banner.
/// Main-actor singleton; the session handoff mirrors
/// PulsePushRegistrationCenter.attach(apiProvider:) — the same seam,
/// attached once from RootView.onAppear.
@MainActor
public final class PulseQuickReplyCoordinator {

    public static let shared = PulseQuickReplyCoordinator()

    /// RootView attaches — the live session provider (rebuilt per gateway
    /// override + identity). Nil provider / nil session / no viewer → the
    /// honest open-room fallback.
    private var sessionProvider: (() -> PulseSession?)?

    /// RootView attaches — opens a conversation through the standard
    /// pendingLinkedRoomId bridge (RootView.openLinkedRoom), the exact
    /// path notification TAPS take.
    public var onOpenRoom: ((String) -> Void)?

    private init() {}

    public func attach(sessionProvider: @escaping () -> PulseSession?) {
        self.sessionProvider = sessionProvider
    }

    /// Entry — PulseReminderNotificationDelegate.didReceive funnels every
    /// PULSE_REPLY_ACTION text-input response here.
    public func handle(text: String, conversationId: String) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        // App lock interplay — a banner must never bypass the gate: refuse
        // the send, open the room instead (the lock cover covers it).
        if PulseAppLock.shared.needsLock {
            NSLog("Pulse quick reply: app lock armed — falling back to opening the room")
            openRoom(conversationId)
            return
        }
        guard let session = sessionProvider?(), session.viewer != nil else {
            NSLog("Pulse quick reply: no live session — falling back to opening the room")
            openRoom(conversationId)
            return
        }
        Task { [weak self] in
            do {
                // The SAME send path ChatRoomView uses (identical POST
                // /api/conversations/{id}/messages route).
                _ = try await session.api.sendMessage(conversationId: conversationId, content: body)
                NSLog("Pulse quick reply: delivered to %@", conversationId)
            } catch {
                if PulseOutboxEngine.isDroppable(error) {
                    // Permanent refusal (4xx) — the room drops these too.
                    // Keep the text as the local draft and let the user
                    // see + fix it in the room.
                    session.toasts.show(Self.describe(error))
                    try? session.store?.saveDraft(conversationId: conversationId, text: body)
                    self?.openRoom(conversationId)
                } else {
                    // Network-class failure — the room's own semantics:
                    // ride the real offline queue (flushes on
                    // foreground/reconnect).
                    session.enqueueOutbox(conversationId: conversationId, clientId: UUID().uuidString, content: body)
                    session.toasts.show("Message queued — sends when you're back online")
                }
            }
        }
    }

    private func openRoom(_ conversationId: String) {
        onOpenRoom?(conversationId)
    }

    private static func describe(_ error: Error) -> String {
        if let failure = error as? PulseAPIClient.Failure, let message = failure.message, !message.isEmpty {
            return message
        }
        return "Reply not sent — \(error.localizedDescription)"
    }
}
