import XCTest
import GRDB
import Combine
@testable import Pulse

@MainActor
final class SavedLibraryConsistencyTests: XCTestCase {
    private func item(_ id: String, content: String = "original", expires: String? = nil, parent: String? = nil) throws -> WireSavedItem {
        var json: [String: Any] = ["id": id, "conversationId": "room", "senderId": "peer", "kind": "text",
                                   "content": content, "createdAt": "2026-10-20T00:00:00Z"]
        if let expires { json["expiresAt"] = expires }
        if let parent { json["parentId"] = parent }
        let message = try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
        return WireSavedItem(savedAt: "2026-10-20T00:00:00Z", conversation: WireSavedConversation(id: "room", isGroup: true, name: "Room"), message: message)
    }

    func testKnownThreadDenialDeathAndExpiryCannotBeBypassedThroughSaved() throws {
        let store = try PulseStore()
        let reply = try item("reply", parent: "root").message
        let saved = WireSavedItem(savedAt: "2026-10-20T00:00:00Z", conversation: try item("reply").conversation, message: reply)
        try store.upsert(savedItems: [saved])
        XCTAssertEqual(try store.cachedSavedItems().count, 1) // offline absence is not denial
        let root = try item("root", expires: "2099-01-01T00:00:00Z").message
        try store.upsert(messages: [root])
        XCTAssertEqual(try store.cachedSavedItems().first?.message.expiresAt, root.expiresAt)
        XCTAssertNil(try store.message(id: reply.id)?.expiresAt)
        try store.denyThread(rootId: root.id)
        XCTAssertTrue(try store.cachedSavedItems().isEmpty)
        _ = try store.acceptThreadSnapshot(rootId: root.id, roomId: "room", messages: [root, reply])
        try store.upsert(messages: [root.deletedCopy()])
        XCTAssertTrue(try store.cachedSavedItems().isEmpty)
        var once = try item("once").message
        once.viewOnce = true
        try store.upsert(savedItems: [WireSavedItem(savedAt: saved.savedAt, conversation: saved.conversation, message: once)])
        XCTAssertTrue(try store.cachedSavedItems().isEmpty)
    }

    func testDeletedAndExpiredSavedRowsAreNotRenderedOrCounted() throws {
        let store = try PulseStore()
        let saved = try item("deleted")
        try store.upsert(savedItems: [saved, item("expired", expires: "2000-01-01T00:00:00Z"), item("keep")])
        try store.upsert(messages: [saved.message.deletedCopy()])
        XCTAssertEqual(try store.cachedSavedItems().map { $0.message.id }, ["keep"])
        XCTAssertEqual(try store.savedIds(), ["keep"])
    }

    func testLateRefreshPreservesNewerTombstoneAndEdits() throws {
        let store = try PulseStore()
        let stale = try item("row")
        try store.upsert(savedItems: [stale])
        try store.upsert(messages: [stale.message.deletedCopy()])
        try store.upsert(savedItems: [stale], replacingSnapshot: true, preservingMessageIds: ["row"])
        XCTAssertTrue(try store.cachedSavedItems().isEmpty)
        XCTAssertNotNil(try store.message(id: "row")?.deletedAt)
        let edit = try item("edited", content: "fresh edit")
        try store.upsert(messages: [edit.message])
        try store.upsert(savedItems: [item("edited")], replacingSnapshot: true, preservingMessageIds: ["edited"])
        XCTAssertEqual(try store.cachedSavedItems().first?.message.content, "fresh edit")
    }

    func testMessageOnlyEditInvalidatesSavedObservation() async throws {
        let store = try PulseStore()
        try store.upsert(savedItems: [item("row")])
        let edited = expectation(description: "saved join observes message edit")
        let observer = store.observeSavedItems()
            .first(where: { $0.first?.message.content == "edited" })
            .sink(receiveCompletion: { _ in }, receiveValue: { _ in edited.fulfill() })
        try store.upsert(messages: [item("row", content: "edited").message])
        await fulfillment(of: [edited], timeout: 3)
        observer.cancel()
    }

    func testFailedReplacementKeepsOldIndexAndRollsBackMessageWrite() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("saved.sqlite").path
        let store = try PulseStore(path: path)
        try store.upsert(savedItems: [item("old")])
        let connection = try DatabaseQueue(path: path)
        try await connection.write { db in
            try db.execute(sql: "CREATE TRIGGER fail_saved BEFORE INSERT ON savedMessages BEGIN SELECT RAISE(ABORT, 'failed write'); END")
        }
        XCTAssertThrowsError(try store.upsert(savedItems: [item("new")], replacingSnapshot: true))
        XCTAssertEqual(try store.cachedSavedItems().map { $0.message.id }, ["old"])
        XCTAssertNil(try store.message(id: "new"))
        try await connection.write { try $0.execute(sql: "DROP TRIGGER fail_saved") }
        try store.upsert(savedItems: [], replacingSnapshot: true)
        XCTAssertTrue(try store.cachedSavedItems().isEmpty)
        XCTAssertNotNil(try store.message(id: "old"))
    }
}
