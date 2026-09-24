import Foundation

// ─────────────────────────────────────────────────────────────
// R2-B — pure room-parity logic ports (no transport, no UI):
//   • unreadAnchorMs       — web chats-tab.tsx:431-435 tap-time freeze
//   • unreadDividerIndex   — web chat-room.tsx:1395-1420 anchor placement
//   • MissedStep           — web chat-room.tsx:1594-1612 missed-count machine
//   • automationValid / webhookNameValid — server validation mirrors
//   • recapGate            — web requestRecap ≥5 live messages gate
// No Date()/Date.now inside: every "now" arrives as a parameter so the
// functions stay deterministic under test.
// ─────────────────────────────────────────────────────────────
public enum PulseRoomParityLogic {

    // ── R30-c — the unread divider anchor ────────────────────

    /// Web chats-tab handlePress parity: the anchor is MY lastReadAt from the
    /// list summary AT TAP TIME, but ONLY when the row carried an unread
    /// badge (unreadCount > 0). No badge / no watermark → nil (no divider,
    /// exactly the web's `Number.isNaN(wm) ? null` branch).
    public static func unreadAnchorMs(myLastReadAtIso: String?, unreadCount: Int?) -> Double? {
        guard let unreadCount, unreadCount > 0 else { return nil }
        guard let iso = myLastReadAtIso, !iso.isEmpty else { return nil }
        guard let date = PulseFormat.date(iso) else { return nil }
        return date.timeIntervalSince1970 * 1000
    }

    /// Web chat-room buildItems parity: the divider sits BEFORE the first
    /// message that is (a) not mine, (b) not deleted, (c) newer than the
    /// anchor. `unreadAnchorMs === null` → never placed (nil). River order
    /// is ascending (oldest → newest), same as the web's built list.
    public static func unreadDividerIndex(
        messages: [WireChatMessage],
        viewerId: String?,
        anchorMs: Double?,
    ) -> Int? {
        guard let anchorMs, let viewerId else { return nil }
        for (index, message) in messages.enumerated() {
            guard message.senderId != viewerId else { continue }
            guard message.deletedAt == nil else { continue }
            guard let created = PulseFormat.date(message.createdAt) else { continue }
            if created.timeIntervalSince1970 * 1000 > anchorMs { return index }
        }
        return nil
    }

    // ── R26 — off-screen arrivals → jump-pill missed badge ───

    /// One step of the web missed-count machine (chat-room.tsx:1594-1612),
    /// extracted pure. Semantics verbatim:
    ///   • at the bottom (tailVisible) → badge resets, watermark advances;
    ///   • more rows than last seen while away → missed grows by the delta;
    ///   • fewer rows (room switch / cache reset) → everything resets;
    ///   • same length → no-op.
    public struct MissedStep: Equatable {
        public let missed: Int
        public let lastSeenLen: Int
        /// True when fresh rows landed while the viewer was away — the
        /// caller flips the pill on (and haptics) from this flag alone.
        public let arrived: Bool
        /// True when the badge/watermark were reset — the caller drops the pill.
        public let reset: Bool
    }

    public static func missedStep(
        previousMissed: Int,
        previousLastSeenLen: Int,
        newLen: Int,
        tailVisible: Bool,
    ) -> MissedStep {
        if tailVisible {
            return MissedStep(missed: 0, lastSeenLen: newLen, arrived: false, reset: true)
        }
        if newLen > previousLastSeenLen {
            return MissedStep(
                missed: previousMissed + (newLen - previousLastSeenLen),
                lastSeenLen: newLen,
                arrived: true,
                reset: false,
            )
        }
        if newLen < previousLastSeenLen {
            return MissedStep(missed: 0, lastSeenLen: newLen, arrived: false, reset: true)
        }
        return MissedStep(missed: previousMissed, lastSeenLen: previousLastSeenLen, arrived: false, reset: false)
    }

    /// The pill badge caps at 99 with a "99+" suffix (web parity).
    public static func missedBadgeText(_ missed: Int) -> String {
        missed > 99 ? "99+" : String(missed)
    }

    // ── R30-b — room-header reminders badge ─────────────────

    /// The viewer's UNFIRED reminders = the header-badge count (web
    /// upcomingReminderCount, chat-room.tsx:2311-2313 `firedAt === null`;
    /// the GET is viewer-scoped so the count spans all rooms). Pure so the
    /// VM fetch stays a one-liner and the rule is testable.
    public static func upcomingReminderCount(_ items: [WireReminderItem]) -> Int {
        items.filter { $0.firedAt == nil }.count
    }

