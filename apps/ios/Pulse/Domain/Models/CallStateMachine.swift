import Foundation
import Combine

// ─────────────────────────────────────────────────────────────
// Pulse — Wave 3 call state machine (pure, injectable clock).
//
// Implements the W3-PLAN shared design exactly:
//   idle → outgoingRinging → connecting → connected → ended
//   idle → incomingRinging → connecting → connected → ended
//
// Terminal mapping (the single-writer rule lives in CallLogMapper):
//   • answered-then-ended            = completed (durationSec from answer)
//   • reject received (caller)       = declined
//   • cancel while ringing (any reason, either side) = missed
//
// Defensive timers (all derived from an injected `now`, never from wall
// clocks inside this type):
//   • ring timeout 40s  (server's 30s is authoritative — this only fires if
//     the server's call:cancel was lost)
//   • PC connect timeout 15s after the answer
//   • disconnected grace 10s before an answered call is torn down
//   • stale-state 45s with no signaling activity
//
// Idempotency: every transition is guarded by callId + current state;
// terminal events after .ended are ignored, duplicate offers/answers are
// ignored, and a callId can never re-ring after it terminated.
// ─────────────────────────────────────────────────────────────

public enum CallState: Equatable, Sendable {
    case idle
    case outgoingRinging
    case incomingRinging
    case connecting
    case connected
    case ended(CallEndContext)
}

/// Why + how the call ended. `durationSec` measures the answer→end span
/// (web parity: connectedAtRef is set when the answer lands, not when ICE
/// completes). `cancelReason` is set for missed outcomes.
public struct CallEndContext: Equatable, Sendable {
    public let outcome: CallOutcome
    public let durationSec: Int
    public let cancelReason: CallCancelReason?

    public init(outcome: CallOutcome, durationSec: Int, cancelReason: CallCancelReason? = nil) {
        self.outcome = outcome
        self.durationSec = durationSec
        self.cancelReason = cancelReason
    }
}

/// Inputs driving the machine. The engine maps each accepted transition to
/// wire emits + log writes; the machine itself stays pure.
public enum CallMachineInput: Equatable, Sendable {
    /// Caller opens the ring (before media/offer is sent — the UI shows
    /// "Calling…" immediately, web parity).
    case startOutgoing(CallInfo)
    /// Callee receives call:offer (from idle only).
    case offerArrived(CallInfo)
    /// Callee tapped accept — media + answer follow (incomingRinging → connecting).
    case acceptRequested
    /// Caller received call:answer (outgoingRinging → connecting).
    case answerArrived
    /// ICE/PC reached connected.
    case peerConnectionReady
    /// ICE dropped while connected — 10s grace window starts (state holds).
    case peerConnectionDisconnected
    /// ICE failed outright (answered calls end as completed — answered-then-ended).
    case peerConnectionFailed
    /// This viewer ends the call (any active state).
    case hangupRequested
    /// The peer ended an active call.
    case hangupReceived
    /// The peer declined (caller side).
    case rejectReceived
    /// This viewer declines an incoming ring (callee side, summary only —
    /// the callee NEVER writes a log row).
    case rejectRequested
    /// Ring torn down: timeout/offline/busy, or the caller's self-cancel relay.
    case cancelReceived(reason: CallCancelReason)
    /// Defensive 40s ring timer (server's 30s is authoritative).
    case ringTimeout
    /// Defensive 15s "never got ICE connected after the answer".
    case connectTimeout
    /// The 10s disconnect grace elapsed without recovery.
    case disconnectGraceExpired
    /// 45s with zero signaling activity in a live state.
    case staleTimeout
    /// Local SDP/media failure (answer apply failed, offer creation failed).
    /// Maps to a terminal state but the engine suppresses BOTH the row write
    /// and any wire emit (web 'Call failed' parity — no row, no emit).
    case abortLocal
    /// Media-permission error card dismissed before the offer was ever sent —
    /// straight back to idle, no end summary (web dismissError parity; the
    /// engine logs the 'missed' row itself before applying this).
    case abandon
    /// ended → idle (auto after ~2.5s, or user dismissal).
    case reset
}

