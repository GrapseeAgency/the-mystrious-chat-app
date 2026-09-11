import XCTest
@testable import Pulse

/// Wave 3 — the pure call state machine: every transition in the W3-PLAN
/// shared design, duplicate-event idempotency, and the pinned terminal
/// mapping (answered→completed, reject→declined, cancel-while-ringing→missed).
final class CallStateMachineTests: XCTestCase {

    private func makeMachine() -> CallStateMachine { CallStateMachine(timers: .standard) }

    private var outgoingInfo: CallInfo {
        CallInfo(
            callId: "call-1",
            conversationId: "conv-1",
            kind: .voice,
            direction: .outgoing,
            peer: CallPeer(id: "peer-9", name: "Ada Lovelace", color: "emerald", avatar: nil),
        )
    }

    private var incomingInfo: CallInfo {
        CallInfo(
            callId: "call-2",
            conversationId: "conv-1",
            kind: .voice,
            direction: .incoming,
            peer: CallPeer(id: "peer-9", name: "Ada Lovelace", color: "emerald", avatar: nil),
        )
    }

    // ── the happy path ──────────────────────────────────────

    func testOutgoingFullFlow() {
        let machine = makeMachine()
        XCTAssertEqual(machine.apply(.startOutgoing(outgoingInfo), now: Date()).to, .outgoingRinging)
        XCTAssertEqual(machine.apply(.answerArrived, now: Date()).to, .connecting)
        XCTAssertEqual(machine.apply(.peerConnectionReady, now: Date()).to, .connected)
        let end = machine.apply(.hangupRequested, now: Date())
        XCTAssertTrue(end.to.isTerminal)
        XCTAssertEqual(end.end?.outcome, .completed)
        XCTAssertGreaterThanOrEqual(end.end?.durationSec ?? -1, 0)
    }

    func testIncomingFullFlow() {
        let machine = makeMachine()
        XCTAssertEqual(machine.apply(.offerArrived(incomingInfo), now: Date()).to, .incomingRinging)
        XCTAssertEqual(machine.apply(.acceptRequested, now: Date()).to, .connecting)
        XCTAssertEqual(machine.apply(.peerConnectionReady, now: Date()).to, .connected)
        let end = machine.apply(.hangupReceived, now: Date())
        XCTAssertTrue(end.to.isTerminal)
        XCTAssertEqual(end.end?.outcome, .completed)
    }

    // ── terminal mappings ───────────────────────────────────

    func testRejectReceivedMapsToDeclined() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let end = machine.apply(.rejectReceived, now: Date())
        XCTAssertEqual(end.end?.outcome, .declined)
    }

    func testCancelWhileRingingMapsToMissed() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let end = machine.apply(.cancelReceived(reason: .cancel), now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
        XCTAssertEqual(end.end?.cancelReason, .cancel)
    }

    func testServerTimeoutCancelMapsToMissed() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let end = machine.apply(.cancelReceived(reason: .timeout), now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
        XCTAssertEqual(end.end?.cancelReason, .timeout)
    }

    func testOfflineCancelMapsToMissed() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let end = machine.apply(.cancelReceived(reason: .offline), now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
    }

    func testBusyCancelMapsToMissed() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let end = machine.apply(.cancelReceived(reason: .busy), now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
    }

    func testIncomingMissedOnCallerCancel() {
        let machine = makeMachine()
        _ = machine.apply(.offerArrived(incomingInfo), now: Date())
        let end = machine.apply(.cancelReceived(reason: .cancel), now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
    }

    func testAnsweredThenMediaFailureIsCompleted() {
        // PINNED mapping: once the answer leg started, the caller logs
        // completed(durationSec) even when the media leg dies.
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        _ = machine.apply(.answerArrived, now: Date())
        let end = machine.apply(.peerConnectionFailed, now: Date())
        XCTAssertEqual(end.end?.outcome, .completed)
    }

    // ── duplicates / invalid inputs are idempotent ──────────

    func testDuplicateAnswerIsNoOp() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let first = machine.apply(.answerArrived, now: Date())
        XCTAssertEqual(first.to, .connecting)
        let duplicate = machine.apply(.answerArrived, now: Date())
        XCTAssertTrue(duplicate.isNoOp)
    }

    func testOfferWhileBusyStartsNothing() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        let second = machine.apply(.offerArrived(incomingInfo), now: Date())
        XCTAssertEqual(second.to, .outgoingRinging)
    }

    func testEventAfterTerminalIsNoOp() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        _ = machine.apply(.rejectReceived, now: Date())
        XCTAssertTrue(machine.apply(.answerArrived, now: Date()).isNoOp)
        XCTAssertTrue(machine.apply(.peerConnectionReady, now: Date()).isNoOp)
    }

    // ── timeouts (defensive ring/connect) ───────────────────

    func testRingTimeoutEndsUnansweredOutgoingAsMissed() {
        var timers = CallTimers.standard
        timers.ringTimeout = 0.05
        let machine = CallStateMachine(timers: timers)
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        // No signaling at all — the defensive ring timer must fire.
        let fired = machine.checkTimeouts(now: Date().addingTimeInterval(0.1))
        XCTAssertEqual(fired, .ringTimeout)
        let end = machine.apply(.ringTimeout, now: Date())
        XCTAssertEqual(end.end?.outcome, .missed)
    }

    func testConnectTimeoutFiresAfterAnswer() {
        var timers = CallTimers.standard
        timers.connectTimeout = 0.05
        let machine = CallStateMachine(timers: timers)
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        _ = machine.apply(.answerArrived, now: Date())
        let fired = machine.checkTimeouts(now: Date().addingTimeInterval(0.1))
        XCTAssertEqual(fired, .connectTimeout)
    }

    func testDisconnectGraceHoldsThenFails() {
        var timers = CallTimers.standard
        timers.disconnectGrace = 0.05
        let machine = CallStateMachine(timers: timers)
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        _ = machine.apply(.answerArrived, now: Date())
        _ = machine.apply(.peerConnectionReady, now: Date())
        // Within the grace window nothing fires.
        XCTAssertNil(machine.checkTimeouts(now: Date()))
        // After it, the machine drops the call (answered → completed).
        guard let fired = machine.checkTimeouts(now: Date().addingTimeInterval(0.1)) else {
            return XCTFail("disconnect grace must fire")
        }
        let end = machine.apply(fired, now: Date())
        XCTAssertEqual(end.end?.outcome, .completed)
    }

    func testOwnershipTracksActiveCallId() {
        let machine = makeMachine()
        _ = machine.apply(.startOutgoing(outgoingInfo), now: Date())
        XCTAssertTrue(machine.owns(callId: "call-1"))
        XCTAssertFalse(machine.owns(callId: "other"))
    }
}
