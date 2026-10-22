import XCTest
@testable import Pulse

@MainActor
final class MessageMergeTests: XCTestCase {
    private func row(_ values: [String: Any] = [:]) throws -> WireChatMessage {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(
            OutboxFixtures.makeMessage(id: "m", conversationId: "room", content: "private caption"))) as? [String: Any])
        object["imagePath"] = "secret.jpg"
        object["payload"] = "secret"
        object["pinnedAt"] = "2026-10-21T00:00:00Z"
        object["expiresAt"] = "2099-01-01T00:00:00Z"
        values.forEach { object[$0.key] = $0.value }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
    }

    func testTombstoneNeverResurrectsInRiverOrThreadAndRedactsSensitiveFields() throws {
        let live = try row()
        let deleted = live.deletedCopy()
        let river = PulseTimelineMerge.merge([deleted], incoming: [live], topicId: nil)
        let thread = PulseTextDelivery.timeline([deleted], pending: [live])
        for result in [deleted, try XCTUnwrap(river.first), try XCTUnwrap(thread.first)] {
            XCTAssertNotNil(result.deletedAt)
            XCTAssertEqual(result.content, "")
            XCTAssertNil(result.imagePath)
            XCTAssertNil(result.payload)
            XCTAssertNil(result.pinnedAt)
            XCTAssertEqual(result.expiresAt, live.expiresAt)
        }
    }

    func testConsumptionAndNewerEditSurviveOlderEnvelopeWithoutFreezingOtherFields() throws {
        let current = try row(["content": "new text", "editedAt": "2026-10-21T00:02:00Z",
            "viewOnce": true, "viewedAt": "2026-10-21T00:01:00Z", "viewedBy": "first"])
        let incoming = try row(["content": "old text", "editedAt": "2026-10-21T00:00:00Z",
            "pinnedAt": "2026-10-21T00:03:00Z"])
        let merged = PulseMessageMerge.merge(current, incoming: incoming)
        XCTAssertEqual(merged.viewedAt, current.viewedAt)
        XCTAssertEqual(merged.viewedBy, "first")
        XCTAssertEqual(merged.viewOnce, true)
        XCTAssertEqual(merged.content, "new text")
        XCTAssertEqual(merged.editedAt, current.editedAt)
        XCTAssertEqual(merged.pinnedAt, incoming.pinnedAt)
    }

    func testStoreRejectsLateResurrectionAndDuplicateBatchOrdering() throws {
        let store = try PulseStore()
        let live = try row()
        try store.upsert(messages: [live, live.deletedCopy(), live])
        let stored = try XCTUnwrap(store.message(id: live.id))
        XCTAssertNotNil(stored.deletedAt)
        XCTAssertEqual(stored.content, "")
        XCTAssertNil(stored.imagePath)
        let committed = try store.mergeMessages([live])
        XCTAssertNotNil(committed.first?.deletedAt)
        XCTAssertNotNil(try store.message(id: live.id)?.deletedAt)
        let other = try PulseStore()
        try other.upsert(messages: [live])
        XCTAssertNil(try other.message(id: live.id)?.deletedAt) // no cross-account terminal ledger
    }

    func testStoreKeepsConsumedStampAcrossUnopenedSnapshot() throws {
        let store = try PulseStore()
        let consumed = try row(["viewOnce": true, "viewedAt": "2026-10-21T00:01:00Z", "viewedBy": "first"])
        try store.upsert(messages: [consumed, row(["viewOnce": true])])
        XCTAssertEqual(try store.message(id: "m")?.viewedAt, consumed.viewedAt)
        XCTAssertEqual(try store.message(id: "m")?.viewedBy, "first")
    }
    func testClosedPollKeepsFrozenTallyAcrossLateOpenRowsInMemoryAndStorage() throws {
        let closed = try row(["kind": "poll", "poll": ["id": "poll", "question": "Question", "closed": true, "totalVotes": 2]])
        let open = try row(["kind": "poll", "content": "new unrelated text", "poll": ["id": "poll", "question": "Question", "closed": false, "totalVotes": 1]])
        let merged = PulseMessageMerge.merge(closed, incoming: open)
        XCTAssertEqual(merged.poll, closed.poll)
        XCTAssertEqual(merged.content, open.content)
        let store = try PulseStore()
        try store.upsert(messages: [closed, open])
        XCTAssertEqual(try store.message(id: closed.id)?.poll, closed.poll)
        try store.upsert(messages: [open.deletedCopy()])
        XCTAssertNil(try store.message(id: closed.id)?.poll)
    }

}
