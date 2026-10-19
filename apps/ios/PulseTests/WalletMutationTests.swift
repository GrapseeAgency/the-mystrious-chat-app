import XCTest
@testable import Pulse

@MainActor
final class WalletMutationTests: XCTestCase {
    func testRapidTransferAndSwapIntentsShareOneSynchronousGate() async {
        let vm = HubViewModel(session: PulseSession())
        let started = expectation(description: "started")
        var continuation: CheckedContinuation<String, Never>?
        var calls = 0, completed = 0
        let first = vm.performWalletMutation(operation: {
            calls += 1; started.fulfill()
            return await withCheckedContinuation { continuation = $0 }
        }, completion: { _ in completed += 1 })!
        XCTAssertTrue(vm.walletMutationBusy)
        XCTAssertNil(vm.performWalletMutation(operation: { calls += 1; return "duplicate" }, completion: { _ in completed += 1 }))
        await fulfillment(of: [started], timeout: 2)
        continuation?.resume(returning: "ok"); await first.value
        XCTAssertEqual(calls, 1); XCTAssertEqual(completed, 1)
        XCTAssertFalse(vm.walletMutationBusy)
    }
    func testSessionReplacementBeforeTaskRunsPreventsTheRequest() async {
        let session = PulseSession()
        let owned = HubViewModel(session: session)
        var called = false
        let request = owned.performWalletMutation(operation: { called = true; return "no" }, completion: { _ in XCTFail("stale") })!
        session.stop()
        await request.value
        XCTAssertFalse(called)
        XCTAssertFalse(owned.walletMutationBusy)
    }

    func testFailureReleasesGateAndDoesNotProduceSuccess() async {
        let vm = HubViewModel(session: PulseSession())
        var succeeded = false, failed = false
        let request = vm.performWalletMutation(operation: { throw URLError(.notConnectedToInternet) }, completion: {
            switch $0 { case .success: succeeded = true; case .failure: failed = true }
        })!
        await request.value
        XCTAssertFalse(succeeded); XCTAssertTrue(failed); XCTAssertFalse(vm.walletMutationBusy)
    }
    func testLateWalletResultCannotCompleteAfterSessionReplacement() async {
        let session = PulseSession(), started = expectation(description: "started")
        let vm = HubViewModel(session: session)
        var continuation: CheckedContinuation<String, Never>?
        var completed = false
        let request = vm.performWalletMutation(operation: {
            started.fulfill()
            return await withCheckedContinuation { continuation = $0 }
        }, completion: { _ in completed = true })!
        await fulfillment(of: [started], timeout: 2)
        session.stop()
        continuation?.resume(returning: "old success"); await request.value
        XCTAssertFalse(completed); XCTAssertFalse(vm.walletMutationBusy)
    }
}

private final class InvalidWalletProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "wallet.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let body = #"{"wallet":{},"to":{"id":"other"},"note":"Swap complete"}"#
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class WalletAcknowledgementTests: XCTestCase {
    private func client() -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [InvalidWalletProtocol.self]
        let session = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://wallet.test")!, userId: "me", session: session), session)
    }
    func testMalformed200CannotReportTransferSuccess() async {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        do { _ = try await api.transferCoins(toUsername: "other", amount: 1, note: nil); XCTFail("Unconfirmed transfer") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
    }
    func testMalformed200CannotReportSwapSuccess() async {
        let (api, session) = client(); defer { session.invalidateAndCancel() }
        do { _ = try await api.swap(direction: "pc2gem", amount: 100); XCTFail("Unconfirmed swap") }
        catch { XCTAssertEqual((error as? PulseAPIClient.Failure)?.kind, .server) }
    }
}