public struct CallMachineTransition: Equatable, Sendable {
    public let from: CallState
    public let to: CallState
    /// Non-nil exactly when `to` is .ended.
    public let end: CallEndContext?
    /// True when the state did not change (duplicate/invalid input).
    public var isNoOp: Bool { from == to && end == nil }

    public static func unchanged(_ state: CallState) -> CallMachineTransition {
        CallMachineTransition(from: state, to: state, end: nil)
    }
}

/// Defensive timer budget (seconds) — the shared design values.
public struct CallTimers: Equatable, Sendable {
    public var ringTimeout: TimeInterval
    public var connectTimeout: TimeInterval
    public var disconnectGrace: TimeInterval
    public var staleAfter: TimeInterval

    public init(
        ringTimeout: TimeInterval = 40,
        connectTimeout: TimeInterval = 15,
        disconnectGrace: TimeInterval = 10,
        staleAfter: TimeInterval = 45
    ) {
        self.ringTimeout = ringTimeout
        self.connectTimeout = connectTimeout
        self.disconnectGrace = disconnectGrace
        self.staleAfter = staleAfter
    }

    public static let standard = CallTimers()
}

public final class CallStateMachine: ObservableObject {
    public let timers: CallTimers

    @Published public private(set) var state: CallState = .idle
    /// The live call (nil while idle).
    public private(set) var call: CallInfo?
    /// When the answer landed (start of the duration window) — nil while unanswered.
    public private(set) var answeredAt: Date?
    /// Last signaling input (offer/answer/ice/reject/cancel/hangup or transition).
    public private(set) var lastActivityAt: Date?

    // Deadline bookkeeping for checkTimeouts(now:).
    public private(set) var ringDeadline: Date?
    public private(set) var connectDeadline: Date?
    public private(set) var graceDeadline: Date?
    public private(set) var staleDeadline: Date?

    /// Duplicate-event idempotency per callId: a callId that already reached a
    /// terminal state can never ring/connect again (cancel/offer races).
    private var terminatedCallIds: Set<String> = []

    public init(timers: CallTimers = .standard) {
        self.timers = timers
    }

    /// True when the current call matches the given id (or no call is live).
    public func owns(callId: String) -> Bool {
        call?.callId == callId
    }

    /// Refreshes the stale-state window on pure signaling traffic (ICE
    /// candidates) that does not change the state.
    public func noteSignalingActivity(now: Date) {
        guard !isTerminal, state != .idle else { return }
        lastActivityAt = now
        staleDeadline = now.addingTimeInterval(timers.staleAfter)
    }

    /// Is the call answered (answer/accept landed) but not yet terminal?
    public var isAnswered: Bool { answeredAt != nil && !isTerminal }

    public var isTerminal: Bool {
        if case .ended = state { return true }
        return false
    }

