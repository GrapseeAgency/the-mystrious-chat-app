import XCTest
@testable import Pulse

final class ReadVisibilityTests: XCTestCase {
    func testPrefetchedClippedAndZeroSizeRowsCannotBeRead() {
        let frames = [
            "visible": CGRect(x: 0, y: 50, width: 100, height: 100),
            "prefetched": CGRect(x: 0, y: 550, width: 100, height: 100),
            "sliver": CGRect(x: 0, y: 490, width: 100, height: 100),
            "above": CGRect(x: 0, y: -120, width: 100, height: 100),
            "short": CGRect(x: 0, y: 200, width: 100, height: 20),
            "short-clipped": CGRect(x: 0, y: -1, width: 100, height: 20),
            "tall": CGRect(x: 0, y: -100, width: 100, height: 800),
            "zero": CGRect(x: 0, y: 100, width: 100, height: 0),
        ]
        XCTAssertEqual(PulseReadViewport.visibleIDs(frames, height: 500), Set(["visible", "short", "tall"]))
        XCTAssertTrue(PulseReadViewport.visibleIDs(frames, height: 0).isEmpty)
        XCTAssertFalse(PulseReadViewport.tailVisible(nil, height: 500))
        XCTAssertFalse(PulseReadViewport.tailVisible(CGRect(x: 0, y: 501, width: 100, height: 4), height: 500))
        XCTAssertTrue(PulseReadViewport.tailVisible(CGRect(x: 0, y: 490, width: 100, height: 4), height: 500))
    }

    func testRefreshCannotBypassDwellAndUnchangedBoundaryDoesNotRestartIt() throws {
        var gate = PulseReadReceiptGate()
        XCTAssertTrue(gate.setBoundary(roomId: "room", messageId: "shown", now: 10))
        XCTAssertNil(gate.begin(roomId: "room", now: 10.59))
        XCTAssertFalse(gate.setBoundary(roomId: "room", messageId: "shown", now: 10.4))
        let request = try XCTUnwrap(gate.begin(roomId: "room", now: 10.61))
        XCTAssertNil(gate.begin(roomId: "room", now: 11)) // one current attempt
        XCTAssertTrue(gate.finish(request, succeeded: true))
        XCTAssertNil(gate.begin(roomId: "room", now: 12)) // no duplicate ACK loop
    }

    func testOcclusionAndForegroundRestartRequireFreshVisibleDwell() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "shown", now: 0)
        gate.setBoundary(roomId: "room", messageId: nil, now: 0.5)
        XCTAssertNil(gate.begin(roomId: "room", now: 1))
        gate.setBoundary(roomId: "room", messageId: "shown", now: 2)
        XCTAssertNil(gate.begin(roomId: "room", now: 2.5))
        gate.restartDwell(now: 3) // foreground again
        XCTAssertNil(gate.begin(roomId: "room", now: 3.5))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 3.61))
    }

    func testOldResponseCannotAcknowledgeOrReleaseNewBoundaryEvenOnReturnToSameRoom() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "a", now: 0)
        let old = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        gate.reset() // navigate away, then back (or replace account)
        gate.setBoundary(roomId: "room", messageId: "a", now: 2)
        XCTAssertNil(gate.begin(roomId: "other", now: 3))
        let replacement = try XCTUnwrap(gate.begin(roomId: "room", now: 3))
        XCTAssertFalse(gate.finish(old, succeeded: true))
        XCTAssertNil(gate.begin(roomId: "room", now: 4)) // replacement still owns the slot
        XCTAssertTrue(gate.finish(replacement, succeeded: false))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 5)) // stale success did not acknowledge it
    }

    func testRefreshTriggersCannotSkipRetryBackoff() throws {
        var gate = PulseReadReceiptGate()
        gate.setBoundary(roomId: "room", messageId: "shown", now: 0)
        let request = try XCTUnwrap(gate.begin(roomId: "room", now: 1))
        XCTAssertTrue(gate.finish(request, succeeded: false))
        gate.deferAttempt(until: 3)
        XCTAssertNil(gate.begin(roomId: "room", now: 2.99))
        XCTAssertNotNil(gate.begin(roomId: "room", now: 3))
    }
}
