import XCTest
import Foundation
@testable import Pulse

/// Wave 0 — REAL Socket.IO round trip against a relay the environment provides.
///
/// The iOS SDK has no Process type: a test bundle compiled for the simulator
/// cannot spawn the node fixture itself (that limitation is what killed the
/// original in-test spawn design at compile time). CI (ios-ci.yml) therefore
/// starts PulseTests/Fixtures/server.js as a runner-side step on
/// 127.0.0.1:3995 and hands the URL to the test host through the Pulse scheme
/// environment (PULSE_RELAY_URL — see project.yml). The simulator shares the
/// host loopback, so 127.0.0.1 from the test process IS the runner.
/// Locally: start `node server.js 3995` inside PulseTests/Fixtures (any port,
/// exported as PULSE_RELAY_URL). Without a relay the test skips honestly.
///
///   1. connect → join → `joined` ack received
///   2. second participant joins → presence:snapshot contains BOTH ids
///   3. typing emitted from the peer → relay arrives
///   4. POST /notify → message:new envelope arrives (decode parity asserted)
final class SocketRoundTripTests: XCTestCase {
    private let viewerId = "ios-w0-a"
    private let peerId = "peer-b"
    private let conversationId = "conv-rt"

    // ── relay discovery ──────────────────────────────────────

    /// PULSE_RELAY_URL (CI/scheme) first, then the documented local default.
    /// Only a relay that answers /health with 200 counts as present.
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

    // ── the round trip ───────────────────────────────────────

    func testJoinPresenceTypingAndNotifyRoundTrip() async throws {
        guard let base = await Self.relayBase() else {
            throw XCTSkip("no pulse relay on PULSE_RELAY_URL / 127.0.0.1:3995 — start Fixtures/server.js (CI does)")
        }

        // 1. Client A — join ack.
        let joinedA = expectation(description: "client A received joined ack")
        let presenceBoth = expectation(description: "presence snapshot contains both ids")
        let typingRelay = expectation(description: "typing relay arrived from peer")
        let notifyArrived = expectation(description: "POST /notify message:new arrived")

        let clientA = PulseSocketClient(socketURL: base)
        // Explicit `self.x` capture expressions — no ambiguity about whether
        // a bare `[prop]` capture-list shorthand resolves instance properties,
        // and the closure retains only the values (not the test case).
        clientA.signals = { [viewerId = self.viewerId, peerId = self.peerId, conversationId = self.conversationId] signal in
            switch signal {
            case .joined(let ids):
                if ids.contains(viewerId) { joinedA.fulfill() }
            case .presenceSnapshot(let ids):
                if ids.contains(viewerId) && ids.contains(peerId) { presenceBoth.fulfill() }
            case .typing(let convId, let userId, _, let isTyping):
                if convId == conversationId && userId == peerId && isTyping { typingRelay.fulfill() }
            case .messageNew(let convId, let raw):
                guard convId == conversationId else { return }
                if let message = PulseSession.decodeMessage(from: raw) {
                    XCTAssertEqual(message.content, "notify-hello")
                    XCTAssertEqual(message.senderId, peerId)
                    notifyArrived.fulfill()
                }
            default:
                break
            }
        }
        clientA.connect(userId: viewerId)
        await fulfillment(of: [joinedA], timeout: 10)

        // 2. Client B — second participant shows up in presence.
        let clientB = PulseSocketClient(socketURL: base)
        clientB.connect(userId: peerId)
        await fulfillment(of: [presenceBoth], timeout: 10)

        // 3. Typing relay B → A.
        clientB.emitTyping(
            recipients: [viewerId],
            conversationId: conversationId,
            userId: peerId,
            userName: "Peer B",
            isTyping: true,
        )
        await fulfillment(of: [typingRelay], timeout: 10)

        // 4. HTTP /notify relay — the exact contract the Next.js API uses.
        let payload: [String: Any] = [
            "event": "message:new",
            "recipients": [viewerId],
            "payload": [
                "type": "message:new",
                "conversationId": conversationId,
                "recipientIds": [viewerId],
                "message": [
                    "id": "m-notify-1",
                    "conversationId": conversationId,
                    "senderId": peerId,
                    "content": "notify-hello",
                    "kind": "text",
                    "createdAt": "2026-09-07T12:26:36.991Z",
                ],
            ],
        ]
        var request = URLRequest(url: base.appendingPathComponent("notify"))
        request.httpMethod = "POST"
        request.timeoutInterval = 6
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        await fulfillment(of: [notifyArrived], timeout: 10)

        clientA.disconnect()
        clientB.disconnect()
    }
}
