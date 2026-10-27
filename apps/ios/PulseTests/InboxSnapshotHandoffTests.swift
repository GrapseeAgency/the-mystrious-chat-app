import Combine
import GRDB
import XCTest
@testable import Pulse

private final class InboxSnapshotProtocol: URLProtocol {
    static let body = """
    {"conversations":[
      {"id":"room","name":"Room","isGroup":false,"members":[],"unreadCount":0,"myManualUnread":true},
      {"id":"other","name":"Other","isGroup":false,"members":[],"unreadCount":4},
      {"id":"archive","name":"Archive","isGroup":false,"members":[],"unreadCount":40,"archivedAt":"2026-01-01T00:00:00Z"},
      {"id":"self","name":"Notes","isGroup":false,"members":[],"unreadCount":50,"isSelf":true}
    ]}
    """
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host?.hasSuffix("badge-snapshot.test") == true
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.path, "/api/conversations", "Handoff must not need another request")
        let status = request.url?.host == "failed-badge-snapshot.test" ? 503 : 200
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((status == 200 ? Self.body : "{\"error\":\"offline\"}").utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class InboxSnapshotHandoffTests: XCTestCase {
    private func fixture(_ json: String) throws -> [WireConversationSummary] {
        try JSONDecoder().decode(WireConversationsPage.self, from: Data(json.utf8)).conversations
    }
    private var stale: String {
        """
        {"conversations":[{"id":"room","name":"Room","isGroup":false,"members":[],"unreadCount":9}]}
        """
    }
    private func transport() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [InboxSnapshotProtocol.self]
        return URLSession(configuration: config)
    }
    private func api(_ transport: URLSession, failed: Bool = false) -> PulseAPIClient {
        PulseAPIClient(baseURL: URL(string: failed ? "https://failed-badge-snapshot.test" : "https://badge-snapshot.test")!,
            userId: "badge-owner", authToken: "fixture-token", session: transport)
    }

    func testPersistedBadgeSnapshotUpdatesBothLoadedInboxesWithoutASecondFetch() async throws {
        let session = PulseSession(), primary = ChatsViewModel(), mirror = MirrorViewModel()
        defer { session.stop() }
        let store = try PulseStore(path: ":memory:")
        let old = try fixture(stale)
        try store.upsert(conversations: old.map { $0.toDomain() }, replacingSnapshot: true)
        primary.acceptCanonicalInbox(old, session: session)
        mirror.acceptCanonicalInbox(old, viewerId: "badge-owner")
        XCTAssertEqual(primary.unreadTotal, 9)
        XCTAssertEqual(mirror.totalUnread, 9)
        let epoch = session.sessionGeneration
        var publications = 0
        let subscription = session.inboxSnapshots.sink { rooms in
            publications += 1
            // Production only publishes after persistence, not from the push payload.
            XCTAssertEqual(try? store.cachedConversations().count, 4)
            primary.acceptCanonicalInbox(rooms, session: session)
            mirror.acceptCanonicalInbox(rooms, viewerId: "badge-owner")
        }
        defer { subscription.cancel() }
        let transport = transport()
        defer { transport.invalidateAndCancel() }
        let result = await PulsePushNotifications.refreshUnreadBadge(using: api(transport), store: store,
            isCurrent: { true }, onSnapshot: { session.publishInboxSnapshot($0, epoch: epoch) })
        XCTAssertTrue(result)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(primary.phase, .loaded)
        XCTAssertEqual(primary.unreadTotal, 5, "Manual markers count once; archive/self do not count")
        XCTAssertEqual(mirror.totalUnread, 5)
        XCTAssertEqual(session.dockUnreadCount, 5)
        XCTAssertEqual(primary.activeRows.first { $0.id == "room" }?.unreadCount, 0)
        XCTAssertEqual(mirror.rows.first { $0.id == "room" }?.manualUnread, true)
        session.publishInboxSnapshot([], epoch: epoch) // canonical empty snapshot is valid
        XCTAssertEqual(primary.unreadTotal, 0)
        XCTAssertEqual(mirror.totalUnread, 0)
        XCTAssertEqual(session.dockUnreadCount, 0)
    }

    func testFailedOrRetiredFetchCannotPublishOrReplaceTheCachedInbox() async throws {
        for retired in [false, true] {
            let store = try PulseStore(path: ":memory:")
            try store.upsert(conversations: fixture(stale).map { $0.toDomain() }, replacingSnapshot: true)
            let transport = transport()
            defer { transport.invalidateAndCancel() }
            var checks = 0, publications = 0
            let result = await PulsePushNotifications.refreshUnreadBadge(using: api(transport, failed: !retired),
                store: store, isCurrent: { checks += 1; return !retired || checks == 1 },
                onSnapshot: { _ in publications += 1 })
            XCTAssertFalse(result)
            XCTAssertEqual(publications, 0)
            XCTAssertEqual(try store.cachedConversations().first?.unreadCount, 9)
        }
    }

    func testFailedSnapshotPersistenceDoesNotPublishAnInventedZero() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("inbox.sqlite").path
        let store = try PulseStore(path: path)
        try store.upsert(conversations: fixture(stale).map { $0.toDomain() }, replacingSnapshot: true)
        let connection = try DatabaseQueue(path: path)
        try await connection.write { db in
            try db.execute(sql: "CREATE TRIGGER refuse_inbox BEFORE INSERT ON conversation BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
        }
        let transport = transport()
        defer { transport.invalidateAndCancel() }
        var publications = 0
        let result = await PulsePushNotifications.refreshUnreadBadge(using: api(transport), store: store,
            isCurrent: { true }, onSnapshot: { _ in publications += 1 })
        XCTAssertFalse(result)
        XCTAssertEqual(publications, 0)
        XCTAssertEqual(try store.cachedConversations().first?.unreadCount, 9, "Replacement rolls back atomically")
    }

    func testRetiredSessionCannotPublishIntoTheReplacementInbox() throws {
        let session = PulseSession(), epoch = session.sessionGeneration
        var publications = 0
        let subscription = session.inboxSnapshots.sink { _ in publications += 1 }
        defer { subscription.cancel() }
        session.publishInboxSnapshot(try fixture(stale), epoch: epoch)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(session.dockUnreadCount, 9)
        session.stop()
        session.publishInboxSnapshot(try fixture(stale), epoch: epoch)
        XCTAssertEqual(publications, 1)
        XCTAssertEqual(session.dockUnreadCount, 0)
    }
}
