import XCTest
@testable import Pulse

private final class MessageReadStateProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "message-read.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/messages/anchor/read-state")
        let mode = request.value(forHTTPHeaderField: "X-Proof-Test") ?? "valid"
        var body: [String: Any] = ["messageReadStateVersion": 1, "userId": "me", "conversationId": "room",
            "messageId": "anchor", "messageAvailable": true, "readByMembershipIds": ["member"]]
        switch mode {
        case "legacy": body = ["ok": true]
        case "owner": body["userId"] = "other"
        case "room": body["conversationId"] = "other"
        case "message": body["messageId"] = "other"
        case "version": body["messageReadStateVersion"] = 0
        case "available": body["messageAvailable"] = "true"
        case "missing": body.removeValue(forKey: "readByMembershipIds")
        case "numeric": body["readByMembershipIds"] = [1]
        case "duplicate": body["readByMembershipIds"] = ["member", "member"]
        case "blank": body["readByMembershipIds"] = [" "]
        case "contradiction": body["messageAvailable"] = false
        case "empty": body["readByMembershipIds"] = [String]()
        case "unavailable": body["messageAvailable"] = false; body["readByMembershipIds"] = [String]()
        default: break
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: mode == "old-server" ? 404 : 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class MessageReadStateTests: XCTestCase {
    func testOverlappingCanonicalPagesKeepTheNewerRequestWithoutDiscardingUnrelatedRows() throws {
        for path in ["page", "thread", "saved"] {
            let store = try PulseStore(path: ":memory:")
            let older = store.beginMessageSnapshot(), newer = store.beginMessageSnapshot()
            var old = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Old response")
            old.readByMembershipIds = ["retired-peer"]
            var current = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Current response")
            current.readByMembershipIds = []
            _ = try store.mergeMessages([current], snapshotRequest: newer)
            let currentVersion = store.messageSnapshotVersion(current.id)
            switch path {
            case "thread":
                let page = try store.acceptThreadSnapshot(rootId: current.id, roomId: "room", messages: [old], snapshotRequest: older)
                XCTAssertEqual(page.parent.content, current.content)
                XCTAssertEqual(page.parent.readByMembershipIds, [])
            case "saved":
                try store.upsert(savedItems: [WireSavedItem(savedAt: old.createdAt,
                    conversation: WireSavedConversation(id: "room", isGroup: false, name: "Room"), message: old)], snapshotRequest: older)
                XCTAssertEqual(try store.cachedSavedItems().first?.message.content, current.content)
            default:
                let unrelated = OutboxFixtures.makeMessage(id: "other", conversationId: "room", content: "Keep this")
                let rows = try store.mergeMessages([old, unrelated], snapshotRequest: older)
                XCTAssertEqual(rows.first?.content, current.content)
                XCTAssertEqual(rows.first?.readByMembershipIds, [])
                XCTAssertEqual(rows.last?.content, unrelated.content)
            }
            XCTAssertEqual(try store.message(id: current.id)?.content, current.content, path)
            XCTAssertEqual(try store.message(id: current.id)?.readByMembershipIds, [], path)
            XCTAssertEqual(store.messageSnapshotVersion(current.id), currentVersion, "Rejected rows cannot invalidate a fresh Info proof")
            XCTAssertEqual(try store.currentMessageSnapshot([old]).first?.content, current.content,
                "A resumed thread/search consumer must not republish the old input")
        }
    }

    func testNewerRequestStillAppliesWhenTheEarlierPageCommitsFirstAndDoesNotSuppressMutationACKs() throws {
        let store = try PulseStore(path: ":memory:")
        let older = store.beginMessageSnapshot(), newer = store.beginMessageSnapshot()
        var old = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Old")
        old.readByMembershipIds = ["retired-peer"]
        _ = try store.mergeMessages([old], snapshotRequest: older)
        var fresh = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "New")
        fresh.readByMembershipIds = []
        _ = try store.mergeMessages([fresh], snapshotRequest: newer)
        XCTAssertEqual(try store.message(id: fresh.id)?.readByMembershipIds, [])
        let ack = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Confirmed edit")
        try store.upsert(messages: [ack]) // mutation ACKs do not enter the GET request lane
        XCTAssertEqual(try store.message(id: ack.id)?.content, ack.content)
    }

    func testCanonicalCacheCommitsFenceOlderInfoEvenWhenTheirProofIsUnchanged() throws {
        let store = try PulseStore(path: ":memory:")
        var message = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Current page body")
        message.readByMembershipIds = []
        try store.upsert(messages: [message])
        for path in ["page", "thread", "saved"] {
            let captured = store.messageSnapshotVersion(message.id)
            switch path {
            case "thread": _ = try store.acceptThreadSnapshot(rootId: message.id, roomId: "room", messages: [message])
            case "saved": try store.upsert(savedItems: [WireSavedItem(savedAt: message.createdAt,
                conversation: WireSavedConversation(id: "room", isGroup: false, name: "Room"), message: message)])
            default: try store.upsert(messages: [message])
            }
            XCTAssertNotEqual(store.messageSnapshotVersion(message.id), captured, path)
            XCTAssertThrowsError(try store.replaceMessageReadState(conversationId: "room", messageId: message.id,
                memberships: ["retired-peer"], snapshotVersion: captured)) { error in
                XCTAssertTrue(error is PulseStore.SupersededMessageReadState, path)
            }
            XCTAssertEqual(try store.message(id: message.id)?.readByMembershipIds, [], path)
            XCTAssertEqual(try store.message(id: message.id)?.content, message.content, path)
        }
        let captured = store.messageSnapshotVersion(message.id)
        try store.upsert(messages: [OutboxFixtures.makeMessage(id: "unrelated", conversationId: "room", content: "Other")])
        XCTAssertTrue(try store.replaceMessageReadState(conversationId: "room", messageId: message.id,
            memberships: ["current-peer"], snapshotVersion: captured), "Unrelated messages must not starve Info")
        XCTAssertEqual(try store.message(id: message.id)?.readByMembershipIds, ["current-peer"])
        let otherAccount = try PulseStore(path: ":memory:")
        XCTAssertEqual(otherAccount.messageSnapshotVersion(message.id), 0)
    }

    func testLaterPositivePageCannotBeClearedByAnEarlierNegativeInfoResponse() throws {
        let store = try PulseStore(path: ":memory:")
        var message = OutboxFixtures.makeMessage(id: "anchor", conversationId: "room", content: "Edited body")
        let captured = store.messageSnapshotVersion(message.id)
        message.readByMembershipIds = ["current-peer"]
        try store.upsert(messages: [message])
        XCTAssertThrowsError(try store.replaceMessageReadState(conversationId: "room", messageId: message.id,
            memberships: [], snapshotVersion: captured))
        XCTAssertEqual(try store.message(id: message.id)?.readByMembershipIds, ["current-peer"])
        let current = store.messageSnapshotVersion(message.id)
        XCTAssertTrue(try store.replaceMessageReadState(conversationId: "room", messageId: message.id,
            memberships: [], snapshotVersion: current), "A fresh canonical negative still removes proof")
    }

    func testExactInfoProofRejectsForeignLegacyMalformedAndUnboundResponses() async throws {
        for mode in ["valid", "empty", "unavailable", "legacy", "owner", "room", "message", "version",
                     "available", "missing", "numeric", "duplicate", "blank", "contradiction", "old-server"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [MessageReadStateProtocol.self]
            config.httpAdditionalHeaders = ["X-Proof-Test": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://message-read.test")!, userId: "me", session: transport)
            do {
                let proof = try await api.messageReadState(conversationId: "room", messageId: "anchor")
                XCTAssertTrue(["valid", "empty", "unavailable"].contains(mode), mode)
                XCTAssertEqual(proof.available, mode != "unavailable")
                XCTAssertEqual(proof.memberships, mode == "valid" ? ["member"] : [])
            } catch {
                XCTAssertFalse(["valid", "empty", "unavailable"].contains(mode), "\(mode): \(error)")
            }
        }
    }
}
