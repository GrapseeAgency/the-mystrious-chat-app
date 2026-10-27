import XCTest
@testable import Pulse

@MainActor
final class RoomDetailReadWatermarkTests: XCTestCase {
    private func room(_ stamp: String?, id: String = "read-room") throws -> WireConversationSummary {
        var reader: [String: Any] = ["id": "peer", "name": "Peer", "color": "amber", "readMembershipId": "peer-membership"]
        reader["lastReadAt"] = stamp ?? NSNull()
        return try JSONDecoder().decode(WireConversationSummary.self, from: JSONSerialization.data(withJSONObject: [
            "id": id, "isGroup": false, "members": [reader], "screenPrivacy": false,
        ]))
    }

    func testInfoProofPatchesTheLiveRiverEvenWithoutACachedRowAndNeverRewritesContent() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00Z"), session: session, beginAutomaticWork: false)
        var message = OutboxFixtures.makeMessage(id: "info", conversationId: "read-room", content: "Current edit")
        message.readByMembershipIds = ["retired-member"]
        model.injectCarrier(message)
        XCTAssertTrue(model.applyMessageReadState("info", memberships: ["peer-membership"], startedAt: session.messageRevision))
        XCTAssertEqual(model.messageInfo("info")?.readByMembershipIds, ["peer-membership"])
        XCTAssertEqual(model.messageInfo("info")?.content, "Current edit")
        XCTAssertFalse(model.applyMessageReadState("info", memberships: ["peer-membership"], startedAt: session.messageRevision))
        XCTAssertTrue(model.applyMessageReadState("info", memberships: [], startedAt: session.messageRevision))
        XCTAssertEqual(model.messageInfo("info")?.readByMembershipIds, [])
        XCTAssertFalse(model.applyMessageReadState("absent", memberships: ["peer-membership"], startedAt: session.messageRevision))
        model.injectCarrier(OutboxFixtures.makeMessage(id: "local_pending", conversationId: "read-room", content: "Queued"))
        XCTAssertFalse(model.applyMessageReadState("local_pending", memberships: ["peer-membership"], startedAt: session.messageRevision))
        session.stop()
        XCTAssertFalse(model.applyMessageReadState("info", memberships: ["peer-membership"], startedAt: session.messageRevision))
    }

    func testInfoAdoptionRechecksTheExactMessageRevisionAfterItsAsyncReturn() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00Z"), session: session, beginAutomaticWork: false)
        let message = OutboxFixtures.makeMessage(id: "info", conversationId: "read-room", content: "Current")
        model.injectCarrier(message)
        let revision = session.messageRevision
        try session.recordMessageMutation(OutboxFixtures.makeMessage(id: "other", conversationId: "read-room", content: "Unrelated"))
        XCTAssertTrue(model.applyMessageReadState("info", memberships: ["peer-membership"], startedAt: revision))
        try session.recordMessageMutation(message)
        XCTAssertFalse(model.applyMessageReadState("info", memberships: [], startedAt: revision))
        XCTAssertEqual(model.messageInfo("info")?.readByMembershipIds, ["peer-membership"])
    }

    func testReadTrafficCannotPreventCanonicalPrivacyAndMembershipAdoption() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00Z"), session: session, beginAutomaticWork: false)
        let newer = try XCTUnwrap(PulseFormat.date("2026-10-26T12:00:00Z"))
        let visible = try XCTUnwrap(model.beginPrivacyRead())
        model.applyKnownReadStamp(userId: "peer", at: newer)
        XCTAssertTrue(model.applyPrivacyRead(visible, detail: try room("2026-10-25T12:00:00Z")))
        XCTAssertEqual(model.partnerLastReadAt, newer, "A positive canonical snapshot cannot rewind a newer live stamp")
        let hidden = try XCTUnwrap(model.beginPrivacyRead())
        model.applyKnownReadStamp(userId: "peer", at: newer.addingTimeInterval(1))
        XCTAssertTrue(model.applyPrivacyRead(hidden, detail: try room(nil)))
        XCTAssertNil(model.partnerLastReadAt, "Read traffic must not suppress canonical privacy removal")
        XCTAssertNil(model.readReceiptMembers.first?.lastReadAt)
    }

    func testOnlyCurrentAccessRefusalCanRetireTheDisplayedReaders() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room("2026-10-24T12:00:00Z"), session: session, beginAutomaticWork: false)
        let old = try XCTUnwrap(model.beginPrivacyRead())
        let current = try XCTUnwrap(model.beginPrivacyRead())
        XCTAssertFalse(model.applyReadMembersRefusal(old))
        XCTAssertNotNil(model.partnerLastReadAt)
        XCTAssertTrue(model.applyReadMembersRefusal(current))
        XCTAssertNil(model.partnerLastReadAt)
        XCTAssertTrue(model.readReceiptMembers.isEmpty)
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
        object["createdAt"] = "2026-10-25T00:00:00Z"
        object["expiresAt"] = "2026-10-26T00:00:00Z"
        model.injectCarrier(try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object)))
        XCTAssertNotNil(model.messageInfo("expired-info", now: try XCTUnwrap(PulseFormat.date("2026-10-25T23:59:59Z"))))
        XCTAssertNil(model.messageInfo("expired-info", now: try XCTUnwrap(PulseFormat.date("2026-10-26T00:00:00Z"))))
        model.applyFreshDetail(try room(nil))
        XCTAssertEqual(model.readReceiptMembers.first?.id, "peer")
        XCTAssertNil(model.readReceiptMembers.first?.lastReadAt, "The visible info sheet must adopt current receipt privacy")
        session.stop()
        XCTAssertNil(model.messageInfo("info"), "An old sheet must not retain another session's message")
    }

    func testFirstReceiptRequiresFreshSharingMetadataAndHidingItRetiresSeen() throws {
        let session = PulseSession()
        let model = RoomViewModel(conversation: try room(nil), session: session, beginAutomaticWork: false)
        func split() -> PulseRoomParityLogic.ReceiptSplit {
            PulseRoomParityLogic.receiptSplit(members: model.readReceiptMembers.map {
                .init(id: $0.id, lastReadAtIso: $0.lastReadAt, readMembershipId: $0.readMembershipId)
            }, viewerId: "viewer-1", createdAtIso: "2026-10-26T00:00:00Z", readByMembershipIds: ["peer-membership"])
        }
        XCTAssertTrue(split().seenBy.isEmpty)
        model.applyFreshDetail(try room("2026-10-26T00:01:00Z"))
        XCTAssertEqual(split().seenBy.map(\.id), ["peer"])
        model.applyFreshDetail(try room(nil))
        XCTAssertTrue(split().seenBy.isEmpty, "Retained exact proof cannot override current receipt privacy")
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
