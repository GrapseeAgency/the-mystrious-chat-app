import XCTest
@testable import Pulse

/// Section 8 acceptance (iOS data layer): replay receipts, intent watermarks,
/// rail revisions, directory paging, and the durable offline journal.
@MainActor
final class Section8ReceiptTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        static var paths: [String] = []
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s8.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            if let body = request.httpBodyStream { // read body for assertions
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
            let key = request.url!.path
            let (status, payload) = Self.responses[key] ?? (200, [:])
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
        let session = URLSession(configuration: config)
        return PulseAPIClient(baseURL: URL(string: "https://s8.test")!, userId: "me", session: session)
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []; Stub.paths = []
    }

    func testFolderCreateCarriesClientIdAndConfirmsReplayReceipt() async throws {
        Stub.responses["/api/folders"] = (200, ["folderVersion": 1, "userId": "me", "replayed": false, "rev": 1,
            "folder": ["id": "f1", "name": "Work", "emoji": "folder", "position": 0, "rev": 0, "conversationIds": [String]()]])
        let folder = try await api().createFolder(name: "Work", emoji: "folder", clientId: "client000000000000001")
        XCTAssertEqual(folder.id, "f1")
        XCTAssertTrue(Stub.bodies.last?.contains("client000000000000001") == true, "the replay identity rides the body")
        // A receipt WITHOUT the replay flag is refused when identity was sent.
        Stub.responses["/api/folders"] = (200, ["folderVersion": 1, "userId": "me",
            "folder": ["id": "f2", "name": "X", "emoji": "folder", "position": 1, "conversationIds": [String]()]])
        do {
            _ = try await api().createFolder(name: "X", emoji: "folder", clientId: "client000000000000002")
            XCTFail("missing replay receipt must be refused")
        } catch { /* expected */ }
    }

    func testPhraseCreateAndListDemandTheRailRevision() async throws {
        Stub.responses["/api/users/me/phrases"] = (201, ["phrase": ["id": "p1", "text": "Hi", "position": 0],
            "listRev": 2, "replayed": false])
        let phrase = try await api().createQuickPhrase(text: "Hi", clientId: "client000000000000003")
        XCTAssertEqual(phrase.id, "p1")
        // Missing listRev on a journaled write: refused.
        Stub.responses["/api/users/me/phrases"] = (201, ["phrase": ["id": "p2", "text": "Ho", "position": 1], "replayed": false])
        do {
            _ = try await api().createQuickPhrase(text: "Ho", clientId: "client000000000000004")
            XCTFail("missing listRev must be refused")
        } catch { /* expected */ }
        // The rail snapshot carries the revision (tolerant: absent passes, malformed fails).
        Stub.responses["/api/users/me/phrases"] = (200, ["phrases": [], "listRev": 7])
        let list = try await api().quickPhrases()
        XCTAssertTrue(list.isEmpty)
        Stub.responses["/api/users/me/phrases"] = (200, ["phrases": [], "listRev": "bad"])
        do { _ = try await api().quickPhrases(); XCTFail("malformed listRev must be refused") } catch { /* expected */ }
    }

    func testBlockIntentCarriesClientIdAndOriginalWatermark() async throws {
        Stub.responses["/api/users/peer/block"] = (200, ["ok": true, "blockReceiptVersion": 1, "actorId": "me",
            "targetId": "peer", "blocked": true, "stale": false, "replayed": false, "intentAt": "2026-02-01T10:00:00Z"])
        try await api().block(userId: "peer", clientId: "client000000000000005", intentAt: "2026-02-01T10:00:00Z")
        XCTAssertTrue(Stub.bodies.last?.contains("client000000000000005") == true)
        XCTAssertTrue(Stub.bodies.last?.contains("2026-02-01T10:00:00Z") == true)
        // A malformed watermark is refused client-side before any trust.
        Stub.responses["/api/users/peer/block"] = (200, ["ok": true, "blockReceiptVersion": 1, "actorId": "me",
            "targetId": "peer", "blocked": true, "intentAt": "not-a-date"])
        do {
            try await api().unblock(userId: "peer", clientId: "client000000000000006", intentAt: "not-a-date")
            XCTFail("malformed watermark must be refused")
        } catch { /* expected */ }
    }

    func testReportReplayReceiptCarriesTheSameRowAndRevision() async throws {
        Stub.responses["/api/users/peer/report"] = (201, ["ok": true, "reportReceiptVersion": 1, "reporterId": "me",
            "reportedId": "peer", "reported": true, "updated": false, "replayed": false, "reportId": "r1",
            "evidenceRev": 0, "reason": "spam", "details": "", "reportedAt": "2026-02-01T10:00:00.000Z"])
        let first = try await api().report(userId: "peer", reason: "spam", details: nil, clientId: "client000000000000007")
        XCTAssertEqual(first.reportId, "r1")
        XCTAssertEqual(first.evidenceRev, 0)
        Stub.responses["/api/users/peer/report"] = (201, ["ok": true, "reportReceiptVersion": 1, "reporterId": "me",
            "reportedId": "peer", "reported": true, "updated": false, "replayed": true, "reportId": "r1",
            "evidenceRev": 0, "reason": "spam", "details": "", "reportedAt": "2026-02-01T10:00:00.000Z"])
        let replay = try await api().report(userId: "peer", reason: "spam", details: nil, clientId: "client000000000000007")
        XCTAssertEqual(replay.replayed, true)
        XCTAssertEqual(replay.reportId, "r1")
    }

    func testDirectoryPageDemandsCursorAgreement() async throws {
        Stub.responses["/api/users"] = (200, ["users": [["id": "u1", "name": "Ada"]], "nextCursor": "u1", "hasMore": true])
        let page = try await api().directoryPage(limit: 1)
        XCTAssertEqual(page.nextCursor, "u1")
        XCTAssertEqual(page.hasMore, true)
        XCTAssertTrue(Stub.paths.last?.contains("limit=1") == true)
        // hasMore=true with a null cursor: refused.
        Stub.responses["/api/users"] = (200, ["users": [], "nextCursor": NSNull(), "hasMore": true])
        do { _ = try await api().directoryPage(); XCTFail("cursor must match hasMore") } catch { /* expected */ }
    }

    func testJournalSurvivesRoundTripAndConfirmRemovesOnlyItsOwnRow() {
        let suite = UserDefaults(suiteName: "section8-journal-test")!
        suite.removePersistentDomain(forName: "section8-journal-test")
        let journal = PulseSection8Journal(defaults: suite)
        let first = journal.enqueue(owner: "me", kind: "block", targetId: "peer")
        let second = journal.enqueue(owner: "me", kind: "report", targetId: "peer", payload: ["reason": "spam"])
        XCTAssertEqual(journal.pending(owner: "me").count, 2)
        XCTAssertEqual(first.clientId.count, 32, "the replay identity is 128 bits of hex")
        // The block body replays the ORIGINAL watermark.
        let body = journal.blockBody(first)
        XCTAssertEqual(body["clientId"] as? String, first.clientId)
        XCTAssertNotNil(body["intentAt"])
        journal.confirm(owner: "me", clientId: first.clientId)
        let remaining = journal.pending(owner: "me")
        XCTAssertEqual(remaining.map(\.clientId), [second.clientId])
        XCTAssertEqual(remaining.first?.payload["reason"], "spam")
    }
}
