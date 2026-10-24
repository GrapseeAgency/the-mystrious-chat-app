import Combine
import XCTest
@testable import Pulse

@MainActor
final class NavigationHandoffTests: XCTestCase {
    private func room(_ id: String) throws -> WireConversationSummary {
        try JSONDecoder().decode(WireConversationSummary.self,
            from: Data("{\"id\":\"\(id)\",\"isGroup\":false,\"members\":[]}".utf8))
    }

    func testRoomConsumedInsidePublisherDoesNotRemainForReplay() async throws {
        let session = PulseSession()
        var cleanup: Task<Void, Never>?
        let listener = session.$pendingOpenRoom.sink { value in
            guard value != nil else { return }
            cleanup = session.consumePendingOpenRoom()
            session.consumePendingJumpMessageId()
        }
        defer { listener.cancel() }
        session.requestOpenRoom(try room("opened"), jumpMessageId: "message")
        let task = try XCTUnwrap(cleanup)
        await task.value
        XCTAssertNil(session.pendingOpenRoom)
        XCTAssertNil(session.pendingJumpMessageId)
    }

    func testDeferredRoomCleanupCannotEraseANewerDeliveryOrItsJump() async throws {
        let session = PulseSession()
        var cleanup: Task<Void, Never>?
        let listener = session.$pendingOpenRoom.sink { value in
            if value?.id == "first" { cleanup = session.consumePendingOpenRoom() }
        }
        defer { listener.cancel() }
        session.requestOpenRoom(try room("first"))
        session.requestOpenRoom(try room("newer"), jumpMessageId: "newer-message")
        let task = try XCTUnwrap(cleanup)
        await task.value
        XCTAssertEqual(session.pendingOpenRoom?.id, "newer")
        XCTAssertEqual(session.pendingJumpMessageId, "newer-message")
    }

    func testUserRouteConsumptionIsReentrancySafeAndPreservesNewerRoutes() async throws {
        let session = PulseSession()
        var cleanup: Task<Void, Never>?
        let listener = session.$pendingUserRoute.sink { value in
            if value?.userId == "first" { cleanup = session.consumePendingUserRoute() }
        }
        defer { listener.cancel() }
        session.requestOpenUser("first", name: "First")
        let consumed = try XCTUnwrap(cleanup)
        await consumed.value
        XCTAssertNil(session.pendingUserRoute)
        session.requestOpenUser("first", name: "First")
        session.requestOpenUser("newer", name: "Newer")
        let superseded = try XCTUnwrap(cleanup)
        await superseded.value
        XCTAssertEqual(session.pendingUserRoute?.userId, "newer")
    }
    func testRoomAndJumpRemainAvailableUntilThePreviouslyUnmountedChatsSubscriberAttaches() async throws {
        let session = PulseSession()
        session.requestOpenRoom(try room("saved-original"), jumpMessageId:"saved-message")
        var delivered: (String, String?)?
        var cleanup: Task<Void, Never>?
        let listener = session.$pendingOpenRoom.sink { value in
            guard let value else { return }
            delivered = (value.id, session.pendingJumpMessageId)
            cleanup = session.consumePendingOpenRoom()
            session.consumePendingJumpMessageId()
        }
        defer { listener.cancel() }
        XCTAssertEqual(delivered?.0, "saved-original")
        XCTAssertEqual(delivered?.1, "saved-message")
        await cleanup?.value
        XCTAssertNil(session.pendingOpenRoom)
        XCTAssertNil(session.pendingJumpMessageId)
    }

    func testLinkedRoomConsumptionInsidePublisherRetainsTheJumpAndDoesNotReplay() async throws {
        let session = PulseSession()
        var received: PulseLinkedRoomRequest?
        var cleanup: Task<Void, Never>?
        let listener = session.$pendingLinkedRoom.sink { request in
            guard let request else { return }
            received = request
            cleanup = session.consumePendingLinkedRoom(request)
        }
        defer { listener.cancel() }
        XCTAssertTrue(session.requestLinkedRoom(" room ",jumpMessageId:" message "))
        XCTAssertEqual(received?.conversationId,"room")
        XCTAssertEqual(received?.jumpMessageId,"message")
        await cleanup?.value
        XCTAssertNil(session.pendingLinkedRoom)
    }

    func testSameRoomNewJumpAndLegacyAssignmentsHaveDistinctConsumptionIdentity() async throws {
        let session = PulseSession()
        session.pendingLinkedRoomId = "room"
        let first = try XCTUnwrap(session.pendingLinkedRoom)
        let oldCleanup = session.consumePendingLinkedRoom(first)
        session.requestLinkedRoom("room",jumpMessageId:"message")
        let latest = try XCTUnwrap(session.pendingLinkedRoom)
        XCTAssertNotEqual(first.id,latest.id)
        await oldCleanup.value
        XCTAssertEqual(session.pendingLinkedRoom,latest)
        XCTAssertFalse(session.requestLinkedRoom("  ",jumpMessageId:"bad"))
        XCTAssertEqual(session.pendingLinkedRoom,latest)
        await session.consumePendingLinkedRoom(latest).value
        XCTAssertNil(session.pendingLinkedRoomId)
    }

    func testLinkedRoomCleanupFromPriorSessionCannotClearNewDelivery() async throws {
        let session = PulseSession()
        session.requestLinkedRoom("room",jumpMessageId:"old")
        let cleanup = session.consumePendingLinkedRoom(try XCTUnwrap(session.pendingLinkedRoom))
        session.stop()
        session.requestLinkedRoom("room",jumpMessageId:"new")
        await cleanup.value
        XCTAssertEqual(session.pendingLinkedRoom?.jumpMessageId,"new")
    }

}
