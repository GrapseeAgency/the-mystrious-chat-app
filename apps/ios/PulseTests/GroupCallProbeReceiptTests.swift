import XCTest
@testable import Pulse

private final class GroupCallProbeProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "group-probe.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Probe-Test") ?? "valid"
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let bound = query.contains { $0.name == "userId" && $0.value == "me" } && query.contains { $0.name == "conversationId" && $0.value == "room" }
        var body: [String: Any] = ["callStateVersion": 1, "conversationId": "room", "userId": "me",
            "callId": "call", "kind": "voice", "startedAt": 1000,
            "members": [["id": "peer", "name": "Peer", "color": "emerald", "avatar": NSNull()] as [String: Any]]]
        switch mode {
        case "empty": body["callId"] = ""; body["members"] = [] as [String]
        case "legacy": body = ["members": []]
        case "room": body["conversationId"] = "other"
        case "owner": body["userId"] = "other"
        case "version": body["callStateVersion"] = 0
        case "version-string": body["callStateVersion"] = "1"
        case "missing-members": body.removeValue(forKey: "members")
        case "missing-call": body.removeValue(forKey: "callId")
        case "kind": body["kind"] = "unknown"
        case "started": body.removeValue(forKey: "startedAt")
        default: break
        }
        let status = mode == "refused" || !bound ? 403 : 200
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class GroupCallProbeReceiptTests: XCTestCase {
    func testOnlyCompleteBoundReceiptsCanPopulateOrClearTheOngoingCall() async throws {
        for mode in ["valid", "empty", "legacy", "room", "owner", "version", "version-string", "missing-members", "missing-call", "kind", "started", "refused"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [GroupCallProbeProtocol.self]
            config.httpAdditionalHeaders = ["X-Probe-Test": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://group-probe.test")!, userId: "me", session: transport)
            do {
                let snapshot = try await api.groupCallState(conversationId: "room")
                XCTAssertTrue(["valid", "empty"].contains(mode), mode)
                XCTAssertEqual(snapshot.members.isEmpty, mode == "empty")
            } catch { XCTAssertFalse(["valid", "empty"].contains(mode), "\(mode): \(error)") }
        }
    }
}
