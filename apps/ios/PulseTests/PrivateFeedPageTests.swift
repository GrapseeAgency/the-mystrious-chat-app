import XCTest
@testable import Pulse

private final class PrivateFeedProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "private-feed.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let mode = String(path.split(separator: "/")[0])
        let search = path.hasSuffix("/search"), saved = path.hasSuffix("/saved")
        let key = search ? "messages" : "items"
        var row: [String: Any] = search ? ["id": "m", "conversationId": "r"] : ["messageId": "m", "conversationId": "r"]
        if saved {
            row = ["savedAt": mode == "bad-date" ? "invalid" : "2026-10-28T00:00:00.000Z", "revision": mode == "bad-revision" ? "01" : "1",
                "conversation": ["id": mode == "foreign-room" ? "other" : "r"],
                "message": ["id": "m", "conversationId": "r", "senderId": "peer", "content": "body", "kind": "text", "createdAt": "2026-10-28T00:00:00.000Z"]]
        }
        if mode == "blank" { row[search ? "id" : "messageId"] = "" }
        let count = mode == "oversize" ? (search ? 31 : 101) : mode == "duplicate" ? 2 : 1
        var rows = Array(repeating: row, count: count)
        if mode == "oversize" {
            for index in rows.indices {
                if saved {
                    var message = rows[index]["message"] as! [String: Any]; message["id"] = "m\(index)"; rows[index]["message"] = message
                } else { rows[index][search ? "id" : "messageId"] = "m\(index)" }
            }
        }
        let continuation = ["empty", "repeat"].contains(mode)
        var body: [String: Any] = [key: mode == "empty" ? [] : rows, "feedPageVersion": 1, "hasMore": continuation]
        if continuation { body["nextCursor"] = mode == "repeat" ? "same" : "next" }
        if mode == "missing-list" { body.removeValue(forKey: key) }
        if mode == "missing-more" { body.removeValue(forKey: "hasMore") }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class PrivateFeedPageTests: XCTestCase {
    func testStrictSearchMentionAndSavedWindowsAndEmptyContinuations() async throws {
        for kind in ["search", "mentions", "saved"] {
            var modes = ["valid", "empty", "missing-list", "missing-more", "duplicate", "oversize", "repeat"]
            modes += kind == "saved" ? ["foreign-room", "bad-revision", "bad-date"] : ["blank"]
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [PrivateFeedProtocol.self]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://private-feed.test/\(mode)")!, userId: "owner", session: transport)
                do {
                    let cursor: String? = mode == "repeat" ? "same" : nil
                    let count: Int, more: Bool?
                    switch kind {
                    case "search":
                        let page = try await api.searchMessages("term", cursor: cursor, requirePagination: true)
                        count = page.messages!.count; more = page.hasMore
                    case "mentions":
                        let page = try await api.mentionPage(cursor: cursor)
                        count = page.items!.count; more = page.hasMore
                    default:
                        let page = try await api.savedPage(userId: "owner", cursor: cursor)
                        count = page.items!.count; more = page.hasMore
                    }
                    XCTAssertTrue(["valid", "empty"].contains(mode), "\(kind): \(mode) accepted")
                    XCTAssertEqual(count, mode == "empty" ? 0 : 1)
                    XCTAssertEqual(more, mode == "empty")
                } catch { XCTAssertFalse(["valid", "empty"].contains(mode), "\(kind): \(mode): \(error)") }
            }
        }
    }
}
