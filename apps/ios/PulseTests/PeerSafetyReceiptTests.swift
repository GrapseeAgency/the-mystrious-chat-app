import XCTest
@testable import Pulse

private final class PeerSafetyProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "safety.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let parts = request.url!.path.split(separator: "/")
        let mode = String(parts[0]), desired = parts[1] == "on"
        let state = mode == "opposite" ? !desired : desired
        var receipt: [String: Any] = ["ok": true, "safetyReceiptVersion": 1, "ownerId": "owner", "peerId": "peer",
            "safetyNumber": Array(repeating: "12345", count: 12).joined(separator: " "), "verified": state,
            "verifiedAt": state ? "2026-01-01T00:00:00.000Z" as Any : NSNull()]
        switch mode {
        case "empty": receipt = [:]
        case "owner": receipt["ownerId"] = "other"
        case "peer": receipt["peerId"] = "other"
        case "version": receipt["safetyReceiptVersion"] = "1"
        case "quoted": receipt["verified"] = state ? "true" : "false"
        case "digits": receipt["safetyNumber"] = "12345"
        case "missing-stamp": receipt.removeValue(forKey: "verifiedAt")
        case "bad-stamp": receipt["verifiedAt"] = "invalid"
        default: break
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: receipt))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class PeerSafetyReceiptTests: XCTestCase {
    func testComparisonReceiptCannotInventVerificationOrAnotherOwnersMark() async {
        for desired in [false, true] {
            for mode in ["valid", "empty", "owner", "peer", "version", "quoted", "opposite", "digits", "missing-stamp", "bad-stamp"] {
                let config = URLSessionConfiguration.ephemeral
                config.protocolClasses = [PeerSafetyProtocol.self]
                let transport = URLSession(configuration: config)
                defer { transport.invalidateAndCancel() }
                let api = PulseAPIClient(baseURL: URL(string: "https://safety.test/\(mode)/\(desired ? "on" : "off")")!, userId: "owner", session: transport)
                do {
                    _ = try await api.safetyState(peerId: "peer")
                    XCTAssertTrue(["valid", "opposite"].contains(mode))
                } catch { XCTAssertFalse(["valid", "opposite"].contains(mode), "\(error)") }
                do {
                    if desired { _ = try await api.verifySafety(peerId: "peer") }
                    else { try await api.resetSafety(peerId: "peer") }
                    XCTAssertEqual(mode, "valid")
                } catch { XCTAssertNotEqual(mode, "valid", "\(error)") }
            }
        }
    }
}

@MainActor
final class SafetyBadgeOwnershipTests: XCTestCase {
    func testSharedCacheNeverInheritsAnotherOwnersPeerCheckmark() {
        let cache = PulseSafetyBadgeCache(), first = UUID(), next = UUID()
        cache.mark("peer", verified: true, epoch: first)
        XCTAssertTrue(cache.isVerified("peer", epoch: first))
        XCTAssertFalse(cache.isVerified("peer", epoch: next))
        cache.mark("different", verified: true, epoch: next)
        XCTAssertFalse(cache.isVerified("peer", epoch: next))
        XCTAssertFalse(cache.isVerified("peer", epoch: first))
        cache.mark("peer", verified: true, epoch: next)
        cache.mark("peer", verified: false, epoch: next)
        XCTAssertFalse(cache.isVerified("peer", epoch: next))
        XCTAssertTrue(cache.isVerified("different", epoch: next))
    }
}