    /// The reminders badge caps at 9 with a "9+" suffix (web
    /// chat-room.tsx:4073 `upcomingReminderCount > 9 ? '9+'` parity).
    public static func reminderBadgeText(_ count: Int) -> String {
        count > 9 ? "9+" : String(count)
    }

    // ── R39 — automation rule validation (server mirrors) ────

    /// Create/rename trigger bounds (automations route AUTOMATION_TRIGGER_*).
    public static let automationTriggerMin = 2
    public static let automationTriggerMax = 40
    /// Reply bound (AUTOMATION_REPLY_MAX).
    public static let automationReplyMax = 500
    /// Webhook display-name bound (webhooks/route.ts WEBHOOK_NAME_MAX).
    public static let webhookNameMax = 32

    /// Trimmed trigger/reply pair must satisfy the server gates BEFORE the
    /// POST/PATCH fires — the honest client-side mirror of the 400 paths.
    public static func automationValid(trigger: String, reply: String) -> Bool {
        let trimmedTrigger = trigger.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedReply = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        let triggerOk = trimmedTrigger.count >= automationTriggerMin
            && trimmedTrigger.count <= automationTriggerMax
        let replyOk = !trimmedReply.isEmpty && trimmedReply.count <= automationReplyMax
        return triggerOk && replyOk
    }

    public static func webhookNameValid(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed.count <= webhookNameMax
    }

    // ── R34-b — AI recap gate ────────────────────────────────

    /// Web requestRecap parity: recap needs ≥5 LIVE (non-deleted) messages
    /// in the room. The caller passes the already-filtered live count.
    public static func recapGatePassed(liveCount: Int) -> Bool {
        liveCount >= 5
    }

    // ── R2-D — message-info receipts (seen vs delivered) ────

    /// One receipt candidate: the member identity + their read watermark.
    /// A plain value keeps the split pure and directly testable.
    public struct ReceiptMember: Equatable, Sendable {
        public let id: String
        public let lastReadAtIso: String?

        public init(id: String, lastReadAtIso: String?) {
            self.id = id
            self.lastReadAtIso = lastReadAtIso
        }
    }

    /// The seen/delivered split behind the message-info sheet (web
    /// chat-room.tsx:5893-5960 + Android MessageSheets.kt:379-424 parity):
    ///   • the viewer is never a recipient (excluded first);
    ///   • "Seen by" = members whose lastReadAt >= the message's createdAt;
    ///   • "Delivered to" = the rest.
    /// A missing or unparseable watermark counts as delivered (never seen) —
    /// web `Number.isNaN(readMs)` parity.
    public struct ReceiptSplit: Equatable, Sendable {
        public let seenBy: [ReceiptMember]
        public let deliveredTo: [ReceiptMember]
    }

    public static func receiptSplit(
        members: [ReceiptMember],
        viewerId: String?,
        createdAtIso: String,
    ) -> ReceiptSplit {
        let others = members.filter { member in
            guard let viewerId, !viewerId.isEmpty else { return true }
            return member.id != viewerId
        }
        guard let createdAt = PulseFormat.date(createdAtIso) else {
            return ReceiptSplit(seenBy: [], deliveredTo: others)
        }
        var seen: [ReceiptMember] = []
        var delivered: [ReceiptMember] = []
        for member in others {
            if let stamp = member.lastReadAtIso, let readAt = PulseFormat.date(stamp),
               readAt >= createdAt {
                seen.append(member)
            } else {
                delivered.append(member)
            }
        }
        return ReceiptSplit(seenBy: seen, deliveredTo: delivered)
    }

    // ── R4-A item 2 — one-shot incognito (web + Android parity) ──

    /// The web disarms the mask after a SERVER-ACCEPTED send
    /// (chat-room.tsx:1747-1751 onSuccess; Android R3-B one-shot disarm,
    /// ChatRoomViewModel.kt:566-570) — the old iOS behavior kept it armed
    /// until manually disarmed. This pure step is the exact shared verdict:
    ///   • armed + server-accepted send → DISARMED (the one-shot consume);
    ///   • queued / offline / 429 / any failure → STILL ARMED (the text may
    ///     still go out later; the mask must not silently drop first).
    /// `armed` is the state CAPTURED AT SEND TIME (Android `anonArmed`
    /// parity) so an in-flight toggle cannot desync the verdict.
    public static func anonDisarmAfterSend(armed: Bool, serverAccepted: Bool) -> Bool {
        if armed && serverAccepted { return false }
        return armed
    }
}
