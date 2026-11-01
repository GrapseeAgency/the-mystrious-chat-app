import XCTest
@testable import Pulse

/// Section 11 wire acceptance (iOS data layer): the durable marketplace
/// listing identity rides the wire with replay receipts (item 1), 410
/// creation_gone surfaces as an honest Failure, the ONE-request app
/// installs batch decodes (item 6), connect/disconnect carry the journaled
/// clientId and treat a replay as an acknowledgement (item 8), the market
/// board pages past the legacy cap (item 3) and the journal roundtrips the
/// hub kinds the session-start flush replays.
@MainActor
final class Section11WireTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        static var paths: [String] = []
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s11.test" }
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

    private func api(userId: String = "me") -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return PulseAPIClient(baseURL: URL(string: "https://s11.test")!, userId: userId,
                              session: URLSession(configuration: config))
    }

    private func journal() -> PulseSection8Journal {
        let suite = "s11-journal-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        return PulseSection8Journal(defaults: defaults)
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []; Stub.paths = []
    }

    private static func listing(_ id: String, title: String = "Ship", price: Int = 25, status: String = "open",
                                seller: String = "me", mine: Bool = true) -> [String: Any] {
        ["id": id, "title": title, "price": price, "asset": "PC", "status": status,
         "createdAt": "2026-11-01T09:00:00.000Z", "seller": ["id": seller, "name": "A"], "mine": mine]
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    // Section 11 item 1 - the listing identity rides the wire and a 200
    // replayed:true envelope is an acknowledgement of the SAME listing.

    func testListingCreateCarriesClientIdAndReplayDecodesTheSameListing() async throws {
        Stub.responses["/api/hub/market"] = (200, ["marketVersion": 1, "userId": "me",
            "listing": Self.listing("l1"), "clientId": "mkt00000000000000001", "replayed": true])
        let row = try await api().createListing(title: "Ship", description: nil, price: 25,
                                                clientId: "mkt00000000000000001")
        XCTAssertEqual(row.id, "l1")
        XCTAssertEqual(row.title, "Ship")
        XCTAssertEqual(row.price, 25)
        XCTAssertEqual(row.status, "open")
        XCTAssertTrue(Stub.bodies.last?.contains("mkt00000000000000001") == true, "the replay identity rides the body")
        XCTAssertTrue(Stub.bodies.last?.contains("\"price\":25") == true)
    }

    func testCreationGoneReceiptSurfacesAsAFailureWithTheRightStatus() async throws {
        Stub.responses["/api/hub/market"] = (410, ["error": "The earlier creation was delivered, but is no longer available.",
            "code": "creation_gone", "clientId": "mkt00000000000000042"])
        do {
            _ = try await api().createListing(title: "Ship", description: nil, price: 25,
                                              clientId: "mkt00000000000000042")
            XCTFail("a 410 creation_gone replay must fail honestly")
        } catch let failure as PulseAPIClient.Failure {
            XCTAssertEqual(failure.status, 410)
            XCTAssertEqual(failure.code, "creation_gone")
            XCTAssertEqual(failure.receiptClientId, "mkt00000000000000042")
        }
    }

    // Section 11 item 6 - ONE batch snapshot for the whole catalog.

    func testAppInstallsBatchDecodesTheCatalogSnapshotAndServerErrorsMapToFailure() async throws {
        Stub.responses["/api/hub/apps/installs"] = (200, ["appVersion": 2, "userId": "me", "installsRev": 4,
            "apps": [["appId": "1", "order": 0, "installed": true, "installedAt": "2026-11-01T09:00:00.000Z"],
                     ["appId": "2", "order": 1, "installed": false, "installedAt": NSNull()]]])
        let batch = try await api().appInstalls()
        XCTAssertEqual(batch.appVersion, 2)
        XCTAssertEqual(batch.userId, "me")
        XCTAssertEqual(batch.installsRev, 4)
        XCTAssertEqual(batch.apps?.count, 2)
        XCTAssertEqual(batch.apps?.first?.appId, "1")
        XCTAssertEqual(batch.apps?.first?.order, 0)
        XCTAssertEqual(batch.apps?.first?.installed, true)
        XCTAssertEqual(batch.apps?.last?.installed, false)
        XCTAssertNil(batch.apps?.last?.installedAt)

        Stub.responses["/api/hub/apps/installs"] = (500, ["error": "boom"])
        do {
            _ = try await api().appInstalls()
            XCTFail("a 500 snapshot must map to a Failure")
        } catch let failure as PulseAPIClient.Failure {
            XCTAssertEqual(failure.kind, .server)
        }
    }

    // Section 11 item 8 - connect/disconnect carry the journaled identity and
    // a replayed snapshot is an acknowledgement, never a hard failure.

    func testInstallAndUninstallCarryClientIdAndReplayIsAnAcknowledgement() async throws {
        Stub.responses["/api/hub/apps/7/install"] = (200, ["appVersion": 1, "appId": "7", "userId": "me",
            "installed": true, "status": "connected", "installedAt": "2026-11-01T09:00:00.000Z",
            "installs": 3, "installers": [["id": "me", "name": "Me"]], "installsRev": 5, "replayed": true])
        let connected = try await api().installApp(appId: "7", clientId: "app000000000000000007")
        XCTAssertEqual(connected.installed, true)
        XCTAssertEqual(connected.installsRev, 5)
        XCTAssertEqual(connected.replayed, true, "the replay receipt decodes")
        XCTAssertTrue(Stub.bodies.last?.contains("app000000000000000007") == true)

        Stub.responses["/api/hub/apps/8/install"] = (200, ["appVersion": 1, "appId": "8", "userId": "me",
            "installed": false, "status": NSNull(), "installedAt": NSNull(),
            "installs": 2, "installers": [["id": "me", "name": "Me"]], "installsRev": 6, "replayed": true])
        let disconnected = try await api().uninstallApp(appId: "8", clientId: "app000000000000000008")
        XCTAssertEqual(disconnected.installed, false)
        XCTAssertEqual(disconnected.replayed, true)
        XCTAssertTrue(Stub.bodies.last?.contains("app000000000000000008") == true)
    }

    // The durable journal roundtrips the hub kinds the flush replays.

    func testMarketJournalRoundtripsAndConfirmRemovesOnlyItsOwnRow() {
        let store = journal()
        let owner = "s11-owner"
        let listing = store.enqueue(owner: owner, kind: "market-create", targetId: "",
            payload: ["title": "Ship", "description": "", "price": "25"])
        let connect = store.enqueue(owner: owner, kind: "app-connect", targetId: "7")
        let pending = store.pending(owner: owner)
        XCTAssertEqual(pending.map(\.kind), ["market-create", "app-connect"])
        XCTAssertEqual(pending.first?.payload["title"], "Ship")
        XCTAssertEqual(pending.first?.payload["price"], "25")
        XCTAssertEqual(pending.last?.targetId, "7")
        // Confirmed receipts are the ONLY way a row leaves the journal.
        store.confirm(owner: owner, clientId: listing.clientId)
        XCTAssertEqual(store.pending(owner: owner).map(\.clientId), [connect.clientId], "confirm removes only its own row")
        // A different owner never sees another account's rows.
        XCTAssertTrue(store.pending(owner: "someone-else").isEmpty)
    }

    // Section 11 items 3/6 - the paging fields decode tolerantly.

    func testMarketPageAndInstallsBatchDecodePagingAndBatchFields() throws {
        let market = try decode(WireMarketPage.self,
            #"{"marketVersion":1,"userId":"u2","listings":[{"id":"l1","title":"Ship","price":25,"asset":"PC","status":"open","createdAt":"2026-11-01T09:00:00.000Z","seller":{"id":"u1","name":"A"},"mine":false}],"hasMore":true,"nextCursor":"open.1771606800000.l1"}"#)
        XCTAssertEqual(market.hasMore, true)
        XCTAssertEqual(market.nextCursor, "open.1771606800000.l1")
        let legacy = try decode(WireMarketPage.self,
            #"{"marketVersion":1,"userId":"u2","listings":[{"id":"l1","title":"Ship","price":25,"asset":"PC","status":"open","createdAt":"2026-11-01T09:00:00.000Z","seller":{"id":"u1","name":"A"},"mine":false}]}"#)
        XCTAssertNil(legacy.hasMore, "the legacy board shape carries no paging fields")
        XCTAssertNil(legacy.nextCursor)

        let batch = try decode(WireAppInstallsBatch.self,
            #"{"appVersion":2,"userId":"u1","installsRev":4,"apps":[{"appId":"1","order":0,"installed":true,"installedAt":"2026-11-01T09:00:00.000Z"},{"appId":"2","order":1,"installed":false,"installedAt":null}]}"#)
        XCTAssertEqual(batch.appVersion, 2)
        XCTAssertEqual(batch.userId, "u1")
        XCTAssertEqual(batch.installsRev, 4)
        XCTAssertEqual(batch.apps?.map(\.appId), ["1", "2"])
        XCTAssertEqual(batch.apps?.first?.installed, true)
        XCTAssertEqual(batch.apps?.last?.order, 1)
    }

    // Section 11 items 3/11 - the paged reads pass limit/cursor through.

    func testPagedReadsPassLimitAndCursorThroughAndDecodeContinuations() async throws {
        Stub.responses["/api/hub/market"] = (200, ["marketVersion": 1, "userId": "me",
            "listings": [Self.listing("l1")], "hasMore": true, "nextCursor": "open.1771606800000.l1"])
        let board = try await api().market(limit: 60, cursor: "open.1771606700000.l0")
        XCTAssertEqual(board.hasMore, true)
        XCTAssertEqual(board.nextCursor, "open.1771606800000.l1")
        XCTAssertTrue(Stub.paths.last?.contains("limit=60") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=open.1771606700000.l0") == true)

        Stub.responses["/api/games"] = (200, ["gameReadVersion": 1, "userId": "me", "conversationId": "room",
            "matches": [["id": "m1", "conversationId": "room"]], "hasMore": true, "nextCursor": "1771606800000.m1"])
        let games = try await api().gamesPage(conversationId: "room", limit: 25, cursor: "1771606700000.m0")
        XCTAssertEqual(games.matches.first?.id, "m1")
        XCTAssertEqual(games.nextCursor, "1771606800000.m1")
        XCTAssertTrue(Stub.paths.last?.contains("limit=25") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=1771606700000.m0") == true)

        Stub.responses["/api/tournaments"] = (200, ["gameReadVersion": 1, "userId": "me", "conversationId": "room",
            "tournaments": [["id": "t1", "conversationId": "room"]], "hasMore": false, "nextCursor": NSNull()])
        let seasons = try await api().tournamentsPage(conversationId: "room", limit: 5, cursor: nil)
        XCTAssertEqual(seasons.tournaments.first?.id, "t1")
        XCTAssertEqual(seasons.hasMore, false)
        XCTAssertNil(seasons.nextCursor)
        XCTAssertTrue(Stub.paths.last?.contains("limit=5") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=") == false, "a nil cursor opens the walk")
    }
}
