import XCTest
@testable import Pulse

private final class RoomControlProtocol: URLProtocol {
    static var methods: [String] = []
    static var paths: [String] = []
    static var bodies: [String] = []
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "controls.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.methods.append(request.httpMethod ?? "GET")
        Self.paths.append(request.url!.path + (request.url!.query.map { "?" + $0 } ?? ""))
        if let body = request.httpBodyStream { // read body for assertions
            var data = Data(); body.open()
            let bufferSize = 4096; let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
            while body.hasBytesAvailable {
                let read = body.read(buffer, maxLength: bufferSize)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            buffer.deallocate(); body.close()
            if let text = String(data: data, encoding: .utf8) { Self.bodies.append(text) }
        } else if let data = request.httpBody, let text = String(data: data, encoding: .utf8) {
            Self.bodies.append(text)
        }
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let action = request.value(forHTTPHeaderField: "X-Action") ?? "list"
        var rule: [String: Any] = ["id": mode == "wrong" ? "other" : "rule", "conversationId": mode == "wrong" ? "other" : "room",
            "trigger": "Hello", "reply": "Welcome", "hits": 0, "enabled": action != "patch"]
        var hook: [String: Any] = ["id": "hook", "name": "Build bot", "createdBy": "me", "token": mode == "wrong" ? "" : "capability",
            "url": "/api/webhooks/capability", "rotatedAt": "2026-02-02T10:00:00.000Z"]
        if mode == "missing" { rule.removeValue(forKey: "enabled"); hook.removeValue(forKey: "token") }
        // Section 12: every list envelope carries the integration revision +
        // the shared page verdict; every mutation receipt carries replayed.
        var body: [String: Any] = ["automation": rule, "automations": [rule], "webhooks": [hook], "ok": true, "deleted": true,
            "userId": mode == "wrong" ? "other" : "me", "automationId": "rule", "token": "capability",
            "revision": 4, "page": ["nextCursor": "1770000000001.a1", "hasMore": true], "replayed": false]
        if mode == "missing" { body.removeValue(forKey: "automations"); body.removeValue(forKey: "webhooks"); body.removeValue(forKey: "deleted") }
        if action == "hookCreate" { body = hook }
        if action == "hookRotate" { // Section 12 item 9 - the fresh capability receipt
            body = hook
            if mode != "missing" {
                body["token"] = mode == "wrong" ? "" : "rotated-capability"
                body["url"] = "/api/webhooks/\(body["token"] as? String ?? "")"
                body["rotatedAt"] = "2026-02-02T10:00:00.000Z"
            }
            body["revision"] = 7
            body["replayed"] = false
        }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class RoomControlAcceptanceTests: XCTestCase {
    private func api(action: String = "list", mode: String = "valid") -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RoomControlProtocol.self]
        config.httpAdditionalHeaders = ["X-Mode": mode, "X-Action": action]
        let session = URLSession(configuration: config)
        return PulseAPIClient(baseURL: URL(string: "https://controls.test")!, userId: "me", session: session)
    }

    override func setUp() {
        super.setUp()
        RoomControlProtocol.methods = []; RoomControlProtocol.paths = []; RoomControlProtocol.bodies = []
    }

