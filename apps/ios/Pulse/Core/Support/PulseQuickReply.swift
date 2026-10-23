import Foundation
import UserNotifications

// R10-b - notification quick reply (UNTextInputNotificationAction).
//
// Notification actions commit to the current account's durable outbox before
// dispatch. The bound outbox engine owns retries, FIFO and acknowledgement;
// no asynchronous direct-send fallback can write into a subsequently switched
// session or overwrite the user's composer draft. Locked/oversized replies are
// retained as non-dispatchable failed intents in Drafts & outbox.
// A missing account store cannot safely assign text to a future login: that
// path reports no save and opens the app without inventing a send.

public enum PulseQuickReply {
    /// Registered in project.yml-independent client code; remote pushes
    /// reference it through the APNs `aps.category` key.
    public static let categoryId = "PULSE_MSG"
    public static let replyActionId = "PULSE_REPLY_ACTION"

    /// Registers the quick-reply category - idempotent per launch (the
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

    enum Persistence: Equatable {
        case ignored
        case queued
        case held(String)
    }

    /// This synchronous commit happens before the OS completion handler. No
    /// network or current-session lookup occurs after the account store capture.
    static func persist(text: String, conversationId: String, clientId: String,
                        locked: Bool, store: PulseStore) throws -> Persistence {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let room = conversationId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, !room.isEmpty else { return .ignored }
        let reason: String? = locked ? "Unlock Pulse before retrying this reply." :
            body.utf16.count > 2000 ? "Reply exceeds 2000 characters. Copy and shorten it before sending." : nil
        let pending = try store.persistNotificationReply(conversationId: room, clientId: clientId, content: body, reason: reason)
        return pending ? .queued : .held(reason ?? "This reply is saved for review.")
    }

    /// Whether a posting that carries this conversationId may carry the
    /// quick-reply category. Blank ids never qualify - a Reply action
    /// without a routable room would be a dead control.
    public static func applies(toConversationId conversationId: String?) -> Bool {
        guard let trimmed = conversationId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return false }
        return true
    }
}

/// Sends (or honestly falls back for) a quick reply typed on a banner.
/// Main-actor singleton; the session handoff mirrors
/// PulsePushRegistrationCenter.attach(apiProvider:) - the same seam,
/// attached once from RootView.onAppear.
@MainActor
public final class PulseQuickReplyCoordinator {

    public static let shared = PulseQuickReplyCoordinator()

    /// RootView attaches - the live session provider (rebuilt per gateway
    /// override + identity). Nil provider / nil session / no viewer → the
    /// honest open-room fallback.
    private var sessionProvider: (() -> PulseSession?)?

    /// RootView attaches - opens a conversation through the standard
    /// pendingLinkedRoomId bridge (RootView.openLinkedRoom), the exact
    /// path notification TAPS take.
    public var onOpenRoom: ((String) -> Void)?

    private init() {}

    public func attach(sessionProvider: @escaping () -> PulseSession?) {
        self.sessionProvider = sessionProvider
    }

    /// Entry - PulseReminderNotificationDelegate.didReceive funnels every
    /// PULSE_REPLY_ACTION text-input response here.
    public func handle(text: String, conversationId: String) {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        guard let session = sessionProvider?(), session.viewer != nil, let store = session.store else {
            NSLog("Pulse quick reply: no account store - reply was not saved")
            sessionProvider?()?.toasts.show("Reply was not saved. Open Pulse and retry after your chats load.")
            openRoom(conversationId)
            return
        }
        do {
            switch try PulseQuickReply.persist(text: body, conversationId: conversationId,
                clientId: UUID().uuidString, locked: PulseAppLock.shared.needsLock, store: store) {
            case .ignored: return
            case .queued:
                session.outbox?.reloadPending()
                session.toasts.show("Reply queued for delivery")
                session.flushOutbox()
            case .held(let reason):
                session.toasts.show("Reply saved in Drafts & outbox. " + reason)
                openRoom(conversationId)
            }
        } catch {
            // Do not mask a failed write with a success toast or clobber an
            // unrelated draft while trying a second write to the same disk.
            session.toasts.show("Reply was not saved: check device storage before retrying.")
            openRoom(conversationId)
        }
    }

    private func openRoom(_ conversationId: String) {
        onOpenRoom?(conversationId)
    }

}
