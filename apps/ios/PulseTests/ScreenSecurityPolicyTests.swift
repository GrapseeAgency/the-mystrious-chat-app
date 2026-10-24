import XCTest
import UIKit
@testable import Pulse

final class ScreenSecurityPolicyTests: XCTestCase {
    func testUnknownOrEitherEnabledPolicyCoversInactiveContent() {
        let values: [Bool?] = [nil, false, true]
        for room in values {
            for personal in values {
                XCTAssertFalse(PulseScreenSecurityPolicy.shouldCover(room: room, personal: personal, active: true))
                XCTAssertEqual(PulseScreenSecurityPolicy.shouldCover(room: room, personal: personal, active: false),
                    room != false || personal != false)
            }
        }
    }
}


@MainActor
final class WindowPrivacyLeaseTests: XCTestCase {
    func testInactiveShieldCoversTheWindowAndOneSurfaceCannotReleaseAnother() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let draft = UIView(frame: window.bounds)
        window.addSubview(draft)
        let state = PulseWindowPrivacyState(window: window, inactive: true)
        let first = UUID(), second = UUID()
        state.update(owner: first, required: true)
        XCTAssertTrue(state.isCovering)
        XCTAssertEqual(window.subviews.last?.accessibilityIdentifier, "pulse.windowPrivacyCover")
        XCTAssertEqual(window.subviews.last?.frame, window.bounds)
        state.update(owner: second, required: false)
        state.remove(owner: second)
        XCTAssertTrue(state.isCovering)
        state.setInactive(false)
        XCTAssertFalse(state.isCovering)
        XCTAssertTrue(draft.superview === window)
        state.setInactive(true)
        XCTAssertTrue(state.isCovering)
        state.remove(owner: first)
        XCTAssertFalse(state.isCovering)
        XCTAssertTrue(state.isEmpty)
    }
    func testExplicitOffAndSeparateWindowsDoNotBorrowAnotherRoomsLease() {
        let first = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let second = UIWindow(frame: first.frame)
        let a = PulseWindowPrivacyState(window: first, inactive: true)
        let b = PulseWindowPrivacyState(window: second, inactive: true)
        let token = UUID()
        a.update(owner: token, required: true)
        b.update(owner: token, required: false)
        XCTAssertTrue(a.isCovering); XCTAssertFalse(b.isCovering)
        a.update(owner: token, required: false)
        XCTAssertFalse(a.isCovering)
    }
    func testFullScreenPresentationDetachmentKeepsProtectionUntilTheRoomIsDismantled() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let anchor = PulseWindowPrivacyShield.Anchor()
        window.addSubview(anchor)
        defer {
            anchor.detach()
            NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        }
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(window.subviews.last?.accessibilityIdentifier, "pulse.windowPrivacyCover")
        anchor.removeFromSuperview()
        XCTAssertEqual(window.subviews.last?.accessibilityIdentifier, "pulse.windowPrivacyCover")
        anchor.detach()
        XCTAssertFalse(window.subviews.contains { $0.accessibilityIdentifier == "pulse.windowPrivacyCover" })
    }
    func testDeactivationEvaluatesCurrentPolicyBeforeTheNextSwiftUIRender() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 640))
        let state = PulseWindowPrivacyState(window: window, inactive: false)
        var required = false
        state.update(owner: UUID(), policy: { required })
        required = true
        state.setInactive(true)
        XCTAssertTrue(state.isCovering)
    }
}
