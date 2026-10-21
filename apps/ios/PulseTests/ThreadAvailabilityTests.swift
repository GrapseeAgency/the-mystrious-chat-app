import XCTest
@testable import Pulse

final class ThreadAvailabilityTests: XCTestCase {
    private func root(_ changes: [String: Any] = [:]) throws -> WireChatMessage {
        var value: [String: Any] = ["id": "root", "conversationId": "room", "senderId": "peer", "kind": "text", "content": "thread", "createdAt": "2026-10-21T00:00:00Z"]
        value.merge(changes) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: value))
    }

    func testRootAvailabilityMatchesSendAndReadBoundaries() throws {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(PulseRemediationLogic.threadRootAvailable(try root(), roomId: "room", rootId: "root", now: now))
        let changes: [[String: Any]] = [["deletedAt": "now"], ["parentId": "other"], ["conversationId": "other"], ["id": "other"],
            ["expiresAt": "1970-01-01T00:16:40Z"], ["expiresAt": "malformed"]]
        for change in changes {
            XCTAssertFalse(PulseRemediationLogic.threadRootAvailable(try root(change), roomId: "room", rootId: "root", now: now))
        }
    }

    func testExpiredCachedRootCanBeReadForDenialWithoutRenderingItAsLive() throws {
        let store = try PulseStore()
        let expired = try root(["expiresAt": "2000-01-01T00:00:00Z"])
        try store.upsert(messages: [expired])
        XCTAssertNil(try store.message(id: "root"))
        let retained = try XCTUnwrap(store.message(id: "root", now: .distantPast))
        XCTAssertFalse(PulseRemediationLogic.threadRootAvailable(retained, roomId: "room", rootId: "root"))
    }
    func testPendingEchoCannotBecomeAThreadRootBeforeAcknowledgement() throws {
        XCTAssertFalse(PulseRemediationLogic.threadRootAvailable(try root(["id": "local_pending"]),
            roomId: "room", rootId: "local_pending"))
    }

}
