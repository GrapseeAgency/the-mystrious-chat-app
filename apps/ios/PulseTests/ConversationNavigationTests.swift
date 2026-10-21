import XCTest
@testable import Pulse

@MainActor
final class ConversationNavigationTests: XCTestCase {
    private func row(_ id: String, members: [String], group: Bool = false, isSelf: Bool = false) throws -> WireConversationSummary {
        let json: [String: Any] = ["id": id, "name": "Same display name", "isGroup": group, "isSelf": isSelf,
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

    func testCreateRejectsWrongPeerWrongKindSharedSelfAndDuplicateMembers() async throws {
        let valid = try row("correct", members: ["peer", "me"])
        let resolved = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["peer"], isGroup: false) { valid }
        XCTAssertEqual(resolved.id, "correct")
        let wrong = try [row("other", members: ["other", "me"]),
            row("group", members: ["peer", "me"], group: true),
            row("self", members: ["peer", "me"], isSelf: true),
            row("duplicate", members: ["peer", "me", "peer"])]
        for response in wrong {
            do {
                _ = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["peer"], isGroup: false) { response }
                XCTFail("Unexpected identity must not navigate")
            } catch let error as PulseAPIClient.Failure {
                XCTAssertEqual(error.kind, .validation)
            }
        }
        let group = try row("selected-group", members: ["me", "a", "b"], group: true)
        let created = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["b", "a"], isGroup: true) { group }
        XCTAssertEqual(created.id, group.id)
    }

    func testLateSuccessfulResponseAfterCancellationCannotOpenChat() async throws {
        let response = try row("late", members: ["me", "peer"])
        let attempt = Task {
            try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["peer"], isGroup: false) {
                withUnsafeCurrentTask { $0?.cancel() }
                return response
            }
        }
        do {
            _ = try await attempt.value
            XCTFail("Canceled navigation must not return a room")
        } catch { XCTAssertTrue(error is CancellationError) }
    }

    func testInvalidSelectionDoesNotDispatchAndRefusalPropagates() async throws {
        var dispatched = false
        do {
            _ = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["me"], isGroup: false) {
                dispatched = true
                return try row("invalid", members: ["me"])
            }
            XCTFail("Self must use the notebook endpoint")
        } catch { XCTAssertFalse(dispatched) }
        do {
            _ = try await PulseConversationNavigation.create(viewerId: "me", memberIds: ["peer"], isGroup: false) {
                throw PulseAPIClient.Failure(kind: .forbidden, message: "Blocked", status: 403)
            }
            XCTFail("A refusal must not navigate")
        } catch let error as PulseAPIClient.Failure { XCTAssertEqual(error.status, 403) }
    }

}
