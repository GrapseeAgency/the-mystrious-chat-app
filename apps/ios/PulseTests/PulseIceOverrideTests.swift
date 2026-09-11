import XCTest
@testable import Pulse

/// Wave 3-HW — manifest `ice` override plumbing (TURN/STUN).
/// Covers: dual `urls` wire forms, credential passthrough, invalid/blank
/// rejection (a broken manifest must never strip a working STUN set),
/// and the offline-relaunch persistence shape (`iceJson` in the vault).
final class PulseIceOverrideTests: XCTestCase {
    // No tearDown reset: `applyIceOverride(nil)` deliberately NEVER clears an
    // adopted override (blank-never-clobbers, same rule as the base URLs) —
    // that semantic is itself asserted below.

    func testUrlsAsArrayWithCredentialsDecodes() throws {
        let json = #"[{"urls":["turn:turn.example.com:3478?transport=udp","turn:turn.example.com:3478?transport=tcp"],"username":"pulse","credential":"s3cr3t"}]"#
        PulseEndpoints.applyIceOverride(json)
        XCTAssertEqual(PulseEndpoints.manifestIceJSON, json)

        let decoded = try JSONDecoder().decode([PulseIceServer].self, from: json.data(using: .utf8)!)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded[0].urls.count, 2)
        XCTAssertEqual(decoded[0].urls[0], "turn:turn.example.com:3478?transport=udp")
        XCTAssertEqual(decoded[0].username, "pulse")
        XCTAssertEqual(decoded[0].credential, "s3cr3t")
    }

    func testUrlsAsSingleStringDecodes() throws {
        let json = #"[{"urls":"turn:turn.example.com:3478"}]"#
        let decoded = try JSONDecoder().decode([PulseIceServer].self, from: json.data(using: .utf8)!)
        XCTAssertEqual(decoded[0].urls, ["turn:turn.example.com:3478"])
        XCTAssertNil(decoded[0].username)
        XCTAssertNil(decoded[0].credential)
    }

    func testInvalidJsonIsIgnoredAndNeverClobbers() {
        PulseEndpoints.applyIceOverride(#"[{"urls":["turn:good:3478"]}]"#)
        let good = PulseEndpoints.manifestIceJSON
        XCTAssertNotNil(good)

        PulseEndpoints.applyIceOverride("not-json{{{")
        XCTAssertEqual(PulseEndpoints.manifestIceJSON, good, "invalid JSON must not replace a working override")

        PulseEndpoints.applyIceOverride("[]")
        XCTAssertEqual(PulseEndpoints.manifestIceJSON, good, "empty ice array must be ignored")

        PulseEndpoints.applyIceOverride("   ")
        XCTAssertEqual(PulseEndpoints.manifestIceJSON, good, "blank must be ignored")

        PulseEndpoints.applyIceOverride(nil)
        XCTAssertEqual(PulseEndpoints.manifestIceJSON, good, "nil must never clear an adopted override (blank-never-clobbers)")
    }

    func testPersistedVaultShapeWithIceJsonRoundTrips() throws {
        let ice = [PulseIceServer(urls: ["turn:turn.example.com:3478"], username: "pulse", credential: "s3cr3t")]
        let iceJSON = String(data: try JSONEncoder().encode(ice), encoding: .utf8)!
        let vault: [String: String] = ["gateway": "https://gw.example.com", "socket": "", "iceJson": iceJSON]
        let payload = try JSONSerialization.data(withJSONObject: vault)

        struct StoredOverride: Decodable {
            let gateway: String?
            let socket: String?
            let iceJson: String?
        }
        let stored = try JSONDecoder().decode(StoredOverride.self, from: payload)
        XCTAssertEqual(stored.gateway, "https://gw.example.com")
        XCTAssertEqual(stored.iceJson, iceJSON)

        PulseEndpoints.applyIceOverride(stored.iceJson)
        XCTAssertNotNil(PulseEndpoints.manifestIceJSON)
    }

    func testLegacyVaultWithoutIceStillLoads() throws {
        let payload = #"{"gateway":"https://gw.example.com","socket":""}"#.data(using: .utf8)!
        struct StoredOverride: Decodable {
            let gateway: String?
            let socket: String?
            let iceJson: String?
        }
        let stored = try JSONDecoder().decode(StoredOverride.self, from: payload)
        XCTAssertNil(stored.iceJson, "pre-HW vaults have no iceJson — must decode without error")
    }
}
