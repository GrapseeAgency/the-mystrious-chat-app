import XCTest
@testable import Pulse

/// Wave 1 data-layer unit tests (W1-DATA-B) — pure logic only, no network
/// and no wall clock: the timeline query builder (limit/before/q URL shape),
/// the reactionsJson cache codec round-trip, and the Wave 1 envelope decode
/// shapes. Store-side v3 persistence lives in PulseStoreMigrationTests.
final class PulseDataLayerTests: XCTestCase {
    // ── timeline query building (GET …/messages?limit=&before=&q=) ──

    func testMessagesPathNewestPage() {
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 200, before: nil, query: nil),
            "/api/conversations/c1/messages?limit=200",
        )
    }

    func testMessagesPathBeforeCursor() {
        // ISO cursors pass through untouched (":" and "." are legal in a
        // query — same shape the web client emits).
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 40, before: "2026-09-07T12:26:36.991Z", query: nil),
            "/api/conversations/c1/messages?limit=40&before=2026-09-07T12:26:36.991Z",
        )
    }

    func testMessagesPathSearchEncodesReservedCharacters() {
        // "&", "+" and spaces must never leak into the raw query — a search
        // for "budget & q2 2026" is ONE param, not four.
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 100, before: nil, query: "budget & q2 2026"),
            "/api/conversations/c1/messages?limit=100&q=budget%20%26%20q2%202026",
        )
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 100, before: nil, query: "c++"),
            "/api/conversations/c1/messages?limit=100&q=c%2B%2B",
        )
    }

    func testMessagesPathBlankQueryIsOmitted() {
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 100, before: nil, query: "   "),
            "/api/conversations/c1/messages?limit=100",
        )
    }

    func testMessagesPathCombinesBeforeAndQuery() {
        XCTAssertEqual(
            PulseAPIClient.messagesPath(conversationId: "c1", limit: 100, before: "2026-09-07T12:26:36.991Z", query: "pdf"),
            "/api/conversations/c1/messages?limit=100&before=2026-09-07T12:26:36.991Z&q=pdf",
        )
    }

    // ── reactionsJson codec (v3 cache column ⇄ grouped wire shape) ──

    func testReactionsJsonRoundTripPreservesGroupsAndOrder() {
        let groups = [
            WireReactionGroup(emoji: "👍", userIds: ["u1", "u2"], count: 2),
            WireReactionGroup(emoji: "❤️", userIds: ["u9"], count: 1),
        ]
        let json = PulseStore.reactionsJsonData(groups)
        XCTAssertTrue(json.contains("\"emoji\""))
        XCTAssertEqual(PulseStore.reactions(fromJson: json), groups)
    }

    func testReactionsJsonNilAndEmptyEncodeToColumnDefault() {
        XCTAssertEqual(PulseStore.reactionsJsonData(nil), "[]")
        XCTAssertEqual(PulseStore.reactionsJsonData([]), "[]")
        XCTAssertEqual(PulseStore.reactions(fromJson: "[]"), [])
        XCTAssertEqual(PulseStore.reactions(fromJson: nil), [])
        // Corrupt rows must degrade to empty, never throw.
        XCTAssertEqual(PulseStore.reactions(fromJson: "not json"), [])
        XCTAssertEqual(PulseStore.reactions(fromJson: "{\"emoji\":\"👍\"}"), [])
    }

    // ── Wave 1 envelope shapes (tolerant decode) ────────────

    func testThreadPageDecodesParentAndReplies() throws {
        let json = """
        {"parent":{"id":"m-root","conversationId":"c1","senderId":"a1",
                   "content":"root","kind":"text","createdAt":"2026-09-07T12:00:00.000Z"},
         "replies":[{"id":"m-r1","conversationId":"c1","senderId":"a2",
                     "content":"one","kind":"text","createdAt":"2026-09-07T12:05:00.000Z",
                     "parentId":"m-root","unknownKey":true}]}
        """
        let page = try JSONDecoder().decode(WireThreadPage.self, from: Data(json.utf8))
        XCTAssertEqual(page.parent.content, "root")
        XCTAssertEqual(page.replies.count, 1)
        XCTAssertEqual(page.replies.first?.parentId, "m-root")
    }

    func testSavedToggleAndUploadResultDecode() throws {
        let saved = try JSONDecoder().decode(WireSavedToggle.self, from: Data(#"{"saved":true}"#.utf8))
        XCTAssertTrue(saved.saved)
        let upload = try JSONDecoder().decode(
            WireUploadResult.self,
            from: Data(#"{"filePath":"uploads/x.bin","imagePath":null}"#.utf8),
        )
        XCTAssertEqual(upload.filePath, "uploads/x.bin")
        XCTAssertNil(upload.imagePath)
    }
}
