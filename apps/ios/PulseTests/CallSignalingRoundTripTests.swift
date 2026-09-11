import XCTest
import Foundation
@testable import Pulse

/// Wave 3 — REAL signaling round trip for the call:* family against the
/// fixture relay (identity-gated call:offer/answer/ice/reject/cancel/hangup).
/// Same relay-discovery contract as SocketRoundTripTests: PULSE_RELAY_URL or
/// 127.0.0.1:3995, honest XCTSkip when no relay answers /health.
///
/// Proves the WIRE, not the UI:
///   1. A offers (caller identity fields intact) → B receives call:offer
///   2. B answers → A receives call:answer (SDP round trip)
///   3. B trickles ICE → A receives call:ice (flat candidate triple)
///   4. A hangs up → B receives call:hangup with durationSec
///   5. Identity gate: a spoofed `from` ≠ joined id is DROPPED by the relay
final class CallSignalingRoundTripTests: XCTestCase {
    private let callerId = "ios-w3-caller"
    private let calleeId = "ios-w3-callee"
    private let conversationId = "conv-call-rt"

    private static func relayBase() async -> URL? {
        if let configured = ProcessInfo.processInfo.environment["PULSE_RELAY_URL"],
           let url = URL(string: configured),
           await Self.healthy(url) {
            return url
        }
        let fallback = URL(string: "http://127.0.0.1:3995")!
        return await Self.healthy(fallback) ? fallback : nil
    }

    private static func healthy(_ base: URL) async -> Bool {
        guard let url = URL(string: base.absoluteString + "/health") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2
        if let (_, response) = try? await URLSession.shared.data(for: request),
           let http = response as? HTTPURLResponse,
           http.statusCode == 200 {
            return true
        }
        return false
    }

    func testOfferAnswerIceHangupRoundTrip() async throws {
        guard let base = await Self.relayBase() else {
            throw XCTSkip("no pulse relay on PULSE_RELAY_URL / 127.0.0.1:3995 — start Fixtures/server.js (CI does)")
        }

        let offerArrived = expectation(description: "callee received call:offer")
        let answerArrived = expectation(description: "caller received call:answer")
        let iceArrived = expectation(description: "caller received call:ice")
        let hangupArrived = expectation(description: "callee received call:hangup")

        let caller = PulseSocketClient(socketURL: base)
        let callee = PulseSocketClient(socketURL: base)

        caller.signals = { [conversationId = self.conversationId] signal in
            guard case .callSignal(let event, let raw) = signal else { return }
            switch event {
            case "call:answer":
                if (raw["sdp"] as? String)?.isEmpty == false { answerArrived.fulfill() }
            case "call:ice":
                if conversationId == (raw["conversationId"] as? String) { iceArrived.fulfill() }
            default:
                break
            }
        }

        callee.signals = { [conversationId = self.conversationId, callerId = self.callerId] signal in
            guard case .callSignal(let event, let raw) = signal else { return }
            switch event {
            case "call:offer":
                if raw["sdp"] as? String == "offer-sdp"
                    && raw["callerName"] as? String == "Caller"
                    && raw["from"] as? String == callerId
                    && raw["conversationId"] as? String == conversationId {
                    offerArrived.fulfill()
                }
            case "call:hangup":
                if (raw["durationSec"] as? Double) == 12 || (raw["durationSec"] as? Int) == 12 {
                    hangupArrived.fulfill()
                }
            default:
                break
            }
        }

        let callerJoined = expectation(description: "caller joined")
        let calleeJoined = expectation(description: "callee joined")
        caller.signals = { signal in
            if case .joined = signal { callerJoined.fulfill() }
        }
        callee.signals = { signal in
            if case .joined = signal { calleeJoined.fulfill() }
        }
        caller.connect(userId: callerId)
        callee.connect(userId: calleeId)
        // The relay targets `user:<id>` rooms — both parties must have JOINED
        // before any call:* emission or the relay drops it (empty room).
        await fulfillment(of: [callerJoined, calleeJoined], timeout: 10)

        // Re-arm the payload observers (signals is a single callback).
        caller.signals = { [conversationId = self.conversationId] signal in
            guard case .callSignal(let event, let raw) = signal else { return }
            switch event {
            case "call:answer":
                if (raw["sdp"] as? String)?.isEmpty == false { answerArrived.fulfill() }
            case "call:ice":
                if conversationId == (raw["conversationId"] as? String) { iceArrived.fulfill() }
            default:
                break
            }
        }

        callee.signals = { [conversationId = self.conversationId, callerId = self.callerId] signal in
            guard case .callSignal(let event, let raw) = signal else { return }
            switch event {
            case "call:offer":
                if raw["sdp"] as? String == "offer-sdp"
                    && raw["callerName"] as? String == "Caller"
                    && raw["from"] as? String == callerId
                    && raw["conversationId"] as? String == conversationId {
                    offerArrived.fulfill()
                }
            case "call:hangup":
                if (raw["durationSec"] as? Double) == 12 || (raw["durationSec"] as? Int) == 12 {
                    hangupArrived.fulfill()
                }
            default:
                break
            }
        }

        // 1. Offer — caller → callee (identity decoration rides along).
        caller.emitCallSignal(
            event: "call:offer",
            payload: [
                "callId": "call-rt-1",
                "conversationId": conversationId,
                "from": callerId,
                "to": calleeId,
                "kind": "voice",
                "sdp": "offer-sdp",
                "callerName": "Caller",
                "callerColor": "emerald",
            ],
        )
        await fulfillment(of: [offerArrived], timeout: 10)

        // 2. Answer — callee → caller.
        callee.emitCallSignal(
            event: "call:answer",
            payload: [
                "callId": "call-rt-1",
                "conversationId": conversationId,
                "from": calleeId,
                "to": callerId,
                "kind": "voice",
                "sdp": "answer-sdp",
            ],
        )
        await fulfillment(of: [answerArrived], timeout: 10)

        // 3. Trickle ICE — flat candidate triple, either direction.
        callee.emitCallSignal(
            event: "call:ice",
            payload: [
                "callId": "call-rt-1",
                "conversationId": conversationId,
                "from": calleeId,
                "to": callerId,
                "kind": "voice",
                "candidate": "candidate:1 1 udp 2130706431 10.0.0.1 8998 typ host",
                "sdpMid": "0",
                "sdpMLineIndex": 0,
            ],
        )
        await fulfillment(of: [iceArrived], timeout: 10)

        // 4. Hangup — durationSec rides the wire (NOT durationMs).
        caller.emitCallSignal(
            event: "call:hangup",
            payload: [
                "callId": "call-rt-1",
                "conversationId": conversationId,
                "from": callerId,
                "to": calleeId,
                "kind": "voice",
                "durationSec": 12,
            ],
        )
        await fulfillment(of: [hangupArrived], timeout: 10)

        // 5. Identity gate — a spoofed sender gets dropped server-side.
        let spoofArrived = expectation(description: "spoofed offer must NOT arrive")
        spoofArrived.isInverted = true
        callee.signals = { signal in
            if case .callSignal(let event, _) = signal, event == "call:offer" {
                spoofArrived.fulfill()
            }
        }
        caller.emitCallSignal(
            event: "call:offer",
            payload: [
                "callId": "call-rt-2",
                "conversationId": conversationId,
                "from": "NOT-\(callerId)",
                "to": calleeId,
                "kind": "voice",
                "sdp": "spoofed",
                "callerName": "Impostor",
            ],
        )
        await fulfillment(of: [spoofArrived], timeout: 3)

        caller.disconnect()
        callee.disconnect()
    }
}
