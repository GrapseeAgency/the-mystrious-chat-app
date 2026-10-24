import XCTest
@testable import Pulse

private final class SettingsReadOwnershipProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "settings-read.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.path, "/api/settings")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200,
            httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"preferences":{"readReceipts":true,"typingVisible":true}}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
final class SettingsReadOwnershipTests: XCTestCase {
    func testRetiredReadCannotApplyServerPrivacyToTheCurrentPrefs() async throws {
        try await verifyRead(mode: "retired")
    }
    func testLocalPrivacyEditAfterReadStartedWinsOverItsLateSnapshot() async throws {
        try await verifyRead(mode: "edited")
    }
    func testUnchangedOwnedReadStillAppliesItsServerSnapshot() async throws {
        try await verifyRead(mode: "owned")
    }

    func testAnAcknowledgementInvalidatesAnOlderFetchEvenAfterItsPendingTokenIsCleared() async throws {
        try await verifyRead(mode: "confirmed")
    }

    private func verifyRead(mode: String) async throws {
        let suite = "SettingsReadOwnershipTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let originalGateway = PulseEndpoints.configuredBase
        defer { defaults.removePersistentDomain(forName: suite); PulseEndpoints.configuredBase = originalGateway }
        PulseEndpoints.configuredBase = "https://settings-read.test"
        let prefs = PulsePrefs(defaults: defaults)
        prefs.setViewer(PulseViewer(id: "owner", name: "Owner"))
        prefs.applyServer(WirePulsePrefs(readReceipts: false))
        var pending: PulseSettingsTicket?
        if mode == "confirmed" {
            prefs.patchRemote = { pending = $0 }
            prefs.setReadReceipts(false)
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [SettingsReadOwnershipProtocol.self]
        let transport = URLSession(configuration: config)
        defer { transport.invalidateAndCancel() }
        let api = PulseAPIClient(baseURL: URL(string: "https://settings-read.test")!, userId: "owner", session: transport)
        var validations = 0
        await prefs.syncFromServer(api: api, isCurrent: {
            validations += 1
            // Inject the transition at reply validation, after the snapshot was
            // requested but before it can touch preferences. No timing sleeps.
            if validations > 1 {
                if mode == "retired" { return false }
                if mode == "edited" { prefs.setReadReceipts(false) }
                if mode == "confirmed", let pending {
                    do { try prefs.acknowledgeSettings(pending, sent: pending.patch, server: WirePulsePrefs(readReceipts: false)) }
                    catch { XCTFail("Acknowledgement failed: \(error)") }
                }
            }
            return true
        })
        XCTAssertGreaterThanOrEqual(validations, 2)
        XCTAssertEqual(prefs.readReceipts, mode == "owned")
    }
}
