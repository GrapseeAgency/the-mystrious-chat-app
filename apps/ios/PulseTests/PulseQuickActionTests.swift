import XCTest
@testable import Pulse

/// R10-b - pure quick-action mapping + the compose deep-link route. The
/// scene-delivery glue (PulseSceneDelegate → pendingRoute → RootView) is
/// UIKit/SwiftUI lifecycle code CI compiles but cannot execute here; these
/// tests pin the pure halves the destinations depend on.
final class PulseQuickActionTests: XCTestCase {

    func testShortcutTypesMapToRoutes() {
        // Byte-identical with project.yml → UIApplicationShortcutItems.
        XCTAssertEqual(PulseQuickActionRoute.openType, "app.pulse.shortcut.open")
        XCTAssertEqual(PulseQuickActionRoute.composeType, "app.pulse.shortcut.compose")
        XCTAssertEqual(PulseQuickActionRoute.parse(shortcutType: PulseQuickActionRoute.openType), .openChats)
        XCTAssertEqual(PulseQuickActionRoute.parse(shortcutType: PulseQuickActionRoute.composeType), .newMessage)
    }

    func testUnknownShortcutTypeIsIgnored() {
        // The app never invents a destination it wasn't asked for.
        XCTAssertNil(PulseQuickActionRoute.parse(shortcutType: "app.pulse.shortcut.unknown"))
        XCTAssertNil(PulseQuickActionRoute.parse(shortcutType: nil))
        XCTAssertNil(PulseQuickActionRoute.parse(shortcutType: ""))
    }

    func testComposeRouteParsesThroughThePulseScheme() {
        // The "New message" quick action and hand-built pulse://new links
        // land on the same .compose case RootView drives into the dock's
        // compose sheet.
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://new")!), .compose)
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://compose")!), .compose)
        // Existing routes unchanged (regression pin).
        XCTAssertEqual(PulseDeepLink.parse(URL(string: "pulse://room/conv-42")!), .room(conversationId: "conv-42"))
        XCTAssertNil(PulseDeepLink.parse(URL(string: "pulse://bogus/x")!))
        // Argument-less non-compose routes still refuse (no half-state).
        XCTAssertNil(PulseDeepLink.parse(URL(string: "pulse://room")!))
    }

    func testQuickReplyCategoryStampGatesOnRoutableConversation() {
        // A Reply action without a routable room would be a dead control -
        // blank/nil conversation ids must never carry the category.
        XCTAssertTrue(PulseQuickReply.applies(toConversationId: "conv-1"))
        XCTAssertFalse(PulseQuickReply.applies(toConversationId: nil))
        XCTAssertFalse(PulseQuickReply.applies(toConversationId: ""))
        XCTAssertFalse(PulseQuickReply.applies(toConversationId: "   "))
        // Constants the remote-push contract depends on.
        XCTAssertEqual(PulseQuickReply.categoryId, "PULSE_MSG")
        XCTAssertEqual(PulseQuickReply.replyActionId, "PULSE_REPLY_ACTION")
    }
}
