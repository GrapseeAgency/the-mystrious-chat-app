import XCTest
@testable import Pulse

private final class RoomDetailAuthorityProtocol: URLProtocol {
    static let room = "room/one?&#%"
    static let owner = "owner&other=x#part"
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "privacy-detail.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
        XCTAssertNil(components.fragment)
        XCTAssertTrue(components.percentEncodedPath.contains("room%2Fone%3F%26%23%25"))
        let body: [String: Any]
        if request.httpMethod == "PATCH" {
            body = ["ok": true, "screenPrivacy": true]
        } else {
            XCTAssertEqual(components.queryItems?.count, 1)
            XCTAssertEqual(components.queryItems?.first?.value, Self.owner)
            let mode = request.value(forHTTPHeaderField: "X-Mode") ?? "valid"
            let members: [[String: Any]] = mode == "missing-member" ? [] : [["id": Self.owner, "name": "Owner", "color": "amber"]]
            body = ["conversation": ["id": mode == "foreign" ? "wrong-room" : Self.room, "isGroup": true, "members": members,
                "screenPrivacy": false, "myScreenPrivacy": false]]
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class RoomDetailAuthorityTests: XCTestCase {
    func testDetailValidatesRoomMembershipAndDoesNotTurnIDsIntoURLSyntax() async throws {
        for mode in ["valid", "foreign", "missing-member"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [RoomDetailAuthorityProtocol.self]
            config.httpAdditionalHeaders = ["X-Mode": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://privacy-detail.test")!, userId: RoomDetailAuthorityProtocol.owner, session: transport)
            do {
                let detail = try await api.conversationDetail(id: RoomDetailAuthorityProtocol.room, userId: RoomDetailAuthorityProtocol.owner)
                XCTAssertEqual(mode, "valid"); XCTAssertEqual(detail.id, RoomDetailAuthorityProtocol.room)
            } catch { XCTAssertNotEqual(mode, "valid"); XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            let confirmed = try await api.setMyScreenPrivacy(RoomDetailAuthorityProtocol.room, on: true)
            XCTAssertTrue(confirmed)
        }
    }
}
