import XCTest
import GRDB
@testable import Pulse

final class QuoteSourcePrivacyTests: XCTestCase {
    private func message(_ id: String, extra: [String: Any] = [:]) throws -> WireChatMessage {
        var json: [String: Any] = ["id":id,"conversationId":"room","senderId":"peer","kind":"text",
            "content":"secret","createdAt":"2090-01-01T00:00:00Z","anon":false,"viewOnce":false,
            "sender":["id":"peer","name":"Real Name"]]
        json.merge(extra) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }
    private func quote(_ id: String, source: String = "source") throws -> WireChatMessage {
        var result = try message(id, extra:["content":"reply"])
        result.replyTo = WireReplySnippet(id:source,content:"newer server quote",senderName:"Quiet Raven",deleted:false,
                                          quoteVersion:1,expiresAt:nil,viewOnce:false)
        return result
    }
    func testKnownPrivacyOnlyTightensQuoteAndNeverReplacesTextWithOlderSource() throws {
        let owner = try quote("reply")
        let source = try message("source")
        let now = try XCTUnwrap(PulseFormat.date("2026-10-24T00:00:00Z"))
        let live = PulseQuotePrivacy.project([owner],known:[source],now:now)[0]
        XCTAssertEqual(live.replyTo?.content,"newer server quote")
        XCTAssertEqual(live.replyTo?.senderName,"Quiet Raven")
        let single = try message("source",extra:["viewOnce":true,"expiresAt":"2090-01-01T00:00:00Z"])
        let hidden = PulseQuotePrivacy.project([owner],known:[single],now:now)[0]
        XCTAssertEqual(hidden.replyTo?.content,"View-once message")
        XCTAssertEqual(hidden.replyTo?.expiresAt,single.expiresAt)
        let dead = PulseQuotePrivacy.project([owner],known:[source.deletedCopy()],now:now)[0]
        XCTAssertEqual(dead.content,"reply")
        XCTAssertEqual(dead.replyTo?.content,"Quoted message unavailable")
        XCTAssertEqual(PulseMessageMerge.merge(dead,incoming:owner).replyTo?.deleted,true)
        let foreign = try message("source",extra:["conversationId":"foreign"])
        XCTAssertEqual(PulseQuotePrivacy.project([owner],known:[foreign],now:now)[0].replyTo?.deleted,true)
        XCTAssertEqual(PulseQuotePrivacy.project([owner],known:[],now:now)[0],owner)
    }
    func testSourceOutsideVisiblePageRevokesQuotesAndDelayedBatchCannotRestoreThem() throws {
        let store = try PulseStore()
        let source = try message("source")
        let owners = try (0...204).map { try quote("quote-\($0)") }
        try store.upsert(messages:[source] + owners)
        try store.upsert(messages:[source.deletedCopy()])
        // No source in this visible window; cache projection still sees its tombstone.
        let projected = try store.projectCachedQuotes(owners)
        XCTAssertTrue(projected.allSatisfy { $0.replyTo?.deleted == true && $0.content == "reply" })
        let late = try store.mergeMessages([owners[0],source])
        XCTAssertEqual(late[0].replyTo?.deleted,true)
        XCTAssertNotNil(late[1].deletedAt)
    }
    func testBatchOrderAndKnownThreadRootPrivacy() throws {
        for sourceFirst in [false,true] {
            let store = try PulseStore()
            let root = try message("root")
            let child = try message("child",extra:["parentId":"root"])
            let owner = try quote("owner",source:"child")
            let batch = sourceFirst ? [root.deletedCopy(),child,owner] : [owner,child,root.deletedCopy()]
            let committed = try store.mergeMessages(batch)
            XCTAssertEqual(committed.first(where: { $0.id == "owner" })?.replyTo?.deleted,true)
            XCTAssertEqual(try store.message(id:"owner")?.replyTo?.deleted,true)
        }
    }
    func testRevocationPersistsAcrossReopenAndUpgradeRepairsPreviouslyLeakedQuotes() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let path = directory.appendingPathComponent("quote-cache.sqlite").path
        let source = try message("source")
        let owner = try quote("owner")
        do {
            let store = try PulseStore(path:path)
            try store.upsert(messages:[owner,source])
            try store.upsert(messages:[source.deletedCopy()])
            try store.saveDraft(conversationId:"room",text:"unfinished")
        }
        do {
            let reopened = try PulseStore(path:path)
            XCTAssertEqual(try reopened.message(id:"owner")?.replyTo?.deleted,true)
        }
        // Reproduce a deployed v11 cache with a known tombstone but stale quote.
        do {
            let db = try DatabaseQueue(path:path)
            try db.write { db in
                try db.execute(sql:"DELETE FROM grdb_migrations WHERE identifier = 'v12'")
                try db.execute(sql:"DROP INDEX message_quote_source")
                try db.execute(sql:"DROP INDEX message_thread_parent")
                try db.execute(sql:"UPDATE message SET wireJson = ? WHERE id = 'owner'",
                    arguments:[String(decoding:try JSONEncoder().encode(owner),as:UTF8.self)])
                // The older column-only tombstone is authoritative even if its envelope lagged.
                try db.execute(sql:"UPDATE message SET wireJson = ? WHERE id = 'source'",
                    arguments:[String(decoding:try JSONEncoder().encode(source),as:UTF8.self)])
            }
        }
        let upgraded = try PulseStore(path:path)
        XCTAssertEqual(try upgraded.message(id:"owner")?.replyTo?.deleted,true)
        XCTAssertEqual(upgraded.draft(conversationId:"room"),"unfinished")
    }
    func testLaterSourceEditsPropagateToCachedNonExpiringQuotesAndStampTheRefresh() throws {
        let owner = try quote("reply")
        let edited = try message("source",extra:["content":"edited body","editedAt":"2026-10-25T00:00:00Z"])
        let now = try XCTUnwrap(PulseFormat.date("2026-10-24T00:00:00Z"))
        let once = PulseQuotePrivacy.project([owner],known:[edited],now:now)[0]
        XCTAssertEqual(once.replyTo?.content,"edited body")
        XCTAssertEqual(once.replyTo?.sourceEditedAt,"2026-10-25T00:00:00Z")
        // Idempotent: the same stamp does not re-refresh; an older stamp never rewinds.
        let again = PulseQuotePrivacy.project([once],known:[edited],now:now)[0]
        XCTAssertEqual(again.replyTo?.content,"edited body")
        let rewind = PulseQuotePrivacy.project([once],known:[try message("source",extra:["content":"rewind","editedAt":"2026-10-23T00:00:00Z"])],now:now)[0]
        XCTAssertEqual(rewind.replyTo?.content,"edited body")
        // A newer edit still lands.
        let newest = PulseQuotePrivacy.project([once],known:[try message("source",extra:["content":"newest","editedAt":"2026-10-26T00:00:00Z"])],now:now)[0]
        XCTAssertEqual(newest.replyTo?.content,"newest")
    }
    func testEditPropagationNeverBypassesViewOnceOrDeletedCaptions() throws {
        let owner = try quote("reply")
        let now = try XCTUnwrap(PulseFormat.date("2026-10-24T00:00:00Z"))
        let single = PulseQuotePrivacy.project([owner],known:[try message("source",extra:["viewOnce":true,"editedAt":"2026-10-25T00:00:00Z"])],now:now)[0]
        XCTAssertEqual(single.replyTo?.content,"View-once message")
        let dead = PulseQuotePrivacy.project([owner],known:[try message("source",extra:["deletedAt":"2026-10-24T00:00:00Z","editedAt":"2026-10-25T00:00:00Z"])],now:now)[0]
        XCTAssertEqual(dead.replyTo?.content,"Quoted message unavailable")
        XCTAssertEqual(dead.replyTo?.deleted,true)
    }
    func testLearnedSourceRootIdSurvivesChildEvictionForRootOnlyRevocation() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let path = directory.appendingPathComponent("quote-provenance.sqlite").path
        let root = try message("root")
        let child = try message("child",extra:["parentId":"root"])
        let owner = try quote("owner",source:"child")
        let store = try PulseStore(path:path)
        try store.mergeMessages([root,child,owner])
        XCTAssertEqual(try store.message(id:"owner")?.replyTo?.sourceRootId,"root")
        // Delete the child row cache entirely (eviction); the learned
        // provenance survives in the quote's stored envelope...
        try DatabaseQueue(path:path).write { db in
            try db.execute(sql:"DELETE FROM message WHERE id = 'child'")
        }
        XCTAssertEqual(try store.message(id:"owner")?.replyTo?.sourceRootId,"root")
        // ...so a root-only tombstone still revokes the quote.
        try store.mergeMessages([root.deletedCopy()])
        XCTAssertEqual(try store.message(id:"owner")?.replyTo?.deleted,true)
    }
}
