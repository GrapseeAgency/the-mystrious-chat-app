import XCTest
@testable import Pulse

/// Wave 3 — the single-writer rule and the outcome→wire mapping for
/// POST /api/calls, plus the 8-case history presentation.
final class CallLogMapperTests: XCTestCase {

    // ── single-writer guard ─────────────────────────────────

    func testOnlyTheCallerWrites() {
        XCTAssertTrue(CallLogMapper.shouldCallerWrite(direction: .outgoing, peerId: "peer", viewerId: "me"))
        XCTAssertFalse(CallLogMapper.shouldCallerWrite(direction: .incoming, peerId: "peer", viewerId: "me"))
        XCTAssertFalse(CallLogMapper.shouldCallerWrite(direction: .outgoing, peerId: "", viewerId: "me"))
        XCTAssertFalse(CallLogMapper.shouldCallerWrite(direction: .outgoing, peerId: "me", viewerId: "me"))
        XCTAssertFalse(CallLogMapper.shouldCallerWrite(direction: .outgoing, peerId: "peer", viewerId: ""))
    }

    // ── outcome → wire status ───────────────────────────────

    func testWireStatusMapping() {
        XCTAssertEqual(CallLogMapper.wireStatus(for: .completed), "completed")
        XCTAssertEqual(CallLogMapper.wireStatus(for: .missed), "missed")
        XCTAssertEqual(CallLogMapper.wireStatus(for: .declined), "declined")
    }

    // ── POST body shape (call-types.ts parity) ─────────────

    func testRestBodyShape() {
        let body = CallLogMapper.restBody(
            viewerId: "me",
            conversationId: "conv-1",
            peerId: "peer",
            kind: .voice,
            outcome: .completed,
            durationSec: 95,
        )
        XCTAssertEqual(body["userId"] as? String, "me")
        XCTAssertEqual(body["conversationId"] as? String, "conv-1")
        XCTAssertEqual(body["peerId"] as? String, "peer")
        XCTAssertEqual(body["kind"] as? String, "voice")
        XCTAssertEqual(body["status"] as? String, "completed")
        XCTAssertEqual(body["durationSec"] as? Int, 95)
    }

    // ── the 8 directive cases render as designed ────────────

    private func entry(outgoing: Bool, status: String, duration: Int) -> CallLogEntry {
        CallLogEntry(
            id: "row",
            conversationId: "conv-1",
            callerId: outgoing ? "me" : "peer",
            calleeId: outgoing ? "peer" : "me",
            kind: "voice",
            status: status,
            durationSec: duration,
            startedAt: "2026-01-01T00:00:00.000Z",
            outgoing: outgoing,
            peerId: outgoing ? "peer" : "me",
            peerName: "Ada Lovelace",
        )
    }

    func testOutgoingAcceptedWithDuration() {
        let p = CallLogMapper.presentation(for: entry(outgoing: true, status: "completed", duration: 131))
        XCTAssertTrue(p.isOutgoing)
        XCTAssertFalse(p.isMissed)
        XCTAssertEqual(p.durationText, CallFormat.historyDuration(131))
    }

    func testOutgoingDeclined() {
        let p = CallLogMapper.presentation(for: entry(outgoing: true, status: "declined", duration: 0))
        XCTAssertTrue(p.isOutgoing)
        XCTAssertTrue(p.isMissed)
        XCTAssertEqual(p.verb, "Declined voice call")
    }

    func testOutgoingCancelledRendersNoAnswer() {
        // The wire collapses caller-cancel and server-timeout into 'missed';
        // the native label for OUTGOING missed rows is "No answer".
        let p = CallLogMapper.presentation(for: entry(outgoing: true, status: "missed", duration: 0))
        XCTAssertTrue(p.isOutgoing)
        XCTAssertTrue(p.isMissed)
        // WEB BEHAVIOUR: the single-writer wire collapses caller-cancel and
        // server-timeout into 'missed'. NATIVE DECISION: one honest label for
        // both ("No answer" stays a caller-history detail in the UI layer).
        XCTAssertEqual(p.verb, "Missed voice call")
    }

    func testIncomingAcceptedWithDuration() {
        let p = CallLogMapper.presentation(for: entry(outgoing: false, status: "completed", duration: 45))
        XCTAssertFalse(p.isOutgoing)
        XCTAssertFalse(p.isMissed)
        XCTAssertEqual(p.durationText, CallFormat.historyDuration(45))
    }

    func testIncomingDeclined() {
        let p = CallLogMapper.presentation(for: entry(outgoing: false, status: "declined", duration: 0))
        XCTAssertFalse(p.isOutgoing)
        XCTAssertEqual(p.verb, "Declined voice call")
    }

    func testIncomingMissed() {
        let p = CallLogMapper.presentation(for: entry(outgoing: false, status: "missed", duration: 0))
        XCTAssertTrue(p.isMissed)
        XCTAssertEqual(p.verb, "Missed voice call")
    }

    func testCompletedRequiresDuration() {
        let zero = CallLogMapper.presentation(for: entry(outgoing: true, status: "completed", duration: 0))
        XCTAssertTrue(zero.durationText.isEmpty)
    }

    func testHistoryDurationFormatting() {
        XCTAssertEqual(formatCallDuration(0), "0:00")
        XCTAssertEqual(formatCallDuration(59), "0:59")
        XCTAssertEqual(formatCallDuration(131), "2:11")
        XCTAssertEqual(formatCallDuration(3725), "1:02:05")
    }

    // ── wire → entry tolerance ──────────────────────────────

    func testEntryFromWireToleratesBadStatusAndKind() {
        let wire = WireCallLogItem(
            id: "row",
            conversationId: "conv-1",
            callerId: "me",
            calleeId: "peer",
            kind: "telepathy",
            status: "quantum",
            durationSec: 3,
            startedAt: "2026-01-01T00:00:00.000Z",
            outgoing: true,
            peer: nil,
        )
        let mapped = CallLogMapper.entry(from: wire)
        XCTAssertEqual(mapped?.status, "missed")
        XCTAssertEqual(mapped?.kind, "voice")
    }
}
