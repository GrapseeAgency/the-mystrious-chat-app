import XCTest
import GRDB
@testable import Pulse

final class MessageExpiryDecodingTests: XCTestCase {
    private func row(_ id: String, expiry: Any = NSNull(), parent: Any = NSNull()) throws -> WireChatMessage {
        let json: [String: Any] = ["id":id,"conversationId":"room","senderId":"peer","kind":"text","content":"private",
            "createdAt":"2026-10-24T00:00:00Z","expiresAt":expiry,"parentId":parent]
        return try JSONDecoder().decode(WireChatMessage.self,from:JSONSerialization.data(withJSONObject:json))
    }
    func testOnlyAbsentExpiryIsPermanentInUIAndSQL() throws {
        let now = Date(timeIntervalSince1970:1000)
        XCTAssertTrue(PulseRemediationLogic.isAlive(nil,now:now))
        for raw in ["", "bad-date", "1970-01-01T00:00:00Z", "1969-12-31T23:59:59Z"] {
            XCTAssertFalse(PulseRemediationLogic.isAlive(raw,now:now))
        }
        let store = try PulseStore()
        try store.upsert(messages:[row("permanent"),row("invalid",expiry:"bad"),row("zero",expiry:"1970-01-01T00:00:00Z"),row("negative",expiry:"1969-12-31T23:59:59Z")])
        XCTAssertEqual(try store.messages(conversationId:"room",now:now).map(\.id),["permanent"])
        XCTAssertNil(try store.message(id:"invalid",now:now))
        try store.upsert(messages:[row("child",expiry:"bad",parent:"permanent")])
        XCTAssertEqual(try store.threadReplyCounts(now:now)["permanent"] ?? 0,0)
    }
    func testMalformedSummaryExpiryDoesNotExposePreviewOrKeepItsUnreadBucketForever() throws {
        let data = try JSONSerialization.data(withJSONObject:["id":"room","isGroup":true,"members":[],"unreadCount":3,
            "unreadExpirations":[["expiresAt":"bad","count":2]],"myManualUnread":true])
        var summary = try JSONDecoder().decode(WireConversationSummary.self,from:data)
        summary.lastMessage = try row("message",expiry:"")
        let projected = summary.removingExpiredSummary()
        XCTAssertNil(projected.lastMessage); XCTAssertEqual(projected.unreadCount,1)
        XCTAssertTrue(projected.manualUnread)
        XCTAssertEqual(projected,projected.removingExpiredSummary())
    }
    func testUpgradeRepairsOldNullExpiryColumnsWithoutErasingDraftOrPermanentRows() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let path = directory.appendingPathComponent("expiry.sqlite").path
        do {
            let store = try PulseStore(path:path)
            try store.upsert(messages:[row("invalid",expiry:"bad"),row("permanent")])
            try store.saveDraft(conversationId:"room",text:"unfinished")
        }
        do {
            let raw = try DatabaseQueue(path:path)
            try raw.write { db in
                try db.execute(sql:"DELETE FROM grdb_migrations WHERE identifier = 'v13'")
                try db.execute(sql:"UPDATE message SET expiresAtEpoch = NULL WHERE id = 'invalid'")
            }
        }
        let reopened = try PulseStore(path:path)
        XCTAssertNil(try reopened.message(id:"invalid"))
        XCTAssertNotNil(try reopened.message(id:"permanent"))
        XCTAssertEqual(reopened.draft(conversationId:"room"),"unfinished")
    }
}
