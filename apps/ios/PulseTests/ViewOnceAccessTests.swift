import XCTest
@testable import Pulse

final class ViewOnceAccessTests: XCTestCase {
    private func row(_ changes: [String: Any] = [:]) throws -> WireChatMessage {
        var json: [String: Any] = ["id": "photo", "conversationId": "room", "senderId": "peer", "content": "caption",
            "kind": "image", "createdAt": "2026-10-21T00:00:00Z", "viewOnce": true, "imagePath": "private.jpg"]
        changes.forEach { json[$0.key] = $0.value }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }
    func testOnlyAnUnopenedReceivedLivePhotoMayRequest() throws {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(PulseViewOnceAccess.canRequest(try row(), viewerId: "me", now: now))
        XCTAssertFalse(PulseViewOnceAccess.canRequest(try row(), viewerId: "", now: now))
        let changes: [[String: Any]] = [["senderId": "me"], ["deletedAt": "now"], ["viewedAt": "now"],
            ["viewOnce": false], ["imagePath": ""], ["id": "local_pending"], ["expiresAt": "1970-01-01T00:16:40Z"]]
        for change in changes {
            XCTAssertFalse(PulseViewOnceAccess.canRequest(try row(change), viewerId: "me", now: now))
        }
    }
    func testAConsumedStampDoesNotGrantAnotherReveal() throws {
        let requested = try row()
        let granted = try row(["viewedAt": "2026-10-21T00:01:00Z", "viewedBy": "me"])
        XCTAssertTrue(PulseViewOnceAccess.granted(true, requested: requested, returned: granted, viewerId: "me"))
        XCTAssertFalse(PulseViewOnceAccess.granted(nil, requested: requested, returned: granted, viewerId: "me"))
        XCTAssertFalse(PulseViewOnceAccess.granted(false, requested: requested, returned: granted, viewerId: "me"))
        XCTAssertFalse(PulseViewOnceAccess.granted(true, requested: requested, returned: granted, viewerId: "other"))
        XCTAssertFalse(PulseViewOnceAccess.granted(true, requested: requested, returned: granted.deletedCopy(), viewerId: "me"))
    }
    func testLegacyWireAcknowledgementFailsClosed() throws {
        let message = try JSONSerialization.jsonObject(with: JSONEncoder().encode(row()))
        let data = try JSONSerialization.data(withJSONObject: ["message": message])
        let receipt = try JSONDecoder().decode(WireViewOnceReceipt.self, from: data)
        XCTAssertNil(receipt.consumed)
    }
}
