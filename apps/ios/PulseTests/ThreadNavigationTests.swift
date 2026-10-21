import XCTest
@testable import Pulse

final class ThreadNavigationTests: XCTestCase {
    private func reply(_ fields: [String: Any] = [:]) throws -> WireChatMessage {
        var json: [String: Any] = ["id": "reply", "conversationId": "room", "parentId": "root",
            "senderId": "peer", "content": "text", "kind": "text", "createdAt": "2026-10-21T00:00:00Z"]
        json.merge(fields) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }

    func testExactReplyRequiresLiveIdentityInThisThread() throws {
        let now = Date(timeIntervalSince1970: 1000)
        let candidate = try reply()
        XCTAssertEqual(PulseThreadNavigation.target(in: [candidate], roomId: "room", rootId: "root", targetId: "reply", now: now)?.id, "reply")
        XCTAssertNil(PulseThreadNavigation.target(in: [candidate], roomId: "room", rootId: "root", targetId: "missing", now: now))
        let invalid: [[String: Any]] = [["parentId": "other"], ["conversationId": "other"], ["deletedAt": "now"],
            ["expiresAt": "1970-01-01T00:16:40Z"], ["expiresAt": "bad"]]
        for fields in invalid {
            XCTAssertNil(PulseThreadNavigation.target(in: [try reply(fields)], roomId: "room", rootId: "root", targetId: "reply", now: now))
        }
    }

    func testIncomingReplyCannotStealAnOlderAnchorOrReadingPosition() {
        XCTAssertFalse(PulseThreadNavigation.shouldFollowTail(nearTail: false, pendingTarget: false))
        XCTAssertFalse(PulseThreadNavigation.shouldFollowTail(nearTail: true, pendingTarget: true))
        XCTAssertFalse(PulseThreadNavigation.shouldFollowTail(nearTail: false, pendingTarget: true))
        XCTAssertTrue(PulseThreadNavigation.shouldFollowTail(nearTail: true, pendingTarget: false))
    }
}
