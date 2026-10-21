import XCTest
import Combine
@testable import Pulse

@MainActor
final class ThreadReplyCountTests: XCTestCase {
    private func row(_ id: String, room: String = "room", parent: String? = nil, expires: Date? = nil) throws -> WireChatMessage {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(
            OutboxFixtures.makeMessage(id: id, conversationId: room, content: id))) as? [String: Any])
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
        XCTAssertEqual(store.countOutbox(), 1)
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
                    XCTAssertTrue(snapshot.counts.isEmpty()); phase = 1; seeded.fulfill()
                case 1:
                    XCTAssertEqual(snapshot.counts, ["root": 1]); phase = 2; inserted.fulfill()
                case 2:
                    XCTAssertTrue(snapshot.counts.isEmpty()); phase = 3; deleted.fulfill()
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
}
