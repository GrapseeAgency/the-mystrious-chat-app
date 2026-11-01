import XCTest
@testable import Pulse

/// Section 10 wire acceptance (iOS data layer): durable creation identities on
/// the wire for events/tasks/kanban/reminders/packets (items 1/4/6/11/13),
/// bounded history pages for events/tasks/boards/whiteboards (2/5/8/10), and
/// the journal retirement contract the replay path depends on.
@MainActor
final class Section10WireTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        static var paths: [String] = []
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s10.test" }
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

    private func api(userId: String = "u") -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return PulseAPIClient(baseURL: URL(string: "https://s10.test")!, userId: userId,
                              session: URLSession(configuration: config))
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []; Stub.paths = []
    }

    // Section 10 item 13 - the packet identity rides the wire and the row retires.

    func testRedPacketCreationCarriesClientIdDecodesReplayAndRetiresTheJournalRow() async throws {
        let userId = "s10-pkt-\(UUID().uuidString)"
        Stub.responses["/api/redpackets"] = (201, ["message": ["id": "m1", "conversationId": "room", "senderId": "u",
            "content": "go", "kind": "redpacket", "createdAt": "2026-11-01T09:00:00.000Z", "deletedAt": nil, "parentId": nil,
            "imagePath": nil, "audioPath": nil, "anon": false], "packet":
            ["id": "p1", "total": 100, "count": 5, "grabbed": 0, "note": "go", "expiresAt": "2026-11-03T09:00:00.000Z"],
            "replayed": true])
        let client = api(userId: userId)
        let result = try await client.createRedPacket(conversationId: "room", total: 100, count: 5, note: "go")
        XCTAssertTrue(result.replayed == true, "the replay receipt decodes")
        XCTAssertTrue(Stub.bodies.last?.contains("clientId") == true)
        let pending = await PulseSection8Journal.shared.pending(owner: userId)
        XCTAssertTrue(pending.isEmpty, "a confirmed receipt is the only exit - and it confirmed")
    }

    // Section 10 items 1/4/6/11 - the four other identities ride the same contract.

    func testEventTaskKanbanReminderCoresCarryTheExplicitIdentity() async throws {
        Stub.responses["/api/conversations/room/events"] = (201, ["event": ["id": "e1", "title": "Meet",
            "startsAt": "2026-11-03T09:00:00.000Z", "createdById": "u"]])
        _ = try await api().createEventCore(conversationId: "room", title: "Meet",
            startsAtIso: "2026-11-03T09:00:00.000Z", description: nil, location: nil, clientId: "event00000000000000001")
        XCTAssertTrue(Stub.bodies.last?.contains("event00000000000000001") == true)

        Stub.responses["/api/hub/tasks"] = (201, ["task": ["id": "t1", "ownerId": "u", "title": "Ship", "status": "todo", "createdAt": "2026-11-01T09:00:00.000Z", "updatedAt": "2026-11-01T09:00:00.000Z"]])
        _ = try await api().createHubTaskCore(title: "Ship", status: nil, clientId: "task000000000000000001")
        XCTAssertTrue(Stub.bodies.last?.contains("task000000000000000001") == true)

        Stub.responses["/api/conversations/room/kanban"] = (201, ["boardReceiptVersion": 1, "userId": "u",
            "conversationId": "room", "card": ["id": "c1", "conversationId": "room", "title": "Card", "column": "todo",
            "position": 0, "assigneeId": nil, "assigneeName": nil, "createdById": "u", "createdByName": nil,
            "sourceMessageId": nil, "sourceRevoked": false, "sourceAvailable": nil, "createdAt": "", "updatedAt": ""]])
        _ = try await api().createKanbanCardCore(conversationId: "room", title: "Card", column: nil,
            assigneeId: nil, messageId: nil, clientId: "kanban00000000000000001")
        XCTAssertTrue(Stub.bodies.last?.contains("kanban00000000000000001") == true)

        Stub.responses["/api/reminders"] = (201, ["item": ["id": "r1", "conversationId": "room", "messageId": nil,
            "note": "", "remindAt": "2026-11-03T09:00:00.000Z", "firedAt": nil, "createdAt": "2026-11-01T09:00:00.000Z",
            "conversation": ["id": "room", "name": "Room", "isGroup": true], "snippet": nil]])
        _ = try await api().createReminderCore(conversationId: "room", messageId: nil, note: nil,
            remindAtIso: "2026-11-03T09:00:00.000Z", clientId: "remnd00000000000000001")
        XCTAssertTrue(Stub.bodies.last?.contains("remnd00000000000000001") == true)
    }

    // Section 10 item 2 - event history pages.

    func testEventHistoryPagesRequestTheKeysetWindowAndGateTheContinuation() async throws {
        Stub.responses["/api/conversations/room/events"] = (200, ["events": [], "eventsRev": 7, "hasMore": true,
            "nextCursor": "1771606800000.e1"])
        let page = try await api().eventsPage(conversationId: "room", limit: 60, cursor: "1771606900000.e9")
        XCTAssertEqual(page.eventsRev, 7)
        XCTAssertEqual(page.nextCursor, "1771606800000.e1")
        XCTAssertTrue(Stub.paths.last?.contains("limit=60") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=1771606900000.e9") == true)

        Stub.responses["/api/conversations/room/events"] = (200, ["events": [], "hasMore": true])
        do {
            _ = try await api().eventsPage(conversationId: "room", limit: 60, cursor: nil)
            XCTFail("a continued page without its cursor must fail")
        } catch { /* expected */ }
    }

    // Section 10 items 5/8/10 - the other three paged surfaces gate the same way.

    func testTaskBoardAndWhiteboardPagesGateTheirContinuations() async throws {
        Stub.responses["/api/hub/tasks"] = (200, ["userId": "u", "tasks": [], "hasMore": true, "nextCursor": "1771606800000.t1"])
        let tasks = try await api().hubTasksPage(limit: 100, cursor: nil)
        XCTAssertEqual(tasks.nextCursor, "1771606800000.t1")
        XCTAssertTrue(Stub.paths.last?.contains("limit=100") == true)

        Stub.responses["/api/conversations/room/kanban"] = (200, ["boardReceiptVersion": 1, "userId": "u",
            "conversationId": "room", "cards": [], "boardRev": 12, "hasMore": true, "nextCursor": "1771606800000.c1"])
        let board = try await api().kanbanBoardPage(conversationId: "room", limit: 200, cursor: nil)
        XCTAssertEqual(board.boardRev, 12)

        Stub.responses["/api/conversations/room/kanban"] = (200, ["boardReceiptVersion": 1, "userId": "u",
            "conversationId": "room", "cards": [], "hasMore": true])
        do {
            _ = try await api().kanbanBoardPage(conversationId: "room", limit: 200, cursor: nil)
            XCTFail("a continued board page without its cursor must fail")
        } catch { /* expected */ }

        Stub.responses["/api/conversations/room/whiteboard"] = (200, ["strokes": [], "complete": true,
            "hasMore": true, "nextCursor": "1771606800000.s1", "operationsVersion": 1])
        let board2 = try await api().whiteboardPage(conversationId: "room", limit: 500, cursor: "1771606700000.s0")
        XCTAssertEqual(board2.nextCursor, "1771606800000.s1")
        XCTAssertTrue(Stub.paths.last?.contains("limit=500") == true)
        XCTAssertTrue(Stub.paths.last?.contains("cursor=1771606700000.s0") == true)

        Stub.responses["/api/conversations/room/whiteboard"] = (200, ["strokes": [], "complete": true, "hasMore": true])
        do {
            _ = try await api().whiteboardPage(conversationId: "room", limit: 500, cursor: nil)
            XCTFail("a continued whiteboard page without its cursor must fail")
        } catch { /* expected */ }
    }

    // The durable journal roundtrips the five new kinds (the flush path reads them).

    func testSection10JournalRoundtripsTheFiveCreationKinds() {
        let suite = "s10-journal-" + UUID().uuidString
        let store = PulseSection8Journal(defaults: UserDefaults(suiteName: suite)!)
        let owner = "s10-owner"
        let publish = store.enqueue(owner: owner, kind: "event-create", targetId: "room", payload: ["title": "Meet"])
        let task = store.enqueue(owner: owner, kind: "task-create", targetId: "", payload: ["title": "Ship"])
        let card = store.enqueue(owner: owner, kind: "kanban-create", targetId: "room", payload: ["title": "Card"])
        let reminder = store.enqueue(owner: owner, kind: "reminder-create", targetId: "room", payload: ["remindAtIso": "x"])
        let packet = store.enqueue(owner: owner, kind: "redpacket-create", targetId: "room", payload: ["total": "10", "count": "2"])
        let pending = store.pending(owner: owner)
        XCTAssertEqual(pending.map(\.kind), ["event-create", "task-create", "kanban-create", "reminder-create", "redpacket-create"])
        store.confirm(owner: owner, clientId: task.clientId)
        XCTAssertEqual(store.pending(owner: owner).map(\.clientId), [publish.clientId, card.clientId, reminder.clientId, packet.clientId])
        XCTAssertTrue(store.pending(owner: "someone-else").isEmpty)
    }
}
