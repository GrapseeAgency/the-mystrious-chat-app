import XCTest
@testable import Pulse

@MainActor
final class RoomDetailReadWatermarkTests: XCTestCase {
    private func room(_ stamp: String?, id: String = "read-room") throws -> WireConversationSummary {
        var reader: [String: Any] = ["id": "peer", "name": "Peer", "color": "amber"]
        reader["lastReadAt"] = stamp ?? NSNull()
        return try JSONDecoder().decode(WireConversationSummary.self, from: JSONSerialization.data(withJSONObject: [
            "id": id, "isGroup": false, "members": [reader], "screenPrivacy": false,
        ]))
    }

    func testMessageInfoUsesCurrentRoomDataAndRetiresUnavailableOrForeignRows() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00Z"), session: session, beginAutomaticWork: false)
        var message = OutboxFixtures.makeMessage(id: "info", conversationId: "read-room", content: "Current")
        message.readByMembershipIds = ["peer-membership"]
        model.injectCarrier(message)
        XCTAssertEqual(model.messageInfo("info")?.readByMembershipIds, ["peer-membership"])
        XCTAssertEqual(model.messageInfo("info")?.content, "Current")
        model.injectCarrier(OutboxFixtures.makeMessage(id: "foreign", conversationId: "another-room", content: "Private"))
        XCTAssertNil(model.messageInfo("foreign"))
        XCTAssertFalse(model.messages.contains { $0.id == "foreign" })
        XCTAssertNil(model.messageInfo("missing"))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(message)) as? [String: Any])
        object["id"] = "expired-info"
        object["expiresAt"] = "2026-10-26T00:00:00Z"
        model.injectCarrier(try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object)))
        XCTAssertNil(model.messageInfo("expired-info", now: try XCTUnwrap(PulseFormat.date("2026-10-26T00:00:00Z"))))
        model.applyFreshDetail(try room(nil))
        XCTAssertNil(model.readReceiptMembers.first?.lastReadAt, "The visible info sheet must adopt current receipt privacy")
        session.stop()
        XCTAssertNil(model.messageInfo("info"), "An old sheet must not retain another session's message")
    }

    func testLateRoomMetadataDoesNotRewindSeenButHiddenReadersAreRemoved() throws {
        let newest = "2026-10-24T12:00:00.000Z"
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room(newest), session: session, beginAutomaticWork: false)
        let expected = try XCTUnwrap(PulseFormat.date(newest))
        XCTAssertEqual(model.partnerLastReadAt, expected)
        model.applyFreshDetail(try room("2026-10-23T12:00:00.000Z"))
        XCTAssertEqual(model.partnerLastReadAt, expected)
        model.applyFreshDetail(try room("2026-10-25T12:00:00.000Z", id: "foreign-room"))
        XCTAssertEqual(model.partnerLastReadAt, expected)
        model.applyFreshDetail(try room(nil))
        XCTAssertNil(model.partnerLastReadAt, "Hidden privacy timestamps must not survive a fresh metadata snapshot")
    }
    func testCanonicalEpochPrivacySentinelRetiresPreviouslyPublishedSeen() throws {
        let session = PulseSession()
        for hidden in ["1970-01-01T00:00:00.000Z", "1969-12-31T23:59:59.999Z", "invalid"] {
            let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00.000Z"), session: session, beginAutomaticWork: false)
            XCTAssertNotNil(model.partnerLastReadAt)
            model.applyFreshDetail(try room(hidden))
            XCTAssertNil(model.partnerLastReadAt, "A private/unknown reader cannot retain their old positive watermark")
            let cold = RoomViewModel(conversation: try room(hidden), session: session, beginAutomaticWork: false)
            XCTAssertNil(cold.partnerLastReadAt)
        }
    }

}
