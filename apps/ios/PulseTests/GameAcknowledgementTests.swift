import XCTest
@testable import Pulse

private final class GameReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "games.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let body: String
        if path.contains("/games/replay") {
            body = #"{"match":{"id":"replay","playerXId":"me","playerOId":"other","board":"XXXOO    ","turn":"X","status":"x_won","winnerId":"me","moveCount":5}}"#
        } else if path.contains("/games/wrongmove") {
            body = #"{"match":{"id":"wrongmove","playerXId":"me","playerOId":"other","board":"X        ","turn":"O","status":"active","moveCount":1}}"#
        } else if path.contains("/games/wrongseat") {
            body = #"{"match":{"id":"wrongseat","playerXId":"x","playerOId":"other","board":"         ","turn":"X","status":"active","moveCount":0}}"#
        } else if path.hasSuffix("/join") {
            body = #"{"entry":{"id":"receipt","tournamentId":"season","userId":"other"}}"#
        } else if path.contains("/tournaments/season") {
            body = #"{"tournament":{"id":"season","status":"running"}}"#
        } else { body = "{}" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class GameAcknowledgementTests: XCTestCase {
    private func client() -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [GameReplyProtocol.self]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://games.test")!, userId: "me", session: session), session)
    }
    func testLostWinningMoveReplyCanBeConfirmedAfterGameEnds() async throws {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        let result = try await api.gameMove("replay", cell: 2)
        XCTAssertEqual(result.match?.status, "x_won")
    }
    func testWrongSquareOrSeatCannotAcknowledgeTheAction() async {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        do { _ = try await api.gameMove("wrongmove", cell: 4); XCTFail("Wrong square confirmed") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        do { _ = try await api.joinGame("wrongseat"); XCTFail("Other player's seat confirmed") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
    }
    func testMissingGameCannotRenderAsAnEmptyInteractiveBoardOrCreationSuccess() async {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        do { _ = try await api.gameDetail("missing"); XCTFail("Missing board accepted") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        do { _ = try await api.createGame(conversationId: "room", opponentId: nil); XCTFail("Missing creation accepted") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
    }
    func testUnconfirmedSeasonActionsCannotShowSuccess() async {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        do { _ = try await api.joinTournament("season"); XCTFail("Other player's membership accepted") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        do { _ = try await api.finishTournament("season"); XCTFail("Running season shown as closed") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        do { _ = try await api.createTournament(conversationId: "room", name: "Cup"); XCTFail("Missing creation accepted") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
    }
}
