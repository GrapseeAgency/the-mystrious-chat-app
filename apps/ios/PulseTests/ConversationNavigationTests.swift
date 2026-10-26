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


    func testNotebookOpensReturnedIdentityAndCachedOfflineCopyWithoutInboxRefresh() async throws {
        let notebook = try row("notebook", members: ["me"], isSelf: true)
        let created = try await PulseConversationNavigation.openSelf(viewerId: "me", cached: []) { notebook }
        XCTAssertEqual(created.id, notebook.id)
        let cached = try await PulseConversationNavigation.openSelf(viewerId: "me", cached: [notebook]) {
            XCTFail("An identity-validated offline notebook must not require the network")
            throw URLError(.notConnectedToInternet)
        }
        XCTAssertEqual(cached.id, notebook.id)
        let wrong = try [row("other-owner", members: ["other"], isSelf: true),
            row("shared", members: ["me", "other"], isSelf: true),
            row("not-self", members: ["me"]),
            row("group", members: ["me"], group: true, isSelf: true)]
        for response in wrong {
            XCTAssertNil(PulseConversationNavigation.selfConversation(in: [response], viewerId: "me"))
            do {
                _ = try await PulseConversationNavigation.openSelf(viewerId: "me", cached: []) { response }
                XCTFail("A foreign/shared chat must not open as a private notebook")
            } catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.kind, .validation) }
        }
    }


    func testLinkedRoomUsesExactOfflineIdentityAndDetailFallback() async throws {
        let wrong = try row("same-title", members: ["me", "other"])
        let target = try row("target", members: ["me", "peer"])
        let cached = try await PulseConversationNavigation.conversation(id: "target", cached: [wrong, target]) {
            XCTFail("An exact cached room should not require a second network request")
            throw URLError(.notConnectedToInternet)
        }
        XCTAssertEqual(cached.id, target.id)
        let fetched = try await PulseConversationNavigation.conversation(id: "target", cached: [wrong]) { target }
        XCTAssertEqual(fetched.id, target.id)
        do {
            _ = try await PulseConversationNavigation.conversation(id: "target", cached: []) { wrong }
            XCTFail("A different room must never satisfy a link")
        } catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.kind, .validation) }
    }


    func testNavigationIntentIsSeparateFromRoomVisibilityRestoration() throws {
        let session = PulseSession()
        let initial = session.roomNavigationGeneration
        let visit = UUID()
        session.showRoomReadVisit(conversationId: "existing-room", entryID: visit)
        session.hideRoomReadVisit(conversationId: "existing-room", entryID: visit)
        XCTAssertEqual(session.roomNavigationGeneration, initial)
        session.noteRoomNavigationIntent()
        let next = session.roomNavigationGeneration
        XCTAssertNotEqual(initial, next)
        session.showRoomReadVisit(conversationId: "existing-room", entryID: visit)
        XCTAssertEqual(session.roomNavigationGeneration, next)
        let target = try row("target", members: ["me", "peer"])
        session.requestOpenRoom(target, jumpMessageId: "message")
        XCTAssertNotEqual(session.roomNavigationGeneration, next)
        XCTAssertEqual(session.pendingOpenRoom?.id, target.id)
        XCTAssertEqual(session.pendingJumpMessageId, "message")
    }

    func testCanceledLinkAndNotebookRequestsIgnoreLateSuccess() async throws {
        for notebook in [false, true] {
            let response = try row("target", members: notebook ? ["me"] : ["me", "peer"], isSelf: notebook)
            let attempt = Task {
                let transport: () async throws -> WireConversationSummary = {
                    withUnsafeCurrentTask { $0?.cancel() }
                    return response
                }
                if notebook {
                    return try await PulseConversationNavigation.openSelf(viewerId: "me", cached: [], request: transport)
                }
                return try await PulseConversationNavigation.conversation(id: "target", cached: [], request: transport)
            }
            do {
                _ = try await attempt.value
                XCTFail("A canceled request cannot produce a navigation result")
            } catch { XCTAssertTrue(error is CancellationError) }
        }
    }

}