    /// Applies one input at the given instant and returns the transition
    /// (including a no-op transition for duplicate/invalid inputs).
    @discardableResult
    public func apply(_ input: CallMachineInput, now: Date) -> CallMachineTransition {
        let from = state
        // Terminal states swallow everything except reset/abandon.
        if case .ended = from {
            switch input {
            case .reset:
                return applyReset(from: from, now: now)
            case .abandon:
                return applyAbandon(from: from, now: now)
            default:
                return .unchanged(from)
            }
        }
        // .idle only starts a call (reset/abandon are already no-ops there).
        if case .idle = from {
            switch input {
            case .startOutgoing(let info):
                return startOutgoing(info, from: from, now: now)
            case .offerArrived(let info):
                return offerArrived(info, from: from, now: now)
            default:
                return .unchanged(from)
            }
        }

        switch input {
        case .startOutgoing, .offerArrived:
            // A second ring while one is live (any direction) is refused here;
            // the engine busy-rejects a foreign offer BEFORE this point.
            return .unchanged(from)

        case .acceptRequested:
            guard case .incomingRinging = from else { return .unchanged(from) }
            state = .connecting
            answeredAt = now
            ringDeadline = nil
            connectDeadline = now.addingTimeInterval(timers.connectTimeout)
            graceDeadline = nil
            lastActivityAt = now
            staleDeadline = now.addingTimeInterval(timers.staleAfter)
            return CallMachineTransition(from: from, to: .connecting, end: nil)

        case .answerArrived:
            guard case .outgoingRinging = from else { return .unchanged(from) }
            state = .connecting
            answeredAt = now
            ringDeadline = nil
            connectDeadline = now.addingTimeInterval(timers.connectTimeout)
            graceDeadline = nil
            lastActivityAt = now
            staleDeadline = now.addingTimeInterval(timers.staleAfter)
            return CallMachineTransition(from: from, to: .connecting, end: nil)

        case .peerConnectionReady:
            switch from {
            case .connecting:
                state = .connected
                connectDeadline = nil
                lastActivityAt = now
                staleDeadline = now.addingTimeInterval(timers.staleAfter)
                return CallMachineTransition(from: from, to: .connected, end: nil)
            case .connected:
                // ICE flapped to connected again during the grace window — recover.
                graceDeadline = nil
                return .unchanged(from)
            default:
                return .unchanged(from)
            }

        case .peerConnectionDisconnected:
            guard case .connected = from else { return .unchanged(from) }
            graceDeadline = now.addingTimeInterval(timers.disconnectGrace)
            lastActivityAt = now
            return .unchanged(from)

        case .peerConnectionFailed:
            guard isAnswered else { return .unchanged(from) }
            return endCall(
                from: from,
                now: now,
                outcome: .completed,
                cancelReason: nil
            )

        case .hangupRequested:
            switch from {
            case .outgoingRinging:
                // Caller cancels an unanswered ring → 'missed' (web parity).
                return endCall(from: from, now: now, outcome: .missed, cancelReason: .cancel)
            case .incomingRinging:
                // The callee's single end button on an incoming ring is a decline.
                return endCall(from: from, now: now, outcome: .declined, cancelReason: nil)
            case .connecting, .connected:
                return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)
            default:
                return .unchanged(from)
            }

        case .rejectRequested:
            guard case .incomingRinging = from else { return .unchanged(from) }
            return endCall(from: from, now: now, outcome: .declined, cancelReason: nil)

        case .rejectReceived:
            // Only meaningful for the caller before ICE connected.
            guard from == .outgoingRinging || from == .connecting else { return .unchanged(from) }
            return endCall(from: from, now: now, outcome: .declined, cancelReason: nil)

        case .cancelReceived(let reason):
            switch from {
            case .outgoingRinging, .incomingRinging:
                // Ring torn down (timeout/offline/busy/self-cancel) → missed.
                return endCall(from: from, now: now, outcome: .missed, cancelReason: reason)
            case .connecting, .connected:
                // Defensive: a cancel after the answer behaves like a hangup.
                return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)
            default:
                return .unchanged(from)
            }

