import XCTest
import Combine
@testable import Pulse

@MainActor
final class ThreadReplyCountTests: XCTestCase {
    private func row(_ id: String, room: String = "room", parent: String? = nil, expires: Date? = nil) throws -> WireChatMessage {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(
            OutboxFixtures.makeMessage(id: id, conversationId: room, content: id))) as? [String: Any])
        object["createdAt"] = "2026-10-21T00:00:00.000Z" // deterministic timestamp ties, not wall-clock luck
        object["parentId"] = parent
        object["expiresAt"] = expires.map { ISO8601DateFormatter().string(from: $0) }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
    }

    func testCountsAndNextDeadlineFollowRootAndReplyVisibilityWithoutPurgingHistory() throws {
        let store = try PulseStore()
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let root = try row("root")
        let expiringRoot = try row("expiring-root", expires: now.addingTimeInterval(20))
        let nested = try row("nested", parent: "root")
        let rows = try [root, expiringRoot, nested,
            row("short", parent: "root", expires: now.addingTimeInterval(10)),
            row("long", parent: "expiring-root", expires: now.addingTimeInterval(60)),
            row("expired", parent: "root", expires: now), row("foreign", room: "other", parent: "root"),
            row("orphan", parent: "missing"), row("invalid-nesting", parent: "nested"),
            row("deleted", parent: "root").deletedCopy()]
        try store.upsert(messages: rows)
        try store.upsert(messages: rows) // duplicated/replayed batch
        let initial = try store.threadReplyCountSnapshot(conversationId: "room", now: now)
        XCTAssertEqual(initial.counts, ["root": 2, "expiring-root": 1]) // nested is itself a valid direct reply
        XCTAssertEqual(initial.nextExpiry, now.addingTimeInterval(10))
        let afterReply = try store.threadReplyCountSnapshot(conversationId: "room", now: now.addingTimeInterval(10))
        XCTAssertEqual(afterReply.counts, ["root": 1, "expiring-root": 1])
        XCTAssertEqual(afterReply.nextExpiry, now.addingTimeInterval(20))
        let afterRoot = try store.threadReplyCountSnapshot(conversationId: "room", now: now.addingTimeInterval(20))
        XCTAssertEqual(afterRoot.counts, ["root": 1])
        XCTAssertNil(afterRoot.nextExpiry)
        try store.upsert(messages: [root.deletedCopy()])
        XCTAssertTrue(try store.threadReplyCountSnapshot(conversationId: "room", now: now.addingTimeInterval(20)).counts.isEmpty)
        XCTAssertNotNil(try store.message(id: "nested", now: now)) // no destructive cleanup to repair counts
    }

    func testRetainedFailedEchoDoesNotCountAndOtherAccountRemainsEmpty() throws {
        let store = try PulseStore()
        try store.upsert(messages: [row("root")])
        let echo = TempMessages.make(conversationId: "room", viewer: PulseViewer(id: "me", name: "Me"),
            clientId: "failed", content: "reply", parentId: "root")
        try store.enqueueMessage(echo, clientId: "failed", envelope: PulseOutboxForward(kind: "text", parentId: "root"))
        try store.rejectOutbox(clientId: "failed", reason: "forbidden")
        try store.upsert(messages: [echo]) // legacy retained echo, still rejected
        XCTAssertTrue(try store.threadReplyCountSnapshot(conversationId: "room").counts.isEmpty)
        XCTAssertEqual(try store.countOutbox(), 1)
        let other = try PulseStore()
        XCTAssertTrue(try other.threadReplyCountSnapshot(conversationId: "room").counts.isEmpty)
    }

    func testObservationUpdatesOnReplyOnlyWritesAndDeletes() async throws {
        let store = try PulseStore()
        try store.upsert(messages: [row("root")])
        let seeded = expectation(description: "empty initial counts")
        let inserted = expectation(description: "reply-only insertion")
        let deleted = expectation(description: "reply-only deletion")
        var phase = 0
        let observation = store.observeThreadReplyCounts(conversationId: "room").removeDuplicates()
            .sink(receiveCompletion: { if case .failure(let error) = $0 { XCTFail("\(error)") } }, receiveValue: { snapshot in
                switch phase {
                case 0:
                    XCTAssertTrue(snapshot.counts.isEmpty); phase = 1; seeded.fulfill()
                case 1:
                    XCTAssertEqual(snapshot.counts, ["root": 1]); phase = 2; inserted.fulfill()
                case 2:
                    XCTAssertTrue(snapshot.counts.isEmpty); phase = 3; deleted.fulfill()
                default: XCTFail("Unexpected count mutation")
                }
            })
        await fulfillment(of: [seeded], timeout: 3)
        let reply = try row("reply", parent: "root")
        try store.upsert(messages: [reply, reply])
        await fulfillment(of: [inserted], timeout: 3)
        try store.upsert(messages: [reply.deletedCopy()])
        await fulfillment(of: [deleted], timeout: 3)
        observation.cancel()
    }

    func testRefusalSurvivesReopenAndOrdinaryRefreshAndIsAccountScoped() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let root = try row("root"), reply = try row("reply", parent: "root")
        do {
            let store = try PulseStore(path: url.path)
            try store.upsert(messages: [root, reply])
            try store.denyThread(rootId: "root")
        }
        let reopened = try PulseStore(path: url.path)
        try reopened.upsert(messages: [root, reply])
        XCTAssertTrue(try reopened.threadAccessDenied(rootId: "root"))
        XCTAssertTrue(try reopened.messages(threadRootId: "root").isEmpty)
        XCTAssertEqual(try reopened.messages(conversationId: "room").map(\.id), ["root"])
        XCTAssertTrue(try reopened.threadReplyCountSnapshot(conversationId: "room").counts.isEmpty)
        XCTAssertNotNil(try reopened.message(id: "reply")) // hidden, not destructively erased
        let other = try PulseStore()
        XCTAssertFalse(try other.threadAccessDenied(rootId: "root"))
        try other.upsert(messages: [root, reply])
        XCTAssertEqual(try other.messages(threadRootId: "root").map(\.id), ["reply"])
        _ = try reopened.acceptThreadSnapshot(rootId: "root", roomId: "room", messages: [root, reply])
        XCTAssertFalse(try reopened.threadAccessDenied(rootId: "root"))
        XCTAssertEqual(try reopened.messages(threadRootId: "root").map(\.id), ["reply"])
    }

    func testInvalidRefreshCannotClearRefusalOrResurrectDeletedRoot() throws {
        let store = try PulseStore(), root = try row("root")
        try store.upsert(messages: [root.deletedCopy()])
        try store.denyThread(rootId: "root")
        XCTAssertThrowsError(try store.acceptThreadSnapshot(rootId: "root", roomId: "room", messages: [root, row("reply", parent: "root")]))
        XCTAssertTrue(try store.threadAccessDenied(rootId: "root"))
        XCTAssertNil(try store.message(id: "reply")) // rejected snapshot rolls back together
        XCTAssertNotNil(try store.message(id: "root")?.deletedAt)
    }

    func testDeniedEnqueueAndGenericRetryPreserveDraftAndOriginalFailedReply() throws {
        let store = try PulseStore(), root = try row("root")
        try store.upsert(messages: [root])
        let envelope = PulseOutboxForward(kind: "text", parentId: "root")
        let echo = TempMessages.make(conversationId: "room", viewer: PulseViewer(id: "me", name: "Me"),
            clientId: "failed", content: "original reply", parentId: "root")
        try store.enqueueMessage(echo, clientId: "failed", envelope: envelope)
        try store.rejectOutbox(clientId: "failed", reason: "forbidden")
        let draftKey = PulseThreadDraft.key("room", parentId: "root")
        try store.saveDraft(conversationId: draftKey, text: "keep typing")
        let token = store.captureDraftConsumption(conversationId: "room", parentId: "root")
        try store.denyThread(rootId: "root")
        XCTAssertFalse(try store.retryOutbox(clientId: "failed", message: echo)) // generic settings retry too
        let next = TempMessages.make(conversationId: "room", viewer: PulseViewer(id: "me", name: "Me"),
            clientId: "next", content: "keep typing", parentId: "root")
        XCTAssertThrowsError(try store.enqueueMessage(next, clientId: "next", envelope: envelope, consumingDraft: token))
        XCTAssertEqual(store.draft(conversationId: draftKey), "keep typing")
        XCTAssertEqual(try store.outboxAll().map(\.clientId), ["failed"])
        XCTAssertNotNil(try store.outboxAll().first?.failureReason)
        XCTAssertNil(try store.message(id: "local_next"))
        _ = try store.acceptThreadSnapshot(rootId: "root", roomId: "room", messages: [root])
        XCTAssertTrue(try store.retryOutbox(clientId: "failed", message: echo))
        XCTAssertEqual(store.draft(conversationId: draftKey), "keep typing")
    }

    func testOfflineForwardPagesReturnToNewerHistoryWithStableRawCursors() throws {
        let store = try PulseStore()
        let replies = try (0..<450).map { try row(String(format: "r%03d", $0), parent: "root") }
        try store.upsert(messages: [row("root")] + replies)
        let first = try store.cachedThreadPage(roomId: "room", rootId: "root", limit: 100)
        let older = try store.cachedThreadPage(roomId: "room", rootId: "root", before: first.cursor, limit: 100)
        let back = try store.cachedThreadPage(roomId: "room", rootId: "root", after: older.newerCursor, limit: 100)
        XCTAssertEqual(back.messages.map(\.id), first.messages.map(\.id))
        XCTAssertEqual(back.cursor, first.cursor)
        XCTAssertEqual(back.newerCursor, first.newerCursor)
        XCTAssertFalse(back.hasMore)
        XCTAssertThrowsError(try store.cachedThreadPage(roomId: "other", rootId: "root", after: older.newerCursor))
        XCTAssertThrowsError(try store.cachedThreadPage(roomId: "room", rootId: "root", before: first.cursor, after: older.newerCursor))
    }

    func testOfflineThreadPagesCross300RowsWithTiesAndDeletedCursorWithoutGaps() throws {
        let store = try PulseStore()
        let replies = try (0..<450).map { try row(String(format: "r%03d", $0), parent: "root") }
        try store.upsert(messages: [row("root")] + replies)
        var page = try store.cachedThreadPage(roomId: "room", rootId: "root", limit: 100)
        XCTAssertEqual(page.messages.count, 100)
        XCTAssertTrue(page.hasMore)
        var ids = page.messages.map(\.id)
        let cursor = try XCTUnwrap(page.cursor)
        try store.deleteMessage(id: cursor.id) // paging must not look up the removed anchor
        try store.upsert(messages: [row("z-new-arrival", parent: "root")])
        while page.hasMore {
            page = try store.cachedThreadPage(roomId: "room", rootId: "root", before: page.cursor, limit: 100)
            ids += page.messages.map(\.id)
        }
        XCTAssertEqual(ids.count, 450)
        XCTAssertEqual(Set(ids), Set(replies.map(\.id)))
        XCTAssertFalse(ids.contains("z-new-arrival")) // belongs to the latest window, not earlier pages
        XCTAssertEqual(try store.cachedThreadPage(roomId: "room", rootId: "root", before: page.cursor).messages.count, 0)
    }

    func testOfflineCursorIncludesPendingIdsButExcludesFailedEchoesAndForeignRows() throws {
        let store = try PulseStore()
        let root = try row("root"), pending = try row("local_pending", parent: "root")
        let failed = try row("local_failed", parent: "root")
        try store.upsert(messages: [root, pending, row("foreign", room: "other", parent: "root"), row("reply", parent: "root").deletedCopy()])
        try store.appendOutbox(conversationId: "room", clientId: "failed", content: "saved failure")
        try store.rejectOutbox(clientId: "failed", reason: "rejected")
        try store.upsert(messages: [failed]) // legacy cached failed echo
        let first = try store.cachedThreadPage(roomId: "room", rootId: "root", limit: 1)
        let second = try store.cachedThreadPage(roomId: "room", rootId: "root", before: first.cursor, limit: 1)
        XCTAssertEqual(first.messages.map(\.id), ["reply"])
        XCTAssertNotNil(first.messages.first?.deletedAt)
        XCTAssertEqual(second.messages.map(\.id), ["local_pending"])
        XCTAssertEqual(second.cursor?.id, "local_pending")
        XCTAssertFalse(second.hasMore)
        XCTAssertEqual(try store.countOutbox(), 1) // recovery intent is not deleted to repair history
        XCTAssertThrowsError(try store.cachedThreadPage(roomId: "other", rootId: "root", before: first.cursor))
        XCTAssertThrowsError(try store.cachedThreadPage(roomId: "room", rootId: "other", before: first.cursor))
    }

    func testEarlierCachePagesHonorRefusalAndRootLifetimeAtEachRead() throws {
        let store = try PulseStore(), now = Date(timeIntervalSince1970: 1000)
        try store.upsert(messages: [row("root", expires: now.addingTimeInterval(1)),
                                   row("a", parent: "root"), row("b", parent: "root")])
        let first = try store.cachedThreadPage(roomId: "room", rootId: "root", limit: 1, now: now)
        XCTAssertTrue(first.hasMore)
        XCTAssertTrue(try store.cachedThreadPage(roomId: "room", rootId: "root", before: first.cursor,
                                                 now: now.addingTimeInterval(1)).messages.isEmpty)
        try store.denyThread(rootId: "root")
        let refused = try store.cachedThreadPage(roomId: "room", rootId: "root", before: first.cursor, now: now)
        XCTAssertTrue(refused.messages.isEmpty)
        XCTAssertFalse(refused.hasMore)
        XCTAssertNil(refused.cursor)
        XCTAssertNotNil(try store.message(id: "a", now: now))
    }

    func testOfflineJumpReadsExactLiveRootAndReplyAndCannotBypassDenial() throws {
        let store = try PulseStore(), now = Date(timeIntervalSince1970: 1000)
        try store.upsert(messages: [row("root", expires: now.addingTimeInterval(1)),
                                   row("reply", parent: "root"), row("foreign", room: "other", parent: "root")])
        XCTAssertEqual(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "reply", now: now)?.reply.id, "reply")
        XCTAssertNil(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "foreign", now: now))
        XCTAssertNil(try store.cachedThreadTarget(roomId: "other", rootId: "root", targetId: "reply", now: now))
        XCTAssertNil(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "reply", now: now.addingTimeInterval(1)))
        try store.denyThread(rootId: "root")
        XCTAssertNil(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "reply", now: now))
    }

    func testOfflineJumpDoesNotNavigateDeletedOrFailedLocalReplies() throws {
        let store = try PulseStore()
        try store.upsert(messages: [row("root"), row("deleted", parent: "root").deletedCopy()])
        try store.appendOutbox(conversationId: "room", clientId: "failed", content: "saved")
        try store.rejectOutbox(clientId: "failed", reason: "refused")
        try store.upsert(messages: [row("local_failed", parent: "root")])
        XCTAssertNil(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "deleted"))
        XCTAssertNil(try store.cachedThreadTarget(roomId: "room", rootId: "root", targetId: "local_failed"))
    }
}
