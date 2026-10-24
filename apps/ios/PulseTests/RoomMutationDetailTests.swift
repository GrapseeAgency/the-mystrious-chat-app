import XCTest
@testable import Pulse

private final class RoomMutationDetailProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "room-mutation.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = request.url!.path.split(separator: "/").first.map(String.init) ?? "valid"
        var detail: [String: Any] = ["id": mode == "foreign" ? "foreign" : "room", "isGroup": mode != "dm",
            "name": "Current server name", "photo": NSNull(), "broadcastMode": false, "screenPrivacy": false, "ttlSeconds": 604800,
            "members": [["id": "me", "name": "Me", "color": "amber"]]]
        if mode == "missing-member" { detail["members"] = [] as [String] }
        if mode == "missing-photo" { detail.removeValue(forKey: "photo") }
        if mode == "missing-name" { detail.removeValue(forKey: "name") }
        if mode == "quoted-policy" { detail["screenPrivacy"] = "false" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: ["conversation": detail]))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class RoomMutationDetailTests: XCTestCase {
    func testRoomMutationRequiresMemberGroupAndTouchedFieldsButPreservesNewerServerTruth() async throws {
        for mode in ["valid", "foreign", "missing-member", "dm", "missing-photo", "missing-name", "quoted-policy"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [RoomMutationDetailProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://room-mutation.test/\(mode)")!, userId: "me", session: transport)
            do {
                let detail = try await api.patchConversation("room", requesterId: "me", name: "Requested name",
                    photo: "/api/uploads/new.png", broadcast: true, screenPrivacy: true)
                XCTAssertEqual(mode, "valid")
                XCTAssertEqual(detail.name, "Current server name"); XCTAssertNil(detail.photo)
                XCTAssertEqual(detail.screenPrivacy, false)
            } catch { XCTAssertNotEqual(mode, "valid") }
            let canReadTTL = !["foreign", "missing-member", "quoted-policy"].contains(mode)
            do {
                let detail = try await api.setDisappearingTtl("room", userId: "me", ttlSeconds: 86400)
                XCTAssertTrue(canReadTTL); XCTAssertEqual(detail.ttlSeconds, 604800)
            } catch { XCTAssertFalse(canReadTTL) }
        }
    }
}
