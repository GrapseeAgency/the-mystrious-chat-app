import XCTest
@testable import Pulse

final class TimelineRecoveryTests: XCTestCase {
    private func row(_ id: String, body: String = "same", topic: String? = nil) throws -> WireChatMessage {
        let row = OutboxFixtures.makeMessage(id: id, conversationId: "room", content: body)
        let data = try JSONEncoder().encode(row)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["topicId"] = topic
        object["createdAt"] = "2026-10-19T00:00:00.000Z"
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
    }

    func testReconnectReplacesKnownEditsAndRetainsDistinctPendingOperations() throws {
        let result = PulseTimelineMerge.merge(
            [try row("a", body: "old"), try row("local_pending"), try row("b")],
            incoming: [try row("a", body: "edited"), try row("c")], topicId: nil)
        XCTAssertEqual(Set(result.map(\.id)), Set(["a", "b", "c", "local_pending"]))
        XCTAssertEqual(result.first { $0.id == "a" }?.content, "edited")
    }

    func testTombstoneAndTopicMoveReplaceCachedLiveRow() throws {
        let original = try row("a", topic: "topic-a")
        let tombstone = original.deletedCopy()
        let deleted = PulseTimelineMerge.merge([original], incoming: [tombstone], topicId: "topic-a")
        XCTAssertNotNil(deleted.first?.deletedAt)
        let moved = PulseTimelineMerge.merge([original], incoming: [try row("a", topic: "topic-b")], topicId: "topic-a")
        XCTAssertTrue(moved.isEmpty)
    }

    func testTimestampTiesHaveStableIdOrdering() throws {
        let result = PulseTimelineMerge.merge([try row("c"), try row("a")], incoming: [try row("b")], topicId: nil)
        XCTAssertEqual(result.map(\.id), ["a", "b", "c"])
    }

    func testSyncAndHistoryQueriesPreserveOpaqueValues() {
        let path = PulseAPIClient.messagesPath(conversationId: "room", limit: 100, before: nil, query: nil,
            syncFrom: "2026-10-19T00:00:00+06:00", syncCursor: "cursor_123-abc")
        XCTAssertTrue(path.contains("syncFrom=2026-10-19T00:00:00%2B06:00"))
        XCTAssertTrue(path.contains("syncCursor=cursor_123-abc"))
        let history = PulseAPIClient.messagesPath(conversationId: "room", limit: 40,
            before: "2026-10-19T00:00:00Z", query: nil, beforeId: "message&other")
        XCTAssertTrue(history.contains("beforeId=message%26other"))
    }

    func testOldServerCannotPretendToSupportSync() throws {
        let data = Data(#"{"messages":[],"hasMore":false,"total":0}"#.utf8)
        let page = try JSONDecoder().decode(WireMessagesPage.self, from: data)
        XCTAssertNil(page.syncVersion)
        XCTAssertNil(page.nextSyncCursor)
    }
    func testRawHistoryCursorAdvancesAcrossHiddenReplies() throws {
        let original = try row("a")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        object["parentId"] = "thread-root"
        let reply = try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
        XCTAssertTrue(PulseTimelineMerge.merge([], incoming: [reply], topicId: nil).isEmpty)
        let cursor = try XCTUnwrap(PulseHistoryCursor.oldest([reply]))
        XCTAssertEqual(cursor.id, "a")
        XCTAssertTrue(cursor.precedes(PulseHistoryCursor(createdAt: reply.createdAt, id: "b")))
        XCTAssertFalse(cursor.precedes(cursor))
        XCTAssertNil(PulseHistoryCursor.oldest([try row("local_pending")]))
    }

    func testDelayedRefreshCannotRewindPagingOrReopenExhaustedHistory() {
        var history = PulseHistoryWindow()
        let newer = PulseHistoryCursor(createdAt: "2026-10-20T00:00:00Z", id: "newer")
        let older = PulseHistoryCursor(createdAt: "2026-10-19T00:00:00Z", id: "older")
        history.record(cursor: newer, hasMore: true)
        let refresh = history.revision
        history.invalidateSeed() // the reader started loading older before refresh returned
        XCTAssertFalse(history.seed(cursor: newer, hasMore: false, ifUnchanged: refresh))
        history.record(cursor: older, hasMore: true)
        XCTAssertFalse(history.seed(cursor: newer, hasMore: false, ifUnchanged: refresh))
        XCTAssertEqual(history.cursor, older)
        XCTAssertTrue(history.hasMore)
        let laterRefresh = history.revision
        history.record(cursor: older, hasMore: false) // empty terminal page: same cursor, new verdict
        XCTAssertFalse(history.seed(cursor: newer, hasMore: true, ifUnchanged: laterRefresh))
        XCTAssertEqual(history.cursor, older)
        XCTAssertFalse(history.hasMore)
    }

    func testTopicResetInvalidatesOldSeedEvenAfterReturningToSameEmptyState() {
        var history = PulseHistoryWindow()
        let first = history.revision
        history.record(cursor: nil, hasMore: false) // select another topic
        history.record(cursor: nil, hasMore: false) // back to General (A -> B -> A)
        let cursor = PulseHistoryCursor(createdAt: "2026-10-19T00:00:00Z", id: "a")
        XCTAssertFalse(history.seed(cursor: cursor, hasMore: true, ifUnchanged: first))
        XCTAssertTrue(history.seed(cursor: cursor, hasMore: true, ifUnchanged: history.revision))
        XCTAssertEqual(history.cursor, cursor)
    }

    func testTopicReloadMergesCacheWithoutTruncatingAlreadyExpandedHistory() throws {
        let expanded = try (0..<400).map { try row("message-\($0)", topic: "topic") }
        let cachedTail = Array(expanded.suffix(300))
        let retained = PulseTimelineMerge.merge(expanded, incoming: cachedTail, topicId: "topic")
        let result = PulseTimelineMerge.merge(retained, incoming: [try row("latest", topic: "topic")], topicId: "topic")
        XCTAssertEqual(result.count, 401)
        XCTAssertTrue(result.contains { $0.id == "message-0" })
        let switched = PulseTimelineMerge.merge(result, incoming: [try row("other", topic: "other")], topicId: "other")
        XCTAssertEqual(switched.map(\.id), ["other"])
    }

}
