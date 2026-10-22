import XCTest
@testable import Pulse

@MainActor
final class ForwardDeliveryTests: XCTestCase {
    private func row(_ fields: [String: Any] = [:]) throws -> WireChatMessage {
        var values: [String: Any] = ["id": "source", "conversationId": "old-room", "senderId": "peer", "content": "hello",
            "kind": "text", "createdAt": "2026-10-22T00:00:00Z"]
        values.merge(fields) { _, new in new }
        return try JSONDecoder().decode(WireChatMessage.self, from: JSONSerialization.data(withJSONObject: values))
    }
    func testRichForwardSurvivesStoreReopenWithExactEnvelopeAndNoSourceRouting() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("forward.sqlite").path
        let payload = #"{"lat":23.1,"lng":91.2,"label":"Feni"}"#
        let source = try row(["kind": "location", "payload": payload, "topicId": "old-topic", "anon": true, "anonAlias": "Alias", "pinnedAt": "stamp"])
        let store = try PulseStore(path: path)
        try store.upsert(messages: [source])
        let echo = try PulseForwardDelivery.enqueue(source: source, targetId: "target", viewer: PulseViewer(id: "me", name: "Me"), store: store)
        let reopened = try PulseStore(path: path)
        let operation = try XCTUnwrap(reopened.outboxPending().first)
        let envelope = try XCTUnwrap(PulseOutboxForward.decode(operation.payloadJson))
        XCTAssertEqual(echo.id, "local_" + operation.clientId)
        XCTAssertEqual(envelope.kind, "location"); XCTAssertEqual(envelope.payload, payload)
        let saved = try XCTUnwrap(reopened.message(id: echo.id))
        XCTAssertEqual(saved.conversationId, "target"); XCTAssertEqual(saved.senderId, "me")
        XCTAssertNil(saved.parentId); XCTAssertNil(saved.topicId); XCTAssertNil(saved.pinnedAt); XCTAssertNil(saved.anonAlias)
    }
    func testStaleSourceAndDeniedThreadCannotEnterOutbox() throws {
        let store = try PulseStore()
        let source = try row()
        try store.upsert(messages: [source.deletedCopy()])
        XCTAssertThrowsError(try PulseForwardDelivery.enqueue(source: source, targetId: "target", viewer: PulseViewer(id: "me", name: "Me"), store: store))
        XCTAssertTrue(try store.outboxPending().isEmpty)
        let root = try row(["id": "root"])
        let reply = try row(["id": "reply", "parentId": "root"])
        try store.upsert(messages: [root, reply]); try store.denyThread(rootId: "root")
        XCTAssertThrowsError(try PulseForwardDelivery.enqueue(source: reply, targetId: "target", viewer: PulseViewer(id: "me", name: "Me"), store: store))
        XCTAssertTrue(try store.outboxPending().isEmpty)
    }
    func testUnsupportedPrivateAndMalformedForwardsAreRefused() throws {
        let cases: [[String: Any]] = [["viewOnce": true], ["kind": "poll"], ["kind": "system"], ["id": "local_pending"],
            ["kind": "sticker"], ["kind": "location", "payload": #"{"lat":true,"lng":0}"#], ["kind": "image"]]
        for fields in cases {
            XCTAssertThrowsError(try PulseForwardDelivery.envelope(for: row(fields)))
        }
        let payload = #"{"emoji":"spark","pack":"Signal"}"#
        XCTAssertEqual(try PulseForwardDelivery.envelope(for: row(["kind": "sticker", "payload": payload])).payload, payload)
    }
}
