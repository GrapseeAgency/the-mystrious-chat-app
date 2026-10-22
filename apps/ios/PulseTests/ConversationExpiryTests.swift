import XCTest
import GRDB
@testable import Pulse

@MainActor
final class ConversationExpiryTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 2_000_000_000)
    private func fixture(deadline: Date) throws -> WireConversationSummary {
        let iso = ISO8601DateFormatter().string(from: deadline)
        let body: [String: Any] = [
            "id": "room", "isGroup": true, "name": "Group", "members": [], "unreadCount": 4,
            "myManualUnread": true, "myDraft": "unfinished", "screenPrivacy": true,
            "slowModeSeconds": 20, "dmBlocked": true,
            "unreadExpirations": [["expiresAt": iso, "count": 2]],
            "lastMessage": ["id": "last", "conversationId": "room", "senderId": "peer", "kind": "file",
                "content": "private preview", "createdAt": iso, "expiresAt": iso, "fileName": "private.pdf"],
        ]
        return try JSONDecoder().decode(WireConversationSummary.self, from: JSONSerialization.data(withJSONObject: body))
    }

    func testDeadlineProjectionIsIdempotentAndDoesNotAcknowledgeUnrelatedUnread() throws {
        let raw = try fixture(deadline: base)
        XCTAssertNotNil(raw.removingExpiredSummary(at: base.addingTimeInterval(-1)).lastMessage)
        let expired = raw.removingExpiredSummary(at: base)
        XCTAssertNil(expired.lastMessage)
        XCTAssertEqual(expired.unreadCount, 2)
        XCTAssertEqual(expired.myDraft, "unfinished")
        XCTAssertTrue(expired.manualUnread)
        XCTAssertEqual(expired, expired.removingExpiredSummary(at: base.addingTimeInterval(100)))
        var oldServer = raw
        oldServer.unreadExpirations = nil
        XCTAssertEqual(oldServer.removingExpiredSummary(at: base).unreadCount, 4)
    }

    func testRootBoundedReplyBucketsExpireWithoutThreadHistory() throws {
        var snapshot = try fixture(deadline: base)
        snapshot.unreadCount = 7
        snapshot.unreadExpirations = [
            WireUnreadExpiration(expiresAt: ISO8601DateFormatter().string(from: base.addingTimeInterval(-10)), count: 1),
            WireUnreadExpiration(expiresAt: ISO8601DateFormatter().string(from: base), count: 4),
            WireUnreadExpiration(expiresAt: ISO8601DateFormatter().string(from: base.addingTimeInterval(10)), count: 1),
        ]
        let early = snapshot.removingExpiredSummary(at: base.addingTimeInterval(-10))
        XCTAssertEqual(early.unreadCount, 6)
        XCTAssertNotNil(early.lastMessage)
        let rootExpired = early.removingExpiredSummary(at: base)
        XCTAssertEqual(rootExpired.unreadCount, 2)
        XCTAssertNil(rootExpired.lastMessage)
        XCTAssertTrue(rootExpired.manualUnread)
        XCTAssertEqual(rootExpired.myDraft, "unfinished")
        let later = rootExpired.removingExpiredSummary(at: base.addingTimeInterval(10))
        XCTAssertEqual(later.unreadCount, 1)
        XCTAssertEqual(later.unreadExpirations, [])
    }

    func testOptimisticMetadataCopiesPreserveExpiryAndPrivacy() throws {
        let raw = try fixture(deadline: base)
        let updated = raw.withPinnedAt("pin").withArchived("archive").withMutedUntil("mute").withManualUnread(false)
        XCTAssertEqual(updated.unreadExpirations, raw.unreadExpirations)
        XCTAssertEqual(updated.screenPrivacy, true)
        XCTAssertEqual(updated.slowModeSeconds, 20)
        XCTAssertEqual(updated.dmBlocked, true)
        XCTAssertNil(updated.removingExpiredSummary(at: base).lastMessage)
        XCTAssertEqual(updated.withUnreadCount(0).removingExpiredSummary(at: base).unreadCount, 0)
    }

    func testReopenedInboxKeepsFullEnvelopeAndExpiresWithoutHistoryOrNetwork() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("chat.sqlite").path
        let raw = try fixture(deadline: Date(timeIntervalSince1970: 100))
        do {
            let store = try PulseStore(path: path)
            try store.upsert(conversations: [raw.toDomain()], replacingSnapshot: true)
            try store.saveDraft(conversationId: "room", text: "keep")
            try store.appendOutbox(conversationId: "room", clientId: "unsent", content: "keep operation")
        }
        let reopened = try PulseStore(path: path)
        let row = try XCTUnwrap(reopened.cachedConversations().first)
        let summary = WireConversationSummary(cached: row)
        XCTAssertNil(summary.lastMessage)
        XCTAssertEqual(summary.unreadCount, 2)
        XCTAssertEqual(summary.myDraft, "unfinished")
        XCTAssertEqual(summary.screenPrivacy, true)
        XCTAssertTrue(try reopened.messages(conversationId: "room").isEmpty)
        try reopened.upsert(conversations: [], replacingSnapshot: true)
        XCTAssertTrue(try reopened.cachedConversations().isEmpty)
        XCTAssertEqual(reopened.draft(conversationId: "room"), "keep")
        XCTAssertEqual(try reopened.countOutbox(), 1)
    }

    func testLegacyCacheNeverFabricatesAPermanentMessageFromAnUnversionedSnippet() {
        let old = PulseConversation(id: "room", kind: .DM, title: "Peer", lastMessagePreview: "unknown expiry", unreadCount: 9)
        let restored = WireConversationSummary(cached: old)
        XCTAssertEqual(restored.name, "Peer")
        XCTAssertNil(restored.lastMessage)
        XCTAssertEqual(restored.unreadCount, 0)
    }

    func testV11MigrationRetiresOnlyUnverifiableDerivedData() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("chat.sqlite").path
        do {
            let store = try PulseStore(path: path)
            try store.upsert(conversations: [PulseConversation(id: "room", kind: .DM, title: "Peer", lastMessagePreview: "old snippet", unreadCount: 4)])
            try store.saveDraft(conversationId: "room", text: "keep")
            try store.appendOutbox(conversationId: "room", clientId: "pending", content: "keep operation")
        }
        do {
            let connection = try DatabaseQueue(path: path)
            try connection.write { db in
                try db.execute(sql: "ALTER TABLE conversation DROP COLUMN wireJson")
                try db.execute(sql: "DELETE FROM grdb_migrations WHERE identifier = 'v11'")
            }
        }
        let reopened = try PulseStore(path: path)
        let row = try XCTUnwrap(reopened.cachedConversations().first)
        XCTAssertNil(row.lastMessagePreview)
        XCTAssertEqual(row.unreadCount, 0)
        XCTAssertEqual(row.title, "Peer")
        XCTAssertEqual(reopened.draft(conversationId: "room"), "keep")
        XCTAssertEqual(try reopened.countOutbox(), 1)
    }
}
