import XCTest
@testable import Pulse

private final class PersonalHubProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "personal-hub.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        let owner = mode == "owner" ? "other" : "me"
        var task: [String: Any] = ["id": mode == "id" ? "other" : "t", "ownerId": mode == "row-owner" ? "other" : owner,
            "title": mode == "title" ? "Ignored edit" : "Task", "status": mode == "status" ? "todo" : "done",
            "createdAt": "2026-10-22T00:00:00Z", "updatedAt": "2026-10-22T00:00:00Z"]
        if mode == "stamp" { task.removeValue(forKey: "updatedAt") }
        var body: [String: Any] = ["userId":owner, "task":task, "tasks":[task], "taskId":mode == "id" ? "other" : "t", "ok":mode != "ok",
            "wallet":["userId":owner, "coins":1, "gems":0], "ledger":[]]
        if mode == "ledger" { body.removeValue(forKey: "ledger") }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode:200, httpVersion:nil,
            headerFields:["Content-Type":"application/json"])!, cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class PersonalHubAcceptanceTests: XCTestCase {
    func testWalletHistoryAndPersonalTaskIntentCannotAcceptPartialOrForeignSuccess() async throws {
        for action in ["wallet","list","create","update","delete"] {
            var modes = ["valid","empty","owner"]
            switch action {
            case "wallet": modes += ["ledger"]
            case "delete": modes += ["id","ok"]
            case "list": modes += ["row-owner","stamp"]
            default: modes += ["row-owner","stamp","title","status"]; if action == "update" { modes += ["id"] }
            }
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [PersonalHubProtocol.self]; config.httpAdditionalHeaders = ["X-Mode":mode]
                let transport = URLSession(configuration:config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL:URL(string:"https://personal-hub.test")!, userId:"me", session:transport)
                do {
                    switch action {
                    case "wallet": _ = try await api.walletPage()
                    case "list": _ = try await api.hubTasks()
                    case "create": _ = try await api.createHubTask(title:"Task", status:"done")
                    case "update": _ = try await api.updateHubTask("t", title:"Task", status:"done")
                    default: try await api.deleteHubTask("t")
                    }
                    XCTAssertEqual(mode,"valid","\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode,"valid","\(action) valid receipt failed: \(error)") }
            }
        }
    }
    func testSnapshotInvalidationDoesNotClearOtherOwnerOrPrivateThreadJournal() throws {
        let store = try PulseStore()
        try store.saveWave7Cache(key:"hub:tasks:v2:me", json:"old", updatedAt:0)
        try store.saveWave7Cache(key:"hub:tasks:v2:other", json:"keep", updatedAt:0)
        try store.denyThread(rootId:"root")
        try store.deleteWave7Cache(key:"hub:tasks:v2:me")
        XCTAssertNil(try store.loadWave7Cache(key:"hub:tasks:v2:me"))
        XCTAssertEqual(try store.loadWave7Cache(key:"hub:tasks:v2:other")?.json,"keep")
        XCTAssertTrue(try store.threadAccessDenied(rootId:"root"))
    }
}
