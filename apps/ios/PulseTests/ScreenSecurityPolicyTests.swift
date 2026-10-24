import XCTest
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
