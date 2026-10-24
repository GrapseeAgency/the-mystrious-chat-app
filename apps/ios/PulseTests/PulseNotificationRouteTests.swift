import XCTest
@testable import Pulse

/// R2-D ITEM 5 - reminder-notification tap routing. The delegate decodes the
/// notification userInfo into PulseDeepLink.room(conversationId) - the exact
/// value the pulse://room scheme (RootView F-DL handler) consumes.
final class PulseNotificationRouteTests: XCTestCase {

    func testDeepLinkMapsRoomConversationFromUserInfo() {
        let link = PulseReminderNotificationDelegate.deepLink(from: [
            "reminderId": "r1",
            "conversationId": "conv-42",
        ])
        XCTAssertEqual(link, .room(conversationId: "conv-42"))
    }

    func testMissingOrBlankConversationIdYieldsNoLink() {
        XCTAssertNil(PulseReminderNotificationDelegate.deepLink(from: ["reminderId": "r1"]))
        XCTAssertNil(PulseReminderNotificationDelegate.deepLink(from: ["conversationId": "   "]))
        XCTAssertNil(PulseReminderNotificationDelegate.deepLink(from: [:]))
        // Non-string payloads (relay junk) never route.
        XCTAssertNil(PulseReminderNotificationDelegate.roomId(from: ["conversationId": 42]))
    }

    func testRoomLinkStillParsesThroughThePulseScheme() {
        // The two routes agree: a hand-built pulse://room/<id> URL and the
        // notification-produced deep link resolve to the SAME case.
        let fromScheme = PulseDeepLink.parse(URL(string: "pulse://room/conv-42")!)
        let fromNotification = PulseReminderNotificationDelegate.deepLink(from: ["conversationId": "conv-42"])
        XCTAssertEqual(fromScheme, fromNotification)
        XCTAssertEqual(fromNotification, .room(conversationId: "conv-42"))
    }

    func testScheduledUserInfoCarriesReminderAndConversation() {
        // The payload builder stamps BOTH keys (schedule + showNow share it);
        // nil / blank conversation ids stay out so old reminders stay tapless.
        XCTAssertEqual(
            PulseReminderNotifications.userInfo(reminderId: "r-9", conversationId: "conv-9"),
            ["reminderId": "r-9", "conversationId": "conv-9"],
        )
        XCTAssertEqual(
            PulseReminderNotifications.userInfo(reminderId: "r-9", conversationId: nil),
            ["reminderId": "r-9"],
        )
        XCTAssertEqual(
            PulseReminderNotifications.userInfo(reminderId: "r-9", conversationId: "  "),
            ["reminderId": "r-9"],
        )
    }
    func testRemoteAndAnchoredReminderTapsKeepTheMessageTarget() {
        let expected = PulseDeepLink.room(conversationId: "room", jumpMessageId: "message")
        XCTAssertEqual(PulseReminderNotificationDelegate.deepLink(from: ["kind":"message","conversationId":" room ","messageId":" message "]), expected)
        let info = PulseReminderNotifications.userInfo(reminderId: "r", conversationId: "room", messageId: "message")
        XCTAssertEqual(PulseReminderNotificationDelegate.deepLink(from: info), expected)
        XCTAssertEqual(PulseReminderNotificationDelegate.deepLink(from: ["conversationId":"room","messageId":42]), .room(conversationId:"room"))
        XCTAssertEqual(PulseReminderNotificationDelegate.deepLink(from: ["kind":"gcall","conversationId":"room","messageId":"not-a-message"]), .room(conversationId:"room"))
        XCTAssertNil(PulseReminderNotifications.userInfo(reminderId:"r",conversationId:nil,messageId:"m")["messageId"])
    }

    func testSchemeJumpAndPercentEscapesDecodeExactlyOnce() {
        XCTAssertEqual(PulseDeepLink.parse(URL(string:"pulse://room/a%252Fb?jump=m%252F1")!), .room(conversationId:"a%2Fb",jumpMessageId:"m%2F1"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string:"pulse://room/a%2Fb?jump=m%2F1")!), .room(conversationId:"a/b",jumpMessageId:"m/1"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string:"pulse:chat/room?jump=message")!), .room(conversationId:"room",jumpMessageId:"message"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string:"pulse://room/a+b?jump=m+1")!), .room(conversationId:"a+b",jumpMessageId:"m+1"))
        XCTAssertEqual(PulseDeepLink.parse(URL(string:"pulse://room/room?jump=%20")!), .room(conversationId:"room"))
    }

    @MainActor func testColdTapReplaysLatestFullTargetOnceAndWarmTapIsNotBufferedAgain() {
        let delegate = PulseReminderNotificationDelegate()
        delegate.route(.room(conversationId:"old",jumpMessageId:"old-message"))
        let expected = PulseDeepLink.room(conversationId:"latest",jumpMessageId:"latest-message")
        delegate.route(expected)
        XCTAssertEqual(delegate.consumeLastTappedLink(), expected)
        XCTAssertNil(delegate.consumeLastTappedLink())
        var received: [PulseDeepLink] = []
        delegate.onDeepLink = { received.append($0) }
        delegate.route(expected)
        XCTAssertEqual(received, [expected])
        XCTAssertNil(delegate.consumeLastTappedLink())
    }

}
