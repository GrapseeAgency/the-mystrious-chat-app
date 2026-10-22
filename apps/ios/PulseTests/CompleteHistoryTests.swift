import XCTest
@testable import Pulse

final class CompleteHistoryTests: XCTestCase {
    private func page(_ ids: [String], more: Bool = false, cursor: String? = nil, version: Int? = 1, room: String = "room") -> WireMessagesPage {
        let rows = ids.map { id -> WireChatMessage in
            let object: [String: Any] = ["id": id, "conversationId": room, "senderId": "me", "content": "hello",
                "kind": "text", "createdAt": "2026-10-22T00:00:00Z"]
            return try! JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
        }
        return WireMessagesPage(messages: rows, hasMore: more, total: ids.count, syncVersion: version, nextSyncCursor: cursor)
    }
    func testFollowsStableCursorPastFormerPageCapAndEmptyRoomSucceeds() async throws {
        var calls = 0
        let result = try await PulseCompleteHistory.load(room: "room") { cursor in
            XCTAssertEqual(cursor, calls == 0 ? nil : "cursor-\(calls)")
            calls += 1
            return self.page([String(format: "m%03d", calls)], more: calls < 30, cursor: calls < 30 ? "cursor-\(calls)" : nil)
        }
        XCTAssertEqual(result.count, 30)
        let empty = try await PulseCompleteHistory.load(room: "room") { _ in self.page([]) }
        XCTAssertTrue(empty.isEmpty)
    }
    func testFailureCancellationOldGatewayAndBrokenProgressNeverReturnPrefix() async throws {
        for mode in ["network", "cancel", "repeated", "empty-more", "old", "wrong-room", "row-limit", "byte-limit"] {
            var calls = 0
            do {
                _ = try await PulseCompleteHistory.load(room: "room", maximumRows: mode == "row-limit" ? 1 : 100,
                    maximumBytes: mode == "byte-limit" ? 1 : 1024 * 1024) { _ in
                    calls += 1
                    if calls == 1 { return self.page(["a"], more: true, cursor: "next") }
                    switch mode {
                    case "network": throw URLError(.notConnectedToInternet)
                    case "cancel": throw CancellationError()
                    case "repeated": return self.page(["a"])
                    case "empty-more": return self.page([], more: true, cursor: "later")
                    case "old": return self.page(["b"], version: nil)
                    case "wrong-room": return self.page(["b"], room: "other")
                    default: return self.page(["b"])
                    }
                }
                XCTFail("\(mode) must not return partial history")
            } catch {
                if mode == "cancel" { XCTAssertTrue(error is CancellationError) }
            }
        }
    }
}
