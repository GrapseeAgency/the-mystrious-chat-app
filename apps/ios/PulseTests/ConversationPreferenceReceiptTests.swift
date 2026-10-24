import XCTest
@testable import Pulse

private final class ConversationPreferenceProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "flags.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let parts = request.url!.path.split(separator: "/")
        let mode = String(parts[0]), enabled = parts[1] == "on"
        var receipt: [String: Any] = ["ok": true, "conversationPreferenceVersion": 1, "conversationId": "room", "userId": "owner",
            "pinned": enabled, "archived": enabled, "mutePreset": enabled ? "8h" as Any : NSNull()]
        for key in ["pinnedAt", "archivedAt", "mutedUntil"] { receipt[key] = enabled ? "2001-01-01T00:00:00.000Z" as Any : NSNull() }
        switch mode {
        case "empty": receipt = [:]
        case "foreign-room": receipt["conversationId"] = "other"
        case "foreign-owner": receipt["userId"] = "other"
        case "wrong-value": receipt["pinned"] = !enabled; receipt["archived"] = !enabled; receipt["mutePreset"] = "1w"
        case "missing-stamp": for key in ["pinnedAt", "archivedAt", "mutedUntil"] { receipt.removeValue(forKey: key) }
        case "bad-stamp": for key in ["pinnedAt", "archivedAt", "mutedUntil"] { receipt[key] = "not-a-date" }
        case "quoted-ok": receipt["ok"] = "true"
        case "quoted-version": receipt["conversationPreferenceVersion"] = "1"
        default: break
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: receipt))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class ConversationPreferenceReceiptTests: XCTestCase {
    func testAllFlagsRequireOwnedReceiptsAndUseExactServerTimestamps() async throws {
        for enabled in [false, true] {
            for mode in ["valid", "empty", "foreign-room", "foreign-owner", "wrong-value", "missing-stamp", "bad-stamp", "quoted-ok", "quoted-version"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [ConversationPreferenceProtocol.self]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let url = URL(string: "https://flags.test/\(mode)/\(enabled ? "on" : "off")")!
                let api = PulseAPIClient(baseURL: url, userId: "owner", session: transport)
                for action in ["pin", "archive", "mute"] {
                    do {
                        let receipt: PulseAPIClient.ConversationPreferenceReceipt
                        switch action {
                        case "pin": receipt = try await api.setPinned(conversationId: "room", pinned: enabled)
                        case "archive": receipt = try await api.archive(conversationId: "room", archived: enabled)
                        default: receipt = try await api.setMuted(conversationId: "room", until: enabled ? "8h" : nil)
                        }
                        XCTAssertEqual(mode, "valid")
                        XCTAssertEqual(receipt.timestamp, enabled ? "2001-01-01T00:00:00.000Z" : nil)
                        XCTAssertEqual(receipt.date?.timeIntervalSince1970, enabled ? 978307200 : nil)
                    } catch { XCTAssertNotEqual(mode, "valid", "\(action): \(error)") }
                }
            }
        }
    }
}
