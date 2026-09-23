import XCTest
@testable import Pulse

/// R2-D ITEM 5 — reminder-notification tap routing. The delegate decodes the
/// notification userInfo into PulseDeepLink.room(conversationId) — the exact
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
}
