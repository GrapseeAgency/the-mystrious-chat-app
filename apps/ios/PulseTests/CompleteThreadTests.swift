import XCTest
@testable import Pulse

@MainActor
final class CompleteThreadTests: XCTestCase {
    private func row(_ id: String, parent: String? = nil) throws -> WireChatMessage {
        var values: [String: Any] = ["id": id, "conversationId": "room", "senderId": "me", "content": "Hi", "kind": "text",
            "createdAt": "2026-10-22T00:00:00Z"]
        if let parent { values["parentId"] = parent }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: values))
    }
    private func page(_ ids: [String], next: String? = nil, root: String = "root", version: Int = 1) throws -> WireThreadSlice {
        WireThreadSlice(parent: try row(root), replies: try ids.map { try row($0, parent: "root") },
            threadPageVersion: version, hasMore: next != nil, nextCursor: next)
    }
    func testManyPagesWithTimestampTiesAndAnExpiryEmptyPage() async throws {
        var calls = 0
        let result = try await PulseCompleteThread.load(rootId: "root") { cursor in
            XCTAssertEqual(cursor, calls == 0 ? nil : "next-\(calls)")
            calls += 1
            return try self.page(calls == 7 ? [] : [String(format: "m%03d", calls)], next: calls < 30 ? "next-\(calls)" : nil)
        }
        XCTAssertEqual(calls, 30)
        XCTAssertEqual(result.replies.count, 29)
    }
    func testInvalidOrInterruptedReadsNeverReturnTheFetchedPrefix() async throws {
        for mode in ["offline", "cancel", "cycle", "repeat", "root", "reply", "version", "rows", "bytes", "pages"] {
            var calls = 0
            do {
                _ = try await PulseCompleteThread.load(rootId: "root", maximumRows: mode == "rows" ? 1 : 100,
                    maximumBytes: mode == "bytes" ? 1 : 1_000_000, maximumPages: mode == "pages" ? 1 : 100) { _ in
                    calls += 1
                    if calls == 1 { return try self.page(["a"], next: "next") }
                    switch mode {
                    case "offline": throw URLError(.notConnectedToInternet)
                    case "cancel": throw CancellationError()
                    case "cycle": return try self.page([], next: "next")
                    case "repeat": return try self.page(["a"])
                    case "root": return try self.page(["b"], root: "foreign")
                    case "reply": return WireThreadSlice(parent: try self.row("root"), replies: [try self.row("b", parent: "foreign")], threadPageVersion: 1, hasMore: false, nextCursor: nil)
                    case "version": return try self.page(["b"], version: 0)
                    default: return try self.page(["b"])
                    }
                }
                XCTFail("\(mode) returned a partial thread")
            } catch { if mode == "cancel" { XCTAssertTrue(error is CancellationError) } }
        }
    }
}
