import XCTest
@testable import Pulse

private final class GroupReplyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "groups.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        let body: String
        if path.contains("/channels/") {
            if path.contains("/confirmed/") {
                body = request.httpMethod == "DELETE" ? #"{"ok":true,"memberCount":0}"# : #"{"already":false,"memberCount":2}"#
            } else if path.contains("/wrong/") {
                body = request.httpMethod == "DELETE" ? #"{"ok":false,"memberCount":1}"# : #"{"already":false,"memberCount":0}"#
            } else { body = "{}" }
        } else if path.hasSuffix("/disappearing") {
            if path.contains("/confirmed/") { body = #"{"conversation":{"id":"confirmed","isGroup":true,"members":[],"ttlSeconds":604800}}"# }
            else if path.contains("/wrong/") { body = #"{"conversation":{"id":"other","isGroup":true,"members":[],"ttlSeconds":86400}}"# }
            else { body = #"{"conversation":{"id":"missing","isGroup":true,"members":[]}}"# }
        } else if path.contains("/confirmed/") {
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
    func testDepartureRetiresOldEntriesWithoutPoppingANewerDestination() throws {
        let group = try JSONDecoder().decode(WireConversationSummary.self, from: Data(#"{"id":"group","isGroup":true,"members":[]}"#.utf8))
        let other = try JSONDecoder().decode(WireConversationSummary.self, from: Data(#"{"id":"other","isGroup":true,"members":[]}"#.utf8))
        let earlier = RoomRoute(conversation: group)
        let leaving = RoomRoute(conversation: group)
        let newer = RoomRoute(conversation: other)
        let reentry = RoomRoute(conversation: group)
        XCTAssertNotEqual(leaving, reentry)
        XCTAssertEqual(RoomRoute.retiring(leaving, from: [earlier, leaving, newer, reentry]), [newer, reentry])
        XCTAssertEqual(RoomRoute.retiring(leaving, from: [newer, reentry]), [newer, reentry])
        XCTAssertTrue(RoomRoute.retiring(leaving, from: [leaving]).isEmpty)
    }

    func testMissingOrMismatchedSettingsAndMemberAcknowledgementsAreRejected() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [GroupReplyProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let api = PulseAPIClient(baseURL: URL(string: "https://groups.test")!, userId: "me", session: session)
        for room in ["missing", "wrong"] {
            do { _ = try await api.subscribeChannel(room); XCTFail("Unconfirmed channel join accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.unsubscribeChannel(room); XCTFail("Unconfirmed channel leave accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.setDisappearingTtl(room, userId: "me", ttlSeconds: 86400); XCTFail("Unconfirmed TTL accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.addMembers(room, requesterId: "me", userIds: ["friend"]); XCTFail("Unconfirmed addition accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.setSlowMode(room, userId: "me", seconds: 5); XCTFail("Unconfirmed throttle accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
            do { _ = try await api.setMyScreenPrivacy(room, on: true); XCTFail("Unconfirmed preference accepted") }
            catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
        }
        let joined = try await api.subscribeChannel("confirmed")
        XCTAssertEqual(joined.already, false)
        let left = try await api.unsubscribeChannel("confirmed")
        XCTAssertEqual(left.ok, true)
        let current = try await api.setDisappearingTtl("confirmed", userId: "me", ttlSeconds: 86400)
        XCTAssertEqual(current.ttlSeconds, 604800) // newer authoritative settings may supersede the request
        let added = try await api.addMembers("confirmed", requesterId: "me", userIds: ["friend"])
        XCTAssertEqual(added, []) // explicit duplicate-only success is not a missing acknowledgement
        let seconds = try await api.setSlowMode("confirmed", userId: "me", seconds: 5)
        XCTAssertEqual(seconds, 5)
        let privacy = try await api.setMyScreenPrivacy("confirmed", on: true)
        XCTAssertTrue(privacy)
    }
}
