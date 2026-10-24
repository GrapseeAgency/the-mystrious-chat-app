import XCTest
@testable import Pulse

private final class PeerReportProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "reports.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let mode = String(request.url!.path.split(separator: "/")[0])
        var body: [String: Any]
        if request.httpMethod == "GET" {
            var row: [String: Any] = ["reason": "spam", "at": "2026-01-01T00:00:00.000Z"]
            if mode == "reason" { row["reason"] = "unknown" }
            if mode == "date" { row["at"] = "invalid" }
            body = ["reportHistoryVersion": 1, "reporterId": "owner", "reportedId": "peer", "reasons": [row]]
            switch mode {
            case "empty": body["reasons"] = [[String: Any]]()
            case "missing": body.removeValue(forKey: "reasons")
            case "duplicate": body["reasons"] = [row, row]
            default: break
            }
        } else {
            body = ["ok": true, "reportReceiptVersion": 1, "reporterId": "owner", "reportedId": "peer",
                "reason": "spam", "details": " evidence 😀 ", "reported": true, "updated": false, "reportedAt": "2026-01-01T00:00:00.000Z"]
            switch mode {
            case "empty": body = [:]
            case "reason": body["reason"] = "other"
            case "details": body["details"] = "different"
            case "reported": body["reported"] = false
            case "version": body["reportReceiptVersion"] = "1"
            case "updated": body.removeValue(forKey: "updated")
            case "date": body["reportedAt"] = "invalid"
            default: break
            }
        }
        if mode == "owner" { body["reporterId"] = "other" }
        if mode == "target" { body["reportedId"] = "other" }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: body))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class PeerReportReceiptTests: XCTestCase {
    func testReportAndHistoryMustConfirmTheRequestedOwnerPeerAndEvidence() async {
        for action in ["report", "history"] {
            let modes = action == "report" ? ["valid", "empty", "owner", "target", "reason", "details", "reported", "version", "updated", "date"] :
                ["valid", "empty", "missing", "owner", "target", "duplicate", "reason", "date"]
            for mode in modes {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [PeerReportProtocol.self]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://reports.test/\(mode)")!, userId: "owner", session: transport)
                let valid = mode == "valid" || (action == "history" && mode == "empty")
                do {
                    if action == "report" { _ = try await api.report(userId: "peer", reason: "spam", details: " evidence 😀 ") }
                    else { _ = try await api.reportHistory(reportedId: "peer") }
                    XCTAssertTrue(valid, "\(action)/\(mode)")
                } catch { XCTAssertFalse(valid, "\(action)/\(mode): \(error)") }
            }
        }
    }
}