    func testInvalidListsRowsAndDeletionReceiptsNeverConfirmControls() async throws {
        for action in ["list", "create", "patch", "delete", "hooks", "hookCreate", "hookDelete", "hookRotate"] {
            for mode in ["valid", "empty", "wrong", "missing"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [RoomControlProtocol.self]
                config.httpAdditionalHeaders = ["X-Mode": mode, "X-Action": action]
                let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://controls.test")!, userId: "me", session: session)
                do {
                    switch action {
                    case "list": _ = try await api.automations(conversationId: "room")
                    case "create": _ = try await api.createAutomation(conversationId: "room", trigger: "Hello", reply: "Welcome")
                    case "patch": _ = try await api.updateAutomation("rule", enabled: false)
                    case "delete": try await api.deleteAutomation("rule")
                    case "hooks": _ = try await api.webhooks(conversationId: "room")
                    case "hookCreate": _ = try await api.createWebhook(conversationId: "room", name: "Build bot")
                    case "hookRotate": _ = try await api.rotateWebhook(token: "capability", requesterId: "me")
                    default: try await api.deleteWebhook(token: "capability")
                    }
                    XCTAssertEqual(mode, "valid", "\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode, "valid", "\(action):\(mode)") }
            }
        }
    }

    // Section 12 item 2 - the automations GET carries limit/cursor and the
    // page verdict (revision + nextCursor/hasMore) decodes.

    func testAutomationsGetCarriesLimitAndCursorAndDecodesThePageVerdict() async throws {
        let page = try await api().automationsPage(conversationId: "room", limit: 25, cursor: "1770000000000.a0")
        XCTAssertEqual(page.revision, 4)
        XCTAssertEqual(page.page?.hasMore, true)
        XCTAssertEqual(page.page?.nextCursor, "1770000000001.a1")
        XCTAssertEqual(page.automations?.first?.id, "rule")
        XCTAssertTrue(RoomControlProtocol.paths.last?.contains("limit=25") == true)
        XCTAssertTrue(RoomControlProtocol.paths.last?.contains("cursor=1770000000000.a0") == true)
        // The legacy no-params call opens the first page: the paging keys
        // stay OFF the query.
        _ = try await api().automations(conversationId: "room")
        XCTAssertTrue(RoomControlProtocol.paths.last?.contains("limit=") == false)
        XCTAssertTrue(RoomControlProtocol.paths.last?.contains("cursor=") == false)
    }

    // Section 12 item 1 - create/delete ride the journaled clientId.

    func testCreateAndDeleteCarryTheJournaledClientId() async throws {
        let client = api(action: "create")
        let row = try await client.createAutomation(conversationId: "room", trigger: "Hello", reply: "Welcome", clientId: "auto000000000000001")
        XCTAssertEqual(row.id, "rule")
        XCTAssertTrue(RoomControlProtocol.bodies.last?.contains("auto000000000000001") == true, "the replay identity rides the POST body")
        try await client.deleteAutomation("rule", clientId: "auto000000000000002")
        XCTAssertTrue(RoomControlProtocol.bodies.last?.contains("auto000000000000002") == true, "the replay identity rides the DELETE body")
        // Without identity the legacy body shape is kept (no clientId key).
        try await client.deleteAutomation("rule")
        XCTAssertTrue(RoomControlProtocol.bodies.last?.contains("clientId") == false)
    }

    func testWebhookDeleteCarriesTheJournaledClientIdOnTheQuery() async throws {
        try await api(action: "hookDelete").deleteWebhook(token: "capability", clientId: "hook000000000000002")
        XCTAssertEqual(RoomControlProtocol.methods.last, "DELETE")
        // The replay identity MUST ride the query next to requesterId - the
        // route reads the body only when the query is bare.
        XCTAssertTrue(RoomControlProtocol.paths.last?.contains("clientId=hook000000000000002") == true)
    }

    // Section 12 item 9 - the rotate PATCH answers the WireWebhookMutation
    // receipt (fresh token + rotatedAt + revision + replayed).

    func testRotatePatchCarriesClientIdAndParsesTheMutationReceipt() async throws {
        let mutation = try await api(action: "hookRotate").rotateWebhook(token: "capability", requesterId: "me", clientId: "hook000000000000001")
        XCTAssertEqual(RoomControlProtocol.methods.last, "PATCH")
        XCTAssertEqual(RoomControlProtocol.paths.last, "/api/webhooks/capability")
        XCTAssertTrue(RoomControlProtocol.bodies.last?.contains("hook000000000000001") == true)
        XCTAssertTrue(RoomControlProtocol.bodies.last?.contains("requesterId") == true)
        XCTAssertEqual(mutation.token, "rotated-capability")
        XCTAssertEqual(mutation.url, "/api/webhooks/rotated-capability")
        XCTAssertEqual(mutation.rotatedAt, "2026-02-02T10:00:00.000Z")
        XCTAssertEqual(mutation.revision, 7)
        XCTAssertEqual(mutation.replayed, false)
    }

    func testWebhookCreateRefusesAMissingReplayReceiptWhenIdentityWasSent() async throws {
        // The fixture's hookCreate body is a bare WebhookDTO - no replayed
        // flag - so the journaled identity cannot be acknowledged.
        do {
            _ = try await api(action: "hookCreate").createWebhook(conversationId: "room", name: "Build bot", clientId: "hook000000000000003")
            XCTFail("a receipt without the replay flag must be refused")
        } catch { /* expected */ }
    }
}
