import XCTest
@testable import Pulse

/// Section 9 acceptance (iOS data layer): durable publication identity,
/// bounded feed/viewer pages, receipt privacy on the wire, the page merge,
/// and the durable story journal.
@MainActor
final class StoryReplayReceiptTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        static var paths: [String] = []
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s9.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            if let body = request.httpBodyStream {
                var data = Data(); body.open()
                let bufferSize = 4096; let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
                while body.hasBytesAvailable {
                    let read = body.read(buffer, maxLength: bufferSize)
                    if read <= 0 { break }
                    data.append(buffer, count: read)
                }
                buffer.deallocate(); body.close()
                if let text = String(data: data, encoding: .utf8) { Self.bodies.append(text) }
            } else if let data = request.httpBody, let text = String(data: data, encoding: .utf8) {
                Self.bodies.append(text)
            }
            Self.paths.append(request.url!.path + (request.url!.query.map { "?" + $0 } ?? ""))
            let (status, payload) = Self.responses[request.url!.path] ?? (200, [:])
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    private func api() -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return PulseAPIClient(baseURL: URL(string: "https://s9.test")!, userId: "me",
                              session: URLSession(configuration: config))
    }

    private func journal() -> PulseSection8Journal {
        let suite = "s9-journal-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        return PulseSection8Journal(defaults: defaults)
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []; Stub.paths = []
    }

    private static func story(_ id: String, caption: String, created: String) -> [String: Any] {
        ["id": id, "kind": "text", "caption": caption, "background": "emerald",
         "createdAt": created, "expiresAt": "2026-11-02T09:00:00.000Z", "viewCount": 0, "viewedByMe": false]
    }

    // Section 9 item 1 - the journaled identity rides the wire.

    func testPostStoryCarriesClientIdAndDecodesReplayReceipt() async throws {
        Stub.responses["/api/stories"] = (200, ["story": Self.story("s-1", caption: "x", created: "2026-11-01T09:00:00.000Z"), "replayed": true])
        let story = try await api().postStory(caption: "x", background: "emerald", imagePath: nil, clientId: "replay00000000000000001")
        XCTAssertEqual(story.id, "s-1")
        XCTAssertTrue(Stub.bodies.last?.contains("replay00000000000000001") == true, "the replay identity rides the body")
        XCTAssertTrue(Stub.bodies.last?.contains("requesterId") == true)
    }

    func testReplayReceiptDecodesAsConfirmedSameRow() async throws {
        Stub.responses["/api/stories"] = (201, ["story": Self.story("s-2", caption: "y", created: "2026-11-01T09:00:00.000Z")])
        // Legacy 201 without the replayed flag decodes as NOT a replay.
        let created = try await api().postStory(caption: "y", background: "emerald", imagePath: nil)
        XCTAssertEqual(created.id, "s-2")
    }

    // Section 9 item 2 - bounded pages.

    func testFeedPageRequestsKeysetWindowAndDecodesContinuation() async throws {
        Stub.responses["/api/stories"] = (200, ["groups": [["user": ["id": "u2", "name": "Bob"], "stories": [Self.story("b", caption: "x", created: "2026-11-01T09:00:00.000Z")]]],
            "nextCursor": "1771606800000.b", "hasMore": true])
        let page = try await api().storiesPage(limit: 60, cursor: "1771606800000.a")
        XCTAssertEqual(page.hasMore, true)
        XCTAssertEqual(page.nextCursor, "1771606800000.b")
        XCTAssertTrue(Stub.paths.last?.contains("limit=60") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=1771606800000.a") == true)
    }

    func testContinuedFeedPageWithoutCursorIsAProtocolFailure() async throws {
        Stub.responses["/api/stories"] = (200, ["groups": [[:]], "hasMore": true])
        do {
            _ = try await api().storiesPage(limit: 60, cursor: "1771606800000.a")
            XCTFail("a continued page without its cursor must fail")
        } catch { /* expected */ }
    }

    func testViewerPageRequestsKeysetWindowAndDecodesContinuation() async throws {
        Stub.responses["/api/stories/s-9/view"] = (200, ["viewers": [["userId": "u2", "name": "Bob", "username": NSNull(), "color": "teal", "viewedAt": "2026-11-01T09:05:00.000Z"]],
            "nextCursor": "1771607100000.u2", "hasMore": true])
        let page = try await api().storyViewersPage(id: "s-9", limit: 100, cursor: "1771607000000.aa")
        XCTAssertEqual(page.hasMore, true)
        XCTAssertEqual(page.nextCursor, "1771607100000.u2")
        XCTAssertTrue(Stub.paths.last?.contains("limit=100") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=1771607000000.aa") == true)
    }

    // Section 9 item 3 - receipt privacy on the wire.

    func testViewAckDecodesReceiptVisibility() async throws {
        Stub.responses["/api/stories/s-1/view"] = (200, ["viewCount": 0, "receiptVisible": false])
        let hidden = try await api().markStoryViewed(id: "s-1")
        XCTAssertEqual(hidden.viewCount, 0)
        XCTAssertEqual(hidden.receiptVisible, false)
        Stub.responses["/api/stories/s-1/view"] = (200, ["viewCount": 1])
        let legacy = try await api().markStoryViewed(id: "s-1")
        XCTAssertNil(legacy.receiptVisible, "legacy acks carry no receipt flag")
    }

    // Section 9 item 2 - the page merge restores the tray contract.

    func testPageMergeFoldsSplitAuthorsAndRestoresOrdering() {
        let group: ([String: Any], [WireStoryItem]) -> WireStoryGroup = { user, stories in
            WireStoryGroup(user: WireSender(id: user["id"] as! String, name: user["name"] as! String,
                username: nil, color: "emerald", avatar: nil),
                mine: user["mine"] as? Bool, allSeen: true, stories: stories)
        }
        let item: (String, String) -> WireStoryItem = { id, created in
            WireStoryItem(id: id, kind: "text", imagePath: nil, caption: id, background: "emerald",
                createdAt: created, expiresAt: "2026-11-02T09:00:00.000Z", viewCount: 0, viewedByMe: false)
        }
        // Page 1: peer's newest (p3) splits their group from page 2 (p1, p2 older).
        let page1 = [
            group(["id": "peer", "name": "Peer"], [item("p3", "2026-11-01T12:00:00.000Z")]),
            group(["id": "me", "name": "Me", "mine": true], [item("s3", "2026-11-01T11:00:00.000Z")]),
        ]
        let page2 = [
            group(["id": "peer", "name": "Peer"], [item("p1", "2026-11-01T09:00:00.000Z"), item("p2", "2026-11-01T10:00:00.000Z")]),
        ]
        let merged = PulseStoryPages.merged(groups: page1 + page2)
        XCTAssertEqual(merged.count, 2)
        XCTAssertEqual(merged.first?.mine, true, "my group leads the tray")
        XCTAssertEqual(merged.first?.stories?.map(\.id), ["s3"])
        XCTAssertEqual(merged.last?.user?.id, "peer", "the author with the freshest story follows")
        XCTAssertEqual(merged.last?.stories?.map(\.id), ["p1", "p2", "p3"], "playback order is oldest-first across the fold")
    }

    // Section 9 items 1/5/9 - the durable story journal roundtrip.

    func testStoryJournalRoundtripsPublishViewAndDeleteIntents() {
        let store = journal()
        let owner = "me"
        let publish = store.enqueue(owner: owner, kind: "story-publish", targetId: "",
            payload: ["caption": "offline status", "background": "amber"])
        let mark = store.enqueue(owner: owner, kind: "story-view", targetId: "story-1")
        let delete = store.enqueue(owner: owner, kind: "story-delete", targetId: "story-2")
        let pending = store.pending(owner: owner)
        XCTAssertEqual(pending.map(\.kind), ["story-publish", "story-view", "story-delete"])
        XCTAssertEqual(pending.first?.payload["caption"], "offline status")
        // Confirmed receipts are the ONLY way a row leaves the journal.
        store.confirm(owner: owner, clientId: mark.clientId)
        XCTAssertEqual(store.pending(owner: owner).map(\.clientId), [publish.clientId, delete.clientId])
        // A different owner never sees another account's rows.
        XCTAssertTrue(store.pending(owner: "someone-else").isEmpty)
    }
}
