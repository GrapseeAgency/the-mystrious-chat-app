import XCTest
@testable import Pulse

private final class NotebookProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "notebook.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = String(request.url!.path.split(separator: "/")[0])
        let members = mode == "shared" ? ["owner", "other"] : [mode == "foreign" ? "other" : "owner"]
        let row: [String: Any] = ["id": mode == "empty" ? "" : "notebook", "isGroup": mode == "group",
            "isSelf": mode != "ordinary", "members": members.map { ["id": $0, "name": $0] }]
        var receipt: [String: Any] = ["notebookReceiptVersion": 1, "userId": mode == "owner" ? "other" : "owner", "conversation": row]
        if mode == "legacy" { receipt.removeValue(forKey: "notebookReceiptVersion") }
        if mode == "version" { receipt["notebookReceiptVersion"] = "1" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: receipt))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class NotebookReceiptTests: XCTestCase {
    func testOnlyAnOwnerBoundPrivateNotebookCanBeOpened() async {
        for mode in ["valid", "legacy", "version", "owner", "empty", "foreign", "shared", "group", "ordinary"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [NotebookProtocol.self]
            let transport = URLSession(configuration: config)
            defer { transport.invalidateAndCancel() }
            let api = PulseAPIClient(baseURL: URL(string: "https://notebook.test/\(mode)")!, userId: "owner", session: transport)
            do { _ = try await api.createSelfChat(); XCTAssertEqual(mode, "valid") }
            catch { XCTAssertNotEqual(mode, "valid", "\(error)") }
        }
    }
}

@MainActor
final class NotebookOpeningOwnershipTests: XCTestCase {
    func testPendingStateAndCleanupBelongToTheOpeningNotThePeerOrOldScreen() throws {
        let model = ChatsViewModel(), first = UUID(), next = UUID()
        let old = try XCTUnwrap(model.beginSelfOpening(epoch: first, owner: "owner"))
        XCTAssertTrue(model.selfCreating)
        XCTAssertNil(model.beginSelfOpening(epoch: first, owner: "owner"))
        model.cancelSelfOpening()
        XCTAssertFalse(model.selfCreating)
        let reopened = try XCTUnwrap(model.beginSelfOpening(epoch: first, owner: "owner"))
        model.finishSelfOpening(old)
        XCTAssertTrue(model.selfCreating)
        let successor = try XCTUnwrap(model.beginSelfOpening(epoch: next, owner: "owner"))
        model.finishSelfOpening(reopened)
        XCTAssertTrue(model.selfCreating)
        model.finishSelfOpening(successor)
        XCTAssertFalse(model.selfCreating)
        XCTAssertNil(model.beginSelfOpening(epoch: next, owner: ""))
    }
}
