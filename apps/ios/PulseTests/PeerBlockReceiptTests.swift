import XCTest
@testable import Pulse

private final class PeerBlockProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "blocks.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let parts = request.url!.path.split(separator: "/")
        let mode = String(parts[0]), enabled = parts[1] == "on"
        var receipt: [String: Any]
        if request.url!.path.hasSuffix("/blocks") {
            var row: [String: Any] = ["id": "peer", "name": "Peer", "blockedAt": "2026-01-01T00:00:00.000Z"]
            switch mode {
            case "self": row["id"] = "owner"
            case "blank": row["id"] = " "
            case "date": row["blockedAt"] = "invalid"
            case "missing-date": row.removeValue(forKey: "blockedAt")
            default: break
            }
            receipt = ["blockListVersion": 1, "userId": "owner", "blocks": [row]]
            switch mode {
            case "empty-list": receipt["blocks"] = [[String: Any]]()
            case "missing-list": receipt.removeValue(forKey: "blocks")
            case "foreign": receipt["userId"] = "other"
            case "version": receipt["blockListVersion"] = "1"
            case "duplicate": receipt["blocks"] = [row, row]
            default: break
            }
        } else {
            receipt = ["ok": true, "blockReceiptVersion": 1, "actorId": "owner", "targetId": "peer", "blocked": enabled]
            switch mode {
            case "empty": receipt = [:]
            case "owner": receipt["actorId"] = "other"
            case "target": receipt["targetId"] = "other"
            case "version": receipt["blockReceiptVersion"] = "1"
            case "ok": receipt["ok"] = "true"
            case "missing": receipt.removeValue(forKey: "blocked")
            case "quoted": receipt["blocked"] = enabled ? "true" : "false"
            case "opposite": receipt["blocked"] = !enabled
            default: break
            }
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: receipt))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class PeerBlockReceiptTests: XCTestCase {
    private func client(_ mode: String, _ enabled: Bool = false) -> (PulseAPIClient, URLSession) {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [PeerBlockProtocol.self]
        let transport = URLSession(configuration: config)
        return (PulseAPIClient(baseURL: URL(string: "https://blocks.test/\(mode)/\(enabled ? "on" : "off")")!,
            userId: "owner", session: transport), transport)
    }
    func testPairReceiptsRequireExactOwnerTargetAndDesiredState() async {
        for enabled in [false, true] {
            for mode in ["valid", "empty", "owner", "target", "version", "ok", "missing", "quoted", "opposite"] {
                let (api, transport) = client(mode, enabled)
                defer { transport.invalidateAndCancel() }
                do {
                    _ = try await api.blockState(target: "peer")
                    XCTAssertTrue(["valid", "opposite"].contains(mode))
                } catch { XCTAssertFalse(["valid", "opposite"].contains(mode), "\(error)") }
                do {
                    if enabled { try await api.block(userId: "peer") } else { try await api.unblock(userId: "peer") }
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "\(error)") }
            }
        }
    }
    func testPrivateListCannotInventAnEmptySuccess() async {
        for mode in ["valid", "empty-list", "missing-list", "foreign", "version", "duplicate", "self", "blank", "date", "missing-date"] {
            let (api, transport) = client(mode)
            defer { transport.invalidateAndCancel() }
            do {
                let rows = try await api.blockedAccounts()
                XCTAssertTrue(["valid", "empty-list"].contains(mode))
                XCTAssertEqual(rows.count, mode == "empty-list" ? 0 : 1)
            } catch { XCTAssertFalse(["valid", "empty-list"].contains(mode), "\(error)") }
        }
    }
}
