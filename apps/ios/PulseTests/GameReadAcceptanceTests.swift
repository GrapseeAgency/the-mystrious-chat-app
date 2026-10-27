import XCTest
@testable import Pulse

private final class GameReadProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "game-read.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.value(forHTTPHeaderField:"X-Mode") ?? "valid"
        let game = request.url!.path.contains("games")
        let row: [String:Any] = ["id":mode == "id" ? "other" : "item","conversationId":mode == "room" ? "other" : "room",
            "board":"         ","playerXId":"me","moveCount":0,"turn":"X","status":game ? "active" : "running","entries":[]]
        var body: [String:Any] = ["gameReadVersion":mode == "version" ? 0 : 1,"userId":mode == "owner" ? "other" : "me","conversationId":"room"]
        if mode != "missing" { body[game ? "match" : "tournament"] = row }
        if mode == "empty" { body = [:] }
        let query = URLComponents(url:request.url!,resolvingAgainstBaseURL:false)?.queryItems
        let status = query?.first(where:{$0.name == "userId"})?.value == "me" ? 200 : 400
        client?.urlProtocol(self,didReceive:HTTPURLResponse(url:request.url!,statusCode:status,httpVersion:nil,headerFields:["Content-Type":"application/json"])!,cacheStoragePolicy:.notAllowed)
        client?.urlProtocol(self,didLoad:try! JSONSerialization.data(withJSONObject:body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
@MainActor
final class GameReadAcceptanceTests: XCTestCase {
    func testPrivateGameAndStandingsRequireTheRequestedOwnerRoomAndID() async throws {
        for action in ["game","tournament"] {
            for mode in ["valid","empty","owner","version","room","missing","id"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [GameReadProtocol.self];config.httpAdditionalHeaders = ["X-Mode":mode]
                let session = URLSession(configuration:config);defer{session.invalidateAndCancel()}
                let api = PulseAPIClient(baseURL:URL(string:"https://game-read.test")!,userId:"me",session:session)
                do {
                    if action == "game" {_ = try await api.gameDetail("item")}
                    else {_ = try await api.tournamentDetail("item")}
                    XCTAssertEqual(mode,"valid","\(action):\(mode)")
                } catch {XCTAssertNotEqual(mode,"valid","\(action):\(error)")}
            }
        }
    }
    func testAccessDenialRetiresPrivateSnapshotsButTransientFailuresDoNot() {
        for kind in [PulseAPIClient.Failure.Kind.auth, .forbidden, .notFound] {
            XCTAssertTrue(PulseAPIClient.Failure(kind:kind,message:nil).invalidatesPrivateGameSnapshot)
        }
        for kind in [PulseAPIClient.Failure.Kind.network, .server, .rateLimited, .validation] {
            XCTAssertFalse(PulseAPIClient.Failure(kind:kind,message:nil).invalidatesPrivateGameSnapshot)
        }
    }
}
