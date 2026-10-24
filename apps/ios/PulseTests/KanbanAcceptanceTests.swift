import XCTest
@testable import Pulse

private final class KanbanReceiptProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "board-receipt.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
        var card: [String: Any] = ["id":"k", "conversationId":"room", "title":"Task", "column":"doing", "position":1,
            "createdById":"me", "sourceMessageId":"source", "createdAt":"2026-10-22T00:00:00Z", "updatedAt":"2026-10-22T00:00:00Z"]
        switch mode {
        case "id": card["id"] = "other"
        case "room": card["conversationId"] = "other"
        case "stamp": card.removeValue(forKey: "updatedAt")
        case "column": card["column"] = "todo"
        case "title": card["title"] = "Ignored edit"
        case "source": card.removeValue(forKey: "sourceMessageId")
        case "assignee": card["assigneeId"] = "other"
        default: break
        }
        var body: [String: Any] = ["boardReceiptVersion":mode == "version" ? 0 : 1,
            "userId":mode == "owner" ? "other" : "me", "conversationId":"room", "card":card,
            "cards": mode == "duplicate" ? [card,card] : [card], "cardId":mode == "id" ? "other" : "k", "ok":mode != "false-ok"]
        if mode == "legacy" { body.removeValue(forKey: "boardReceiptVersion") }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class KanbanAcceptanceTests: XCTestCase {
    func testBoardActionsRequireTheAcknowledgedIntentNotAnEmptyFallbackCard() async throws {
        for action in ["create","update","delete","list"] {
            var modes = ["valid","legacy","version","owner"]
            switch action {
            case "delete": modes += ["id","false-ok"]
            case "list": modes += ["room","stamp","duplicate"]
            case "create": modes += ["room","stamp","column","title","source","assignee"]
            default: modes += ["id","room","stamp","column","title","assignee"]
            }
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [KanbanReceiptProtocol.self]; config.httpAdditionalHeaders = ["X-Mode":mode]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string:"https://board-receipt.test")!, userId:"me", session:transport)
                do {
                    switch action {
                    case "create": _ = try await api.createKanbanCard(conversationId:"room", title:"Task", column:"doing", assigneeId:nil, messageId:"source")
                    case "update": _ = try await api.updateKanbanCard("k", title:"Task", column:"doing", assigneeId:nil, clearAssignee:true, position:nil)
                    case "delete": try await api.deleteKanbanCard("k")
                    default: _ = try await api.kanbanBoard(conversationId:"room")
                    }
                    XCTAssertEqual(mode,"valid","\(action):\(mode)")
                } catch { XCTAssertNotEqual(mode,"valid","\(action) valid receipt failed: \(error)") }
            }
        }
    }
}
