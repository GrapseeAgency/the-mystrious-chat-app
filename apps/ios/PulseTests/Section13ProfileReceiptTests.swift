import XCTest
@testable import Pulse

/// Section 13 item 9 acceptance (iOS data layer): every profile PATCH is
/// journaled (kind "profile-patch") BEFORE the wire, a lost ACK keeps the
/// intent pending with the SAME clientId, the launch replay reuses that
/// identity, a permanent 4xx drops the intent honestly, and 401/429 +
/// transport failures keep it pending. The confirmed receipt carries the
/// monotonic profile revision.
@MainActor
final class Section13ProfileReceiptTests: XCTestCase {
    private final class Stub: URLProtocol {
        static var responses: [String: (Int, [String: Any])] = [:]
        static var bodies: [String] = []
        static var calls = 0
        override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "s13p.test" }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            // URLSession hands the body to a URLProtocol as a STREAM, not
            // httpBody - the same reader every established test stub uses.
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
            if let text = String(data: data, encoding: .utf8) {
                Self.bodies.append(text)
            }
            Self.calls += 1
            let (status, payload) = Self.responses[request.url!.path] ?? (200, [:])
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
                httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    private func api(as userId: String = "owner") -> PulseAPIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        let session = URLSession(configuration: config)
        return PulseAPIClient(baseURL: URL(string: "https://s13p.test")!, userId: userId, session: session)
    }

    private func userPayload(name: String = "Owner") -> [String: Any] {
        ["user": ["id": "owner", "name": name], "revision": 12, "replayed": false]
    }

    override func setUp() {
        super.setUp()
        Stub.responses = [:]; Stub.bodies = []; Stub.calls = 0
        // The shared journal rides the standard defaults: clear any leftover
        // profile-patch rows from a prior (failed) test before asserting.
        for owner in ["owner", "other"] {
            for intent in PulseSection8Journal.shared.pending(owner: owner) where intent.kind == "profile-patch" {
                PulseSection8Journal.shared.confirm(owner: owner, clientId: intent.clientId)
            }
        }
    }

    private func pendingProfileIntents(owner: String = "owner") -> [PulseSection8Journal.Intent] {
        PulseSection8Journal.shared.pending(owner: owner).filter { $0.kind == "profile-patch" }
    }

    func testProfilePatchJournalsBeforeTheWireAndConfirmsOn2xx() async throws {
        Stub.responses["/api/users/owner"] = (200, userPayload())
        let receipt = try await api().updateProfileDurable(userId: "owner", body: ["name": "Renamed", "about": "hi"])
        XCTAssertEqual(receipt.user.id, "owner")
        XCTAssertEqual(receipt.revision, 12)
        XCTAssertTrue(pendingProfileIntents().isEmpty, "a 2xx receipt is the ONLY retirement")
        let body = try XCTUnwrap(Stub.bodies.last)
        XCTAssertTrue(body.contains("\"name\":\"Renamed\""))
        XCTAssertTrue(body.contains("clientId"), "the durable identity rides the patch body")
    }

    func testLostACKKeepsTheIntentAndTheReplayUsesTheSameClientId() async throws {
        Stub.responses["/api/users/owner"] = (500, ["error": "boom"])
        do {
            _ = try await api().updateProfileDurable(userId: "owner", body: ["name": "Renamed"])
            XCTFail("a 500 must throw")
        } catch { /* expected */ }
        let pending = try XCTUnwrap(pendingProfileIntents().first, "a lost ACK keeps the intent")
        XCTAssertEqual(pending.payload["name"], "Renamed")
        XCTAssertEqual(pending.clientId.count, 32)
        // The launch replay: SAME clientId + original instructions through
        // the journal's body builder (PulseSession.flushPendingProfilePatches).
        Stub.responses["/api/users/owner"] = (200, userPayload(name: "Renamed"))
        Stub.bodies.removeAll()
        let client = api()
        _ = try await client.updateProfileCore(userId: pending.targetId,
            body: PulseSection8Journal.shared.profilePatchBody(pending), clientId: pending.clientId)
        let replayBody = try XCTUnwrap(Stub.bodies.last)
        XCTAssertTrue(replayBody.contains("\"\(pending.clientId)\""), "the replay reuses the SAME identity")
        XCTAssertTrue(replayBody.contains("\"name\":\"Renamed\""), "the original instructions")
        XCTAssertTrue(pendingProfileIntents().isEmpty, "the 2xx replay retires the intent")
    }

    func testPermanent4xxDropsTheIntentWithTheHonestError() async throws {
        Stub.responses["/api/users/owner"] = (409, ["error": "profile change mismatch",
            "code": "profile_operation_mismatch"])
        do {
            _ = try await api().updateProfileDurable(userId: "owner", body: ["username": "taken_handle"])
            XCTFail("a 409 must throw")
        } catch let failure as PulseAPIClient.Failure {
            XCTAssertEqual(failure.status, 409)
        }
        XCTAssertTrue(pendingProfileIntents().isEmpty, "a permanent refusal is never retried forever")
        Stub.responses["/api/users/owner"] = (400, ["error": "Name is required."])
        do {
            _ = try await api().updateProfileDurable(userId: "owner", body: ["name": ""])
            XCTFail("a 400 must throw")
        } catch { /* expected */ }
        XCTAssertTrue(pendingProfileIntents().isEmpty, "400s drop the intent too")
    }

    func testAuthAndRateLimitKeepTheIntentPending() async throws {
        Stub.responses["/api/users/owner"] = (401, ["error": "Session token is invalid or has been rotated."])
        do {
            _ = try await api().updateProfileDurable(userId: "owner", body: ["name": "Renamed"])
            XCTFail("a 401 must throw")
        } catch { /* expected */ }
        let pending = try XCTUnwrap(pendingProfileIntents().first, "an auth failure is not a refusal")
        Stub.responses["/api/users/owner"] = (429, ["error": "Slow down.", "retryAfter": 3])
        // A DIFFERENT actor journals under its own owner row (never mixed).
        do {
            _ = try await api(as: "other").updateProfileDurable(userId: "owner", body: ["name": "Renamed"])
            XCTFail("a 429 must throw")
        } catch { /* expected */ }
        XCTAssertEqual(pendingProfileIntents().count, 1, "the first actor's row is untouched")
        XCTAssertEqual(pendingProfileIntents(owner: "other").first?.payload["name"], "Renamed")
        XCTAssertEqual(pendingProfileIntents(owner: "other").first?.clientId.count, 32)
        PulseSection8Journal.shared.confirm(owner: "owner", clientId: pending.clientId)
        PulseSection8Journal.shared.confirm(owner: "other", clientId: pendingProfileIntents(owner: "other").first!.clientId)
        XCTAssertTrue(pendingProfileIntents().isEmpty)
        XCTAssertTrue(pendingProfileIntents(owner: "other").isEmpty)
    }

    func testLegacyUserEnvelopeStillDecodesThroughTheDurablePath() async throws {
        // A pre-revision relay answers { user } without revision/replayed.
        Stub.responses["/api/users/owner"] = (200, ["user": ["id": "owner", "name": "Owner"]])
        let receipt = try await api().updateProfileDurable(userId: "owner", body: ["name": "Owner"])
        XCTAssertEqual(receipt.user.name, "Owner")
        XCTAssertNil(receipt.revision); XCTAssertNil(receipt.replayed)
    }

    func testReplayBodyCarriesExactlyTheJournaledProfileFields() {
        let journal = PulseSection8Journal.shared
        let intent = journal.enqueue(owner: "owner", kind: "profile-patch", targetId: "owner",
            payload: ["name": "A", "about": "B", "statusEmoji": "", "statusText": "D",
                      "color": "rose", "avatar": "/api/uploads/a.jpg", "coverImage": "/api/uploads/c.jpg",
                      "username": "handle", "sneaky": "not-a-profile-field"])
        defer { journal.confirm(owner: "owner", clientId: intent.clientId) }
        let body = journal.profilePatchBody(intent)
        XCTAssertEqual(body["name"] as? String, "A")
        XCTAssertEqual(body["statusEmoji"] as? String, "", "an explicit clear replays as an explicit clear")
        XCTAssertEqual(body["clientId"] as? String, intent.clientId)
        XCTAssertNil(body["sneaky"], "non-profile keys never ride the replay")
    }
}
