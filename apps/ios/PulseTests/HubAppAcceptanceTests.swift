import XCTest
@testable import Pulse

private final class HubAppReceiptProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "hub-app.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField:"X-Mode") ?? "valid"
        let desired = request.httpMethod != "DELETE"
        let installed = mode == "ignored" ? !desired : desired
        var room: [String:Any] = ["id":"room","isGroup":true,"members":[["id":"me","name":"Me"]]]
        if ["roster","preview"].contains(mode) { room["members"] = [] }
        if mode == "private" { room["myDraft"] = "private draft" }
        var body: [String:Any] = ["appVersion":mode == "version" ? 0 : 1,"appId":mode == "app" ? "2" : "1",
            "userId":mode == "owner" ? "other" : "me","installed":installed,"installs":mode == "count" ? -1 : 1,
            "installedAt":"2026-10-22T00:00:00Z","installers":[],"joined":mode != "preview","memberCount":mode == "count" ? 2 : 1,"conversation":room]
        if installed { body["status"] = mode == "status" ? "muted" : "connected" }
        else if mode == "status" { body["status"] = "connected" }
        if mode == "stamp" { body.removeValue(forKey:"installedAt") }
        if mode == "empty" { body = [:] }
        client?.urlProtocol(self, didReceive:HTTPURLResponse(url:request.url!,statusCode:200,httpVersion:nil,headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self, didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class HubAppAcceptanceTests: XCTestCase {
    func testAppResponsesMustMatchOwnerAppAndRequestedAction() async throws {
        for action in ["state","connect","disconnect","community","join"] {
            var modes = ["valid","empty","owner","app","version"]
            switch action {
            case "state": modes += ["count","stamp","status"]
            case "connect","disconnect": modes += ["count","status","ignored"]
            default: modes += ["roster","count","private","preview"]
            }
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [HubAppReceiptProtocol.self];config.httpAdditionalHeaders = ["X-Mode":mode]
                let transport = URLSession(configuration:config);defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL:URL(string:"https://hub-app.test")!,userId:"me",session:transport)
                let shouldAccept = mode == "valid" || (action == "community" && mode == "preview")
                do {
                    switch action {
                    case "state": _ = try await api.appInstallState(appId:"1")
                    case "connect": _ = try await api.installApp(appId:"1")
                    case "disconnect": _ = try await api.uninstallApp(appId:"1")
                    case "community": _ = try await api.appCommunity(appId:"1")
                    default: _ = try await api.joinAppCommunity(appId:"1")
                    }
                    XCTAssertTrue(shouldAccept,"\(action):\(mode)")
                } catch { XCTAssertFalse(shouldAccept,"\(action):\(mode):\(error)") }
            }
        }
    }
}