        case .hangupReceived:
            guard isAnswered else { return .unchanged(from) }
            return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)

        case .ringTimeout:
            guard from == .outgoingRinging || from == .incomingRinging else { return .unchanged(from) }
            return endCall(from: from, now: now, outcome: .missed, cancelReason: .timeout)

        case .connectTimeout:
            guard case .connecting = from, isAnswered else { return .unchanged(from) }
            // Answered-then-ended → completed (shared design terminal rule).
            return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)

        case .disconnectGraceExpired:
            guard case .connected = from, isAnswered,
                  let deadline = graceDeadline, now >= deadline else { return .unchanged(from) }
            return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)

        case .staleTimeout:
            guard let deadline = staleDeadline, now >= deadline else { return .unchanged(from) }
            if isAnswered {
                return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)
            }
            return endCall(from: from, now: now, outcome: .missed, cancelReason: .timeout)

        case .abortLocal:
            // Local media/SDP failure: unanswered → missed, answered → completed,
            // but the engine suppresses wire + log for this input.
            if isAnswered {
                return endCall(from: from, now: now, outcome: .completed, cancelReason: nil)
            }
            return endCall(from: from, now: now, outcome: .missed, cancelReason: .cancel)

        case .abandon:
            return applyAbandon(from: from, now: now)

        case .reset:
            return applyReset(from: from, now: now)
        }
    }

    /// Returns the next timeout input to apply (engine ticks once a second),
    /// or nil when nothing is due. Ring → connect → grace → stale priority.
    public func checkTimeouts(now: Date) -> CallMachineInput? {
        switch state {
        case .outgoingRinging, .incomingRinging:
            if let deadline = ringDeadline, now >= deadline { return .ringTimeout }
        case .connecting:
            if let deadline = connectDeadline, now >= deadline { return .connectTimeout }
        case .connected:
            if let deadline = graceDeadline, now >= deadline { return .disconnectGraceExpired }
        case .idle, .ended:
            return nil
        }
        if let deadline = staleDeadline, now >= deadline { return .staleTimeout }
        return nil
    }

    // ── transition internals ─────────────────────────────────

    private func startOutgoing(_ info: CallInfo, from: CallState, now: Date) -> CallMachineTransition {
        guard case .idle = from else { return .unchanged(from) }
        guard !terminatedCallIds.contains(info.callId) else { return .unchanged(from) }
        call = info
        state = .outgoingRinging
        answeredAt = nil
        ringDeadline = now.addingTimeInterval(timers.ringTimeout)
        connectDeadline = nil
        graceDeadline = nil
        lastActivityAt = now
        staleDeadline = now.addingTimeInterval(timers.staleAfter)
        return CallMachineTransition(from: from, to: .outgoingRinging, end: nil)
    }

    private func offerArrived(_ info: CallInfo, from: CallState, now: Date) -> CallMachineTransition {
        guard case .idle = from else { return .unchanged(from) }
        guard !terminatedCallIds.contains(info.callId) else { return .unchanged(from) }
        call = info
        state = .incomingRinging
        answeredAt = nil
        ringDeadline = now.addingTimeInterval(timers.ringTimeout)
        connectDeadline = nil
        graceDeadline = nil
        lastActivityAt = now
        staleDeadline = now.addingTimeInterval(timers.staleAfter)
        return CallMachineTransition(from: from, to: .incomingRinging, end: nil)
    }

    private func endCall(
        from: CallState,
        now: Date,
        outcome: CallOutcome,
        cancelReason: CallCancelReason?
    ) -> CallMachineTransition {
        let duration = durationSec(now: now)
        let end = CallEndContext(outcome: outcome, durationSec: duration, cancelReason: cancelReason)
        if let callId = call?.callId {
            terminatedCallIds.insert(callId)
        }
        state = .ended(end)
        clearTimers()
        return CallMachineTransition(from: from, to: .ended(end), end: end)
    }

    private func applyReset(from: CallState, now: Date) -> CallMachineTransition {
        guard case .ended = from else { return .unchanged(from) }
        state = .idle
        call = nil
        answeredAt = nil
        clearTimers()
        return CallMachineTransition(from: from, to: .idle, end: nil)
    }

    /// Hard teardown to idle without an end summary (media-failure dismiss).
    private func applyAbandon(from: CallState, now: Date) -> CallMachineTransition {
        guard !(from == .idle) else { return .unchanged(from) }
        state = .idle
        call = nil
        answeredAt = nil
        clearTimers()
        return CallMachineTransition(from: from, to: .idle, end: nil)
    }

    private func clearTimers() {
        ringDeadline = nil
        connectDeadline = nil
        graceDeadline = nil
        staleDeadline = nil
    }

    private func durationSec(now: Date) -> Int {
        guard let answeredAt else { return 0 }
        return max(0, Int(now.timeIntervalSince(answeredAt).rounded()))
    }
}
