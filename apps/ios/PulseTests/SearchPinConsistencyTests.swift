import XCTest
@testable import Pulse

@MainActor
final class SearchPinConsistencyTests: XCTestCase {
    private func hit(_ fields: [String: Any] = [:]) throws -> WireSearchMessage {
        var json: [String: Any] = ["id": "hit", "conversationId": "room", "content": "autumn"]
        json.merge(fields) { _, new in new }
        return try JSONDecoder().decode(WireSearchMessage.self, from: JSONSerialization.data(withJSONObject: json))
    }

    func testSearchMetadataSurvivesDecodingAndHidesUnsafePreviews() throws {
        let now = Date(timeIntervalSince1970: 1000)
        XCTAssertTrue(try hit().isVisibleSearchHit(now: now))
        for fields: [String: Any] in [["expiresAt": "1970-01-01T00:16:40Z"], ["expiresAt": "bad"],
                                      ["viewOnce": true], ["deletedAt": "1970-01-01T00:00:00Z"]] {
            XCTAssertFalse(try hit(fields).isVisibleSearchHit(now: now))
        }
        XCTAssertEqual(try hit(["anon": true, "anonAlias": "Autumn Fox"]).searchSenderName, "Autumn Fox")
    }

    func testSearchQueryCannotInjectAnotherQueryParameter() throws {
        let text = "a&userId=other + #? বাংলা"
        let path = PulseAPIClient.searchPath(userId: "owner&x", query: text)
        let items = try XCTUnwrap(URLComponents(string: "https://example.invalid" + path)?.queryItems)
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items.first?.value, "owner&x")
        XCTAssertEqual(items.last?.value, text)
        XCTAssertTrue(path.contains("%2B"))
    }

    func testExplicitPinRemovalUpdatesBothCachedPayloadAndIndexedPin() throws {
        let store = try PulseStore()
        let json = """
        {"id":"pin","conversationId":"room","senderId":"peer","content":"keep this edit","kind":"text","createdAt":"2026-10-21T00:00:00Z","pinnedAt":"2026-10-21T00:00:00Z"}
        """
        let message = try JSONDecoder().decode(WireChatMessage.self, from: Data(json.utf8))
        try store.upsert(messages: [message])
        try store.clearMessagePin(id: "pin")
        try store.clearMessagePin(id: "pin")
        XCTAssertNil(try store.message(id: "pin")?.pinnedAt)
        XCTAssertNil(try store.messages(conversationId: "room").first?.pinnedAt)
        XCTAssertEqual(try store.message(id: "pin")?.content, "keep this edit")
    }
}
