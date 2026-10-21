import XCTest
@testable import Pulse

final class ConversationNavigationTests: XCTestCase {
    private func row(_ id: String, members: [String], group: Bool = false) throws -> WireConversationSummary {
        let json: [String: Any] = ["id": id, "name": "Same display name", "isGroup": group,
            "members": members.map { ["id": $0, "name": "Same display name"] }]
        return try JSONDecoder().decode(WireConversationSummary.self, from: JSONSerialization.data(withJSONObject: json))
    }
    func testNameCollisionsGroupsAndExtraMembersCannotSelectAnotherConversation() throws {
        let rows = try [row("wrong-person", members: ["me", "other"]),
            row("group", members: ["me", "peer"], group: true),
            row("extra-member", members: ["me", "peer", "other"]),
            row("correct", members: ["peer", "me"])]
        XCTAssertEqual(PulseConversationNavigation.directConversation(in: rows, viewerId: "me", peerId: "peer")?.id, "correct")
        XCTAssertNil(PulseConversationNavigation.directConversation(in: rows, viewerId: "me", peerId: "missing"))
        XCTAssertNil(PulseConversationNavigation.directConversation(in: rows, viewerId: "me", peerId: "me"))
        XCTAssertNil(PulseConversationNavigation.directConversation(in: rows, viewerId: "new-account", peerId: "peer"))
    }
}
