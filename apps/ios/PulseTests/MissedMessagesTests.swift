import XCTest
@testable import Pulse

final class MissedMessagesTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func row(_ id: String, sender: String = "peer", topic: String? = nil, parent: String? = nil,
                     room: String = "room", stamp: String = "2026-10-21T00:00:00Z") throws -> WireChatMessage {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(
            OutboxFixtures.makeMessage(id: id, conversationId: room, content: "same body"))) as? [String: Any])
        object["senderId"] = sender
        object["topicId"] = topic
        object["parentId"] = parent
        object["createdAt"] = stamp
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: object))
    }
    private func reconcile(_ state: inout PulseMissedMessages, _ rows: [WireChatMessage], reading: Bool = false, topic: String? = nil) {
        state.reconcile(rows, roomId: "room", viewerId: "me", topicId: topic, readingTail: reading, now: now)
    }
    private func receive(_ state: inout PulseMissedMessages, _ rows: [WireChatMessage], topic: String? = nil) {
        state.receive(rows, roomId: "room", viewerId: "me", topicId: topic, now: now)
    }

    func testHistoryAndBootstrapNeverInventArrivalsAndUnrelatedDeletionDoesNotClearThem() throws {
        var state = PulseMissedMessages()
        let baseline = try [row("baseline")]
        reconcile(&state, baseline)
        XCTAssertEqual(state.count, 0)
        let incoming = try row("incoming")
        receive(&state, [incoming])
        let history = try (0..<200).map { try row("older-\($0)", stamp: "2026-10-20T00:00:00Z") }
        reconcile(&state, history + baseline + [incoming])
        XCTAssertEqual(state.count, 1)
        reconcile(&state, baseline + [incoming]) // remove unrelated history
        XCTAssertEqual(state.count, 1)
        reconcile(&state, baseline + [incoming.deletedCopy()])
        XCTAssertEqual(state.count, 0)
    }

    func testReplayAndOwnEchoReplacementDoNotCountButIdenticalPeerMessagesAreDistinct() throws {
        var state = PulseMissedMessages()
        let a = try row("a"), b = try row("b")
        let temp = try row("local_own", sender: "me"), delivered = try row("own", sender: "me")
        receive(&state, [a, b, a, temp, delivered])
        reconcile(&state, [a, b, temp])
        XCTAssertEqual(state.count, 2)
        reconcile(&state, [a, b, delivered]) // equal length, different own ID
        XCTAssertEqual(state.count, 2)
        reconcile(&state, [a, b, delivered], reading: true)
        XCTAssertEqual(state.count, 0)
        receive(&state, [a, b]) // socket replay after actually seeing the tail
        reconcile(&state, [a, b, delivered])
        XCTAssertEqual(state.count, 0)
    }

    func testHiddenTopicThreadForeignRoomAndExpiredMessagesAreNotRiverArrivals() throws {
        var state = PulseMissedMessages()
        var expiry = try row("expired", topic: "selected")
        expiry.expiresAt = ISO8601DateFormatter().string(from: now)
        let rows = try [row("live", topic: "selected"), row("topic", topic: "other"),
            row("reply", topic: "selected", parent: "root"), row("foreign", topic: "selected", room: "other")] + [expiry]
        receive(&state, rows, topic: "selected")
        reconcile(&state, rows, topic: "selected")
        XCTAssertEqual(state.count, 1)
        state.reset()
        reconcile(&state, rows, topic: nil) // switching topics seeds known rows, not arrivals
        XCTAssertEqual(state.count, 0)
    }

    func testCatchUpCountsOnlyUnknownRowsAfterCapturedBoundaryAndPreservesLiveRaces() throws {
        var state = PulseMissedMessages()
        let baseline = try row("m"), later = try row("z"), older = try row("a")
        reconcile(&state, [baseline])
        let boundary = try XCTUnwrap(PulseMissedMessages.latestBoundary([baseline]))
        receive(&state, [later]) // live delivery wins while HTTP is suspended
        reconcile(&state, [baseline, later])
        state.receive([older, baseline, later], roomId: "room", viewerId: "me", topicId: nil, after: boundary, now: now)
        reconcile(&state, [older, baseline, later])
        XCTAssertEqual(state.count, 1)
        var expired = later
        expired.expiresAt = ISO8601DateFormatter().string(from: now)
        reconcile(&state, [older, baseline, expired])
        XCTAssertEqual(state.count, 0)
    }
}
