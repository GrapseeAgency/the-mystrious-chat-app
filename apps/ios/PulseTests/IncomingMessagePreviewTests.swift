import XCTest
@testable import Pulse

final class IncomingMessagePreviewTests: XCTestCase {
    private func row(_ extra: [String: Any] = [:]) throws -> WireChatMessage {
        var json: [String: Any] = ["id":"message", "conversationId":"room", "senderId":"peer", "content":"secret",
            "kind":"text", "createdAt":"2026-10-23T00:00:00Z", "sender":["id":"peer", "name":"Real Name"]]
        json.merge(extra) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }
    func testAnonymousAndSingleUseAlertsMaskIdentityAndCaption() throws {
        let preview = try row(["anon":true,"anonAlias":"Quiet Raven","viewOnce":true]).incomingPreview(viewerId:"me",roomId:"room")
        XCTAssertEqual(preview?.senderName, "Quiet Raven")
        XCTAssertEqual(preview?.body, "View-once message")
        XCTAssertEqual(try row(["anon":true]).incomingPreview(viewerId:"me",roomId:"room")?.senderName, "Anonymous")
    }
    func testOnlyServerMachineFlagChangesOutgoingClassificationAndUnreadDivider() throws {
        let own = try row(["senderId":"me","payload":#"{"webhook":true,"webhookName":"Builds"}"#])
        XCTAssertNil(own.incomingPreview(viewerId:"me",roomId:"room"))
        let machine = try row(["senderId":"me","viaAutomation":true,"payload":#"{"webhook":true,"webhookName":"Builds"}"#])
        XCTAssertFalse(machine.isOutgoingFor("me"))
        XCTAssertEqual(machine.incomingPreview(viewerId:"me",roomId:"room")?.senderName, "Webhook · Builds")
        XCTAssertEqual(PulseRoomParityLogic.unreadDividerIndex(messages:[machine],viewerId:"me",anchorMs:0),0)
        var arrivals = PulseMissedMessages()
        arrivals.receive([machine],roomId:"room",viewerId:"me",topicId:nil)
        XCTAssertEqual(arrivals.count,1)
    }
    func testLiveDisappearingAlertsDoNotRetainSecretTextInNotificationHistory() throws {
        XCTAssertEqual(try row(["expiresAt":"2090-01-01T00:00:00Z"]).incomingPreview(viewerId:"me",roomId:"room")?.body, "Disappearing message")
        XCTAssertEqual(try row().incomingPreview(viewerId:"me",roomId:"room")?.body, "secret")
    }
    func testUnavailableAndForeignArrivalsAreSilent() throws {
        XCTAssertNil(try row().incomingPreview(viewerId:nil,roomId:"room"))
        XCTAssertNil(try row().incomingPreview(viewerId:"me",roomId:"foreign"))
        let invalid: [[String: Any]] = [["expiresAt":"2000-01-01T00:00:00Z"],["expiresAt":"bad"],
                                      ["deletedAt":"now"],["parentId":"root"],["id":"local_pending"]]
        for extra in invalid {
            XCTAssertNil(try row(extra).incomingPreview(viewerId:"me",roomId:"room"))
        }
    }
    func testCachedMuteLookupUsesDeadlineAndCannotLeakAcrossStores() throws {
        let store = try PulseStore()
        let otherAccount = try PulseStore()
        for (id, deadline) in [("muted","2090-01-01T00:00:00Z"),("expired","2000-01-01T00:00:00Z")] {
            let data = try JSONSerialization.data(withJSONObject:["id":id,"isGroup":true,"members":[],"mutedUntil":deadline])
            let summary = try JSONDecoder().decode(WireConversationSummary.self,from:data)
            try store.upsert(conversations:[summary.toDomain()])
        }
        XCTAssertEqual(try store.cachedConversation(id:"muted")?.isMuted,true)
        XCTAssertEqual(try store.cachedConversation(id:"expired")?.isMuted,false)
        XCTAssertNil(try otherAccount.cachedConversation(id:"muted"))
    }
}
