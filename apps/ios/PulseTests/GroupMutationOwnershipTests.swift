import XCTest
@testable import Pulse

private final class GroupReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "groups.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let body: String
        if path.contains("/confirmed/") {
            if path.hasSuffix("/members") { body = #"{"added":[]}"# }
            else if path.hasSuffix("/slow-mode") { body = #"{"ok":true,"slowModeSeconds":5}"# }
            else { body = #"{"screenPrivacy":true}"# }
        } else if path.contains("/wrong/") {
            if path.hasSuffix("/members") { body = #"{"added":["stranger"]}"# }
            else if path.hasSuffix("/slow-mode") { body = #"{"ok":true,"slowModeSeconds":0}"# }
            else { body = #"{"screenPrivacy":false}"# }
        } else { body = "{}" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class GroupMutationOwnershipTests: XCTestCase {
    func testClaimIsSynchronousAndRetiredCompletionCannotClearSuccessor() throws {
        var gate = PulseMutationGate()
        let owner = UUID()
        let first = try XCTUnwrap(gate.begin(owner: owner, resource: "group"))
        XCTAssertNil(gate.begin(owner: owner, resource: "group"))
        gate.cancel()
        let next = try XCTUnwrap(gate.begin(owner: owner, resource: "group"))
        XCTAssertFalse(gate.finish(first))
        XCTAssertTrue(gate.isCurrent(next, owner: owner, resource: "group"))
        XCTAssertTrue(gate.isBusy)
        XCTAssertTrue(gate.finish(next))
        XCTAssertFalse(gate.isBusy)
    }
    func testDifferentAccountOrRoomCannotAdoptAnAcknowledgement() throws {
        var gate = PulseMutationGate()
        let owner = UUID()
        let request = try XCTUnwrap(gate.begin(owner: owner, resource: "group"))
        XCTAssertFalse(gate.isCurrent(request, owner: UUID(), resource: "group"))
        XCTAssertFalse(gate.isCurrent(request, owner: owner, resource: "other"))
        gate.cancel()
        XCTAssertFalse(gate.isCurrent(request, owner: owner, resource: "group"))
    }
    func testMissingOrMismatchedSettingsAndMemberAcknowledgementsAreRejected() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [GroupReplyProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let api = PulseAPIClient(baseURL: URL(string: "https://groups.test")!, userId: "me", session: session)
        for room in ["missing", "wrong"] {
            do { _ = try await api.addMembers(room, requesterId: "me", userIds: ["friend"]); XCTFail("Unconfirmed addition accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.setSlowMode(room, userId: "me", seconds: 5); XCTFail("Unconfirmed throttle accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.setMyScreenPrivacy(room, on: true); XCTFail("Unconfirmed preference accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        }
        let added = try await api.addMembers("confirmed", requesterId: "me", userIds: ["friend"])
        XCTAssertEqual(added, []) // explicit duplicate-only success is not a missing acknowledgement
        let seconds = try await api.setSlowMode("confirmed", userId: "me", seconds: 5)
        XCTAssertEqual(seconds, 5)
        let privacy = try await api.setMyScreenPrivacy("confirmed", on: true)
        XCTAssertTrue(privacy)
    }
}
