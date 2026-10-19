import XCTest
@testable import Pulse

private final class CreationReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix("creation.test") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: 1024)
                if count <= 0 { break }
                data.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let id = body["clientId"] as? String ?? "missing"
        let mode = request.value(forHTTPHeaderField: "X-Creation-Fixture") ?? "success"
        let isGame = request.url!.path == "/api/games/create"
        let valid = UUID(uuidString: id) != nil && (isGame || request.url!.path == "/api/tournaments/create")
        let status = !valid ? 400 : mode == "lost" ? 503 : (mode == "gone" || mode == "wrong-gone") ? 410 : 200
        let resource = "object-" + id
        let kind = isGame ? "game" : "tournament"
        let link = try! JSONSerialization.data(withJSONObject: [isGame ? "matchId" : "tournamentId": resource])
        let carrier: [String: Any] = ["id": "carrier-" + id, "conversationId": body["conversationId"]!, "senderId": body["userId"]!,
            "content": "Created", "kind": kind, "createdAt": "2026-01-15T00:00:00.000Z", "payload": String(data: link, encoding: .utf8)!]
        var payload: [String: Any] = ["clientId": mode == "bad" ? "wrong-receipt" : id, "replayed": true, "message": carrier]
        if isGame {
            payload["match"] = ["id": resource, "conversationId": body["conversationId"]!, "playerXId": body["userId"]!,
                "playerOId": "joined-later", "board": "X        ", "moveCount": 1, "turn": "O", "status": "active"]
        } else {
            payload["tournament"] = ["id": resource, "name": body["name"]!, "status": "finished"]
        }
        if status != 200 { payload = ["error": id] }
        if status == 410 { payload = ["error": "Already delivered but expired", "code": "creation_gone", "clientId": mode == "gone" ? id : "wrong-id"] }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class RoomCreationRetryTests: XCTestCase {
    private func client(_ directory: URL, mode: String = "success", host: String = "one.creation.test", owner: String = "me") -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CreationReplyProtocol.self]
        config.httpAdditionalHeaders = ["X-Creation-Fixture": mode]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://" + host)!, userId: owner, session: session,
            creationOperations: PulseWalletOperations(directory: directory)), session)
    }
    func testLostResponseAndUnacceptedResponseRetainIdentityAcrossReopen() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (lost, session1) = client(dir, mode: "lost"); defer { session1.invalidateAndCancel() }
        var firstID: String?
        do { _ = try await lost.createGame(conversationId: "room", opponentId: nil); XCTFail("Expected lost response") }
        catch let failure as PulseAPIClient.Failure { firstID = failure.message; XCTAssertEqual(failure.status, 503) }
        let (reopened, session2) = client(dir); defer { session2.invalidateAndCancel() }
        let replay = try await reopened.createGame(conversationId: "room", opponentId: nil)
        XCTAssertEqual(replay.clientId, firstID)
        XCTAssertEqual(replay.match?.playerOId, "joined-later")
        let notYetAccepted = try await reopened.createGame(conversationId: "room", opponentId: nil)
        XCTAssertEqual(notYetAccepted.clientId, firstID)
        try await reopened.acknowledgeGameCreation(conversationId: "room", opponentId: nil, receipt: replay)
        let next = try await reopened.createGame(conversationId: "room", opponentId: nil)
        XCTAssertNotEqual(next.clientId, firstID)
        try await reopened.acknowledgeGameCreation(conversationId: "room", opponentId: nil, receipt: replay)
        let afterStaleAck = try await reopened.createGame(conversationId: "room", opponentId: nil)
        XCTAssertEqual(afterStaleAck.clientId, next.clientId)
    }
    func testMalformedReceiptCannotRetireIntentAndDifferentGatewayOwnerCannotShareIt() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (lost, s1) = client(dir, mode: "lost"); defer { s1.invalidateAndCancel() }
        var firstID: String?
        do { _ = try await lost.createGame(conversationId: "room", opponentId: nil) }
        catch let failure as PulseAPIClient.Failure { firstID = failure.message }
        let (bad, s2) = client(dir, mode: "bad"); defer { s2.invalidateAndCancel() }
        do { _ = try await bad.createGame(conversationId: "room", opponentId: nil); XCTFail("Bad ACK accepted") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        let (valid, s3) = client(dir); defer { s3.invalidateAndCancel() }
        let reply = try await valid.createGame(conversationId: "room", opponentId: nil)
        XCTAssertEqual(reply.clientId, firstID)
        let (otherGateway, s4) = client(dir, host: "two.creation.test"); defer { s4.invalidateAndCancel() }
        let other = try await otherGateway.createGame(conversationId: "room", opponentId: nil)
        XCTAssertNotEqual(other.clientId, firstID)
        let (otherOwner, s5) = client(dir, owner: "other"); defer { s5.invalidateAndCancel() }
        let owned = try await otherOwner.createGame(conversationId: "room", opponentId: nil)
        XCTAssertNotEqual(owned.clientId, firstID)
    }
    func testSeasonRecoveryAndCancelledAcknowledgementDoNotLoseTheKey() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (api, session) = client(dir); defer { session.invalidateAndCancel() }
        let receipt = try await api.createTournament(conversationId: "room", name: " Cup ")
        XCTAssertEqual(receipt.tournament?.status, "finished")
        let cancelled = Task { try await api.acknowledgeTournamentCreation(conversationId: "room", name: "Cup", receipt: receipt) }
        cancelled.cancel()
        do { try await cancelled.value; XCTFail("Cancelled acceptance consumed the receipt") } catch { }
        let retry = try await api.createTournament(conversationId: "room", name: "Cup")
        XCTAssertEqual(retry.clientId, receipt.clientId)
        try await api.acknowledgeTournamentCreation(conversationId: "room", name: "Cup", receipt: receipt)
        let next = try await api.createTournament(conversationId: "room", name: "Cup")
        XCTAssertNotEqual(next.clientId, receipt.clientId)
    }
    func testOnlyMatchingRemovedCreationReceiptAllowsADeliberateNewCreation() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let (api, s1) = client(dir); defer { s1.invalidateAndCancel() }
        let first = try await api.createGame(conversationId: "room", opponentId: nil)
        let (wrong, s2) = client(dir, mode: "wrong-gone"); defer { s2.invalidateAndCancel() }
        do { _ = try await wrong.createGame(conversationId: "room", opponentId: nil); XCTFail("Wrong receipt accepted") } catch { }
        let retained = try await api.createGame(conversationId: "room", opponentId: nil)
        XCTAssertEqual(retained.clientId, first.clientId)
        let (gone, s3) = client(dir, mode: "gone"); defer { s3.invalidateAndCancel() }
        do { _ = try await gone.createGame(conversationId: "room", opponentId: nil); XCTFail("Expected terminal receipt") }
        catch let failure as PulseAPIClient.Failure { XCTAssertEqual(failure.status, 410); XCTAssertEqual(failure.receiptClientId, first.clientId) }
        let deliberateNext = try await api.createGame(conversationId: "room", opponentId: nil)
        XCTAssertNotEqual(deliberateNext.clientId, first.clientId)
    }

}
