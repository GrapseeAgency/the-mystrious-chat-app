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

final class WalletOperationJournalTests: XCTestCase {
    func testPendingInstructionSurvivesReopenAndStaleAckCannotEraseItsSuccessor() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let firstStore = PulseWalletOperations(directory: dir)
        let intent = ["https://one.test", "me", "swap", "100"]
        let first = try await firstStore.begin(intent)
        let reopened = PulseWalletOperations(directory: dir)
        let retry = try await reopened.begin(intent)
        XCTAssertEqual(first, retry)
        try await reopened.finish(intent, id: first)
        let next = try await reopened.begin(intent)
        XCTAssertNotEqual(first, next)
        try await reopened.finish(intent, id: first)
        let stillPending = try await reopened.begin(intent)
        XCTAssertEqual(next, stillPending)
        let otherOwner = try await reopened.begin(["https://one.test", "other", "swap", "100"])
        XCTAssertNotEqual(next, otherOwner)
    }
    func testGatewayAliasesUseTheSameNamespace() {
        XCTAssertEqual(PulseWalletOperations.gatewayKey(URL(string: "https://EXAMPLE.test:443/")!), PulseWalletOperations.gatewayKey(URL(string: "https://example.test")!))
        XCTAssertNotEqual(PulseWalletOperations.gatewayKey(URL(string: "https://example.test/a")!), PulseWalletOperations.gatewayKey(URL(string: "https://example.test/b")!))
    }
}

private final class WalletReceiptProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "wallet-receipt.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            while true {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(contentsOf: buffer.prefix(count))
            }
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let id = body?["clientId"] as? String ?? "missing-id"
        let lost = request.value(forHTTPHeaderField: "X-Wallet-Fixture") == "lost"
        let valid = request.url?.path == "/api/hub/wallet/operations/swap" && UUID(uuidString: id) != nil
        let status = !valid ? 400 : (lost ? 503 : 200)
        let wallet: [String: Any] = ["userId": body?["userId"] as? String ?? "", "coins": 50, "gems": 1]
        let payload: [String: Any] = status == 200
            ? ["clientId": id, "wallet": wallet]
            : ["error": id]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: payload))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class WalletRetryTransportTests: XCTestCase {
    func testRetryAfterLostResponseUsesSameOperationAndSuccessRetiresIt() async throws {
        let owner = UUID().uuidString
        func client(_ lost: Bool) -> (PulseAPIClient, URLSession) {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [WalletReceiptProtocol.self]
            config.httpAdditionalHeaders = ["X-Wallet-Fixture": lost ? "lost" : "success"]
            let session = URLSession(configuration: config)
            return (PulseAPIClient(baseURL: URL(string: "https://wallet-receipt.test")!, userId: owner, session: session), session)
        }
        let (first, firstSession) = client(true)
        defer { firstSession.invalidateAndCancel() }
        var firstID: String?
        do { _ = try await first.swap(direction: "pc2gem", amount: 100); XCTFail("Expected lost response") }
        catch let error as PulseAPIClient.Failure { XCTAssertEqual(error.status, 503); firstID = error.message }
        let (reopened, retrySession) = client(false)
        defer { retrySession.invalidateAndCancel() }
        let replay = try await reopened.swap(direction: "pc2gem", amount: 100)
        XCTAssertEqual(replay.clientId, firstID)
        let deliberateNext = try await reopened.swap(direction: "pc2gem", amount: 100)
        XCTAssertNotEqual(deliberateNext.clientId, firstID)
    }
}
