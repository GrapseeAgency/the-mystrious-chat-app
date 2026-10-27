import XCTest
@testable import Pulse

final class MessageOriginTests: XCTestCase {
    func testMachineOriginRequiresServerFlagAndDeletionHidesCaption() {
        let hook = #"{"webhook":true,"webhookName":"Build bot"}"#
        XCTAssertEqual(PulseMessageOrigin.label(payload: hook, automated: true), "Webhook · Build bot")
        XCTAssertEqual(PulseMessageOrigin.label(payload: hook, automated: false), "Unverified integration")
        XCTAssertEqual(PulseMessageOrigin.label(payload: #"{"bot":true}"#, automated: true), "Bot")
        XCTAssertEqual(PulseMessageOrigin.label(payload: nil, automated: true), "Automation")
        XCTAssertNil(PulseMessageOrigin.label(payload: nil, automated: false))
        XCTAssertNil(PulseMessageOrigin.label(payload: hook, automated: true, deleted: true))
        XCTAssertNil(PulseMessageOrigin.label(payload: #"{"bot":"true"}"#, automated: false))
        XCTAssertNil(PulseMessageOrigin.label(payload: #"{"webhook":1}"#, automated: false))
        XCTAssertEqual(PulseMessageOrigin.label(payload: "not-json", automated: true), "Automation")
    }
    func testDisplayNameControlsAndForwardedOriginAreRemoved() throws {
        XCTAssertEqual(PulseMessageOrigin.label(payload: #"{"webhook":true,"webhookName":"\u202eBuilds\n"}"#, automated: true), "Webhook · Builds")
        XCTAssertNil(try PulseMessageOrigin.forwardPayload(#"{"bot":true,"webhook":true,"webhookName":"Bot","webhookColor":"amber"}"#))
        XCTAssertEqual(try PulseMessageOrigin.forwardPayload(#"{"bot":true,"effect":"sparkles"}"#), #"{"effect":"sparkles"}"#)
        let payload = #"{ "lat":23.1, "lng":91.2 }"#
        XCTAssertEqual(try PulseMessageOrigin.forwardPayload(payload), payload)
        XCTAssertThrowsError(try PulseMessageOrigin.forwardPayload("[]"))
    }
}
