import XCTest
@testable import Pulse

final class ReplySnippetTests: XCTestCase {
    private func decode(_ metadata: String) throws -> WireReplySnippet {
        try JSONDecoder().decode(WireReplySnippet.self, from: Data("""
        {"id":"source","content":"secret","senderName":"Quiet Raven",\(metadata)}
        """.utf8))
    }
    func testLegacyAndIncompleteQuotesHideNameAndBodyWithoutLosingSourceID() throws {
        for metadata in [#""deleted":false"#, #""deleted":false,"quoteVersion":1,"viewOnce":false"#,
                         #""deleted":false,"quoteVersion":1,"viewOnce":false,"expiresAt":"bad-date""#,
                         #""deleted":false,"quoteVersion":2,"viewOnce":false,"expiresAt":null"#] {
            let quote = try decode(metadata)
            XCTAssertEqual(quote.id, "source")
            XCTAssertEqual(quote.senderName, "Reply")
            XCTAssertEqual(quote.content, "Quoted message unavailable")
            let stored = String(decoding: try JSONEncoder().encode(quote), as: UTF8.self)
            XCTAssertFalse(stored.contains("secret")); XCTAssertFalse(stored.contains("Quiet Raven"))
        }
    }
    func testPermanentWitnessAndViewOnceGateSurviveRoundTrip() throws {
        let quote = try decode(#""deleted":false,"quoteVersion":1,"viewOnce":false,"expiresAt":null"#)
        let restored = try JSONDecoder().decode(WireReplySnippet.self, from: JSONEncoder().encode(quote))
        XCTAssertEqual(restored.content, "secret"); XCTAssertEqual(restored.senderName, "Quiet Raven")
        let single = try decode(#""deleted":false,"quoteVersion":1,"viewOnce":true,"expiresAt":null"#)
        XCTAssertEqual(single.content, "View-once message")
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(single), as: UTF8.self).contains("secret"))
    }
    func testQuoteExpiryAndLateSnapshotCannotExtendTheOriginalDeadline() throws {
        let quote = try decode(#""deleted":false,"quoteVersion":1,"viewOnce":false,"expiresAt":"2090-01-01T00:00:00Z""#)
        let expiry = try XCTUnwrap(PulseFormat.date("2090-01-01T00:00:00Z"))
        XCTAssertEqual(quote.presentation(at: expiry.addingTimeInterval(-1)).content, "secret")
        XCTAssertEqual(quote.presentation(at: expiry).content, "Quoted message unavailable")
        let late = try decode(#""deleted":false,"quoteVersion":1,"viewOnce":false,"expiresAt":null"#)
        XCTAssertEqual(late.mergingBounds(from: quote).expiresAt, quote.expiresAt)
        let deleted = WireReplySnippet(id: "source", content: "secret", senderName: "Real Name", deleted: true,
                                      quoteVersion: 1, expiresAt: nil, viewOnce: false)
        XCTAssertEqual(late.mergingBounds(from: deleted).senderName, "Unavailable message")
    }
    @MainActor func testOptimisticAnonymousQuoteAndPrivacyBoundsSurviveStoreReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("quotes.sqlite").path
        let source = try JSONDecoder().decode(WireChatMessage.self, from: Data(#"{"id":"source","conversationId":"room","senderId":"peer","content":"secret","kind":"text","createdAt":"2026-10-23T00:00:00Z","sender":{"id":"peer","name":"Real Name"},"anon":true,"anonAlias":"Quiet Raven","viewOnce":true,"expiresAt":"2090-01-01T00:00:00Z"}"#.utf8))
        var id = ""
        do {
            let store = try PulseStore(path: path)
            let queued = try PulseTextDelivery.enqueue(store: store, viewer: PulseViewer(id: "me", name: "Me"),
                                                      conversationId: "room", content: "reply", replyTo: source)
            id = queued.id
        }
        let reopened = try PulseStore(path: path)
        let quote = try XCTUnwrap(try reopened.message(id: id)?.replyTo)
        XCTAssertEqual(quote.senderName, "Quiet Raven")
        XCTAssertEqual(quote.content, "View-once message")
        XCTAssertEqual(quote.expiresAt, source.expiresAt)
    }
}
