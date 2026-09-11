import XCTest
@testable import Pulse

/// Wave 3 — the call-log cache + offline single-writer queue in PulseStore:
/// v4→v5 migration survival, wire sync + prune, and queue dedupe semantics.
final class PulseStoreCallLogTests: XCTestCase {

    private func makeStore() throws -> PulseStore { try PulseStore() }

    private func row(
        id: String,
        status: String = "completed",
        outgoing: Bool = true,
        duration: Int = 42,
        startedAt: String = "2026-01-01T00:0\(id.count % 10):00.000Z",
    ) -> CallLogEntry {
        CallLogEntry(
            id: id,
            conversationId: "conv-1",
            callerId: outgoing ? "me" : "peer",
            calleeId: outgoing ? "peer" : "me",
            kind: "voice",
            status: status,
            durationSec: duration,
            startedAt: startedAt,
            outgoing: outgoing,
            peerId: outgoing ? "peer" : "me",
            peerName: "Ada Lovelace",
            peerUsername: "ada",
            peerColor: "emerald",
            peerAvatar: nil,
        )
    }

    // ── cache round-trip ────────────────────────────────────

    func testCallLogRoundTripAndOrdering() throws {
        let store = try makeStore()
        let older = row(id: "r1", startedAt: "2026-01-01T00:00:00.000Z")
        let newer = row(id: "r2", startedAt: "2026-01-02T00:00:00.000Z")
        try store.upsert(callLog: [older, newer])
        let rows = try store.callLog()
        XCTAssertEqual(rows.map(\.id), ["r2", "r1"], "history must read newest-first")
        XCTAssertEqual(rows.first?.peerName, "Ada Lovelace")
        XCTAssertEqual(rows.first?.peer.username, "ada")
    }

    func testServerPruneDropsRowsTheEndpointNoLongerLists() throws {
        let store = try makeStore()
        try store.upsert(callLog: [row(id: "r1"), row(id: "r2")])
        // Server truth now only lists r2 — r1 must vanish (server cap 50).
        try store.syncCallLog(from: [wireCallItem(id: "r2")])
        let rows = try store.callLog()
        XCTAssertEqual(rows.map(\.id), ["r2"])
    }

    func testSyncCallLogMapsWirePeerDenormalized() throws {
        let store = try makeStore()
        try store.syncCallLog(from: [
            WireCallLogItem(
                id: "srv-1",
                conversationId: "conv-9",
                callerId: "me",
                calleeId: "peer",
                kind: nil,
                status: "missed",
                durationSec: nil,
                startedAt: "2026-01-03T00:00:00.000Z",
                outgoing: true,
                peer: WireCallPeerInfo(id: "peer", name: "Ada Lovelace", username: "ada", color: "emerald", avatar: nil),
            ),
        ])
        let rows = try store.callLog()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].kind, "voice", "nil wire kind degrades to voice")
        XCTAssertEqual(rows[0].durationSec, 0, "nil wire duration degrades to 0")
        XCTAssertEqual(rows[0].peerId, "peer")
        XCTAssertEqual(rows[0].peerName, "Ada Lovelace")
    }

    // ── offline single-writer queue ─────────────────────────

    func testQueueDedupesOnIdenticalPayload() throws {
        let store = try makeStore()
        let payload: [String: Any] = [
            "userId": "me",
            "conversationId": "conv-1",
            "peerId": "peer",
            "kind": "voice",
            "status": "missed",
            "durationSec": 0,
        ]
        try store.appendCallLogQueue(payload: payload)
        try store.appendCallLogQueue(payload: payload)
        XCTAssertEqual(try store.callLogQueueAll().count, 1, "UNIQUE payloadJson must dedupe double-enqueues")
    }

    func testQueueFIFOAndAttemptBumps() throws {
        let store = try makeStore()
        try store.appendCallLogQueue(payload: ["userId": "a"])
        try store.appendCallLogQueue(payload: ["userId": "b"])
        let queue = try store.callLogQueueAll()
        XCTAssertEqual(queue.count, 2)
        // FIFO: the first row is the oldest.
        XCTAssertTrue((queue.first?.payloadJson ?? "").contains("\"a\""))
        let firstId = queue[0].id!
        try store.bumpCallLogQueueAttempts(id: firstId)
        queue = try store.callLogQueueAll()
        XCTAssertEqual(queue.first { $0.id == firstId }?.attempts, 1)
        try store.deleteCallLogQueue(id: firstId)
        queue = try store.callLogQueueAll()
        XCTAssertEqual(queue.count, 1)
        XCTAssertFalse((queue.first?.payloadJson ?? "").contains("\"a\""))
    }

    // ── fixture ─────────────────────────────────────────────

    private func wireCallItem(id: String) -> WireCallLogItem {
        WireCallLogItem(
            id: id,
            conversationId: "conv-1",
            callerId: "me",
            calleeId: "peer",
            kind: "voice",
            status: "completed",
            durationSec: 10,
            startedAt: "2026-01-02T00:00:00.000Z",
            outgoing: true,
            peer: WireCallPeerInfo(id: "peer", name: "Ada Lovelace", username: nil, color: "emerald", avatar: nil),
        )
    }
}
