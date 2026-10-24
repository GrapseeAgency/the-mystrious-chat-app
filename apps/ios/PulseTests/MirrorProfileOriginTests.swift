import XCTest
@testable import Pulse

private final class MirrorProfileOriginProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        ["captured-profile.test", "replacement-profile.test"].contains(request.url?.host ?? "")
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.host, "captured-profile.test")
        XCTAssertEqual(request.url?.path, "/api/users/owner")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
        let mode = request.value(forHTTPHeaderField: "X-Profile-Mode") ?? "valid"
        let status = mode == "missing" ? 404 : 200
        let object: [String: Any] = status == 404 ? ["error": "Not found"] : [
            "user": ["id": mode == "foreign" ? "other" : "owner", "name": "Captured profile", "color": "amber"],
        ]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: object))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class MirrorProfileOriginTests: XCTestCase {
    func testProfileLoaderKeepsTheCapturedOriginAndCredentialTogether() async throws {
        let previous = PulseEndpoints.configuredBase
        defer { PulseEndpoints.configuredBase = previous }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MirrorProfileOriginProtocol.self]
        let transport = URLSession(configuration: config)
        defer { transport.invalidateAndCancel() }
        let api = PulseAPIClient(baseURL: URL(string: "https://captured-profile.test")!, userId: "owner",
            authToken: "fixture-token", session: transport)
        PulseEndpoints.configuredBase = "https://replacement-profile.test"
        let profile = try await MirrorProfileRow.fetch(using: api)
        XCTAssertEqual(profile.name, "Captured profile")
        XCTAssertEqual(profile.color, "amber")
    }

    func testForeignProfileAndHttpFailureAreNotAcceptedAsTheViewer() async throws {
        for mode in ["foreign", "missing"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [MirrorProfileOriginProtocol.self]
            config.httpAdditionalHeaders = ["X-Profile-Mode": mode]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://captured-profile.test")!, userId: "owner",
                authToken: "fixture-token", session: transport)
            do {
                _ = try await MirrorProfileRow.fetch(using: api)
                XCTFail("Invalid profile response was accepted: \(mode)")
            } catch let error as PulseAPIClient.Failure {
                XCTAssertEqual(error.kind, mode == "foreign" ? .server : .notFound)
            }
        }
    }
}
