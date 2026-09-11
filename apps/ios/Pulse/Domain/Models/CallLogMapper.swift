import Foundation

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 call-log mapping.
//
// Single-writer rule (src/app/api/calls/route.ts + web call-overlay.tsx):
// the CALLER's client writes EVERY terminal row exactly once — the callee
// never writes, so no double rows are possible. Wire statuses are exactly
// completed | missed | declined; durationSec rides the wire (NOT durationMs).
//
// POST /api/calls body:
//   { userId, conversationId, peerId, kind, status, durationSec? }
// ─────────────────────────────────────────────────────────────

public enum CallLogMapper {
    /// Outcome → wire status (identity mapping over the three wire values).
    public static func wireStatus(for outcome: CallOutcome) -> String {
        outcome.rawValue
    }

    /// The single-writer rule: only the caller writes, and never against
    /// itself (self-chats can't ring anyway — the relay needs two identities).
    public static func shouldCallerWrite(
        direction: CallDirection,
        peerId: String,
        viewerId: String
    ) -> Bool {
        direction == .outgoing && !peerId.isEmpty && peerId != viewerId && !viewerId.isEmpty
    }

    /// Terminal outcome + direction → the exact POST /api/calls body.
    public static func restBody(
        viewerId: String,
        conversationId: String,
        peerId: String,
        kind: CallKind,
        outcome: CallOutcome,
        durationSec: Int
    ) -> [String: Any] {
        [
            "userId": viewerId,
            "conversationId": conversationId,
            "peerId": peerId,
            "kind": kind.rawValue,
            "status": wireStatus(for: outcome),
            // Server clamps to ≥0 and floors; only meaningful for completed.
            "durationSec": max(0, durationSec),
        ]
    }

    /// GET /api/calls row → cached history entry (tolerant: status/kind fall
    /// back exactly like the server's toItem guard — bad status → missed,
    /// bad kind → voice).
    public static func entry(from wire: WireCallLogItem) -> CallLogEntry? {
        guard !wire.id.isEmpty else { return nil }
        let status = CallOutcome(rawValue: wire.status) ?? .missed
        let kind = CallKind(wireValue: wire.kind)
        let peer = wire.peer
        return CallLogEntry(
            id: wire.id,
            conversationId: wire.conversationId,
            callerId: wire.callerId,
            calleeId: wire.calleeId,
            kind: kind.rawValue,
            status: status.rawValue,
            durationSec: max(0, wire.durationSec ?? 0),
            startedAt: wire.startedAt,
            outgoing: wire.outgoing,
            peerId: peer?.id ?? "",
            peerName: peer?.name ?? "Unknown",
            peerUsername: peer?.username,
            peerColor: peer?.color,
            peerAvatar: peer?.avatar,
        )
    }

    /// History-row presentation — web calls-page.tsx parity for ALL 8
    /// directive cases:
    ///   outgoing accepted  → "Outgoing voice call" (emerald, ↑, duration)
    ///   outgoing declined  → "Declined voice call" (rose, ↑)
    ///   outgoing cancelled → status missed → "Missed voice call" (rose, ↑)
    ///   incoming accepted  → "Incoming voice call" (emerald, ↓, duration)
    ///   incoming declined  → "Declined voice call" (rose, ↓)
    ///   timeout            → status missed → "Missed voice call" (rose, ↑)
    ///   missed             → status missed → "Missed voice call" (rose, ↓)
    ///   completed w/ time  → duration text ("45 sec" / "12 min" / "1 h 05 min")
    public static func presentation(for entry: CallLogEntry) -> CallRowPresentation {
        let outcome = CallOutcome(rawValue: entry.status) ?? .missed
        let kind = CallKind(wireValue: entry.kind)
        let missed = outcome != .completed
        let verb: String
        switch outcome {
        case .declined: verb = "Declined"
        case .missed: verb = "Missed"
        case .completed: verb = entry.outgoing ? "Outgoing" : "Incoming"
        }
        let noun = kind == .video ? "video call" : "voice call"
        let durationText = outcome == .completed && entry.durationSec > 0
            ? CallFormat.historyDuration(entry.durationSec)
            : ""
        return CallRowPresentation(
            verb: "\(verb) \(noun)",
            isMissed: missed,
            isVideo: kind == .video,
            isOutgoing: entry.outgoing,
            durationText: durationText,
        )
    }
}

/// One call-history row presentation (icon/tone/labels for CallsView).
public struct CallRowPresentation: Equatable, Sendable {
    /// e.g. "Outgoing voice call" / "Missed voice call" / "Declined video call".
    public let verb: String
    /// Rose tone when missed/declined, emerald otherwise (web parity).
    public let isMissed: Bool
    /// Video glyph rides the row label (native UI stays audio — the row
    /// honestly reports what the wire logged).
    public let isVideo: Bool
    /// Arrow direction: outgoing ↑ / incoming ↓.
    public let isOutgoing: Bool
    /// "45 sec" / "12 min" / "1 h 05 min" — empty when the call has no duration.
    public let durationText: String

    public init(
        verb: String,
        isMissed: Bool,
        isVideo: Bool,
        isOutgoing: Bool,
        durationText: String
    ) {
        self.verb = verb
        self.isMissed = isMissed
        self.isVideo = isVideo
        self.isOutgoing = isOutgoing
        self.durationText = durationText
    }
}
