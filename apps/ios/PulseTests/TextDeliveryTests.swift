import XCTest
@testable import Pulse

@MainActor
final class TextDeliveryTests: XCTestCase {
    private let viewer = PulseViewer(id: "sender", name: "Sender")

    private func message(_ id: String, time: String = "2026-10-20T12:00:00.000Z", parent: String? = nil) throws -> WireChatMessage {
        var body: [String: Any] = ["id": id, "conversationId": "room", "senderId": "sender", "kind": "text", "content": id, "createdAt": time]
        if let parent { body["parentId"] = parent }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: body))
    }

    func testEffectReplyTopicAndMaskSurviveReopeningBeforeDelivery() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("messages.sqlite").path
        var store: PulseStore? = try PulseStore(path: path)
        let queued = try PulseTextDelivery.enqueue(store: XCTUnwrap(store), viewer: viewer, conversationId: "room",
            content: "  Still here  ", effect: "sparkles", replyTo: message("quote"), topicId: "topic", anon: true)
        XCTAssertEqual(queued.content, "Still here")
        XCTAssertEqual(queued.topicId, "topic")
        XCTAssertEqual(queued.anon, true)
        XCTAssertNotNil(queued.anonAlias)
        let first = try XCTUnwrap(try store?.outboxPending().first)
        store = nil
        let reopened = try PulseStore(path: path)
        let entry = try XCTUnwrap(try reopened.outboxPending().first)
        XCTAssertEqual(entry.clientId, first.clientId)
        XCTAssertEqual(queued.id, PulseOutboxEngine.tempMessageId(clientId: entry.clientId))
        XCTAssertEqual(try reopened.message(id: queued.id)?.content, "Still here")
        let envelope = try XCTUnwrap(PulseOutboxForward.decode(entry.payloadJson))
        XCTAssertEqual(envelope.replyToId, "quote")
        XCTAssertEqual(envelope.topicId, "topic")
        XCTAssertEqual(envelope.anon, true)
        XCTAssertEqual(PulseRemediationLogic.effectOfPayload(envelope.payload), "sparkles")
        XCTAssertEqual(try PulseTextDelivery.pendingMessages(store: reopened, conversationId: "room").map(\.id), [queued.id])
        XCTAssertTrue(try PulseTextDelivery.pendingMessages(store: reopened, conversationId: "other").isEmpty)
        try reopened.resolveDelivery(clientId: entry.clientId, message: message("confirmed"))
        XCTAssertTrue(try PulseTextDelivery.pendingMessages(store: reopened, conversationId: "room").isEmpty)
        XCTAssertNil(try reopened.message(id: queued.id))
        XCTAssertNotNil(try reopened.message(id: "confirmed"))
    }

    func testEmptyArmedEffectAndUnsupportedEffectNeverEnterOutbox() throws {
        let store = try PulseStore()
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: " \n", effect: "echo"))
        XCTAssertThrowsError(try PulseTextDelivery.enqueue(store: store, viewer: viewer, conversationId: "room", content: "hello", effect: "unknown"))
        XCTAssertEqual(store.countPendingOutbox(), 0)
        XCTAssertTrue(try store.messages(conversationId: "room").isEmpty)
    }

    func testNewestFirstPageIsChronologicalAndKeepsQueuedMessagesWithoutDuplicateIds() throws {
        let older = try message("older", time: "2026-10-20T11:00:00.000Z")
        let newer = try message("newer")
        let pending = try message("local_pending", time: "2026-10-20T12:01:00.000Z")
        XCTAssertEqual(PulseTextDelivery.timeline([newer, older, newer], pending: [pending, pending]).map(\.id), ["older", "newer", "local_pending"])
    }

    func testCachedRoomAndThreadWindowsKeepNewestRowsInReadingOrder() throws {
        let store = try PulseStore()
        try store.upsert(messages: [
            message("first", time: "2026-10-20T10:00:00.000Z", parent: "root"),
            message("second", time: "2026-10-20T11:00:00.000Z", parent: "root"),
            message("third", time: "2026-10-20T12:00:00.000Z", parent: "root"),
        ])
        XCTAssertEqual(try store.messages(conversationId: "room", limit: 2).map(\.id), ["second", "third"])
        XCTAssertEqual(try store.messages(threadRootId: "root", limit: 2).map(\.id), ["second", "third"])
    }
}
